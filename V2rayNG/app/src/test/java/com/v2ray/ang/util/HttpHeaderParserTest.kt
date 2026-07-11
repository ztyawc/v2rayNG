package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeaderParserTest {

    @Test
    fun parsesExampleHeadersAndPreservesValueColons() {
        val input = """
            Host: proxy.example.test
            X-T5-Auth: test-token
            X-Endpoint: https://example.com:443/path
        """.trimIndent()

        val result = HttpHeaderParser.parse(input)

        assertTrue(result.isSuccess)
        assertEquals(
            linkedMapOf(
                "Host" to "proxy.example.test",
                "X-T5-Auth" to "test-token",
                "X-Endpoint" to "https://example.com:443/path",
            ),
            result.headers
        )
        assertEquals(input, HttpHeaderParser.format(result.headers))
    }

    @Test
    fun ignoresBlankLinesAndAcceptsWindowsLineEndings() {
        val result = HttpHeaderParser.parse("\r\nHost: proxy.example.test\r\n\r\nX-T5-Auth: test-token\r\n")

        assertTrue(result.isSuccess)
        assertEquals(
            linkedMapOf("Host" to "proxy.example.test", "X-T5-Auth" to "test-token"),
            result.headers
        )
    }

    @Test
    fun treatsEmptyAndBlankOnlyInputAsNoHeaders() {
        val empty = HttpHeaderParser.parse(null)
        val blankOnly = HttpHeaderParser.parse("\n  \n\t\n")

        assertTrue(empty.isSuccess)
        assertTrue(empty.headers.isEmpty())
        assertTrue(blankOnly.isSuccess)
        assertTrue(blankOnly.headers.isEmpty())
    }

    @Test
    fun rejectsMalformedHeaderLinesAndInvalidTokens() {
        val missingColon = HttpHeaderParser.parse("Host: proxy.example.test\nX-T5-Auth test-token")
        assertFalse(missingColon.isSuccess)
        assertEquals(2, missingColon.error?.lineNumber)
        assertEquals(HttpHeaderParser.ErrorType.MISSING_COLON, missingColon.error?.type)

        val invalidName = HttpHeaderParser.parse("Bad Header: value")
        assertFalse(invalidName.isSuccess)
        assertEquals(HttpHeaderParser.ErrorType.INVALID_NAME, invalidName.error?.type)

        val invalidValue = HttpHeaderParser.parse("X-Test: value\rnot-a-new-line")
        assertFalse(invalidValue.isSuccess)
        assertEquals(HttpHeaderParser.ErrorType.INVALID_VALUE, invalidValue.error?.type)
    }

    @Test
    fun rejectsDuplicateNamesCaseInsensitively() {
        val result = HttpHeaderParser.parse("Host: proxy.example.test\nhOsT: another.example")

        assertFalse(result.isSuccess)
        assertEquals(2, result.error?.lineNumber)
        assertEquals(HttpHeaderParser.ErrorType.DUPLICATE_NAME, result.error?.type)
    }
}
