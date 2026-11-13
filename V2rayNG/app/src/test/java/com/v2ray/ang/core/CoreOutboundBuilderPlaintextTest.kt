package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoreOutboundBuilderPlaintextTest {
    private fun build(profile: ProfileItem): OutboundBean {
        val methodName = if (profile.configType == EConfigType.VLESS) "toOutboundVless" else "toOutboundTrojan"
        val method = CoreOutboundBuilder::class.java.getDeclaredMethod(methodName, ProfileItem::class.java)
        method.isAccessible = true
        return method.invoke(CoreOutboundBuilder, profile) as OutboundBean
    }

    private fun profile(type: EConfigType) = ProfileItem.create(type).apply {
        server = "9.9.9.9"
        serverPort = "80"
        password = if (type == EConfigType.VLESS) "27848739-7e62-4138-9fd3-098a63964b6b" else "test-password"
        method = "none"
        network = "tcp"
        // Use a profile-owned mask so global fragment settings are outside this test.
        finalMask = "{}"
    }

    @Test
    fun publicPlaintextOutboundsDoNotGenerateTlsOrRealitySettings() {
        for (type in listOf(EConfigType.VLESS, EConfigType.TROJAN)) {
            for (security in listOf(null, "", "none")) {
                val profile = profile(type).apply { this.security = security }
                val out = build(profile)
                assertEquals(type.name.lowercase(), out.protocol)
                assertEquals(profile.server, out.settings!!.address)
                assertEquals(80, out.settings!!.port)
                assertNull(out.streamSettings!!.tlsSettings)
                assertNull(out.streamSettings!!.realitySettings)
                assertEquals(security?.takeIf { it.isNotBlank() }, out.streamSettings!!.security)
                val json = JsonUtil.parseString(JsonUtil.toJson(out))!!.asJsonObject
                val stream = json.getAsJsonObject("streamSettings")
                assertFalse(stream.has("tlsSettings"))
                assertFalse(stream.has("realitySettings"))
                if (type == EConfigType.VLESS) {
                    assertEquals(profile.password, out.settings!!.id)
                    assertEquals("none", out.settings!!.encryption)
                } else {
                    assertEquals(profile.password, out.settings!!.password)
                }
            }
        }
    }

    @Test
    fun existingSecureOutboundsPreserveTlsAndRealitySettings() {
        for (type in listOf(EConfigType.VLESS, EConfigType.TROJAN)) {
            for (security in listOf(AppConfig.TLS, AppConfig.REALITY)) {
                val profile = profile(type).apply {
                    this.security = security
                    insecure = false
                    sni = "proxy.example.net"
                    publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    shortId = "abcd"
                }
                val stream = build(profile).streamSettings!!
                assertEquals(security, stream.security)
                val settings = if (security == AppConfig.TLS) stream.tlsSettings else stream.realitySettings
                assertNotNull(settings)
                assertEquals(profile.sni, settings!!.serverName)
                assertFalse(settings.allowInsecure)
                if (security == AppConfig.REALITY) {
                    assertEquals(profile.publicKey, settings.publicKey)
                    assertEquals(profile.shortId, settings.shortId)
                }
            }
        }
    }
}
