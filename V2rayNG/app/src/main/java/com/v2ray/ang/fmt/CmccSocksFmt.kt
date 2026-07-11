package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Formatter for the private CMCC SOCKS variants described by the `cmcc://` URI.
 *
 * The private authentication mode is intentionally required instead of inferred so a
 * malformed import can never silently become a normal SOCKS proxy.
 */
object CmccSocksFmt : FmtBase() {
    const val AUTH_80 = "0x80"
    const val AUTH_82 = "0x82"

    /** Returns the canonical private SOCKS authentication mode, or null when unsupported. */
    fun normalizeCmccProtocol(value: String?): String? = when (value?.trim()?.lowercase()) {
        AUTH_80 -> AUTH_80
        AUTH_82 -> AUTH_82
        else -> null
    }

    /** Parses a `cmcc://user:password@host:port?auth=0x80#remark` URI. */
    fun parse(str: String): ProfileItem? {
        return try {
            val uri = URI(Utils.fixIllegalUrl(str))
            if (!uri.scheme.equals("cmcc", ignoreCase = true)) return null
            if (uri.idnHost.isEmpty() || uri.port !in 1..65535) return null

            val credentials = uri.userInfo?.split(":", limit = 2) ?: return null
            if (credentials.size != 2 || credentials[0].isBlank() || credentials[1].isBlank()) {
                return null
            }

            val cmccProtocol = parseAuth(uri) ?: return null
            ProfileItem.create(EConfigType.PRIVATE_SOCKS).apply {
                remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
                server = uri.idnHost
                serverPort = uri.port.toString()
                username = credentials[0]
                password = credentials[1]
                this.cmccProtocol = cmccProtocol
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Converts a private SOCKS profile to the URI body; callers prepend `cmcc://`. */
    fun toUri(config: ProfileItem): String {
        val query = hashMapOf(
            "auth" to (normalizeCmccProtocol(config.cmccProtocol) ?: AUTH_80)
        )
        val userInfo = "${config.username.orEmpty()}:${config.password.orEmpty()}"
        return toUri(config, userInfo, query)
    }

    private fun parseAuth(uri: URI): String? {
        val authValues = uri.rawQuery
            ?.split('&')
            ?.mapNotNull { item ->
                val pair = item.split('=', limit = 2)
                if (pair.size != 2) return@mapNotNull null
                val key = Utils.decodeURIComponent(pair[0])
                if (!key.equals("auth", ignoreCase = true)) return@mapNotNull null
                Utils.decodeURIComponent(pair[1])
            }
            ?: return null

        return authValues.singleOrNull()?.let(::normalizeCmccProtocol)
    }
}
