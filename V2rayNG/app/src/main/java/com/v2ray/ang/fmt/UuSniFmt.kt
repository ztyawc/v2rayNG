package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.UuSniOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.net.URI

/** The supported protocol is custom SNI/TCP without an outer TLS connection. */
object UuSniFmt {
    fun isValid(config: ProfileItem): Boolean {
        if (config.configType != EConfigType.UUSNI) return false
        val options = config.uuSni ?: return false
        val key = options.obfsKey ?: return false
        val port = config.serverPort?.toIntOrNull() ?: return false
        if (key !in 0..255 || port !in 1..65535) return false
        val parts = config.server.orEmpty().split('.')
        if (parts.size != 4 || parts.any { part ->
                part.isEmpty() || part.any { it !in '0'..'9' } ||
                    part.length > 3 || (part.length > 1 && part.startsWith('0')) ||
                    part.toIntOrNull() !in 0..255
            }) return false
        val first = parts[0].toInt()
        return config.server != "0.0.0.0" && first !in 224..255
    }

    fun parse(text: String): ProfileItem? {
        return try {
            val uri = URI(text)
            if (!uri.scheme.equals("uusni", true) || uri.rawUserInfo != null || uri.rawPath.orEmpty().isNotEmpty()) null
            else {
                val query = linkedMapOf<String, String>()
                for (entry in uri.rawQuery.orEmpty().split('&')) {
                    val pair = entry.split('=', limit = 2)
                    if (pair.size != 2) return null
                    val key = Utils.decodeURIComponent(pair[0])
                    if (key !in setOf("obfs-key", "sni-crypt") || query.containsKey(key)) return null
                    query[key] = Utils.decodeURIComponent(pair[1])
                }
                val crypt = when (query["sni-crypt"]) { "1" -> true; "0" -> false; else -> return null }
                ProfileItem.create(EConfigType.UUSNI).apply {
                    server = uri.host
                    serverPort = uri.port.toString()
                    remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifEmpty { "UU SNI" }
                    uuSni = UuSniOptions(query["obfs-key"]?.toIntOrNull(), crypt)
                }.takeIf(::isValid)
            }
        } catch (_: Exception) {
            // Never log an imported URI; it contains the user's obfuscation parameter.
            null
        }
    }

    /** Returns the body; sharing adds AppConfig.UU_SNI, as for other protocols. */
    fun toUri(config: ProfileItem): String {
        require(isValid(config)) { "Invalid UU SNI profile" }
        val options = requireNotNull(config.uuSni)
        return "${config.server}:${config.serverPort}?obfs-key=${options.obfsKey}" +
            "&sni-crypt=${if (options.sniCrypt) 1 else 0}#${Utils.encodeURIComponent(config.remarks)}"
    }
}
