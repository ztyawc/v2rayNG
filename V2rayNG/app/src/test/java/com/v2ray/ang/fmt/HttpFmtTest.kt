package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class HttpFmtTest {
    @Test fun shareRoundTripPreservesCredentialsHeadersAndLiteralPercent() {
        val profile = ProfileItem.create(EConfigType.HTTP).apply {
            server = "2001:db8::1"
            serverPort = "443"
            username = "user:name+%"
            password = "pass:word+%"
            remarks = "Node%2FShanghai"
            httpHeaders = linkedMapOf("Host" to "proxy.example.test", "X-T5-Auth" to "a:b% +")
        }
        val share = "http://" + HttpFmt.toUri(profile)
        assertTrue(HttpFmt.isProxyUri(share))
        assertFalse(share.contains("kotlin.Unit"))
        val restored = HttpFmt.parse(share)!!
        assertEquals(profile.server, restored.server)
        assertEquals(profile.serverPort, restored.serverPort)
        assertEquals(profile.username, restored.username)
        assertEquals(profile.password, restored.password)
        assertEquals(profile.httpHeaders, restored.httpHeaders)
        assertEquals(profile.remarks, restored.remarks)
    }
    @Test fun supportsUnauthenticatedProxyAndEmptyHeaders() {
        val profile = ProfileItem.create(EConfigType.HTTP).apply { server = "127.0.0.1"; serverPort = "8080" }
        val restored = HttpFmt.parse("http://" + HttpFmt.toUri(profile))!!
        assertNull(restored.username)
        assertNull(restored.httpHeaders)
    }
    @Test fun rejectsSubscriptionUrlsAndInvalidShares() {
        listOf("http://127.0.0.1/sub", "https://example.test/sub", "http://example.test:0?proxy=http",
            "http://example.test:8080?proxy=http&headers=%7B%22X-Test%22%3A%22a%5Cu0000b%22%7D").forEach {
            assertNull(it, HttpFmt.parse(it))
        }
    }
}
