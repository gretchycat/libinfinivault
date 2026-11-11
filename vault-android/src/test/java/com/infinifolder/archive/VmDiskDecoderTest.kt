package com.infinifolder.archive

import com.infinifolder.storage.FileMagicDetector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class VmDiskDecoderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVmDiskArchiveFormatsAndMagic() {
        // Formats check
        assertTrue(ArchiveFormats.isDiscImage("disk.vdi"))
        assertTrue(ArchiveFormats.isDiscImage("disk.qcow2"))
        assertTrue(ArchiveFormats.isDiscImage("disk.vmdk"))
        assertTrue(ArchiveFormats.isDiscImage("disk.vhd"))
        assertTrue(ArchiveFormats.isDiscImage("disk.vhdx"))
        assertTrue(ArchiveFormats.isArchive("disk.vdi"))
        assertTrue(ArchiveFormats.isArchive("disk.qcow2"))
        assertTrue(ArchiveFormats.isArchive("disk.vmdk"))
        assertTrue(ArchiveFormats.isArchive("disk.vhd"))
        assertTrue(ArchiveFormats.isArchive("disk.vhdx"))

        // Magic detector check using temporary files
        val qcow2File = tempFolder.newFile("test_detect.qcow2")
        qcow2File.writeBytes(byteArrayOf('Q'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 0xFB.toByte()) + ByteArray(200))
        assertEquals("application/x-qemu-disk", FileMagicDetector.detect(qcow2File)?.mimeType)

        val vdiFile = tempFolder.newFile("test_detect.vdi")
        val vdiHeader = ByteArray(1024)
        vdiHeader[0x40] = 0x7F.toByte()
        vdiHeader[0x41] = 0x10.toByte()
        vdiHeader[0x42] = 0xDA.toByte()
        vdiHeader[0x43] = 0xBE.toByte()
        vdiFile.writeBytes(vdiHeader)
        assertEquals("application/x-virtualbox-vdi", FileMagicDetector.detect(vdiFile)?.mimeType)

        val vmdkFile = tempFolder.newFile("test_detect.vmdk")
        vmdkFile.writeBytes(byteArrayOf('K'.code.toByte(), 'D'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte()) + ByteArray(500))
        assertEquals("application/x-vmdk", FileMagicDetector.detect(vmdkFile)?.mimeType)

        val vhdFile = tempFolder.newFile("test_detect.vhd")
        val vhdContent = ByteArray(512)
        System.arraycopy("cxsparse".toByteArray(StandardCharsets.US_ASCII), 0, vhdContent, 0, 8)
        vhdFile.writeBytes(vhdContent)
        assertEquals("application/x-vhd", FileMagicDetector.detect(vhdFile)?.mimeType)

        val vhdxFile = tempFolder.newFile("test_detect.vhdx")
        val vhdxContent = ByteArray(512)
        System.arraycopy("vhdxfile".toByteArray(StandardCharsets.US_ASCII), 0, vhdxContent, 0, 8)
        vhdxFile.writeBytes(vhdxContent)
        assertEquals("application/x-vhdx", FileMagicDetector.detect(vhdxFile)?.mimeType)
    }

    @Test
    fun testFatFileSystemReadWriteCreateDelete() {
        val rawFat = buildBlankFat16(totalSectors = 4000)
        val imgFile = tempFolder.newFile("test_fat16.img")
        imgFile.writeBytes(rawFat)

        val raf = RandomAccessFile(imgFile, "rw")
        val dev = FileBlockDevice(raf, isReadOnly = false, size = imgFile.length())
        val fatFs = FatFileSystem(dev)

        // Initially no files in root
        val initialEntries = fatFs.readEntries()
        assertTrue(initialEntries.isEmpty())

        // Create a file
        val content1 = "Hello InfiniFolder FAT16 Write!".toByteArray(StandardCharsets.UTF_8)
        fatFs.createFile("hello.txt", content1)

        val entriesAfterCreate = fatFs.readEntries()
        assertEquals(1, entriesAfterCreate.size)
        assertEquals("hello.txt", entriesAfterCreate[0].path)
        assertEquals(content1.size.toLong(), entriesAfterCreate[0].sizeBytes)

        val readBack = readEntryBytes(dev, entriesAfterCreate[0])
        assertEquals("Hello InfiniFolder FAT16 Write!", String(readBack, StandardCharsets.UTF_8))

        // Create a subdirectory and a file inside it
        fatFs.createDirectory("docs")
        val content2 = "Deep document inside subfolder".toByteArray(StandardCharsets.UTF_8)
        fatFs.createFile("docs/deep.txt", content2)

        val entriesWithSub = fatFs.readEntries()
        val paths = entriesWithSub.map { it.path }.toSet()
        assertTrue(paths.contains("hello.txt"))
        assertTrue(paths.contains("docs"))
        assertTrue(paths.contains("docs/deep.txt"))

        val deepEntry = entriesWithSub.first { it.path == "docs/deep.txt" }
        assertEquals(content2.size.toLong(), deepEntry.sizeBytes)
        val readDeep = readEntryBytes(dev, deepEntry)
        assertEquals("Deep document inside subfolder", String(readDeep, StandardCharsets.UTF_8))

        // Update file
        val updatedContent = "Updated content with different length!".toByteArray(StandardCharsets.UTF_8)
        fatFs.writeEntry("hello.txt", updatedContent)
        val updatedEntry = fatFs.readEntries().first { it.path == "hello.txt" }
        assertEquals(updatedContent.size.toLong(), updatedEntry.sizeBytes)
        val readUpdated = readEntryBytes(dev, updatedEntry)
        assertEquals("Updated content with different length!", String(readUpdated, StandardCharsets.UTF_8))

        // Delete file
        fatFs.deleteEntry("hello.txt")
        val remainingPaths = fatFs.readEntries().map { it.path }
        assertFalse(remainingPaths.contains("hello.txt"))
        assertTrue(remainingPaths.contains("docs"))
        assertTrue(remainingPaths.contains("docs/deep.txt"))

        dev.close()
    }

    private fun readEntryBytes(dev: BlockDevice, entry: DiscImageReader.DiscEntry): ByteArray {
        val out = ByteArrayOutputStream()
        entry.extractor!!.invoke(dev, out)
        return out.toByteArray()
    }

    @Test
    fun testVdiDynamicBlockDeviceAndArchiveReader() {
        val rawFat = buildBlankFat16(totalSectors = 4000)
        val vdiFile = tempFolder.newFile("virtual_drive.vdi")
        buildSyntheticDynamicVdi(vdiFile, rawFat)

        val reader = ArchiveReader(tempFolder.newFolder("vdi_cache"))
        val tree = reader.buildTree(null, "virtual_drive.vdi", { vdiFile.inputStream() }, vdiFile)
        assertTrue(reader.isWritable("virtual_drive.vdi", vdiFile))

        // Create a file in VDI via ArchiveReader
        reader.createFile("virtual_drive.vdi", vdiFile, "vdi_test.txt", "VirtualBox VDI Success!".toByteArray(StandardCharsets.UTF_8))

        // Read back
        val updatedTree = reader.buildTree(null, "virtual_drive.vdi", { vdiFile.inputStream() }, vdiFile)
        val items = updatedTree.list("")
        assertTrue(items.any { it.name == "vdi_test.txt" })

        val content = reader.openEntryStream("virtual_drive.vdi", "vdi_test.txt", { vdiFile.inputStream() }, vdiFile)
            .bufferedReader().readText()
        assertEquals("VirtualBox VDI Success!", content)
    }

    @Test
    fun testVhdDynamicBlockDeviceAndArchiveReader() {
        val rawFat = buildBlankFat16(totalSectors = 4000)
        val vhdFile = tempFolder.newFile("virtual_drive.vhd")
        buildSyntheticDynamicVhd(vhdFile, rawFat)

        val reader = ArchiveReader(tempFolder.newFolder("vhd_cache"))
        val tree = reader.buildTree(null, "virtual_drive.vhd", { vhdFile.inputStream() }, vhdFile)
        assertTrue(reader.isWritable("virtual_drive.vhd", vhdFile))

        reader.createFile("virtual_drive.vhd", vhdFile, "vhd_test.txt", "Microsoft VHD Success!".toByteArray(StandardCharsets.UTF_8))

        val updatedTree = reader.buildTree(null, "virtual_drive.vhd", { vhdFile.inputStream() }, vhdFile)
        val items = updatedTree.list("")
        assertTrue(items.any { it.name == "vhd_test.txt" })

        val content = reader.openEntryStream("virtual_drive.vhd", "vhd_test.txt", { vhdFile.inputStream() }, vhdFile)
            .bufferedReader().readText()
        assertEquals("Microsoft VHD Success!", content)
    }

    @Test
    fun testQcow2BlockDeviceReadWrite() {
        val qcowFile = tempFolder.newFile("disk.qcow2")
        val clusterSize = 65536
        val virtualSize = 2L * 1024 * 1024 // 2MB
        buildSyntheticQcow2(qcowFile, virtualSize, clusterSize)

        val raf = RandomAccessFile(qcowFile, "rw")
        val dev = VmDiskDecoder.open(raf, isReadOnly = false, baseFile = qcowFile)
        assertNotNull(dev)
        assertEquals(virtualSize, dev!!.size)

        // Write to cluster 0 and cluster 1
        val testData1 = "Hello QCOW2 cluster 0".toByteArray(StandardCharsets.UTF_8)
        dev.writeAt(0, testData1, 0, testData1.size)

        val testData2 = "Hello QCOW2 cluster 1 at 64KB".toByteArray(StandardCharsets.UTF_8)
        dev.writeAt(clusterSize.toLong(), testData2, 0, testData2.size)

        // Read back
        val buf1 = ByteArray(testData1.size)
        dev.readAt(0, buf1, 0, buf1.size)
        assertEquals("Hello QCOW2 cluster 0", String(buf1, StandardCharsets.UTF_8))

        val buf2 = ByteArray(testData2.size)
        dev.readAt(clusterSize.toLong(), buf2, 0, buf2.size)
        assertEquals("Hello QCOW2 cluster 1 at 64KB", String(buf2, StandardCharsets.UTF_8))

        dev.close()
    }


    // =========================================================================
    // Synthetic Helpers
    // =========================================================================

    private fun buildBlankFat16(totalSectors: Int): ByteArray {
        val bytesPerSector = 512
        val reservedSectors = 1
        val numFats = 2
        val sectorsPerFat = 32
        val rootEntryCount = 512
        val img = ByteArray(totalSectors * bytesPerSector)
        val buf = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)

        img[0] = 0xEB.toByte()
        img[1] = 0x3C.toByte()
        img[2] = 0x90.toByte()
        buf.putShort(11, bytesPerSector.toShort())
        img[13] = 1 // 1 sector per cluster
        buf.putShort(14, reservedSectors.toShort())
        img[16] = numFats.toByte()
        buf.putShort(17, rootEntryCount.toShort())
        buf.putShort(19, totalSectors.toShort())
        img[21] = 0xF8.toByte() // Media descriptor
        buf.putShort(22, sectorsPerFat.toShort())
        System.arraycopy("FAT16   ".toByteArray(StandardCharsets.US_ASCII), 0, img, 54, 8)
        img[510] = 0x55.toByte()
        img[511] = 0xAA.toByte()

        // Cluster 0 and 1 are reserved in FAT
        for (f in 0 until numFats) {
            val fatOff = (reservedSectors + f * sectorsPerFat) * bytesPerSector
            buf.putShort(fatOff, 0xFFF8.toShort())
            buf.putShort(fatOff + 2, 0xFFFF.toShort())
        }

        return img
    }

    private fun buildSyntheticDynamicVdi(outFile: File, diskContent: ByteArray) {
        val blockSize = 1024 * 1024 // 1MB block size
        val virtualSize = diskContent.size.toLong()
        val numBlocks = ((virtualSize + blockSize - 1) / blockSize).toInt()
        val headerSize = 512
        val bmapOffset = 512
        val dataOffset = ((bmapOffset + numBlocks * 4 + 511) / 512) * 512

        val raf = RandomAccessFile(outFile, "rw")
        val headerBuf = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.putInt(0x40, 0xBEDA107F.toInt()) // VDI image signature
        headerBuf.putInt(0x44, 0x00010001) // Version 1.1
        headerBuf.putInt(0x48, headerSize) // Header size
        headerBuf.putInt(0x4C, 1) // Image type: Dynamic
        headerBuf.putInt(0x154, bmapOffset) // Offset blocks
        headerBuf.putInt(0x158, dataOffset) // Offset data
        headerBuf.putLong(0x170, virtualSize) // Disk size bytes
        headerBuf.putInt(0x178, blockSize) // Block size
        headerBuf.putInt(0x180, numBlocks) // Total blocks
        headerBuf.putInt(0x184, numBlocks) // Allocated blocks
        raf.seek(0)
        raf.write(headerBuf.array())

        // Block map: map block i -> block i
        val bmapBuf = ByteBuffer.allocate(numBlocks * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numBlocks) {
            bmapBuf.putInt(i)
        }
        raf.seek(bmapOffset.toLong())
        raf.write(bmapBuf.array())

        // Write disk content
        raf.seek(dataOffset.toLong())
        raf.write(diskContent)
        raf.close()
    }

    private fun buildSyntheticDynamicVhd(outFile: File, diskContent: ByteArray) {
        val blockSize = 2 * 1024 * 1024 // 2MB VHD default block size
        val virtualSize = diskContent.size.toLong()
        val maxBatEntries = ((virtualSize + blockSize - 1) / blockSize).toInt()

        val raf = RandomAccessFile(outFile, "rw")

        // Conectix Footer (512 bytes)
        val footer = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        footer.put("conectix".toByteArray(StandardCharsets.US_ASCII))
        footer.putInt(8, 0x00000002) // Features
        footer.putInt(12, 0x00010000) // File Format Version 1.0
        footer.putLong(16, 512L) // Data Offset -> Dynamic Header is at 512
        footer.putInt(24, 0) // Timestamp
        footer.put("win ".toByteArray(StandardCharsets.US_ASCII)) // Creator App
        footer.putInt(32, 0x00060001) // Creator Version
        footer.put("Wi2k".toByteArray(StandardCharsets.US_ASCII)) // Creator OS
        footer.putLong(40, virtualSize) // Original size
        footer.putLong(48, virtualSize) // Current size
        footer.putInt(60, 3) // Dynamic hard disk type

        // Checksum calculation for footer: one's complement of sum of all bytes except checksum field (64..67)
        var sum = 0
        val footerBytes = footer.array()
        for (i in 0 until 512) {
            if (i in 64..67) continue
            sum += (footerBytes[i].toInt() and 0xFF)
        }
        val checksum = sum.inv()
        footer.putInt(64, checksum)

        raf.seek(0)
        raf.write(footer.array())

        // Dynamic Header (1024 bytes) at offset 512
        val dynHeader = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN)
        dynHeader.put("cxsparse".toByteArray(StandardCharsets.US_ASCII))
        dynHeader.putLong(8, -1L) // Table Offset is at 1536
        dynHeader.putLong(16, 1536L) // Table Offset
        dynHeader.putInt(24, 0x00010000) // Header Version
        dynHeader.putInt(28, maxBatEntries) // Max Table Entries
        dynHeader.putInt(32, blockSize) // Block Size

        // Dyn header checksum
        var dynSum = 0
        val dynBytes = dynHeader.array()
        for (i in 0 until 1024) {
            if (i in 36..39) continue
            dynSum += (dynBytes[i].toInt() and 0xFF)
        }
        dynHeader.putInt(36, dynSum.inv())

        raf.seek(512)
        raf.write(dynHeader.array())

        // BAT table at 1536
        // Sector bitmap size for 2MB block = 2MB / 512 / 8 = 512 bytes (1 sector)
        val batOffset = 1536L
        val batSizeBytes = ((maxBatEntries * 4 + 511) / 512) * 512
        val firstBlockOffset = batOffset + batSizeBytes

        val batBuf = ByteBuffer.allocate(batSizeBytes).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until maxBatEntries) {
            val blockSectorOffset = (firstBlockOffset + i * (512 + blockSize)) / 512
            batBuf.putInt(blockSectorOffset.toInt())
        }
        raf.seek(batOffset)
        raf.write(batBuf.array())

        // For each block, write 512-byte bitmap (all 0xFF indicating sectors allocated) + data
        val bitmap = ByteArray(512) { 0xFF.toByte() }
        for (i in 0 until maxBatEntries) {
            val blkPos = firstBlockOffset + i * (512 + blockSize)
            raf.seek(blkPos)
            raf.write(bitmap)
            val chunkLen = Math.min(blockSize.toLong(), diskContent.size - i.toLong() * blockSize).toInt()
            if (chunkLen > 0) {
                raf.write(diskContent, i * blockSize, chunkLen)
            }
        }

        // VHD requires copy of footer at the end of the file
        val endPos = raf.length()
        raf.seek(endPos)
        raf.write(footer.array())

        raf.close()
    }

    private fun buildSyntheticQcow2(outFile: File, virtualSize: Long, clusterSize: Int) {
        val raf = RandomAccessFile(outFile, "rw")
        val clusterBits = Integer.numberOfTrailingZeros(clusterSize)

        // Header: 104 bytes
        val header = ByteBuffer.allocate(clusterSize).order(ByteOrder.BIG_ENDIAN)
        header.putInt(0, 0x514649FB) // 'QFI\xfb'
        header.putInt(4, 3) // Version 3
        header.putLong(8, 0L) // Backing file offset
        header.putInt(16, 0) // Backing file size
        header.putInt(20, clusterBits)
        header.putLong(24, virtualSize)
        header.putInt(32, 0) // Crypt method: none
        header.putInt(36, 1) // L1 size: 1 entry is enough for up to (clusterSize / 8) * clusterSize
        header.putLong(40, clusterSize.toLong()) // L1 table at cluster 1
        header.putLong(48, 0L) // Refcount table offset (unused for our read/write test)
        header.putInt(56, 0) // Refcount table clusters
        header.putInt(60, 0) // Number of snapshots
        header.putLong(64, 0L) // Snapshots offset

        // Cluster 0: Header
        raf.seek(0)
        raf.write(header.array())

        // Cluster 1: L1 table -> points to Cluster 2 for L2 table
        val l1Table = ByteBuffer.allocate(clusterSize).order(ByteOrder.BIG_ENDIAN)
        l1Table.putLong(0, (2L * clusterSize)) // L2 table at cluster 2
        raf.seek(clusterSize.toLong())
        raf.write(l1Table.array())

        // Cluster 2: L2 table -> maps guest cluster 0 to cluster 3, guest cluster 1 to cluster 4
        val l2Table = ByteBuffer.allocate(clusterSize).order(ByteOrder.BIG_ENDIAN)
        l2Table.putLong(0, (3L * clusterSize))
        l2Table.putLong(8, (4L * clusterSize))
        raf.seek(2L * clusterSize)
        raf.write(l2Table.array())

        // Cluster 3 & 4: Data clusters (zeroed)
        val emptyCluster = ByteArray(clusterSize)
        raf.seek(3L * clusterSize)
        raf.write(emptyCluster)
        raf.seek(4L * clusterSize)
        raf.write(emptyCluster)

        raf.close()
    }
}
