package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.HttpHeaderParser
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreOutboundBuilderHttpTest {

    @Test
    fun httpOutboundWritesCustomHeadersToSettings() {
        val profile = ProfileItem.create(EConfigType.HTTP).apply {
            server = "192.0.2.1"
            serverPort = "443"
            httpHeaders = linkedMapOf(
                "Host" to "proxy.example.test",
                "X-T5-Auth" to "test-token",
            )
        }

        val outbound = CoreOutboundBuilder.toOutboundHttp(profile)

        assertNotNull(outbound)
        assertEquals(profile.httpHeaders, outbound?.settings?.headers)

        val settings = JsonParser.parseString(JsonUtil.toJson(outbound))
            .asJsonObject
            .getAsJsonObject("settings")
        assertEquals("proxy.example.test", settings.getAsJsonObject("headers").get("Host").asString)
        assertEquals("test-token", settings.getAsJsonObject("headers").get("X-T5-Auth").asString)
    }

    @Test
    fun httpOutboundOmitsHeadersForBlankOnlyEditorInput() {
        val parsedHeaders = HttpHeaderParser.parse("\n \n\t\n")
        assertTrue(parsedHeaders.isSuccess)

        val profile = ProfileItem.create(EConfigType.HTTP).apply {
            server = "192.0.2.1"
            serverPort = "443"
            httpHeaders = parsedHeaders.headers
        }

        val outbound = CoreOutboundBuilder.toOutboundHttp(profile)

        assertNotNull(outbound)
        assertNull(outbound?.settings?.headers)
        val settings = JsonParser.parseString(JsonUtil.toJson(outbound))
            .asJsonObject
            .getAsJsonObject("settings")
        assertFalse(settings.has("headers"))
    }

    @Test
    fun profileEqualityIncludesHttpHeaders() {
        val profile = ProfileItem.create(EConfigType.HTTP).apply {
            server = "192.0.2.1"
            serverPort = "443"
            httpHeaders = linkedMapOf("Host" to "proxy.example.test")
        }

        val withDifferentHeaders = profile.copy(
            httpHeaders = linkedMapOf("Host" to "another.example")
        )

        assertNotEquals(profile, withDifferentHeaders)
    }

    @Test
    fun httpHeadersSurviveProfileJsonSerialization() {
        val profile = ProfileItem.create(EConfigType.HTTP).apply {
            httpHeaders = linkedMapOf(
                "Host" to "proxy.example.test",
                "X-T5-Auth" to "test-token",
            )
        }

        val restored = JsonUtil.fromJson(
            JsonUtil.toJson(profile),
            ProfileItem::class.java
        )

        assertEquals(profile.httpHeaders, restored?.httpHeaders)
    }
}
