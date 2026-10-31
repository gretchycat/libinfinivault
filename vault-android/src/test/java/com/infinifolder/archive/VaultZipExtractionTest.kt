package com.infinifolder.archive

import io.github.gretchycat.infinistream.CancellationToken
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VaultZipExtractionTest {
    private fun withZip(entries: Map<String, ByteArray>, action: (File, File) -> Unit) {
        val root = Files.createTempDirectory("vault-epub").toFile()
        try {
            val file = File(root, "book.epub")
            ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            } }
            action(file, File(root, "expanded").apply { mkdirs() })
        } finally { root.deleteRecursively() }
    }
    @Test fun expandsEveryResourceThroughSuppliedOutputWithActualCounts() = withZip(linkedMapOf(
        "META-INF/" to byteArrayOf(), "META-INF/container.xml" to "container".toByteArray(), "OPS/a.xhtml" to "chapter".toByteArray(), "empty" to byteArrayOf())) { file, dir ->
        val opened = mutableListOf<String>()
        val report = VaultZipExtraction.extract(file, dir, { opened += it.relativeTo(dir).path; it.outputStream() })
        assertEquals(3, report.files); assertEquals(16L, report.bytes); assertEquals(0, report.skippedUnsafeEntries)
        assertEquals(listOf("META-INF/container.xml", "OPS/a.xhtml", "empty"), opened)
        assertEquals("chapter", File(dir, "OPS/a.xhtml").readText())
    }
    @Test fun unsafePathsAreSkippedAndNeverReachOutputFactory() = withZip(linkedMapOf(
        "../outside" to byteArrayOf(1), "/absolute" to byteArrayOf(2), "a\\..\\escape" to byteArrayOf(3),
        "safe.txt" to "safe".toByteArray())) { file, dir ->
        val opened = mutableListOf<File>()
        val result = VaultZipExtraction.extract(file, dir, { opened += it; it.outputStream() })
        assertEquals(3, result.skippedUnsafeEntries); assertEquals(listOf(File(dir, "safe.txt")), opened)
        assertFalse(File(dir.parentFile, "outside").exists())
    }
    @Test fun sinkCapacityFailurePreservesCauseAndClosesOutput() = withZip(mapOf("large" to ByteArray(100_000))) { file, dir ->
        val capacity = IOException("capacity exhausted"); var closes = 0
        val thrown = assertThrows(IOException::class.java) { VaultZipExtraction.extract(file, dir, {
            object : OutputStream() {
                override fun write(value: Int) { throw capacity }
                override fun write(b: ByteArray, off: Int, len: Int) { throw capacity }
                override fun close() { closes++ }
            }
        }) }
        assertSame(capacity, thrown); assertEquals(1, closes)
    }
    @Test fun cancellationBeforeOpeningDoesNotAllocateOutput() = withZip(mapOf("a" to byteArrayOf(1))) { file, dir ->
        assertThrows(InterruptedIOException::class.java) { VaultZipExtraction.extract(file, dir,
            { fail("No output expected"); it.outputStream() }, CancellationToken { true }) }
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun cancellationDuringCopyClosesOutputAndStopsBeforeNextEntry() = withZip(linkedMapOf(
        "large" to ByteArray(200_000), "later" to byteArrayOf(1))) { file, dir ->
        var cancelled = false; var closes = 0; var writes = 0
        assertThrows(InterruptedIOException::class.java) { VaultZipExtraction.extract(file, dir, {
            object : OutputStream() {
                override fun write(value: Int) { fail("bulk copy expected") }
                override fun write(b: ByteArray, off: Int, len: Int) { writes++; cancelled = true }
                override fun close() { closes++ }
            }
        }, CancellationToken { cancelled }) }
        assertEquals(1, writes); assertEquals(1, closes); assertFalse(File(dir, "later").exists())
    }
    @Test fun invalidArchiveFailsBeforeResourceWrites() {
        val root = Files.createTempDirectory("vault-invalid-epub").toFile()
        try {
            val file = File(root, "invalid.epub").apply { writeText("not a zip") }
            assertThrows(IOException::class.java) { VaultZipExtraction.extract(file, root, { fail("No output expected"); it.outputStream() }) }
        } finally { root.deleteRecursively() }
    }
}
