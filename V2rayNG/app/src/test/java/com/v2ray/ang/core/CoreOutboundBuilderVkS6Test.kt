package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.VkS6Options
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class CoreOutboundBuilderVkS6Test {
    private fun profile() = ProfileItem.create(EConfigType.VKS6).apply {
        server = "203.0.113.10"; serverPort = "8050"
        username = "vk-test"; password = "synthetic-secret"
        vkS6 = VkS6Options(8150)
        network = "ws"; security = "tls"; cmccProtocol = "0x80"
    }

    @Test fun buildsNativeS6WithIpv4DnsAndDisablesMuxAndOtherTransports() {
        for (end in listOf(null, 8050, 8150)) {
            val out = CoreOutboundBuilder.toOutboundVkS6(profile().apply { vkS6 = VkS6Options(end) })!!
            assertEquals("vks6", out.protocol)
            assertEquals("ForceIPv4", out.targetStrategy)
            assertEquals("tcp", out.streamSettings!!.network)
            assertNull(out.streamSettings!!.security)
            assertFalse(out.mux!!.enabled)
            assertEquals(-1, out.mux!!.concurrency)
            val settings = JsonParser.parseString(JsonUtil.toJson(out)).asJsonObject.getAsJsonObject("settings")
            assertEquals("203.0.113.10", settings["address"].asString)
            assertEquals(8050, settings["port"].asInt)
            assertEquals("vk-test", settings["username"].asString)
            assertEquals("synthetic-secret", settings["password"].asString)
            if (end == null) assertFalse(settings.has("portEnd")) else assertEquals(end, settings["portEnd"].asInt)
            for (key in listOf("user", "pass", "cmccProtocol", "obfsKey", "sniCrypt")) assertFalse(settings.has(key))
        }
    }

    @Test fun invalidCredentialsAndEndpointsNeverProduceAnOutbound() {
        for (invalid in listOf(profile().apply { password = "" }, profile().apply { server = "node.test" },
            profile().apply { serverPort = "65536" }, profile().apply { vkS6 = VkS6Options(8049) })) {
            assertNull(CoreOutboundBuilder.toOutboundVkS6(invalid))
        }
    }
}
