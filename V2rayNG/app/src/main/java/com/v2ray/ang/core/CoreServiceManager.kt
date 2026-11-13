package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.service.ServiceLifecycle
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException

object CoreServiceManager {

    private lateinit var coreController: CoreController
    private val nativeMutex = Mutex()
    private val nativeAccess = Any()
    private val commandGeneration = AtomicLong()
    private var receiverService: WeakReference<Service>? = null
    @Volatile private var activeSession: Session? = null
    val workScope: CoroutineScope? get() = activeSession?.workScope
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null

    /** Tun descriptor owned by the active service, null in proxy-only and root modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        private set

    fun createSession(
        control: ServiceControl,
        setup: suspend () -> ParcelFileDescriptor?,
        afterStart: suspend () -> Unit = {},
        release: suspend () -> Unit = {},
        afterStop: suspend () -> Unit = {},
    ): Session {
        val predecessor = activeSession?.requestStop()
        serviceControl = SoftReference(control)
        return Session(control, setup, afterStart, release, afterStop, predecessor).also { activeSession = it }
    }

    /** One service instance owns commands, setup, handovers and notification workers. */
    class Session internal constructor(
        private val control: ServiceControl,
        private val setup: suspend () -> ParcelFileDescriptor?,
        private val afterStart: suspend () -> Unit,
        private val release: suspend () -> Unit,
        private val afterStop: suspend () -> Unit,
        private val predecessor: Job?,
    ) {
        private val lifecycle = ServiceLifecycle()
        private val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + Dispatchers.IO)
        private var workers = SupervisorJob(owner)
        var workScope = CoroutineScope(workers + Dispatchers.IO)
            private set
        private var commandJob: Job? = null
        private var stopJob: Job? = null
        private var ownsResources = false
        private var profileGuid: String? = null
        private val service get() = control.getService()

        fun start(intent: Intent? = null) {
            if (intent?.hasExtra(LauncherManager.RESTART_GENERATION) == true &&
                intent.getLongExtra(LauncherManager.RESTART_GENERATION, -1) != commandGeneration.get()) {
                requestStop()
                return
            }
            val token = lifecycle.start() ?: return
            try {
                registerCommands(service)
                commandJob = scope.launch { startAttempt(token) }
            } catch (failure: Exception) {
                LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=register guid=$profileGuid failed", failure)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                requestStop()
            }
        }

        private suspend fun startAttempt(token: Long) {
            try {
                predecessor?.join()
                nativeMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    check(lifecycle.accepts(token))
                    profileGuid = MmkvManager.getSelectServer()
                    ownsResources = true // setup may fail after acquiring only some resources.
                    runInterruptible { check(SettingsManager.initAssets(service, service.assets)) { "Native assets unavailable" } }
                    CoreNativeManager.initCoreEnv(service)
                    if (!::coreController.isInitialized) coreController = CoreNativeManager.newCoreController(CoreCallback())
                    if (processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        processFinder = XrayProcessFinder(service.applicationContext)
                        coreController.registerProcessFinder(processFinder)
                    }
                    SettingsManager.refreshRuntimeSocksPort()
                    val descriptor = setup()
                    currentCoroutineContext().ensureActive()
                    if (!startCoreLoop(service, descriptor)) error("Core setup failed")
                    afterStart()
                    currentCoroutineContext().ensureActive()
                    if (!lifecycle.running(token)) throw CancellationException("Service stopped during setup")
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
                    startNetworkMonitor(service)
                    NotificationManager.startSpeedNotification()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=setup guid=$profileGuid failed", failure)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                requestStop()
            }
        }

        fun restart() {
            val token = lifecycle.restart() ?: return
            networkMonitor?.unregister()
            networkMonitor = null
            commandJob?.cancel()
            workers.cancel()
            commandJob = scope.launch {
                try {
                    workers.join()
                    nativeMutex.withLock { cleanup(keepForeground = true) }
                    ensureActive()
                    if (!lifecycle.accepts(token)) return@launch
                    workers = SupervisorJob(owner)
                    workScope = CoroutineScope(workers + Dispatchers.IO)
                    val requestedMode = when {
                        SettingsManager.isRootMode() -> "CoreRootService"
                        SettingsManager.isVpnMode() -> "CoreVpnService"
                        else -> "CoreProxyOnlyService"
                    }
                    if (requestedMode != service.javaClass.simpleName) {
                        val stamp = commandGeneration.get()
                        ensureActive()
                        if (!lifecycle.accepts(token)) return@launch
                        // Retire this session before Android can create the next mode's service;
                        // createSession must not invalidate the new launch's generation.
                        lifecycle.stopped()
                        unregisterCommands(service)
                        try {
                            LauncherManager.restartFromDaemon(service, stamp)
                        } finally {
                            withContext(NonCancellable + Dispatchers.Main) { service.stopSelf() }
                            owner.cancel()
                        }
                    } else {
                        startAttempt(token)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=restart guid=$profileGuid failed", failure)
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                    requestStop()
                }
            }
        }

        @Synchronized
        fun requestStop(): Job? {
            if (!lifecycle.stop()) return stopJob
            if (stopJob?.isActive == true) return stopJob
            commandGeneration.incrementAndGet()
            // Invalidate commands before cancellation can resume blocked native/root work.
            networkMonitor?.unregister()
            networkMonitor = null
            commandJob?.cancel()
            workers.cancel()
            stopJob = scope.launch {
                commandJob?.join()
                workers.join()
                try {
                    withContext(NonCancellable) { nativeMutex.withLock { cleanup(keepForeground = false) } }
                    lifecycle.stopped()
                    unregisterCommands(service)
                    withContext(Dispatchers.Main) { service.stopSelf() }
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                    owner.cancel()
                } catch (failure: Exception) {
                    LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=cleanup guid=$profileGuid failed", failure)
                }
            }
            return stopJob
        }

        private suspend fun cleanup(keepForeground: Boolean) {
            if (ownsResources) {
                // VPN/LAN/root resources are removed before the listener they depend on.
                release()
                stopCoreLoop(service)
                afterStop()
                SettingsManager.clearRuntimeSocksPort()
                ownsResources = false
            }
            if (!keepForeground && activeSession === this) NotificationManager.cancelNotification()
        }

        fun destroy() {
            val stopping = requestStop()
            // Leak prevention: finish routing/tunnel removal before Android destroys the service.
            // Root commands and this callback both have explicit bounds; never wait indefinitely.
            val completed = runBlocking { withTimeoutOrNull(3500) { stopping?.join(); true } } == true
            if (!completed) {
                LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=destroy guid=$profileGuid cleanup timed out",
                    TimeoutException("Service cleanup exceeded 3500ms"))
            }
            unregisterCommands(service)
            owner.cancel()
            if (activeSession === this) {
                activeSession = null
                serviceControl = null
            }
        }

        fun canReload(token: Long): Boolean = lifecycle.phase == ServiceLifecycle.Phase.RUNNING && lifecycle.accepts(token)
        fun generation(): Long = lifecycle.current()

        fun reportState() {
            scope.launch {
                val running = canReload(generation()) && isRunning()
                MessageHelper.sendMsg2UI(service,
                    if (running) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING, "")
            }
        }
    }

    private fun registerCommands(service: Service) {
        if (receiverService?.get() === service) return
        receiverService?.get()?.let(::unregisterCommands)
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(service, mMsgReceive, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverService = WeakReference(service)
    }

    private fun unregisterCommands(service: Service) {
        if (receiverService?.get() !== service) return
        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=cleanup unregister receiver failed", error)
        }
        receiverService = null
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = ::coreController.isInitialized && coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    private suspend fun startCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?): Boolean {
        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        return isRunning()
    }

    @Throws(Exception::class)
    private suspend fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=start guid=$guid")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        synchronized(nativeAccess) { coreController.startLoop(result.content, tunFd) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        if (!isRunning()) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            withContext(Dispatchers.Main) { browserDialer?.stop() }
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                withContext(Dispatchers.Main) { browserDialer?.start(service, dialerAddr) }
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                withContext(Dispatchers.Main) { browserDialer?.start(service, dialerAddr) }
            }

            else -> {}
        }

    }

    private suspend fun stopCoreLoop(service: Service) {
        NotificationManager.stopSpeedNotification()
        if (isRunning()) synchronized(nativeAccess) { coreController.stopLoop() }
        CoreNativeManager.reconcileBrowserDialer("")
        browserDialer?.let { dialer -> withContext(Dispatchers.Main) { dialer.stop() } }
        browserDialer = null
        currentConfig = null
        currentVpnInterface = null
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            scope = activeSession?.workScope ?: return,
            onHandover = { monitor -> reloadCore(monitor) },
            component = service.javaClass.simpleName,
            profileGuid = MmkvManager.getSelectServer(),
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(monitor: NetworkMonitor) {
        val session = activeSession ?: return
        val token = session.generation()
        session.workScope.launch {
            nativeMutex.withLock {
                // A cancelled or replaced monitor cannot restart a stopped service.
                if (networkMonitor !== monitor || !monitor.isRegistered() || activeSession !== session ||
                    !session.canReload(token) || !isRunning()) return@withLock
                val service = getService() ?: return@withLock
                val descriptor = currentVpnInterface
                try {
                    NotificationManager.stopSpeedNotification()
                    synchronized(nativeAccess) { coreController.stopLoop() }
                    ensureActive()
                    if (!session.canReload(token) || networkMonitor !== monitor) return@withLock
                    launchCore(service, descriptor, isReload = true)
                    ensureActive()
                    NotificationManager.startSpeedNotification()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=reload guid=${MmkvManager.getSelectServer()} failed", IOException(failure.javaClass.simpleName))
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
                    session.requestStop()
                }
            }
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = synchronized(nativeAccess) {
            if (!isRunning()) return emptyList()
            coreController.queryAllOutboundTrafficStats()
        }

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (!isRunning()) {
            return
        }

        workScope?.launch {
            val service = getService() ?: return@launch
            var time = -1L
            val errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=measure guid=${MmkvManager.getSelectServer()} failed", IOException(e.javaClass.simpleName))
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Service mode=${service.javaClass.simpleName} phase=measure-fallback guid=${MmkvManager.getSelectServer()} failed", IOException(e.javaClass.simpleName))
                }
            }

            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.d(AppConfig.TAG, "Core status code=$l")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    activeSession?.reportState()
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    activeSession?.restart()
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
