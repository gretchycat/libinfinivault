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
}
