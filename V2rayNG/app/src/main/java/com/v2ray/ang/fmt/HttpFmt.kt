package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.HttpHeaderParser
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.net.URI

/** HTTP proxy shares carry an explicit marker so ordinary subscription URLs stay URLs. */
object HttpFmt : FmtBase() {
    fun isProxyUri(str: String): Boolean = runCatching {
        val uri = URI(Utils.fixIllegalUrl(str))
        uri.scheme.equals("http", true) && query(uri)["proxy"] == "http"
    }.getOrDefault(false)

    fun parse(str: String): ProfileItem? = runCatching {
        if (!isProxyUri(str)) return null
        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.idnHost.isEmpty() || uri.port !in 1..65535) return null
        val parameters = query(uri)
        val headers = parameters["headers"]?.let { json ->
            val value = JsonParser.parseString(json).asJsonObject
            value.entrySet().associateTo(linkedMapOf()) { (key, item) ->
                require(item.isJsonPrimitive && item.asJsonPrimitive.isString)
                key to item.asString
            }
        }
        if (!HttpHeaderParser.isValid(headers)) return null
        val credentials = uri.rawUserInfo?.split(':', limit = 2)?.map(Utils::decodeURIComponent)
        if (credentials != null && (credentials.size != 2 || credentials[0].isBlank())) return null
        ProfileItem.create(EConfigType.HTTP).apply {
            server = uri.idnHost
            serverPort = uri.port.toString()
            remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifEmpty { "none" }
            username = credentials?.get(0)
            password = credentials?.get(1)
            httpHeaders = headers
        }
    }.getOrNull()

    fun toUri(profile: ProfileItem): String {
        require(HttpHeaderParser.isValid(profile.httpHeaders)) { "Invalid HTTP outbound headers" }
        val credentials = "${Utils.encodeURIComponent(profile.username.orEmpty())}:${Utils.encodeURIComponent(profile.password.orEmpty())}"
        val query = hashMapOf("proxy" to "http")
        profile.httpHeaders?.takeIf { it.isNotEmpty() }?.let { query["headers"] = JsonUtil.toJson(it) }
        val body = toUriWithEncodedUserInfo(profile, credentials, query)
        return if (profile.username.isNullOrEmpty()) body.substringAfter('@') else body
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').associate { item ->
        val pair = item.split('=', limit = 2)
        Utils.decodeURIComponent(pair[0]) to Utils.decodeURIComponent(pair.getOrElse(1) { "" })
    }
}
