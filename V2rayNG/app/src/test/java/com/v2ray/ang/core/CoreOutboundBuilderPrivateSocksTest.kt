package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoreOutboundBuilderPrivateSocksTest {

    private fun validProfile() = ProfileItem.create(EConfigType.PRIVATE_SOCKS).apply {
        server = "198.51.100.7"
        serverPort = "10800"
        username = "test-user"
        password = "test-password"
        cmccProtocol = "0x80"
    }

    @Test
    fun privateSocksOutboundEmitsEachCmccProtocolInTheSocksSettings() {
        listOf("0x80", "0x82").forEach { auth ->
            val profile = ProfileItem.create(EConfigType.PRIVATE_SOCKS).apply {
                // A documentation-range IP keeps this pure JVM test independent of MMKV's
                // configurable domain-resolution path.
                server = "198.51.100.7"
                serverPort = "10800"
                username = "test-user"
                password = "test-password"
                cmccProtocol = auth
            }

            val outbound = CoreOutboundBuilder.toOutboundPrivateSocks(profile)

            assertNotNull(outbound)
            assertEquals("socks", outbound?.protocol)
            val settings = outbound?.settings
            assertEquals("198.51.100.7", settings?.address)
            assertEquals(10800, settings?.port)
            assertEquals("test-user", settings?.user)
            assertEquals("test-password", settings?.pass)
            assertEquals(auth, settings?.cmccProtocol)

            val settingsJson = JsonParser.parseString(JsonUtil.toJson(outbound))
                .asJsonObject
                .getAsJsonObject("settings")
            assertEquals(auth, settingsJson.get("cmccProtocol").asString)
        }
    }

    @Test
    fun privateSocksOutboundRejectsInvalidPrivateConfiguration() {
        listOf(
            validProfile().apply { cmccProtocol = "0x81" },
            validProfile().apply { username = " " },
            validProfile().apply { password = " " },
            validProfile().apply { serverPort = "0" },
        ).forEach { profile ->
            assertNull(CoreOutboundBuilder.toOutboundPrivateSocks(profile))
        }
    }
}
