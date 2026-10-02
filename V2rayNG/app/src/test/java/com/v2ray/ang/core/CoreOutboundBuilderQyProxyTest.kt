package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.QyProxyOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class CoreOutboundBuilderQyProxyTest {
    private fun profile() = ProfileItem.create(EConfigType.QYPROXY).apply {
        server = "198.51.100.7"
        serverPort = "7025"
        username = "test-user"
        password = "test-secret"
        qyProxy = QyProxyOptions(sn = "test-device", gameId = 456, serverId = 123, gameArea = "hk")
    }

    @Test fun nativeSettingsPreserveRoleCredentialsAndDistinctGameAndServerIds() {
        for (role in listOf("cn2", "cn2_download")) {
            val p = profile().apply { qyProxy = qyProxy!!.copy(role = role) }
            val out = CoreOutboundBuilder.toOutboundQyProxy(p)!!
            assertEquals("qyproxy", out.protocol)
            assertFalse(out.mux!!.enabled)
            val json = JsonParser.parseString(JsonUtil.toJson(out)).asJsonObject.getAsJsonObject("settings")
            assertEquals("198.51.100.7", json["address"].asString)
            assertEquals(7025, json["port"].asInt)
            assertEquals("test-user", json["user"].asString)
            assertEquals("test-secret", json["password"].asString)
            assertEquals(role, json["role"].asString)
            assertEquals("test-device", json["sn"].asString)
            assertEquals(456, json["gameId"].asInt)
            assertEquals(123, json["serverId"].asInt)
            assertEquals(20, json["clientType"].asInt)
            assertEquals("hk", json["gameArea"].asString)
            assertEquals("hk", json["zone"].asString)
            assertEquals("hy-android", json["product"].asString)
            assertEquals("1.0.0", json["clientVersion"].asString)
            assertFalse(json.has("cmccProtocol"))
        }
    }

    @Test fun invalidNativeSettingsAreRejected() {
        listOf(profile().apply { qyProxy = null }, profile().apply { qyProxy = qyProxy!!.copy(role = "primary") },
            profile().apply { qyProxy = qyProxy!!.copy(sn = "") }, profile().apply { serverPort = "0" },
            profile().apply { qyProxy = qyProxy!!.copy(serverId = 0x100000000L) })
            .forEach { assertNull(CoreOutboundBuilder.toOutboundQyProxy(it)) }
    }
}
