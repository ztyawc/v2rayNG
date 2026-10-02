package com.v2ray.ang.util

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.io.PrintWriter

class LogUtilPrivacyTest {
    @Test fun retainsFailureTypesAndLocationsWithoutSecretMessages() {
        val cause = IllegalArgumentException("https://user:dummy-secret@host/path?token=dummy-secret")
        val source = IllegalStateException("password=dummy-secret", cause).apply {
            addSuppressed(IllegalArgumentException("private-key=dummy-secret"))
        }
        val safe = LogUtil.sanitizedThrowable(source)
        val rendered = StringWriter().also { safe.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse(rendered.contains("dummy-secret"))
        assertTrue(rendered.contains("IllegalStateException"))
        assertTrue(rendered.contains("IllegalArgumentException"))
        assertArrayEquals(source.stackTrace, safe.stackTrace)
        assertArrayEquals(cause.stackTrace, safe.cause!!.stackTrace)
    }
}
