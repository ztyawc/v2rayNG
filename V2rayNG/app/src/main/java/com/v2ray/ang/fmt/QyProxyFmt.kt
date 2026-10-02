package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.QyProxyOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

/** CN2 and CN2 download share QyProxy framing, with separate role sessions. */
object QyProxyFmt : FmtBase() {
    const val CN2 = "cn2"
    const val CN2_DOWNLOAD = "cn2_download"
    private val keys = setOf("role", "sn", "game-id", "server-id", "client-type", "game-area", "zone", "product", "version")

    fun isValid(config: ProfileItem): Boolean {
        val options = config.qyProxy ?: return false
        val port = config.serverPort?.toIntOrNull() ?: return false
        val host = config.server.orEmpty()
        if (host.isBlank() || ':' in host || host.any { it.isWhitespace() || it == '\u0000' } || port !in 1..65535) return false
        if (options.role != CN2 && options.role != CN2_DOWNLOAD) return false
        if (listOf(options.gameId, options.serverId, options.clientType).any { it !in 1..0xffffffffL }) return false
        val username = config.username.orEmpty()
        val texts = listOf(username, config.password.orEmpty(), options.sn, options.gameArea, options.product, options.version)
        if (texts.any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size !in 1..128 || '\u0000' in it }) return false
        if (options.zone.toByteArray(Charsets.UTF_8).size > 128 || '\u0000' in options.zone) return false
        return '&' !in username && '&' !in options.sn &&
                "$username&${options.clientType}&${options.gameId}&${options.sn}".toByteArray(Charsets.UTF_8).size <= 254
    }

    fun parse(text: String): ProfileItem? {
        return try {
            val uri = URI(Utils.fixIllegalUrl(text))
            if (!uri.scheme.equals("qyproxy", true) || uri.rawPath.orEmpty().isNotEmpty()) null
            else {
                val credentials = uri.rawUserInfo?.split(':', limit = 2)?.map(Utils::decodeURIComponent)
                val query = linkedMapOf<String, String>()
                for (item in uri.rawQuery.orEmpty().split('&')) {
                    val pair = item.split('=', limit = 2)
                    if (pair.size != 2) return null
                    val key = Utils.decodeURIComponent(pair[0])
                    if (key !in keys || query.containsKey(key)) return null
                    query[key] = Utils.decodeURIComponent(pair[1])
                }
                if (credentials?.size != 2) null
                else ProfileItem.create(EConfigType.QYPROXY).apply {
                    remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifEmpty { "none" }
                    server = uri.idnHost
                    serverPort = uri.port.toString()
                    username = credentials[0]
                    password = credentials[1]
                    qyProxy = QyProxyOptions(
                        role = query["role"] ?: CN2,
                        sn = query["sn"].orEmpty(),
                        gameId = query["game-id"]?.toLongOrNull() ?: 0,
                        serverId = query["server-id"]?.toLongOrNull() ?: 0,
                        clientType = query["client-type"]?.let { it.toLongOrNull() ?: return null } ?: 20,
                        gameArea = query["game-area"] ?: "default",
                        zone = query["zone"].orEmpty(),
                        product = query["product"] ?: "hy-android",
                        version = query["version"] ?: "1.0.0"
                    )
                }.takeIf(::isValid)
            }
        } catch (_: Exception) {
            // Import failures must not log the URI, which contains the secret.
            null
        }
    }

    fun toUri(config: ProfileItem): String {
        require(isValid(config)) { "Invalid QyProxy profile" }
        val options = requireNotNull(config.qyProxy)
        val query = hashMapOf(
            "role" to options.role, "sn" to options.sn,
            "game-id" to options.gameId.toString(), "server-id" to options.serverId.toString(),
            "client-type" to options.clientType.toString(), "game-area" to options.gameArea,
            "zone" to options.zone, "product" to options.product, "version" to options.version
        )
        return toUriWithEncodedUserInfo(config,
            "${Utils.encodeURIComponent(config.username.orEmpty())}:${Utils.encodeURIComponent(config.password.orEmpty())}", query)
    }
}
