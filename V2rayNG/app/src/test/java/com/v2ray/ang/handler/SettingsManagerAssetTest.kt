package com.v2ray.ang.handler

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class SettingsManagerAssetTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun firstInstallAndZeroLengthRecoveryPublishCompleteAssets() {
        val target = File(directory.root, "geosite.dat")
        SettingsManager.installBundledAsset(target) { "complete".byteInputStream() }
        assertEquals("complete", target.readText())
        target.writeText("")
        SettingsManager.installBundledAsset(target) { "repaired".byteInputStream() }
        assertEquals("repaired", target.readText())
        assertEquals(listOf(target), directory.root.listFiles()!!.toList())
    }

    @Test fun preservesUserProvidedAssetAndCleansInterruptedCopies() {
        val existing = directory.newFile("geoip.dat").apply { writeText("user-data") }
        SettingsManager.installBundledAsset(existing) { throw AssertionError("Must not replace user assets") }
        assertEquals("user-data", existing.readText())
        val target = File(directory.root, "geosite.dat")
        var reads = 0
        assertThrows(IOException::class.java) {
            SettingsManager.installBundledAsset(target) {
                object : InputStream() {
                    override fun read(): Int {
                        if (reads++ < 3) return 65
                        assertFalse("Partial asset must never become visible", target.exists())
                        throw IOException("Interrupted copy")
                    }
                }
            }
        }
        assertFalse(target.exists())
        assertEquals(listOf(existing), directory.root.listFiles()!!.toList())
        assertThrows(IllegalStateException::class.java) {
            SettingsManager.installBundledAsset(target) { byteArrayOf().inputStream() }
        }
        assertFalse(target.exists())
    }

    @Test fun failedBatchRestoresEveryPreviousFile() {
        val existing = directory.newFile("user.dat").apply { writeText("user-data") }
        val empty = directory.newFile("empty.dat")
        val first = File(directory.root, "first.dat")
        val last = File(directory.root, "last.dat")
        assertThrows(IOException::class.java) {
            SettingsManager.installBundledAssets(listOf(existing, empty, first, last)) { target ->
                if (target == last) throw IOException("Missing bundle")
                "complete".byteInputStream()
            }
        }
        assertEquals("user-data", existing.readText())
        assertEquals(0L, empty.length())
        assertFalse(first.exists())
        assertFalse(last.exists())
        SettingsManager.installBundledAssets(listOf(existing, empty, first, last)) { "complete".byteInputStream() }
        assertEquals("user-data", existing.readText())
        listOf(empty, first, last).forEach { assertEquals("complete", it.readText()) }
    }
}
