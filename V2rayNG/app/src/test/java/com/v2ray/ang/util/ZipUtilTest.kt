package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

class ZipUtilTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun backupArchiveRoundTripsMultipleStoreFiles() {
        val source = temporaryFolder.newFolder("stores")
        File(source, "MAIN").writeText("index")
        File(source, "PROFILE_FULL_CONFIG").writeText("payload")
        val archive = File(temporaryFolder.root, "backup.zip")
        assertTrue(ZipUtil.zipFromFolder(source.absolutePath, archive.absolutePath))
        val restored = File(temporaryFolder.root, "restored")
        assertTrue(ZipUtil.unzipToFolder(archive, restored.absolutePath))
        assertEquals("index", File(restored, "MAIN").readText())
        assertEquals("payload", File(restored, "PROFILE_FULL_CONFIG").readText())
        assertTrue(archive.delete())
        assertFalse(ZipUtil.zipFromFolder("", archive.absolutePath))
    }

    @Test
    fun expiredArchiveCleanupKeepsRecentFilesAndOtherCacheEntries() {
        val old = temporaryFolder.newFile("old.zip").apply { setLastModified(1000) }
        val boundary = temporaryFolder.newFile("boundary.zip").apply { setLastModified(2000) }
        val recent = temporaryFolder.newFile("recent.zip").apply { setLastModified(3000) }
        val other = temporaryFolder.newFile("other.txt").apply { setLastModified(1000) }
        val directory = temporaryFolder.newFolder("folder.zip").apply { setLastModified(1000) }
        ZipUtil.removeExpiredArchives(temporaryFolder.root, 2000)
        assertFalse(old.exists())
        listOf(boundary, recent, other, directory).forEach { assertTrue(it.exists()) }
        ZipUtil.removeExpiredArchives(File(temporaryFolder.root, "missing"), 2000)
    }

    @Test
    fun unzipToFolderExtractsNestedFile() {
        val archive = createArchive("nested/config" to "value".toByteArray())
        val destination = File(temporaryFolder.root, "destination")

        assertTrue(ZipUtil.unzipToFolder(archive, destination.absolutePath))
        assertEquals("value", File(destination, "nested/config").readText())
    }

    @Test
    fun extractArchiveRejectsPathTraversal() {
        val archive = createArchive("../outside" to "overwrite".toByteArray())
        val outside = File(temporaryFolder.root, "outside")

        assertArchiveRejected(archive)

        assertFalse(outside.exists())
    }

    @Test
    fun extractArchiveRejectsOversizedArchive() {
        val archive = createArchive("config" to byteArrayOf(1))

        assertArchiveRejected(
            archive,
            extractionLimits(maxArchiveBytes = archive.length() - 1),
        )
    }

    @Test
    fun extractArchiveRejectsTooManyEntries() {
        val archive = createArchive(
            "first" to byteArrayOf(1),
            "second" to byteArrayOf(2),
        )
        assertArchiveRejected(archive, extractionLimits(maxEntries = 1))
    }

    @Test
    fun extractArchiveRejectsOversizedEntry() {
        val archive = createArchive("config" to ByteArray(5))

        assertArchiveRejected(archive, extractionLimits(maxEntryBytes = 4))
    }

    @Test
    fun extractArchiveRejectsExcessiveTotalSize() {
        val archive = createArchive(
            "first" to ByteArray(3),
            "second" to ByteArray(3),
        )
        assertArchiveRejected(archive, extractionLimits(maxTotalBytes = 5))
    }

    @Test
    fun extractArchiveRejectsExcessiveCompressionRatio() {
        val archive = createArchive("config" to ByteArray(4096))

        assertArchiveRejected(archive, extractionLimits(maxCompressionRatio = 2.0))
    }

    @Test
    fun extractArchiveRejectsDuplicateCanonicalDestination() {
        val archive = createArchive(
            "config" to byteArrayOf(1),
            "nested/../config" to byteArrayOf(2),
        )
        assertArchiveRejected(archive)
    }

    @Test
    fun extractArchiveRejectsEntryBelowFile() {
        val archive = createArchive(
            "parent" to byteArrayOf(1),
            "parent/child" to byteArrayOf(2),
        )
        assertArchiveRejected(archive)
    }

    private fun assertArchiveRejected(
        archive: File,
        limits: ZipUtil.ExtractionLimits = extractionLimits(),
    ) {
        val destination = File(temporaryFolder.root, "destination")
        assertThrows(ZipException::class.java) {
            ZipUtil.extractArchive(archive, destination, limits)
        }
        assertFalse(destination.exists())
    }

    private fun createArchive(vararg entries: Pair<String, ByteArray>): File {
        val archive = temporaryFolder.newFile()
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (name, contents) ->
                output.putNextEntry(ZipEntry(name))
                output.write(contents)
                output.closeEntry()
            }
        }
        return archive
    }

    private fun extractionLimits(
        maxArchiveBytes: Long = 1024L * 1024L,
        maxEntries: Int = 10,
        maxEntryBytes: Long = 16L * 1024L,
        maxTotalBytes: Long = 32L * 1024L,
        maxCompressionRatio: Double = 1000.0,
    ) = ZipUtil.ExtractionLimits(
        maxArchiveBytes = maxArchiveBytes,
        maxEntries = maxEntries,
        maxEntryBytes = maxEntryBytes,
        maxTotalBytes = maxTotalBytes,
        maxCompressionRatio = maxCompressionRatio,
    )
}
