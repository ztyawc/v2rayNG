package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.VkS6Options
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer

/** The observed VK S6 IPv4/TCP download variant, using current session credentials. */
object VkS6Fmt {
    fun isValid(config: ProfileItem): Boolean {
        if (config.configType != EConfigType.VKS6) return false
        val port = config.serverPort?.toIntOrNull() ?: return false
        val end = config.vkS6?.portEnd ?: port
        if (port !in 1..65535 || end !in port..65535) return false
        val address = config.server ?: return false
        if (address.length !in 7..15) return false
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { part ->
                part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part.startsWith('0')) || part.toIntOrNull() !in 0..255
            }) return false
        if (config.server == "0.0.0.0" || parts[0].toInt() >= 224) return false
        return validCredential(config.username) && validCredential(config.password)
    }

    private fun validCredential(value: String?): Boolean = value != null && value.length in 1..255 &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size in 1..255

    // Credential bytes must survive import exactly. Unlike the general URI helper,
    // this decoder rejects malformed UTF-8 instead of replacing bytes in a secret.
    private fun decode(raw: String): String {
        require(Charsets.UTF_8.newEncoder().canEncode(raw))
        val bytes = ByteArrayOutputStream()
        var offset = 0
        while (offset < raw.length) {
            val next = raw.indexOf('%', offset).let { if (it < 0) raw.length else it }
            bytes.write(raw.substring(offset, next).toByteArray(Charsets.UTF_8))
            if (next == raw.length) break
            require(next + 2 < raw.length)
            bytes.write(raw.substring(next + 1, next + 3).toInt(16))
            offset = next + 3
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun parse(text: String): ProfileItem? {
        return try {
            val uri = URI(text)
            if (!uri.scheme.equals("vks6", true) || uri.rawPath.orEmpty().isNotEmpty()) null
            else {
                val credentials = uri.rawUserInfo?.split(':', limit = 2) ?: return null
                if (credentials.size != 2) return null
                var portEnd: Int? = null
                uri.rawQuery?.let { query ->
                    val pair = query.split('=', limit = 2)
                    if (pair.size != 2 || decode(pair[0]) != "port-end" || '&' in pair[1]) return null
                    portEnd = decode(pair[1]).toIntOrNull() ?: return null
                }
                ProfileItem.create(EConfigType.VKS6).apply {
                    server = uri.host
                    serverPort = uri.port.toString()
                    username = decode(credentials[0])
                    password = decode(credentials[1])
                    remarks = decode(uri.rawFragment.orEmpty()).ifEmpty { "VK S6" }
                    vkS6 = VkS6Options(portEnd)
                }.takeIf(::isValid)
            }
        } catch (_: Exception) {
            // Never log an imported URI or exceptions containing session credentials.
            null
        }
    }

    /** Returns the URI body; the existing sharing pipeline prepends vks6://. */
    fun toUri(config: ProfileItem): String {
        require(isValid(config)) { "Invalid VK S6 profile" }
        val credentials = "${Utils.encodeURIComponent(config.username.orEmpty())}:${Utils.encodeURIComponent(config.password.orEmpty())}"
        val range = config.vkS6?.portEnd?.let { "?port-end=$it" }.orEmpty()
        return "$credentials@${config.server}:${config.serverPort}$range#${Utils.encodeURIComponent(config.remarks)}"
    }
}
