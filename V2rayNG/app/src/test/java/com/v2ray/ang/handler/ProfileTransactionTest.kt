package com.v2ray.ang.handler

import org.junit.Assert.*
import org.junit.Test

class ProfileTransactionTest {
    private class MemoryStorage : ProfileTransaction.Storage {
        val values = linkedMapOf<Pair<String, String>, String>()
        var failAt = -1
        var writes = 0
        var crashAt = -1
        override fun read(store: String, key: String) = values[store to key]
        override fun write(store: String, key: String, value: String?): Boolean {
            writes++
            if (writes == crashAt) throw AssertionError("Simulated process loss")
            if (writes == failAt) return false
            if (value == null) values.remove(store to key) else values[store to key] = value
            return true
        }
        override fun sync(store: String) = Unit
    }

    private val changes = listOf(
        ProfileTransaction.Record("PROFILE", "new", "new payload"),
        ProfileTransaction.Record("RAW", "new", "raw payload"),
        ProfileTransaction.Record("MAIN", "INDEX", "[new]"),
        ProfileTransaction.Record("MAIN", "SELECTED", "new"),
        ProfileTransaction.Record("PROFILE", "old", null),
    )

    private fun storage() = MemoryStorage().apply {
        values["MAIN" to "INDEX"] = "[old]"
        values["MAIN" to "SELECTED"] = "old"
        values["PROFILE" to "old"] = "old payload"
    }

    @Test fun commitsPayloadIndexSelectionAndDeletionTogether() {
        val store = storage()
        ProfileTransaction(store).commit(changes)
        assertEquals("[new]", store.read("MAIN", "INDEX"))
        assertEquals("new", store.read("MAIN", "SELECTED"))
        assertEquals("new payload", store.read("PROFILE", "new"))
        assertNull(store.read("PROFILE", "old"))
        assertNull(store.read("MAIN", ProfileTransaction.JOURNAL))
    }

    @Test fun everyWriteFailureRestoresAllOldRecordsAndRemovesNewPayloads() {
        // Includes the journal, each payload/index/selection/deletion, and journal removal.
        for (failure in 1..changes.size + 2) {
            val store = storage()
            val before = store.values.toMap()
            store.failAt = failure
            try {
                ProfileTransaction(store).commit(changes)
                fail("Write $failure should fail")
            } catch (_: ProfileStorageException) { }
            assertEquals("Failure at $failure", before, store.values)
        }
    }

    @Test fun restartRecoversInterruptedTransactionsAtEveryMutation() {
        for (crash in 2..changes.size + 2) {
            val store = storage()
            val before = store.values.toMap()
            store.crashAt = crash
            try { ProfileTransaction(store).commit(changes) } catch (_: AssertionError) { }
            assertNotNull(store.read("MAIN", ProfileTransaction.JOURNAL))
            ProfileTransaction(store).recover()
            assertEquals("Crash at $crash", before, store.values)
        }
    }

    @Test fun emptyBatchLeavesStorageUntouched() {
        val store = storage()
        val before = store.values.toMap()
        ProfileTransaction(store).commit(emptyList())
        ProfileTransaction(store).recover()
        assertEquals(before, store.values)
        assertEquals(0, store.writes)
    }

    @Test fun failedRecoveryRetainsJournalForNextAttempt() {
        val store = storage().apply { crashAt = 4 }
        val before = store.values.toMap()
        try { ProfileTransaction(store).commit(changes) } catch (_: AssertionError) { }
        store.failAt = store.writes + 1
        try { ProfileTransaction(store).recover(); fail() } catch (_: ProfileStorageException) { }
        assertNotNull(store.read("MAIN", ProfileTransaction.JOURNAL))
        ProfileTransaction(store).recover()
        assertEquals(before, store.values)
    }
}
