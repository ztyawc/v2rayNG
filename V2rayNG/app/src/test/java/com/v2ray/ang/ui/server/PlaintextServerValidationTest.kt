package com.v2ray.ang.ui.server

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class PlaintextServerValidationTest {
    private fun validate(activity: BaseServerActivity, methodName: String, config: ProfileItem): Boolean {
        val method = if (methodName == "validateCommonConfig") {
            BaseServerActivity::class.java.getDeclaredMethod(methodName, ProfileItem::class.java)
        } else {
            activity.javaClass.getDeclaredMethod(methodName, ProfileItem::class.java)
        }
        method.isAccessible = true
        return method.invoke(activity, config) as Boolean
    }

    private fun trojan(): ProfileItem = ProfileItem.create(EConfigType.TROJAN).apply {
        server = "proxy.example.net"
        serverPort = "443"
        password = "test-password"
    }

    @Test
    fun trojanEditorAcceptsPlaintextAndSecureProfiles() {
        val activity = Mockito.mock(ServerTrojanActivity::class.java, Mockito.CALLS_REAL_METHODS)
        Mockito.mockStatic(Class.forName("com.v2ray.ang.extension.ToastExtKt")).use {
            for (security in listOf(null, "", "none", AppConfig.TLS, AppConfig.REALITY)) {
                val profile = trojan().apply { this.security = security }
                assertTrue("Common validation rejected $security", validate(activity, "validateCommonConfig", profile))
                assertTrue("Trojan validation rejected $security", validate(activity, "validateProtocolConfig", profile))
            }
        }
    }

    @Test
    fun trojanEditorStillRejectsMissingPasswords() {
        val activity = Mockito.mock(ServerTrojanActivity::class.java, Mockito.CALLS_REAL_METHODS)
        Mockito.mockStatic(Class.forName("com.v2ray.ang.extension.ToastExtKt")).use {
            for (password in listOf(null, "", " ")) {
                val profile = trojan().apply { this.password = password }
                assertFalse(validate(activity, "validateCommonConfig", profile))
                assertFalse(validate(activity, "validateProtocolConfig", profile))
            }
        }
    }

    @Test
    fun vlessEditorAcceptsPlaintextAndStillRequiresUserId() {
        val activity = Mockito.mock(ServerVlessActivity::class.java, Mockito.CALLS_REAL_METHODS)
        Mockito.mockStatic(Class.forName("com.v2ray.ang.extension.ToastExtKt")).use {
            val profile = ProfileItem.create(EConfigType.VLESS).apply {
                server = "proxy.example.net"
                serverPort = "80"
                password = "27848739-7e62-4138-9fd3-098a63964b6b"
                method = "none"
            }
            assertTrue(validate(activity, "validateCommonConfig", profile))
            assertTrue(validate(activity, "validateProtocolConfig", profile))
            profile.password = ""
            assertFalse(validate(activity, "validateCommonConfig", profile))
            assertFalse(validate(activity, "validateProtocolConfig", profile))
        }
    }

    @Test
    fun plaintextTrojanStillRejectsMalformedTransportOptions() {
        val activity = Mockito.mock(ServerTrojanActivity::class.java, Mockito.CALLS_REAL_METHODS)
        val logLevel = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val previousLogLevel = logLevel.getInt(null)
        logLevel.setInt(null, Log.ERROR)
        try {
            Mockito.mockStatic(Log::class.java).use {
                Mockito.mockStatic(Class.forName("com.v2ray.ang.extension.ToastExtKt")).use {
                    assertFalse(validate(activity, "validateCommonConfig", trojan().apply { xhttpExtra = "{" }))
                    assertFalse(validate(activity, "validateCommonConfig", trojan().apply { finalMask = "{" }))
                }
            }
        } finally {
            logLevel.setInt(null, previousLogLevel)
        }
    }
}
