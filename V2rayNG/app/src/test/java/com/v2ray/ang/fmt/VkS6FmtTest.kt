package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.VkS6Options
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class VkS6FmtTest {
    private fun profile() = ProfileItem.create(EConfigType.VKS6).apply {
        remarks = "VK 香港 +%"
        server = "203.0.113.10"; serverPort = "8050"
        username = " 用户 :@+%&="; password = " 密码 :@+%&= "
        vkS6 = VkS6Options(8150)
    }

    @Test fun shareAndBackupPreserveCredentialsAndPortRange() {
        for (end in listOf(null, 8050, 8150, 65535)) {
            val original = profile().apply { vkS6 = VkS6Options(end) }
            val restored = VkS6Fmt.parse(AppConfig.VK_S6 + VkS6Fmt.toUri(original))!!
            assertEquals(original.remarks, restored.remarks)
            assertEquals(original.server, restored.server)
            assertEquals(original.serverPort, restored.serverPort)
            assertEquals(original.username, restored.username)
            assertEquals(original.password, restored.password)
            assertEquals(original.vkS6, restored.vkS6)
            assertEquals(EConfigType.VKS6, restored.configType)
            assertEquals(original, JsonUtil.fromJson(JsonUtil.toJson(original), ProfileItem::class.java))
        }
        assertEquals(EConfigType.VKS6, EConfigType.fromInt(14))
        val simple = VkS6Fmt.parse("vks6://a+b:pass:word@203.0.113.10:8050")!!
        assertEquals("a+b", simple.username)
        assertEquals("pass:word", simple.password)
        assertNull(simple.vkS6!!.portEnd)
    }

    @Test fun malformedAmbiguousAndUnsupportedLinksAreRejected() {
        val base = "vks6://vk-test:synthetic-secret@203.0.113.10:8050?port-end=8150"
        assertNotNull(VkS6Fmt.parse(base))
        for (text in listOf(
            base.replace("vk-test:", ":"), base.replace(":synthetic-secret", ":"),
            base.replace("vk-test:synthetic-secret@", ""), base.replace("8150", "8049"),
            base.replace("8150", "0"), base.replace("8150", "65536"), base.replace("8150", "8150.5"),
            base.replace("8150", ""), base + "&port-end=8151", base + "&tls=1", base.replace("port-end", "udp"),
            base.replace("203.0.113.10", "node.test"), base.replace("203.0.113.10", "[::1]"),
            base.replace(":8050?", ":0?"), base.replace(":8050?", ":65536?"),
            base.replace(":8050?", ":8050/path?"), base.replace("vk-test", "%FF"),
            base.replace("synthetic-secret", "%E4%B8"), base.replace("vks6:", "uusni:")
        )) assertNull("Malformed synthetic link accepted", VkS6Fmt.parse(text))
    }

    @Test fun credentialLimitsCountUtf8BytesAndPreserveWhitespace() {
        for (value in listOf(" ", "x".repeat(255), "中".repeat(85))) {
            assertTrue(VkS6Fmt.isValid(profile().apply { username = value; password = value }))
        }
        for (value in listOf("", "x".repeat(256), "中".repeat(86), "\uD800")) {
            assertFalse(VkS6Fmt.isValid(profile().apply { username = value }))
            assertFalse(VkS6Fmt.isValid(profile().apply { password = value }))
        }
    }

    @Test fun sharingCanonicalizesAcceptedNumericPortText() {
        for (port in listOf("8050", "+8050", "08050")) {
            val original = profile().apply { serverPort = port }
            assertTrue(VkS6Fmt.isValid(original))
            val restored = VkS6Fmt.parse(AppConfig.VK_S6 + VkS6Fmt.toUri(original))!!
            assertEquals("8050", restored.serverPort)
            assertEquals(original.vkS6, restored.vkS6)
        }
    }

    @Test fun invalidProfilesCannotBeSharedAndRangeChangesIdentity() {
        for (invalid in listOf(
            profile().apply { server = "1.02.3.4" }, profile().apply { server = "0.0.0.0" },
            profile().apply { server = "224.0.0.1" }, profile().apply { server = "255.255.255.255" },
            profile().apply { server = "1.2.3.256" }, profile().apply { serverPort = "bad" },
            profile().apply { vkS6 = VkS6Options(8049) }, profile().apply { username = null }
        )) {
            assertFalse(VkS6Fmt.isValid(invalid))
            assertThrows(IllegalArgumentException::class.java) { VkS6Fmt.toUri(invalid) }
        }
        val original = profile()
        assertNotEquals(original.duplicateIdentity(), original.copy(vkS6 = VkS6Options(8151)).duplicateIdentity())
        assertNotEquals(original.duplicateIdentity(), original.copy(password = "another-session").duplicateIdentity())
    }

    @Test fun precedingReleasedUuSniAndQyProxyFormatsRemainReadable() {
        val old = """{"configVersion":4,"configType":"UUSNI","subscriptionId":"old-group","remarks":"old SNI","server":"203.0.113.7","serverPort":"5556","uuSni":{"obfsKey":77,"sniCrypt":true}}"""
        val config = JsonUtil.fromJson(old, ProfileItem::class.java)!!
        assertEquals(EConfigType.UUSNI, config.configType)
        assertNull(config.vkS6)
        assertEquals(config.uuSni, UuSniFmt.parse(AppConfig.UU_SNI + UuSniFmt.toUri(config))!!.uuSni)
        assertEquals(EConfigType.QYPROXY, EConfigType.fromInt(12))
        assertEquals(EConfigType.UUSNI, EConfigType.fromInt(13))
    }
}
