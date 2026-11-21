package com.v2ray.ang.dto.entities

/** QyProxy negotiation fields, owned by the profile and retained by backup/export. */
data class QyProxyOptions(
    val role: String = "cn2",
    val sn: String = "",
    val gameId: Long = 0,
    val serverId: Long = 0,
    val clientType: Long = 20,
    val gameArea: String = "default",
    val zone: String = "",
    val product: String = "hy-android",
    val version: String = "1.0.0"
)
