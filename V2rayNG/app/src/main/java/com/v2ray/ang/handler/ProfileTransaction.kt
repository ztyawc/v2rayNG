package com.v2ray.ang.handler

import com.v2ray.ang.util.JsonUtil

/** A single profile-index mutation; MmkvManager owns locking and the stores' lifetime. */
internal class ProfileTransaction(private val storage: Storage) {
    interface Storage {
        fun read(store: String, key: String): String?
        fun write(store: String, key: String, value: String?): Boolean
        fun sync(store: String)
    }

    data class Record(val store: String, val key: String, val value: String?)

    fun recover() {
        val journal = storage.read(MAIN, JOURNAL) ?: return
        val previous = JsonUtil.fromJson(journal, Array<Record>::class.java)
            ?: throw ProfileStorageException("Unreadable profile transaction journal")
        restore(previous.toList())
        clearJournal()
    }

    fun commit(records: List<Record>) {
        if (records.isEmpty()) return
        val changes = records.associateBy { it.store to it.key }.values.toList()
        val previous = changes.map { it.copy(value = storage.read(it.store, it.key)) }
        requireWrite(storage.write(MAIN, JOURNAL, JsonUtil.toJson(previous)))
        storage.sync(MAIN)
        try {
            changes.forEach { requireWrite(storage.write(it.store, it.key, it.value)) }
            changes.map { it.store }.distinct().forEach(storage::sync)
            clearJournal()
        } catch (failure: Exception) {
            try {
                restore(previous)
                clearJournal()
            } catch (rollbackFailure: Exception) {
                // Keep the durable journal so the next locked access retries recovery.
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun restore(records: List<Record>) {
        records.forEach { requireWrite(storage.write(it.store, it.key, it.value)) }
        records.map { it.store }.distinct().forEach(storage::sync)
    }

    private fun clearJournal() {
        requireWrite(storage.write(MAIN, JOURNAL, null))
        storage.sync(MAIN)
    }

    private fun requireWrite(success: Boolean) {
        if (!success) throw ProfileStorageException("Profile transaction storage write failed")
    }

    companion object {
        const val MAIN = "MAIN"
        const val JOURNAL = "PROFILE_TRANSACTION"
    }
}
