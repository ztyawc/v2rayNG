package com.v2ray.ang.util

import java.util.Locale

/**
 * Parses the multiline HTTP outbound header editor format.
 *
 * Header names are kept exactly as entered so that intentional casing (for example, `X-T5-Auth`)
 * is preserved in generated core configuration. Duplicate detection follows HTTP's
 * case-insensitive field-name rules.
 */
object HttpHeaderParser {
    enum class ErrorType {
        MISSING_COLON,
        INVALID_NAME,
        INVALID_VALUE,
        DUPLICATE_NAME,
    }

    data class ParseError(
        val lineNumber: Int,
        val type: ErrorType,
    )

    data class ParseResult(
        val headers: LinkedHashMap<String, String>,
        val error: ParseError? = null,
    ) {
        val isSuccess: Boolean
            get() = error == null
    }

    /**
     * Parses one `Name: Value` header per line.
     *
     * Empty lines are ignored. Only the first colon separates a name from its value, so values
     * such as URLs and timestamps retain subsequent colons. CR/LF cannot be present in a stored
     * header value.
     */
    fun parse(rawHeaders: String?): ParseResult {
        val headers = linkedMapOf<String, String>()
        val seenNames = hashSetOf<String>()

        rawHeaders.orEmpty().splitToSequence('\n').forEachIndexed { index, rawLine ->
            // Accept Windows line endings while still rejecting a CR embedded in a value.
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) {
                return@forEachIndexed
            }

            val separatorIndex = line.indexOf(':')
            if (separatorIndex < 0) {
                return ParseResult(
                    headers,
                    ParseError(index + 1, ErrorType.MISSING_COLON)
                )
            }

            val name = line.substring(0, separatorIndex)
            val rawValue = line.substring(separatorIndex + 1)
            if (!name.isHttpToken()) {
                return ParseResult(
                    headers,
                    ParseError(index + 1, ErrorType.INVALID_NAME)
                )
            }
            if (rawValue.contains('\r') || rawValue.contains('\n')) {
                return ParseResult(
                    headers,
                    ParseError(index + 1, ErrorType.INVALID_VALUE)
                )
            }

            val value = rawValue.trim()

            val normalizedName = name.lowercase(Locale.ROOT)
            if (!seenNames.add(normalizedName)) {
                return ParseResult(
                    headers,
                    ParseError(index + 1, ErrorType.DUPLICATE_NAME)
                )
            }
            headers[name] = value
        }

        return ParseResult(headers)
    }

    /** Formats persisted headers back into the editor's one-header-per-line form. */
    fun format(headers: Map<String, String>?): String {
        return headers.orEmpty().entries.joinToString("\n") { (name, value) -> "$name: $value" }
    }

    private fun String.isHttpToken(): Boolean {
        return isNotEmpty() && all { char ->
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in TOKEN_SPECIALS
        }
    }

    private const val TOKEN_SPECIALS = "!#$%&'*+-.^_`|~"
}
