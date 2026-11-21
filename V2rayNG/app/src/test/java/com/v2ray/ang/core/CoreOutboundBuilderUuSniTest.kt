package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.UuSniOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class CoreOutboundBuilderUuSniTest {
    private fun profile() = ProfileItem.create(EConfigType.UUSNI).apply {
        server = "203.0.113.10"
        serverPort = "5556"
        uuSni = UuSniOptions(77, true)
        username = "unrelated-user"
        password = "unrelated-password"
        security = "tls"
        network = "ws"
    }

    @Test fun createsNativeTcpOutboundWithoutLegacyCredentialsOrTransport() {
        for (key in listOf(0, 77, 255)) for (crypt in listOf(false, true)) {
            val p = profile().apply { uuSni = UuSniOptions(key, crypt) }
            val out = CoreOutboundBuilder.toOutboundUuSni(p)!!
            assertEquals("uusni", out.protocol)
            assertFalse(out.mux!!.enabled)
            assertEquals(-1, out.mux!!.concurrency)
            assertEquals("tcp", out.streamSettings!!.network)
            assertNull(out.streamSettings!!.security)
            val settings = JsonParser.parseString(JsonUtil.toJson(out)).asJsonObject.getAsJsonObject("settings")
            assertEquals("203.0.113.10", settings["address"].asString)
            assertEquals(5556, settings["port"].asInt)
            assertEquals(key, settings["obfsKey"].asInt)
            assertEquals(crypt, settings["sniCrypt"].asBoolean)
            for (field in listOf("user", "pass", "password", "cmccProtocol", "sessionId")) assertFalse(settings.has(field))
        }
    }

    @Test fun rejectsMissingKeyUnsupportedNodeAndInvalidPort() {
        listOf(profile().apply { uuSni = null }, profile().apply { uuSni = UuSniOptions() },
            profile().apply { uuSni = UuSniOptions(-1) }, profile().apply { server = "node.test" },
            profile().apply { serverPort = "65536" }).forEach { assertNull(CoreOutboundBuilder.toOutboundUuSni(it)) }
    }
}
