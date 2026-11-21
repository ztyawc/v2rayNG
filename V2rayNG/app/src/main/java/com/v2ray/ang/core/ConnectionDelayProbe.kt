package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Pure decision and error-sanitization rules for connected-outbound delay probes.
 */
internal object ConnectionDelayProbe {
    const val FALLBACK_SKIP_ELAPSED_MS = 3_500L
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun shouldTryFallback(
        primaryUrl: String,
        fallbackUrl: String,
        primaryDelayMillis: Long,
        primaryElapsedMs: Long,
        primaryError: Throwable?,
    ): Boolean {
        if (primaryDelayMillis >= 0L) return false
        if (fallbackUrl.isBlank() || fallbackUrl == primaryUrl) return false
        if (primaryElapsedMs >= FALLBACK_SKIP_ELAPSED_MS) return false
        return !isTimeoutOrCancellation(primaryError)
    }

    fun isTimeoutOrCancellation(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is CancellationException ||
                current is SocketTimeoutException ||
                current is InterruptedIOException
            ) {
                return true
            }
            val msg = current.message?.lowercase(Locale.ROOT).orEmpty()
            if ("timeout" in msg ||
                "timed out" in msg ||
                "deadline exceeded" in msg ||
                "canceled" in msg ||
                "cancelled" in msg
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    fun extractErrorMessage(error: Throwable?): String {
        val raw = error?.message?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val stripped = if ("\": " in raw) raw.substringAfter("\": ") else raw.substringAfter("\":", raw)
        return URL_REGEX.replace(stripped, " ").replace(WHITESPACE_REGEX, " ").trim()
    }
}
