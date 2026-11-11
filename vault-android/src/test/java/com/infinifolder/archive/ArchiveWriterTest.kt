package com.infinifolder.archive

import android.net.Uri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceCapability
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceMetadata
import com.infinifolder.model.ResourceRef
import com.infinifolder.storage.StorageProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ArchiveWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNormalizeArchiveName() {
        assertEquals("backup.zip", ArchiveWriter.normalizeArchiveName("backup"))
        assertEquals("backup.tar.gz", ArchiveWriter.normalizeArchiveName("backup.tar.gz"))
        assertEquals("test.7z", ArchiveWriter.normalizeArchiveName("test.7z"))
        assertEquals("folder/data.zip", ArchiveWriter.normalizeArchiveName("folder/data"))
        assertEquals("folder/data.cpio", ArchiveWriter.normalizeArchiveName("folder/data.cpio"))
    }

    @Test
    fun testIsSupportedFormat() {
        assertTrue(ArchiveWriter.isSupportedFormat("archive.zip"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.tar"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.tar.gz"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.tgz"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.tar.bz2"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.tar.xz"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.7z"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.cb7"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.cpio"))
        assertTrue(ArchiveWriter.isSupportedFormat("archive.ar"))

        assertFalse(ArchiveWriter.isSupportedFormat("archive.rar"))
        assertFalse(ArchiveWriter.isSupportedFormat("archive.iso"))
        assertFalse(ArchiveWriter.isSupportedFormat("archive.unknown"))
    }

    private class MockStorageProvider(
        private val fileData: Map<String, ByteArray>,
        private val directories: Map<String, List<ResourceEntry>>
    ) : StorageProvider {
        override val id: String = "mock"

        override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> =
            ProviderResult.Success(directories[parent.documentId] ?: emptyList())

        override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
            val bytes = fileData[ref.documentId] ?: return ProviderResult.Failure("Not found")
            return ProviderResult.Success(ByteArrayInputStream(bytes))
        }

        override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.CREATE)
        override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> = ProviderResult.Unsupported(ResourceCapability.RENAME)
        override fun delete(ref: ResourceRef): ProviderResult<Unit> = ProviderResult.Unsupported(ResourceCapability.DELETE)
        override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.COPY)
        override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> = ProviderResult.Unsupported(ResourceCapability.MOVE)
    }

    private val dummyUri = android.net.TestUri("mock://test")

    @Test
    fun testCreateZipArchive() {
        val file1Entry = ResourceEntry(
            ResourceRef("mock", "doc:file1.txt"),
            ResourceMetadata("file1.txt", "text/plain", 11L, 1000L, false),
            dummyUri
        )
        val file2Entry = ResourceEntry(
            ResourceRef("mock", "doc:folder/nested.txt"),
            ResourceMetadata("nested.txt", "text/plain", 12L, 2000L, false),
            dummyUri
        )
        val folderEntry = ResourceEntry(
            ResourceRef("mock", "doc:folder"),
            ResourceMetadata("folder", "vnd.android.document/directory", null, 1500L, true),
            dummyUri
        )

        val mockProvider = MockStorageProvider(
            fileData = mapOf(
                "doc:file1.txt" to "Hello File1".toByteArray(),
                "doc:folder/nested.txt" to "Hello Nested".toByteArray()
            ),
            directories = mapOf(
                "doc:folder" to listOf(file2Entry)
            )
        )

        val targetZip = tempFolder.newFile("output.zip")
        ArchiveWriter.createArchive(
            targetFile = targetZip,
            archiveName = "output.zip",
            entries = listOf(file1Entry, folderEntry),
            provider = mockProvider
        )

        assertTrue(targetZip.exists())
        assertTrue(targetZip.length() > 0)

        // Verify with ArchiveReader
        val reader = ArchiveReader(tempFolder.newFolder("zip_extract"))
        val tree = reader.buildTree(null, "output.zip", { targetZip.inputStream() }, targetZip)

        val rootEntries = tree.list("")
        assertEquals(2, rootEntries.size)
        assertTrue(rootEntries.any { it.name == "file1.txt" && !it.isDirectory })
        assertTrue(rootEntries.any { it.name == "folder" && it.isDirectory })

        val folderEntries = tree.list("folder")
        assertEquals(1, folderEntries.size)
        assertEquals("nested.txt", folderEntries[0].name)

        val outDir = tempFolder.newFolder("zip_out")
        val extracted = reader.extractEntry("output.zip", "file1.txt", File(outDir, "file1.txt"), { targetZip.inputStream() }, targetZip)
        assertEquals("Hello File1", extracted.readText())

        val extractedNested = reader.extractEntry("output.zip", "folder/nested.txt", File(outDir, "nested.txt"), { targetZip.inputStream() }, targetZip)
        assertEquals("Hello Nested", extractedNested.readText())
    }

    @Test
    fun testCreateTarGzArchive() {
        val fileEntry = ResourceEntry(
            ResourceRef("mock", "doc:note.txt"),
            ResourceMetadata("note.txt", "text/plain", 10L, 1000L, false),
            dummyUri
        )
        val mockProvider = MockStorageProvider(
            fileData = mapOf("doc:note.txt" to "TarGz Note".toByteArray()),
            directories = emptyMap()
        )

        val targetTarGz = tempFolder.newFile("output.tar.gz")
        ArchiveWriter.createArchive(
            targetFile = targetTarGz,
            archiveName = "output.tar.gz",
            entries = listOf(fileEntry),
            provider = mockProvider
        )

        assertTrue(targetTarGz.exists())
        assertTrue(targetTarGz.length() > 0)

        val reader = ArchiveReader(tempFolder.newFolder("targz_extract"))
        val tree = reader.buildTree(null, "output.tar.gz", { targetTarGz.inputStream() }, targetTarGz)

        val rootEntries = tree.list("")
        assertEquals(1, rootEntries.size)
        assertEquals("note.txt", rootEntries[0].name)

        val outDir = tempFolder.newFolder("targz_out")
        val extracted = reader.extractEntry("output.tar.gz", "note.txt", File(outDir, "note.txt"), { targetTarGz.inputStream() }, targetTarGz)
        assertEquals("TarGz Note", extracted.readText())
    }

    @Test
    fun testCreate7zArchive() {
        val fileEntry = ResourceEntry(
            ResourceRef("mock", "doc:data.txt"),
            ResourceMetadata("data.txt", "text/plain", 8L, 1000L, false),
            dummyUri
        )
        val mockProvider = MockStorageProvider(
            fileData = mapOf("doc:data.txt" to "7z Data!".toByteArray()),
            directories = emptyMap()
        )

        val target7z = tempFolder.newFile("output.7z")
        ArchiveWriter.createArchive(
            targetFile = target7z,
            archiveName = "output.7z",
            entries = listOf(fileEntry),
            provider = mockProvider
        )

        assertTrue(target7z.exists())
        assertTrue(target7z.length() > 0)

        val reader = ArchiveReader(tempFolder.newFolder("7z_extract"))
        val tree = reader.buildTree(null, "output.7z", { target7z.inputStream() }, target7z)

        val rootEntries = tree.list("")
        assertEquals(1, rootEntries.size)
        assertEquals("data.txt", rootEntries[0].name)

        val outDir = tempFolder.newFolder("7z_out")
        val extracted = reader.extractEntry("output.7z", "data.txt", File(outDir, "data.txt"), { target7z.inputStream() }, target7z)
        assertEquals("7z Data!", extracted.readText())
    }

    @Test
    fun testCancellation() {
        val fileEntry = ResourceEntry(
            ResourceRef("mock", "doc:note.txt"),
            ResourceMetadata("note.txt", "text/plain", 10L, 1000L, false),
            dummyUri
        )
        val mockProvider = MockStorageProvider(
            fileData = mapOf("doc:note.txt" to "Note".toByteArray()),
            directories = emptyMap()
        )

        val targetZip = tempFolder.newFile("cancelled.zip")
        try {
            ArchiveWriter.createArchive(
                targetFile = targetZip,
                archiveName = "cancelled.zip",
                entries = listOf(fileEntry),
                provider = mockProvider,
                isCancelled = { true }
            )
            fail("Expected InterruptedException")
        } catch (_: InterruptedException) {
            // Success
        }
    }

    @Test
    fun testStreamDirectlyToOutputStream() {
        val fileEntry = ResourceEntry(
            ResourceRef("mock", "doc:stream.txt"),
            ResourceMetadata("stream.txt", "text/plain", 14L, 1000L, false),
            dummyUri
        )
        val mockProvider = MockStorageProvider(
            fileData = mapOf("doc:stream.txt" to "Streamed Data!".toByteArray()),
            directories = emptyMap()
        )

        val outBytes = java.io.ByteArrayOutputStream()
        var reportedBytes = 0L
        ArchiveWriter.createArchive(
            targetStream = outBytes,
            archiveName = "streamed.zip",
            entries = listOf(fileEntry),
            provider = mockProvider,
            onByteProgress = { bytesWritten, _, _, _ ->
                reportedBytes = bytesWritten
            }
        )

        val byteArray = outBytes.toByteArray()
        assertTrue(byteArray.isNotEmpty())

        val targetFile = tempFolder.newFile("streamed.zip")
        targetFile.writeBytes(byteArray)

        val reader = ArchiveReader(tempFolder.newFolder("stream_extract"))
        val tree = reader.buildTree(null, "streamed.zip", { targetFile.inputStream() }, targetFile)
        val rootEntries = tree.list("")
        assertEquals(1, rootEntries.size)
        assertEquals("stream.txt", rootEntries[0].name)

        val outDir = tempFolder.newFolder("stream_out")
        val extracted = reader.extractEntry("streamed.zip", "stream.txt", File(outDir, "stream.txt"), { targetFile.inputStream() }, targetFile)
        assertEquals("Streamed Data!", extracted.readText())
    }
}
