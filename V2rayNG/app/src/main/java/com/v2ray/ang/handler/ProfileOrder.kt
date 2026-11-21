package com.v2ray.ang.handler

/** Applies a stale view's order to current membership without removing or resurrecting profiles. */
internal object ProfileOrder {
    fun merge(current: List<String>, requested: List<String>): List<String> {
        val members = current.toSet()
        val ordered = requested.filter { it in members }.distinct()
        val orderedMembers = ordered.toSet()
        return ordered + current.filter { it !in orderedMembers }.distinct()
    }

    fun move(current: List<String>, fromGuid: String, toGuid: String): List<String> {
        if (fromGuid == toGuid || fromGuid !in current || toGuid !in current) return current
        return current.toMutableList().apply {
            val target = indexOf(toGuid)
            remove(fromGuid)
            add(target.coerceAtMost(size), fromGuid)
        }
    }
}
