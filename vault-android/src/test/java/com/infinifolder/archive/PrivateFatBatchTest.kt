package com.infinifolder.archive

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PrivateFatBatchTest {
    @get:Rule val temp = TemporaryFolder()

    @After fun resetMountHooks() {
        MountedPartitionDetector.mockMountEntries = null
        MountedPartitionDetector.mockLoopDevice = null
        MountedPartitionDetector.mockIsLocked = null
    }

    private fun image(): File {
        val bytes = ByteArray(5000 * 512)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0xEB.toByte(); bytes[1] = 0x3C; bytes[2] = 0x90.toByte()
        b.putShort(11, 512); bytes[13] = 1; b.putShort(14, 1)
        bytes[16] = 2; b.putShort(17, 512); b.putShort(19, 5000)
        bytes[21] = 0xF8.toByte(); b.putShort(22, 32)
        "FAT16   ".toByteArray().copyInto(bytes, 54)
        bytes[510] = 0x55; bytes[511] = 0xAA.toByte()
        for (offset in listOf(512, 33 * 512)) {
            b.putShort(offset, 0xFFF8.toShort()); b.putShort(offset + 2, 0xFFFF.toShort())
        }
        return temp.newFile("original.img").apply { writeBytes(bytes) }
    }

    @Test fun privateSmallWritesRemainReadableWithoutPerEntryMountProbes() {
        val original = image()
        val originalBytes = original.readBytes()
        val snapshot = original.copyTo(File(temp.root, "snapshot.img"))
        val reader = ArchiveReader(temp.newFolder("read-cache"))
        var probes = 0
        MountedPartitionDetector.mockMountEntries = emptyList()
        MountedPartitionDetector.mockLoopDevice = { probes++; "/dev/loop-test" }
        val expected = linkedMapOf<String, ByteArray>()
        PrivateBatchBlockDevice(snapshot, writable = true).use { dev ->
            for (directory in 0 until 6) {
                val parent = "Long directory $directory"
                reader.createDirectory(snapshot.name, snapshot, parent, dev)
                for (index in 0 until 20) {
                    val path = "$parent/Small file $index.bin"
                    val bytes = ByteArray(1024 + index) { (it + directory + index).toByte() }
                    val payload = File(temp.root, "payload").apply { writeBytes(bytes) }
                    reader.createFile(snapshot.name, snapshot, path, byteArrayOf(), dev)
                    reader.writeEntryFromFile(snapshot.name, snapshot, path, payload, dev)
                    expected[path] = bytes
                    val read = reader.openEntryStream(snapshot.name, path, { snapshot.inputStream() }, snapshot, dev)
                        .use { it.readBytes() }
                    assertArrayEquals(path, bytes, read)
                }
            }
        }
        assertEquals("Private writes must not invoke mount/loop probes", 0, probes)
        RandomAccessFile(snapshot, "rw").use { it.fd.sync() }
        val tree = reader.buildTree(null, snapshot.name, { snapshot.inputStream() }, snapshot)
        assertEquals(6, tree.list("").size)
        expected.forEach { (path, bytes) ->
            assertNotNull(tree.getEntry(path))
            val read = reader.openEntryStream(snapshot.name, path, { snapshot.inputStream() }, snapshot)
                .use { it.readBytes() }
            assertArrayEquals(path, bytes, read)
        }
        val updated = snapshot.readBytes()
        assertArrayEquals("Both FAT copies must agree", updated.copyOfRange(512, 33 * 512),
            updated.copyOfRange(33 * 512, 65 * 512))
        assertArrayEquals("Unpublished writes must leave the original intact", originalBytes, original.readBytes())
    }

    @Test fun ordinaryAndMismatchedDevicesStillCheckTheOriginalMount() {
        val original = image()
        val before = original.readBytes()
        val snapshot = original.copyTo(File(temp.root, "snapshot.img"))
        val reader = ArchiveReader(temp.newFolder("reader"))
        var probes = 0
        MountedPartitionDetector.mockMountEntries = emptyList()
        MountedPartitionDetector.mockLoopDevice = { probes++; "/dev/loop-test" }
        val handles = listOf(
            FileBlockDevice(RandomAccessFile(original, "rw"), ownsFile = true),
            PrivateBatchBlockDevice(snapshot, writable = true)
        )
        handles.forEach { dev ->
            dev.use {
                try {
                    reader.createDirectory(original.name, original, "Must not exist", it)
                    fail("Mounted original must be rejected, including a mismatched private handle")
                } catch (_: MountedPartitionException) { }
            }
        }
        assertEquals(2, probes)
        assertArrayEquals(before, original.readBytes())
        assertArrayEquals(before, snapshot.readBytes())
    }

    @Test fun privateMarkerDoesNotMakeAReadOnlyHandleWritable() {
        val snapshot = image()
        val before = snapshot.readBytes()
        MountedPartitionDetector.mockMountEntries = emptyList()
        PrivateBatchBlockDevice(snapshot, writable = false).use { dev ->
            try {
                ArchiveReader(temp.newFolder("reader")).createFile(snapshot.name, snapshot, "denied.txt", byteArrayOf(1), dev)
                fail("Read-only private handle must reject writes")
            } catch (_: IOException) { }
        }
        assertArrayEquals(before, snapshot.readBytes())
    }

    @Test fun privateDeletionReclaimsClustersAndPreservesOtherFiles() {
        val original = image()
        val keep = ByteArray(1700) { (it * 13).toByte() }
        var freeBeforeSelectedFiles = 0L
        FileBlockDevice(RandomAccessFile(original, "rw"), ownsFile = true, flushOnRequest = false).use { dev ->
            val fs = FatFileSystem(dev)
            fs.createFile("Keep this file.bin", keep)
            freeBeforeSelectedFiles = fs.fileSystemInfoWithSpace().availableBytes!!
            fs.createDirectory("Remove these files")
            for (index in 0 until 120) fs.createFile("Remove these files/Small file $index.bin", ByteArray(701) { index.toByte() })
        }
        val originalBytes = original.readBytes()
        val snapshot = original.copyTo(File(temp.root, "delete-snapshot.img"))
        val reader = ArchiveReader(temp.newFolder("delete-reader"))
        var mountProbes = 0
        MountedPartitionDetector.mockMountEntries = emptyList()
        MountedPartitionDetector.mockLoopDevice = { mountProbes++; "/dev/loop-test" }
        PrivateBatchBlockDevice(snapshot, writable = true).use { dev ->
            try {
                reader.deleteEntry(snapshot.name, snapshot, "Remove these files", dev)
                fail("Low-level delete must still reject a nonempty directory")
            } catch (_: IOException) { }
            for (index in 0 until 120) reader.deleteEntry(snapshot.name, snapshot, "Remove these files/Small file $index.bin", dev)
            reader.deleteEntry(snapshot.name, snapshot, "Remove these files", dev)
            assertEquals(freeBeforeSelectedFiles, FatFileSystem(dev).fileSystemInfoWithSpace().availableBytes)
        }
        RandomAccessFile(snapshot, "rw").use { it.fd.sync() }
        val tree = reader.buildTree(null, snapshot.name, { snapshot.inputStream() }, snapshot)
        assertEquals(listOf("Keep this file.bin"), tree.list("").map { it.name })
        assertArrayEquals(keep, reader.openEntryStream(snapshot.name, "Keep this file.bin", { snapshot.inputStream() }, snapshot).use { it.readBytes() })
        assertEquals(0, mountProbes)
        val updated = snapshot.readBytes()
        assertArrayEquals(updated.copyOfRange(512, 33 * 512), updated.copyOfRange(33 * 512, 65 * 512))
        assertArrayEquals("Deletion in the unpublished copy must not touch the original", originalBytes, original.readBytes())
    }
}
