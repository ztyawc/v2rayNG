package com.v2ray.ang

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

class HttpRedirectTest {
    private class Server : Closeable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val captured = AtomicReference<Map<String, String>>()
        var redirect: String? = null
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5000
                        val input = connection.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1]
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val response = if (path == "/start" && redirect != null) {
                            "HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            captured.set(headers)
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        }
                        connection.getOutputStream().write(response.toByteArray())
                    }
                } catch (error: IOException) { if (!socket.isClosed) throw error }
            }
        }.apply { isDaemon = true }
        fun start() { worker.start() }
        override fun close() { socket.close(); worker.join(1000) }
    }

    @Test fun crossOriginRedirectDropsAuthorizationCookiesAndCustomSecrets() {
        Server().use { destination ->
            Server().use { source ->
                source.redirect = destination.url + "/target"
                destination.start(); source.start()
                assertEquals("ok", HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(
                    source.url.replace("http://", "http://user:password@") + "/start",
                    requestHeaders = "{\"Authorization\":\"Bearer dummy\",\"Cookie\":\"dummy\",\"X-Secret\":\"dummy\"}")))
                val headers = destination.captured.get()
                assertNull(headers["authorization"])
                assertNull(headers["cookie"])
                assertNull(headers["x-secret"])
                assertNull(headers["proxy-authorization"])
            }
        }
    }

    @Test fun relativeSameOriginRedirectRetainsConfiguredAuthorization() {
        Server().use { source ->
            source.redirect = "/target"
            source.start()
            assertEquals("ok", HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(source.url + "/start",
                requestHeaders = "{\"Authorization\":\"Bearer dummy\"}")))
            assertEquals("Bearer dummy", source.captured.get()["authorization"])
        }
    }

    @Test(expected = IOException::class)
    fun rejectsHttpsDowngrade() { HttpUtil.redirectTarget("https://example.test/sub".toHttpUrl(), "http://example.test/sub") }

    @Test fun originComparisonIncludesSchemeAndPort() {
        val origin = "https://example.test/a".toHttpUrl()
        assertTrue(HttpUtil.sameOrigin(origin, "https://EXAMPLE.test:443/b".toHttpUrl()))
        assertFalse(HttpUtil.sameOrigin(origin, "https://example.test:444/b".toHttpUrl()))
        assertFalse(HttpUtil.sameOrigin(origin, "http://example.test/b".toHttpUrl()))
        assertFalse(HttpUtil.sameOrigin(origin, "https://other.test/b".toHttpUrl()))
    }
}
