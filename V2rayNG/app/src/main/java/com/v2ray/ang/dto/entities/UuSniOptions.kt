package com.v2ray.ang.dto.entities

/** Custom SNI/TCP settings owned by the profile, including backup and sharing. */
data class UuSniOptions(
    val obfsKey: Int? = null,
    val sniCrypt: Boolean = true
)
