package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.runInterruptible

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread. On teardown the rules are removed before the core stops.
 */
class CoreRootService : Service(), ServiceControl {

    private lateinit var session: CoreServiceManager.Session
    private var rootSetupAttempted = false

    override fun onCreate() {
        super.onCreate()
        session = CoreServiceManager.createSession(
            control = this,
            setup = { runInterruptible { check(RootManager.isRootAvailable()) { "Root access unavailable" } }; null },
            afterStart = {
                rootSetupAttempted = true
                runInterruptible { check(RootProxyManager.start(this)) { "Root setup failed" } }
            },
            release = {
                if (rootSetupAttempted) {
                    runInterruptible { RootProxyManager.stop(this) }
                    rootSetupAttempted = false
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        session.start(intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session.destroy()
        super.onDestroy()
    }

    override fun getService(): Service = this

    override fun startService() {
        session.start()
    }

    override fun stopService() {
        session.requestStop()
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}
