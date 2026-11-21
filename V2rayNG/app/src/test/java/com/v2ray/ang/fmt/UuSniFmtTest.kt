package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.UuSniOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class UuSniFmtTest {
    private fun profile() = ProfileItem.create(EConfigType.UUSNI).apply {
        remarks = "SNI 香港 +%"
        server = "203.0.113.10"
        serverPort = "5556"
        uuSni = UuSniOptions(77, true)
    }

    @Test fun shareAndBackupRoundTripEveryOptionIncludingExplicitZero() {
        for (key in listOf(0, 77, 255)) for (crypt in listOf(false, true)) {
            val original = profile().apply { uuSni = UuSniOptions(key, crypt) }
            val restored = UuSniFmt.parse(AppConfig.UU_SNI + UuSniFmt.toUri(original))!!
            assertEquals(original.remarks, restored.remarks)
            assertEquals(original.server, restored.server)
            assertEquals(original.serverPort, restored.serverPort)
            assertEquals(original.uuSni, restored.uuSni)
            assertEquals(EConfigType.UUSNI, restored.configType)
            assertEquals(original, JsonUtil.fromJson(JsonUtil.toJson(original), ProfileItem::class.java))
        }
        assertEquals(EConfigType.UUSNI, EConfigType.fromInt(13))
    }

    @Test fun missingAmbiguousOrUnsupportedParametersNeverDefaultToZeroOrTls() {
        val base = "uusni://203.0.113.10:5556?obfs-key=77&sni-crypt=1"
        assertNotNull(UuSniFmt.parse(base))
        listOf(base.replace("77", "256"), base.replace("77", "-1"), base.replace("77", ""),
            base.replace("obfs-key=77&", ""), base.replace("&sni-crypt=1", ""),
            base.replace("sni-crypt=1", "sni-crypt=true"), base + "&obfs-key=0", base + "&outer-tls=1",
            base.replace(":5556?", ":5556/path?"), base.replace("203.0.113.10", "[::1]"),
            base.replace("203.0.113.10", "node.test"), base.replace("203.0.113.10", "user@203.0.113.10"),
            base.replace(":5556", ":0"), base.replace(":5556", ":65536"), base.replace("uusni:", "socks:"))
            .forEach { assertNull(it, UuSniFmt.parse(it)) }
    }

    @Test fun invalidProfilesCannotBeBuiltOrShared() {
        val invalid = listOf(profile().apply { uuSni = null }, profile().apply { uuSni = UuSniOptions() },
            profile().apply { uuSni = UuSniOptions(256) }, profile().apply { server = "1.02.3.4" },
            profile().apply { server = "0.0.0.0" }, profile().apply { server = "224.0.0.1" },
            profile().apply { server = "255.255.255.255" }, profile().apply { server = "1.2.3.256" },
            profile().apply { server = "1.2.3" }, profile().apply { server = "" }, profile().apply { serverPort = "invalid" })
        invalid.forEach {
            assertFalse(UuSniFmt.isValid(it))
            assertThrows(IllegalArgumentException::class.java) { UuSniFmt.toUri(it) }
        }
    }

    @Test fun precedingReleasedProfilesRemainReadableWithoutNewOptions() {
        val old = """{"configVersion":4,"configType":"QYPROXY","subscriptionId":"old-group","remarks":"old node","server":"198.51.100.7","serverPort":"7025","username":"old-user","password":"old-secret","qyProxy":{"role":"primary","sn":"old-device","gameId":456,"serverId":123}}"""
        val config = JsonUtil.fromJson(old, ProfileItem::class.java)!!
        assertEquals(EConfigType.QYPROXY, config.configType)
        assertEquals("old-secret", config.password)
        assertEquals("primary", config.qyProxy!!.role)
        assertNull(config.uuSni)
        assertEquals(config.qyProxy, JsonUtil.fromJson(JsonUtil.toJson(config), ProfileItem::class.java)!!.qyProxy)
    }
}
