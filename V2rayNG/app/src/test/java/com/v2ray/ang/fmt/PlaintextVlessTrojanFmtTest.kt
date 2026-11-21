package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaintextVlessTrojanFmtTest {
    @Test
    fun plaintextSharesExplicitlyPreserveSecurityNone() {
        for (type in listOf(EConfigType.VLESS, EConfigType.TROJAN)) {
            for (security in listOf(null, "", "none")) {
                val profile = ProfileItem.create(type).apply {
                    server = "proxy.example.net"
                    serverPort = "80"
                    password = if (type == EConfigType.VLESS) "27848739-7e62-4138-9fd3-098a63964b6b" else "test-password"
                    method = "none"
                    network = "ws"
                    path = "/proxy"
                    remarks = "No TLS"
                    this.security = security
                }
                val share = if (type == EConfigType.VLESS) {
                    "vless://" + VlessFmt.toUri(profile)
                } else {
                    "trojan://" + TrojanFmt.toUri(profile)
                }
                assertTrue("Plaintext share must explicitly disable TLS", share.contains("security=none"))
                val parsed = if (type == EConfigType.VLESS) VlessFmt.parse(share)!! else TrojanFmt.parse(share)
                val restored = JsonUtil.fromJson(JsonUtil.toJson(parsed), ProfileItem::class.java)!!
                assertEquals(profile.configType, restored.configType)
                assertEquals(profile.server, restored.server)
                assertEquals(profile.serverPort, restored.serverPort)
                assertEquals(profile.password, restored.password)
                assertEquals(profile.network, restored.network)
                assertEquals(profile.path, restored.path)
                assertEquals(profile.remarks, restored.remarks)
                assertFalse(restored.security == AppConfig.TLS || restored.security == AppConfig.REALITY)
            }
        }
    }

    @Test
    fun oldTrojanSharesWithoutSecurityKeepTheirTlsDefault() {
        for (suffix in listOf("", "?type=tcp")) {
            val profile = TrojanFmt.parse("trojan://test-password@proxy.example.net:443$suffix")
            assertEquals(AppConfig.TLS, profile.security)
            assertFalse(profile.insecure == true)
        }
    }

    @Test
    fun tlsAndRealitySharesKeepTheirSecurityParameters() {
        for (type in listOf(EConfigType.VLESS, EConfigType.TROJAN)) {
            for (security in listOf(AppConfig.TLS, AppConfig.REALITY)) {
                val profile = ProfileItem.create(type).apply {
                    server = "proxy.example.net"
                    serverPort = "443"
                    password = "test-password"
                    this.security = security
                    sni = "proxy.example.net"
                    publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    shortId = "abcd"
                    insecure = false
                }
                val restored = if (type == EConfigType.VLESS) {
                    VlessFmt.parse("vless://" + VlessFmt.toUri(profile))!!
                } else {
                    TrojanFmt.parse("trojan://" + TrojanFmt.toUri(profile))
                }
                assertEquals(security, restored.security)
                assertEquals(profile.sni, restored.sni)
                assertEquals(profile.publicKey, restored.publicKey)
                assertEquals(profile.shortId, restored.shortId)
                assertFalse(restored.insecure == true)
            }
        }
    }
}
