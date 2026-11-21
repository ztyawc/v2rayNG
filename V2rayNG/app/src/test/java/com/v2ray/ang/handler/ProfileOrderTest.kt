package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderTest {
    @Test fun staleFilteredOrderKeepsHiddenAndNewMembersWithoutResurrectingRemovedOnes() {
        assertEquals(listOf("b", "a", "hidden", "new"), ProfileOrder.merge(
            listOf("a", "hidden", "b", "new"), listOf("b", "removed", "a")))
    }
    @Test fun movingByGuidKeepsAllMembers() {
        assertEquals(listOf("b", "a", "hidden"), ProfileOrder.move(listOf("a", "hidden", "b"), "b", "a"))
        assertEquals(listOf("hidden", "b", "a"), ProfileOrder.move(listOf("a", "hidden", "b"), "a", "b"))
    }
    @Test fun staleOrEmptyMovesDoNothing() {
        val current = listOf("new")
        assertEquals(current, ProfileOrder.move(current, "removed", "new"))
        assertEquals(current, ProfileOrder.move(current, "new", "new"))
        assertEquals(emptyList<String>(), ProfileOrder.move(emptyList(), "a", "b"))
    }
}
