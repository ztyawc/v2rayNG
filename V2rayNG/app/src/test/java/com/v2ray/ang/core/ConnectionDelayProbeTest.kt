package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ConnectionDelayProbeTest {

    @Test
    fun shouldTryFallbackSkipsSuccessfulIdenticalBlankAndTimedOutProbes() {
        val primary = "https://www.gstatic.com/generate_204"
        val fallback = "https://www.google.com/generate_204"

        assertFalse(ConnectionDelayProbe.shouldTryFallback(primary, fallback, 85L, 85L, null))
        assertFalse(ConnectionDelayProbe.shouldTryFallback(primary, primary, -1L, 120L, IOException("invalid status: 403")))
        assertFalse(ConnectionDelayProbe.shouldTryFallback(primary, "", -1L, 120L, IOException("invalid status: 403")))
        assertFalse(
            ConnectionDelayProbe.shouldTryFallback(
                primary,
                fallback,
                -1L,
                ConnectionDelayProbe.FALLBACK_SKIP_ELAPSED_MS,
                IOException("connection reset"),
            )
        )
        assertFalse(
            ConnectionDelayProbe.shouldTryFallback(
                primary,
                fallback,
                -1L,
                200L,
                Exception("Get \"https://www.gstatic.com/generate_204\": net/http: TLS handshake timeout"),
            )
        )
        assertFalse(
            ConnectionDelayProbe.shouldTryFallback(
                primary,
                fallback,
                -1L,
                200L,
                SocketTimeoutException("Read timed out"),
            )
        )
        assertFalse(
            ConnectionDelayProbe.shouldTryFallback(
                primary,
                fallback,
                -1L,
                10L,
                CancellationException("context canceled"),
            )
        )
        assertTrue(
            ConnectionDelayProbe.shouldTryFallback(
                "https://custom.example/generate_204",
                fallback,
                -1L,
                180L,
                Exception("invalid status: 502 Bad Gateway"),
            )
        )
    }

    @Test
    fun extractErrorMessageStripsGoUrlPrefixAndRedactsRawUrls() {
        assertEquals(
            "net/http: TLS handshake timeout",
            ConnectionDelayProbe.extractErrorMessage(
                Exception("Get \"https://www.gstatic.com/generate_204\": net/http: TLS handshake timeout")
            )
        )
        assertEquals(
            "dial connection refused",
            ConnectionDelayProbe.extractErrorMessage(
                Exception("dial https://secret:pass@example.com/path connection refused")
            )
        )
        assertEquals("", ConnectionDelayProbe.extractErrorMessage(null))
    }
}
