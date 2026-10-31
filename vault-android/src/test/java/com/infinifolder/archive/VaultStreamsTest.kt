package com.infinifolder.archive

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VaultStreamsTest {
    @Test fun entryFromCommonPositionalSourceRetainsMetadataAndClosesItsHandles() {
        val dir = java.nio.file.Files.createTempDirectory("vault-stream").toFile()
        try {
            val file = File(dir, "example.zip")
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("hello.txt")); zip.write("hello".toByteArray()); zip.closeEntry() }
            val resource = VaultStreams.entry(ArchiveReader(dir), FileSourceResource(file), "hello.txt",
                ArchiveEntry("hello.txt", "hello.txt", false, 5, null, "text/plain"))
            assertEquals("text/plain", resource.metadata.content.mimeType)
            assertFalse(Capability.POSITIONAL_READ in resource.metadata.capabilities)
            resource.open().use { assertEquals("hello", it.asInputStream(false).readBytes().decodeToString()) }
            resource.open().use { assertEquals("hello", it.asInputStream(false).readBytes().decodeToString()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun misleadingExtensionStillUsesTheDetectedZipParser() {
        val dir = java.nio.file.Files.createTempDirectory("vault-wrong-extension").toFile()
        try {
            val file = File(dir, "misleading.tar")
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("hello.txt")); zip.write("actual zip".toByteArray()); zip.closeEntry() }
            val entry = VaultStreams.entry(ArchiveReader(dir), FileSourceResource(file), "hello.txt")
            entry.open().use { assertEquals("actual zip", it.asInputStream(false).readBytes().decodeToString()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun sequentialZipPrefixIsReplayedAndPositionalHandlesAreDiscoveredAtOpen() {
        val dir = java.nio.file.Files.createTempDirectory("vault-sequential").toFile()
        try {
            val output = java.io.ByteArrayOutputStream()
            ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("entry")); zip.write("sequential".toByteArray()); zip.closeEntry() }
            val resource = object : SourceResource {
                override val metadata = StreamMetadata(name = "misleading.tar")
                override fun open(): ByteSource = InputStreamSource(output.toByteArray().inputStream(), metadata)
            }
            VaultStreams.entry(ArchiveReader(dir), resource, "entry").open().use {
                assertEquals("sequential", it.asInputStream(false).readBytes().decodeToString())
                assertFalse(Capability.POSITIONAL_READ in it.metadata.capabilities)
            }
        } finally { dir.deleteRecursively() }
    }

}
