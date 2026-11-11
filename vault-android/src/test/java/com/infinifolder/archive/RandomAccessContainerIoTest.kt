package com.infinifolder.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class RandomAccessContainerIoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMemoryBlockDeviceCapabilitiesAndTruncate() {
        val initial = "Hello, world!".toByteArray(StandardCharsets.UTF_8)
        val dev = MemoryBlockDevice(initial, isReadOnly = false)

        assertEquals(initial.size.toLong(), dev.size)
        assertTrue(dev.capabilities.contains(DeviceCapability.READ))
        assertTrue(dev.capabilities.contains(DeviceCapability.WRITE))
        assertTrue(dev.capabilities.contains(DeviceCapability.TRUNCATE))
        assertFalse(dev.isReadOnly)

        // Read
        val buf = ByteArray(5)
        val read = dev.readAt(7L, buf, 0, 5)
        assertEquals(5, read)
        assertEquals("world", String(buf, StandardCharsets.UTF_8))

        // Write
        val newText = "Infini".toByteArray(StandardCharsets.UTF_8)
        dev.writeAt(0L, newText, 0, newText.size)
        assertEquals("Infini world!", String(dev.toByteArray(), StandardCharsets.UTF_8))

        // Truncate
        dev.truncate(6L)
        assertEquals(6L, dev.size)
        assertEquals("Infini", String(dev.toByteArray(), StandardCharsets.UTF_8))

        // Read-only device
        val roDev = MemoryBlockDevice(initial, isReadOnly = true)
        assertTrue(roDev.isReadOnly)
        assertFalse(roDev.capabilities.contains(DeviceCapability.WRITE))
        try {
            roDev.writeAt(0L, newText, 0, newText.size)
            fail("Expected IOException on read-only write")
        } catch (_: Exception) {}
    }

    @Test
    fun testBlockDeviceSeekableByteChannel() {
        val data = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray(StandardCharsets.US_ASCII)
        val dev = MemoryBlockDevice(data, isReadOnly = false)
        val channel = dev.asSeekableByteChannel()

        assertTrue(channel.isOpen)
        assertEquals(data.size.toLong(), channel.size())
        assertEquals(0L, channel.position())

        // Read from channel
        val dst = ByteBuffer.allocate(10)
        val bytesRead = channel.read(dst)
        assertEquals(10, bytesRead)
        assertEquals(10L, channel.position())
        dst.flip()
        val readStr = StandardCharsets.US_ASCII.decode(dst).toString()
        assertEquals("0123456789", readStr)

        // Seek and read
        channel.position(20L)
        assertEquals(20L, channel.position())
        val dst2 = ByteBuffer.allocate(6)
        channel.read(dst2)
        dst2.flip()
        assertEquals("KLMNOP", StandardCharsets.US_ASCII.decode(dst2).toString())

        // Channel write
        channel.position(0L)
        val writeBuf = ByteBuffer.wrap("ABCD".toByteArray(StandardCharsets.US_ASCII))
        channel.write(writeBuf)
        channel.position(0L)
        val checkBuf = ByteBuffer.allocate(4)
        channel.read(checkBuf)
        checkBuf.flip()
        assertEquals("ABCD", StandardCharsets.US_ASCII.decode(checkBuf).toString())
    }

    @Test
    fun testRangeCachedBlockDeviceHitMissAndInvalidation() {
        val backing = MemoryBlockDevice(ByteArray(256 * 1024) { (it % 127).toByte() }, isReadOnly = false)
        val cached = RangeCachedBlockDevice(backing, blockSize = 4096, maxCachedBlocks = 16)

        assertEquals(backing.size, cached.size)
        assertEquals(0L, cached.hitCount)
        assertEquals(0L, cached.missCount)

        // First read causes a cache miss
        val buf1 = ByteArray(100)
        cached.readAt(0L, buf1, 0, 100)
        assertEquals(1L, cached.missCount)
        assertEquals(0L, cached.hitCount)

        // Second read from the same 4KB block causes a cache hit
        val buf2 = ByteArray(100)
        cached.readAt(50L, buf2, 0, 100)
        assertEquals(1L, cached.missCount)
        assertEquals(1L, cached.hitCount)
        assertEquals((50 % 127).toByte(), buf2[0])

        // Write causes cache invalidation for affected block
        val modified = byteArrayOf(99, 99, 99)
        cached.writeAt(10L, modified, 0, 3)

        // Next read at block 0 is a miss again because it was invalidated
        cached.readAt(10L, buf1, 0, 3)
        assertEquals(2L, cached.missCount)
        assertEquals(99.toByte(), buf1[0])
    }

    @Test
    fun testZipRandomAccessOverBlockDevice() {
        val zipFile = tempFolder.newFile("test_random_access.zip")
        ZipArchiveOutputStream(zipFile).use { zos ->
            // Entry 1: Stored (uncompressed)
            val e1 = ZipArchiveEntry("stored.txt")
            val c1 = "Stored content uncompressed 1234567890".toByteArray(StandardCharsets.UTF_8)
            e1.method = ZipArchiveEntry.STORED
            e1.size = c1.size.toLong()
            e1.compressedSize = c1.size.toLong()
            val crc = CRC32()
            crc.update(c1)
            e1.crc = crc.value
            zos.putArchiveEntry(e1)
            zos.write(c1)
            zos.closeArchiveEntry()

            // Entry 2: Deflated (compressed)
            val e2 = ZipArchiveEntry("deflated.txt")
            val c2 = "Deflated content compressed lorem ipsum dolor sit amet".toByteArray(StandardCharsets.UTF_8)
            zos.putArchiveEntry(e2)
            zos.write(c2)
            zos.closeArchiveEntry()
        }

        // Open via RangeCachedBlockDevice
        val raf = RandomAccessFile(zipFile, "r")
        val fileDev = FileBlockDevice(raf, isReadOnly = true)
        val cachedDev = RangeCachedBlockDevice(fileDev, blockSize = 4096, maxCachedBlocks = 16)

        val reader = ArchiveReader(tempFolder.newFolder("temp_reader"))
        val tree = reader.buildTree(null, zipFile.name, { zipFile.inputStream() }, device = cachedDev)

        val rootEntries = tree.list("")
        assertEquals(2, rootEntries.size)
        assertTrue(rootEntries.any { it.name == "stored.txt" })
        assertTrue(rootEntries.any { it.name == "deflated.txt" })

        // Extract entry directly via BlockDevice
        val target1 = tempFolder.newFile("extracted_stored.txt")
        reader.extractEntry(zipFile.name, "stored.txt", target1, { zipFile.inputStream() }, device = cachedDev)
        assertEquals("Stored content uncompressed 1234567890", target1.readText(StandardCharsets.UTF_8))

        // Open entry stream directly via BlockDevice
        val stream = reader.openEntryStream(zipFile.name, "deflated.txt", { zipFile.inputStream() }, device = cachedDev)
        val text = stream.bufferedReader(StandardCharsets.UTF_8).readText()
        assertEquals("Deflated content compressed lorem ipsum dolor sit amet", text)

        raf.close()
    }

    @Test
    fun testZipInPlaceModification() {
        val zipFile = tempFolder.newFile("test_in_place.zip")
        val initialData = "Original 1234567".toByteArray(StandardCharsets.UTF_8) // 16 bytes

        ZipArchiveOutputStream(zipFile).use { zos ->
            val e1 = ZipArchiveEntry("config.bin")
            e1.method = ZipArchiveEntry.STORED
            e1.size = initialData.size.toLong()
            e1.compressedSize = initialData.size.toLong()
            val crc = CRC32()
            crc.update(initialData)
            e1.crc = crc.value
            zos.putArchiveEntry(e1)
            zos.write(initialData)
            zos.closeArchiveEntry()

            val e2 = ZipArchiveEntry("other.txt")
            val otherData = "Unrelated data to preserve".toByteArray(StandardCharsets.UTF_8)
            zos.putArchiveEntry(e2)
            zos.write(otherData)
            zos.closeArchiveEntry()
        }

        val raf = RandomAccessFile(zipFile, "rw")
        val dev = FileBlockDevice(raf, isReadOnly = false)

        assertTrue(ZipInPlaceModifier.canModifyInPlace(dev, "config.bin"))
        assertFalse(ZipInPlaceModifier.canModifyInPlace(dev, "other.txt")) // Deflated cannot be modified in place

        val replacement = "Updated  8765432".toByteArray(StandardCharsets.UTF_8) // exactly 16 bytes
        ZipInPlaceModifier.modifyStoredEntry(dev, "config.bin", replacement)
        dev.flush()

        // Re-open with ZipFile to verify archive integrity and CRC-32 validation
        ZipFile.builder().setSeekableByteChannel(dev.asSeekableByteChannel()).get().use { zf ->
            val entry = zf.getEntry("config.bin")
            assertNotNull(entry)
            val stream = zf.getInputStream(entry)
            val readBytes = stream.readBytes()
            assertEquals("Updated  8765432", String(readBytes, StandardCharsets.UTF_8))

            val other = zf.getEntry("other.txt")
            assertNotNull(other)
            assertEquals("Unrelated data to preserve", String(zf.getInputStream(other).readBytes(), StandardCharsets.UTF_8))
        }

        // Size mismatch must be rejected
        val wrongSize = "Short".toByteArray(StandardCharsets.UTF_8)
        try {
            ZipInPlaceModifier.modifyStoredEntry(dev, "config.bin", wrongSize)
            fail("Expected IOException on size mismatch")
        } catch (_: Exception) {}

        raf.close()
    }

    @Test
    fun test7zRandomAccessOverBlockDevice() {
        val szFile = tempFolder.newFile("test_sample.7z")
        SevenZOutputFile(szFile).use { sz ->
            val e1 = sz.createArchiveEntry(tempFolder.newFile("f1.txt"), "hello.txt")
            sz.putArchiveEntry(e1)
            sz.write("Hello 7z world!".toByteArray(StandardCharsets.UTF_8))
            sz.closeArchiveEntry()
        }

        val raf = RandomAccessFile(szFile, "r")
        val dev = FileBlockDevice(raf, isReadOnly = true)

        val reader = ArchiveReader(tempFolder.newFolder("temp_7z"))
        val tree = reader.buildTree(null, szFile.name, { szFile.inputStream() }, device = dev)

        val list = tree.list("")
        assertEquals(1, list.size)
        assertEquals("hello.txt", list[0].name)

        val stream = reader.openEntryStream(szFile.name, "hello.txt", { szFile.inputStream() }, device = dev)
        assertEquals("Hello 7z world!", stream.bufferedReader(StandardCharsets.UTF_8).readText())

        raf.close()
    }

    @Test
    fun testPartitionSliceAndCapabilityPropagation() {
        val parentData = ByteArray(1024) { it.toByte() }
        val parentDev = MemoryBlockDevice(parentData, isReadOnly = false)

        val slice = PartitionSliceBlockDevice(parentDev, startOffset = 100L, size = 50L)
        assertEquals(50L, slice.size)
        assertFalse(slice.isReadOnly)
        assertFalse(slice.capabilities.contains(DeviceCapability.TRUNCATE)) // Slice cannot be truncated

        // Read at offset 0 of slice should map to offset 100 of parent
        val b = ByteArray(1)
        slice.readAt(0L, b, 0, 1)
        assertEquals(100.toByte(), b[0])

        // Write to slice should reflect in parent
        slice.writeAt(0L, byteArrayOf(42), 0, 1)
        val checkParent = ByteArray(1)
        parentDev.readAt(100L, checkParent, 0, 1)
        assertEquals(42.toByte(), checkParent[0])

        // Out-of-bounds write to slice must throw IOException
        try {
            slice.writeAt(45L, ByteArray(10), 0, 10) // 45 + 10 = 55 > 50
            fail("Expected IOException on slice out of bounds write")
        } catch (_: Exception) {}

        // Read-only override
        val roSlice = PartitionSliceBlockDevice(parentDev, startOffset = 0L, size = 50L, readOnlyOverride = true)
        assertTrue(roSlice.isReadOnly)
        assertFalse(roSlice.capabilities.contains(DeviceCapability.WRITE))
    }

    @Test
    fun testTarStreamOverBlockDevice() {
        val tarFile = tempFolder.newFile("test_sample.tar")
        TarArchiveOutputStream(tarFile.outputStream()).use { tos ->
            val e = TarArchiveEntry("test_tar_entry.txt")
            val content = "Tar test payload".toByteArray(StandardCharsets.UTF_8)
            e.size = content.size.toLong()
            tos.putArchiveEntry(e)
            tos.write(content)
            tos.closeArchiveEntry()
        }

        val raf = RandomAccessFile(tarFile, "r")
        val dev = FileBlockDevice(raf, isReadOnly = true)

        val reader = ArchiveReader(tempFolder.newFolder("temp_tar"))
        val tree = reader.buildTree(null, tarFile.name, { tarFile.inputStream() }, device = dev)

        val entries = tree.list("")
        assertEquals(1, entries.size)
        assertEquals("test_tar_entry.txt", entries[0].name)

        val stream = reader.openEntryStream(tarFile.name, "test_tar_entry.txt", { tarFile.inputStream() }, device = dev)
        assertEquals("Tar test payload", stream.bufferedReader(StandardCharsets.UTF_8).readText())

        raf.close()
    }

    @Test
    fun testExtensionlessRemoteDiskImageMounting() {
        val disk = ByteArray(5100 * 512)
        // Protective MBR
        disk[446 + 4] = 0xEE.toByte()
        disk[510] = 0x55.toByte()
        disk[511] = 0xAA.toByte()

        // LBA 1: GPT Header
        val gptOff = 512
        val gptBuf = ByteBuffer.wrap(disk, gptOff, 92).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        System.arraycopy("EFI PART".toByteArray(StandardCharsets.US_ASCII), 0, disk, gptOff, 8)
        gptBuf.putInt(8, 0x00010000)
        gptBuf.putInt(12, 92)
        gptBuf.putLong(24, 1L)
        gptBuf.putLong(32, 5099L)
        gptBuf.putLong(40, 34L)
        gptBuf.putLong(48, 5066L)
        gptBuf.putLong(72, 2L)
        gptBuf.putInt(80, 128)
        gptBuf.putInt(84, 128)

        // Partition 1 entry at LBA 2
        val p1Off = 1024
        val p1Buf = ByteBuffer.wrap(disk, p1Off, 128).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val efiGuid = byteArrayOf(
            0x28.toByte(), 0x73.toByte(), 0x2A.toByte(), 0xC1.toByte(),
            0x1F.toByte(), 0xF8.toByte(),
            0xD2.toByte(), 0x11.toByte(),
            0xBA.toByte(), 0x4B.toByte(),
            0x00.toByte(), 0xA0.toByte(), 0xC9.toByte(), 0x3E.toByte(), 0xC9.toByte(), 0x3B.toByte()
        )
        System.arraycopy(efiGuid, 0, disk, p1Off, 16)
        p1Buf.putLong(32, 34L)
        p1Buf.putLong(40, 5033L)
        val p1NameChars = "EFI System Partition".toCharArray()
        for (i in p1NameChars.indices) {
            p1Buf.putChar(56 + i * 2, p1NameChars[i])
        }

        // Test FileMagicDetector
        val detected = com.infinifolder.storage.FileMagicDetector.detectFromBytes(disk.copyOf(4096))
        assertNotNull(detected)
        assertEquals("application/x-raw-disk-image", detected!!.mimeType)
        assertEquals("GPT Partitioned Disk Image", detected.description)
        assertTrue(detected.isArchiveOrDiscImage)

        // Test ArchiveFormats with detected MIME type for extensionless file "devsda"
        assertTrue(ArchiveFormats.isArchive("devsda", detected.mimeType))
        assertTrue(ArchiveFormats.isDiscImage("devsda", detected.mimeType))
        assertTrue(ArchiveFormats.isDefaultBrowsableArchive("devsda", detected.mimeType))

        // Test virtual mounting via ArchiveReader with RangeCachedBlockDevice wrapping MemoryBlockDevice
        val rawDev = MemoryBlockDevice(disk, isReadOnly = true)
        val cachedDev = RangeCachedBlockDevice(rawDev, blockSize = 512, maxCachedBlocks = 64)
        val reader = ArchiveReader(tempFolder.newFolder("temp_devsda"))
        val tree = reader.buildTree(null, "devsda", { cachedDev.asInputStream() }, device = cachedDev)

        val rootEntries = tree.list("")
        assertTrue(rootEntries.isNotEmpty())
        assertEquals("Partition 1 (EFI System Partition)", rootEntries[0].name)
        assertTrue(rootEntries[0].isDirectory)
    }
}
