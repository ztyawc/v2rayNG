package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.QyProxyOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class QyProxyFmtTest {
    private fun profile(role: String = QyProxyFmt.CN2) = ProfileItem.create(EConfigType.QYPROXY).apply {
        server = "198.51.100.7"
        serverPort = "7025"
        username = "test:name+%"
        password = "test:secret+%"
        remarks = "CN2 香港+%"
        qyProxy = QyProxyOptions(role, "device+%", 19535, 21254, gameArea = "hk", zone = "zone+%")
    }

    @Test fun bothRolesRoundTripAllNegotiationFieldsAndSpecialCharacters() {
        for (role in listOf(QyProxyFmt.CN2, QyProxyFmt.CN2_DOWNLOAD)) {
            val original = profile(role)
            val restored = QyProxyFmt.parse(AppConfig.QYPROXY + QyProxyFmt.toUri(original))!!
            assertEquals(original.server, restored.server)
            assertEquals(original.serverPort, restored.serverPort)
            assertEquals(original.username, restored.username)
            assertEquals(original.password, restored.password)
            assertEquals(original.remarks, restored.remarks)
            assertEquals(original.qyProxy, restored.qyProxy)
            assertEquals(EConfigType.QYPROXY, restored.configType)
        }
    }

    @Test fun missingRequiredAndUnsupportedOrAmbiguousUriFieldsFail() {
        val base = "qyproxy://test-user:test-secret@cn2.example.test:7025?role=cn2&sn=device&game-id=456&server-id=123"
        assertNotNull(QyProxyFmt.parse(base))
        listOf(base.replace("role=cn2", "role=primary"), base.replace("role=cn2", "role=cn2&role=cn2_download"),
            base.replace("sn=device", "sn="), base.replace("game-id=456", "game-id=0"),
            base.replace("game-id=456", "game-id=4294967296"), base.replace("server-id=123", "server-id=-1"),
            base.replace(":7025", ":65536"), base.replace("test-user:test-secret", "test-user"),
            base.replace("cn2.example.test", "[::1]"), base + "&client-type=bad", base + "&tls=true", base + "&sn=other",
            base.replace("qyproxy:", "socks:"), base.replace(":7025?", ":7025/path?"), base.replace("device", "%00"))
            .forEach { assertNull(it, QyProxyFmt.parse(it)) }
    }

    @Test fun validatesByteLimitsUnsignedIdsAndRawUsernameComposition() {
        assertTrue(QyProxyFmt.isValid(profile().apply { qyProxy = qyProxy!!.copy(gameId = 0xffffffffL, serverId = 0xffffffffL) }))
        val invalid = listOf(
            profile().apply { username = "already&20&456&device" }, profile().apply { password = " " },
            profile().apply { password = "秘".repeat(43) }, profile().apply { username = "x".repeat(129) },
            profile().apply { qyProxy = qyProxy!!.copy(sn = "bad&device") },
            profile().apply { qyProxy = qyProxy!!.copy(clientType = 0) },
            profile().apply { qyProxy = qyProxy!!.copy(gameArea = "") },
            profile().apply { qyProxy = qyProxy!!.copy(product = "") },
            profile().apply { qyProxy = qyProxy!!.copy(version = "") },
            profile().apply { qyProxy = qyProxy!!.copy(zone = "x".repeat(129)) },
            profile().apply { qyProxy = null })
        invalid.forEach { assertFalse(QyProxyFmt.isValid(it)) }
    }

    @Test fun backupSerializationPreservesBothRoleAndAllMetadata() {
        val original = profile(QyProxyFmt.CN2_DOWNLOAD)
        val restored = JsonUtil.fromJson(JsonUtil.toJson(original), ProfileItem::class.java)!!
        assertEquals(original, restored)
        assertEquals(12, EConfigType.fromInt(12)?.value)
    }

    @Test fun precedingReleasedPrivateSocksAndHttpProfilesRemainReadable() {
        val previous = """{"configVersion":4,"configType":"PRIVATE_SOCKS","remarks":"old node","server":"198.51.100.7","serverPort":"10800","username":"old-user","password":"old-secret","cmccProtocol":"0x82"}"""
        val restored = JsonUtil.fromJson(previous, ProfileItem::class.java)!!
        assertEquals(EConfigType.PRIVATE_SOCKS, restored.configType)
        assertEquals("0x82", restored.cmccProtocol)
        assertNull(restored.qyProxy)
        assertNotNull(CmccSocksFmt.parse(AppConfig.CMCC_SOCKS + CmccSocksFmt.toUri(restored)))
        val http = JsonUtil.fromJson("""{"configVersion":4,"configType":"HTTP","server":"198.51.100.8","serverPort":"8080","httpHeaders":{"Host":"example.test"}}""", ProfileItem::class.java)!!
        assertEquals(mapOf("Host" to "example.test"), http.httpHeaders)
        assertNull(http.qyProxy)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRoleCannotBeExportedAsCn2() { QyProxyFmt.toUri(profile("primary")) }
}
