package com.v2ray.ang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.ui.backup.BackupViewModel
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Run only on a dedicated test device; fixtures use loopback and contain no real credentials. */
@RunWith(AndroidJUnit4::class)
class RuntimeRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = LinkedBlockingQueue<Int>()
    private val group = AppConfig.DEFAULT_SUBSCRIPTION_ID
    private val ids = listOf("audit-keep-a", "audit-hidden", "audit-keep-b")
    private var previousMode: String? = null
    private var previousSelection: String? = null
    private var previousGroup: String? = null
    private var previousDynamic = false
    private var previousRoot = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            events.offer(intent?.getIntExtra("key", 0) ?: 0)
        }
    }

    @Before fun prepare() {
        previousMode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        previousSelection = MmkvManager.getSelectServer()
        previousGroup = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID)
        previousDynamic = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT)
        previousRoot = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_MODE_ENABLE)
        ids.forEach { id ->
            MmkvManager.encodeServerConfig(id, ProfileItem.create(EConfigType.SOCKS).apply {
                subscriptionId = group
                remarks = id
                server = "127.0.0.1"
                serverPort = "9"
            })
        }
        MmkvManager.reorderServerList(ids, group)
        MmkvManager.setSelectServer(ids.first())
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, group)
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, "PROXY")
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, true)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
    }

    @After fun cleanup() {
        LauncherManager.stopService(context)
        context.unregisterReceiver(receiver)
        ids.forEach(MmkvManager::removeServer)
        MmkvManager.setSelectServer(previousSelection.orEmpty())
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, previousMode)
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, previousGroup)
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, previousDynamic)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, previousRoot)
    }

    @Test fun proxyColdStartDuplicateRestartAndRepeatedStop() {
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        val port = SettingsManager.getSocksPort()
        assertTrue("Daemon port must be the same port observed by this process", listening(port))
        LauncherManager.startService(context)
        LauncherManager.startService(context)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
        awaitEvent(AppConfig.MSG_STATE_RUNNING)
        assertEquals(port, SettingsManager.getSocksPort())
        assertFalse(events.contains(AppConfig.MSG_STATE_START_FAILURE))
        assertFalse(events.contains(AppConfig.MSG_STATE_STOP_SUCCESS))
        assertFalse(events.contains(AppConfig.MSG_STATE_START_SUCCESS))
        LauncherManager.restartService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        val restartedPort = SettingsManager.getSocksPort()
        assertTrue(listening(restartedPort))
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        assertFalse(listening(restartedPort))
        LauncherManager.stopService(context)
        assertNull(events.poll(2, TimeUnit.SECONDS))
        assertFalse(listening(restartedPort))
    }

    @Test fun stopCancelsPendingProxyRestart() {
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        val port = SettingsManager.getSocksPort()
        LauncherManager.restartService(context)
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        assertFalse(listening(port))
        assertNull(events.poll(2, TimeUnit.SECONDS))
        assertNull(MmkvManager.decodeSettingsString("runtime_socks_port"))
    }

    @Test fun externalUidCannotStopTheDaemon() {
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        val port = SettingsManager.getSocksPort()
        val command = "am broadcast -a ${AppConfig.BROADCAST_ACTION_SERVICE} -p ${BuildConfig.APPLICATION_ID} --ei key ${AppConfig.MSG_STATE_STOP}"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertNull(events.poll(2, TimeUnit.SECONDS))
        assertTrue(listening(port))
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        assertFalse(listening(port))
    }

    @Test fun externalUidCannotForgeMainRunningState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model: MainViewModel? = null
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[MainViewModel::class.java] }
            assertFalse(model!!.uiState.value.isRunning)
            val command = "am broadcast -a ${AppConfig.BROADCAST_ACTION_ACTIVITY} -p ${BuildConfig.APPLICATION_ID} --ei key ${AppConfig.MSG_STATE_START_SUCCESS}"
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            Thread.sleep(500)
            assertFalse(model!!.uiState.value.isRunning)
        }
    }

    @Test fun deniedRootSetupReleasesForegroundServiceAndListener() {
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, true)
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_FAILURE)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        assertNull(MmkvManager.decodeSettingsString("runtime_socks_port"))
        assertFalse(events.contains(AppConfig.MSG_STATE_START_SUCCESS))
    }

    @Test fun staleResultsCannotRecreateDeletedProfileMetadata() {
        MmkvManager.encodeServerTestDelayMillis(ids[1], 42)
        assertEquals(42L, MmkvManager.decodeServerAffiliationInfo(ids[1])?.testDelayMillis)
        MmkvManager.removeServer(ids[1])
        MmkvManager.encodeServerTestDelayMillis(ids[1], 99)
        assertNull(MmkvManager.decodeServerAffiliationInfo(ids[1]))
        assertNull(MmkvManager.decodeServerConfig(ids[1]))
        MmkvManager.reorderServerList(listOf(ids[2], ids[1]), group)
        assertEquals(listOf(ids[2], ids[0]), MmkvManager.decodeServerList(group))
        assertEquals(ids.first(), MmkvManager.getSelectServer())
    }

    @Test fun stopDuringAssetSetupCannotStartProxyLater() {
        assertStopDuringAssetSetup()
    }

    @Test fun stopDuringAssetSetupCannotStartVpnLater() {
        grantVpn()
        assertStopDuringAssetSetup()
        awaitCondition { !vpnPresent() }
    }

    private fun assertStopDuringAssetSetup() {
        val assetDirectory = context.getDir(AppConfig.DIR_ASSETS, Context.MODE_PRIVATE)
        FileOutputStream(File(assetDirectory, ".bundled-assets.lock"), true).channel.use { channel ->
            channel.lock().use {
                LauncherManager.startService(context)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                var registered = false
                while (!registered && System.nanoTime() < deadline) {
                    MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
                    registered = events.poll(1, TimeUnit.SECONDS) == AppConfig.MSG_STATE_NOT_RUNNING
                }
                assertTrue("Setup must register commands before acquiring assets", registered)
                LauncherManager.stopService(context)
            }
        }
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        assertNull(events.poll(2, TimeUnit.SECONDS))
        assertNull(MmkvManager.decodeSettingsString("runtime_socks_port"))
    }

    @Test fun vpnColdDuplicateRestartAndStopReleaseTunnel() {
        grantVpn()
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        assertTrue(listening(SettingsManager.getSocksPort()))
        awaitCondition { vpnPresent() }
        LauncherManager.startService(context)
        LauncherManager.startService(context)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
        awaitEvent(AppConfig.MSG_STATE_RUNNING)
        assertFalse(events.contains(AppConfig.MSG_STATE_START_SUCCESS))
        LauncherManager.restartService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        val port = SettingsManager.getSocksPort()
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        awaitCondition { !vpnPresent() }
        assertFalse(listening(port))
        LauncherManager.stopService(context)
        assertNull(events.poll(2, TimeUnit.SECONDS))
    }

    @Test fun vpnFailureAfterDescriptorAcquisitionRemovesTunnel() {
        grantVpn()
        MmkvManager.encodeServerConfig(ids.first(), ProfileItem.create(EConfigType.CUSTOM).apply {
            subscriptionId = group
            remarks = "invalid-native-fixture"
        })
        MmkvManager.encodeServerRaw(ids.first(), "{")
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_FAILURE)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        awaitCondition { !vpnPresent() }
        assertNull(MmkvManager.decodeSettingsString("runtime_socks_port"))
        assertFalse(events.contains(AppConfig.MSG_STATE_START_SUCCESS))
    }

    @Test fun vpnStopCancelsPendingRestart() {
        grantVpn()
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        val port = SettingsManager.getSocksPort()
        LauncherManager.restartService(context)
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        awaitCondition { !vpnPresent() }
        assertFalse(listening(port))
        assertNull(events.poll(2, TimeUnit.SECONDS))
    }

    @Test fun restartCanSwitchProxyToVpnWithoutInvalidatingNewSession() {
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        grantVpn()
        LauncherManager.restartService(context)
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
        awaitCondition { vpnPresent() }
        assertTrue(listening(SettingsManager.getSocksPort()))
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        awaitCondition { !vpnPresent() }
    }

    @Test fun proxyFailureAfterForegroundAndPortAcquisitionRemovesResources() {
        MmkvManager.encodeServerConfig(ids.first(), ProfileItem.create(EConfigType.CUSTOM).apply {
            subscriptionId = group
            remarks = "invalid-native-fixture"
        })
        MmkvManager.encodeServerRaw(ids.first(), "{")
        LauncherManager.startService(context)
        awaitEvent(AppConfig.MSG_STATE_START_FAILURE)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        assertNull(MmkvManager.decodeSettingsString("runtime_socks_port"))
        assertFalse(events.contains(AppConfig.MSG_STATE_START_SUCCESS))
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        awaitCondition { notifications.activeNotifications.none { it.id == 1 } }
    }

    @Test fun backupDocumentRoundTripAndFailureCleanTemporaryFiles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = File(context.cacheDir, "audit-backup-fixture").apply { mkdirs() }
        val model = BackupViewModel(context.applicationContext as android.app.Application)
        val store = ViewModelStore().apply { put("backup", model) }
        try {
            val share = async { withTimeout(120_000) { model.viewModelEvent.first() } }
            instrumentation.runOnMainSync { model.shareBackup(cache, "audit") }
            val archive = File((share.await() as BackupViewModel.BackupViewModelEvent.ShareFile).filePath)
            assertTrue(archive.isFile)
            assertEquals(listOf(archive), archive.parentFile!!.listFiles()!!.toList())
            val document = File(cache, "document.zip")
            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".cache", document)
            instrumentation.runOnMainSync { model.exportLocal(archive.absolutePath, uri) }
            awaitCondition { !model.isLoading.value }
            assertFalse(archive.exists())
            assertTrue(document.length() > 0)
            MmkvManager.decodeServerConfig(ids.first())!!.also { profile ->
                profile.remarks = "changed"
                MmkvManager.encodeServerConfig(ids.first(), profile)
            }
            val restored = async { withTimeout(120_000) { model.viewModelEvent.first() } }
            instrumentation.runOnMainSync { model.restoreFromUri(cache, uri) }
            assertTrue(restored.await() is BackupViewModel.BackupViewModelEvent.RestoreSuccess)
            awaitCondition { !model.isLoading.value }
            assertEquals(ids.first(), MmkvManager.decodeServerConfig(ids.first())?.remarks)
            val failed = File(cache, "failed.zip").apply { writeText("fixture") }
            instrumentation.runOnMainSync { model.exportLocal(failed.absolutePath, Uri.parse("content://invalid-audit-provider/destination")) }
            awaitCondition { !model.isLoading.value }
            assertFalse(failed.exists())
            assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith("restore_") })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            cache.deleteRecursively()
        }
    }

    @Test fun searchAndGuidSelectionSurviveActivityRecreationAndReorderPersists() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[MainViewModel::class.java].onAction(MainAction.ShowSearch(true))
                ViewModelProvider(activity)[MainViewModel::class.java].onAction(MainAction.Search("keep"))
            }
            scenario.recreate()
            var model: MainViewModel? = null
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[MainViewModel::class.java] }
            awaitCondition { model!!.serverGroupState(group).value.servers.map { it.guid } == listOf(ids[0], ids[2]) }
            assertEquals("keep", model!!.uiState.value.searchQuery)
            assertTrue(model!!.uiState.value.searchVisible)
            assertEquals(ids.first(), model!!.uiState.value.selectedGuid)
            scenario.onActivity { model!!.onAction(MainAction.MoveServer(group, ids[2], ids[0])) }
            assertEquals(ids, MmkvManager.decodeServerList(group))
            scenario.onActivity { model!!.onAction(MainAction.ShowSearch(false)) }
            awaitCondition { !model!!.uiState.value.isFiltering }
            scenario.onActivity { model!!.onAction(MainAction.MoveServer(group, ids[2], ids[0])) }
            awaitCondition { MmkvManager.decodeServerList(group) == listOf(ids[2], ids[0], ids[1]) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model: MainViewModel? = null
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[MainViewModel::class.java] }
            awaitCondition { model!!.serverGroupState(group).value.servers.map { it.guid } == listOf(ids[2], ids[0], ids[1]) }
        }
    }

    private fun awaitEvent(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            val actual = events.poll(1, TimeUnit.SECONDS) ?: continue
            assertNotEquals("Unexpected setup failure", AppConfig.MSG_STATE_START_FAILURE.takeUnless { expected == it } ?: -1, actual)
            if (actual == expected) return
        }
        fail("Timed out waiting for daemon event $expected")
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for state by domain ID")
    }

    private fun listening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
        true
    } catch (_: Exception) {
        false
    }

    private fun grantVpn() {
        val command = "appops set ${BuildConfig.APPLICATION_ID} ACTIVATE_VPN allow"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertNull("Dedicated test device must allow the VPN consent operation", VpnService.prepare(context))
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
    }

    private fun vpnPresent(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }
}
