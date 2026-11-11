package com.infinifolder.archive

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class DiscImageReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIso9660AndJolietAndBzip2() {
        val rawIso = buildSyntheticJolietIso(
            dirName = "Docs Folder",
            fileName = "Hello Joliet World.txt",
            content = "Hello from Joliet ISO 9660 disc image!".toByteArray(StandardCharsets.UTF_8)
        )
        val isoFile = tempFolder.newFile("test.iso")
        isoFile.writeBytes(rawIso)

        val reader = ArchiveReader(tempFolder.newFolder("temp_iso"))
        val tree = reader.buildTree(null, "test.iso", { isoFile.inputStream() }, isoFile)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("Docs Folder", rootItems[0].name)
        assertTrue(rootItems[0].isDirectory)

        val childItems = tree.list("Docs Folder")
        assertEquals(1, childItems.size)
        assertEquals("Hello Joliet World.txt", childItems[0].name)

        val text = reader.openEntryStream("test.iso", "Docs Folder/Hello Joliet World.txt", { isoFile.inputStream() }, isoFile)
            .bufferedReader().readText()
        assertEquals("Hello from Joliet ISO 9660 disc image!", text)

        // Also test bzip2-compressed .iso.bz2
        val bz2File = tempFolder.newFile("test.iso.bz2")
        bz2File.outputStream().use { fos ->
            BZip2CompressorOutputStream(fos).use { bzos ->
                bzos.write(rawIso)
            }
        }
        val bz2Tree = reader.buildTree(null, "test.iso.bz2", { bz2File.inputStream() }, bz2File)
        assertEquals("Hello Joliet World.txt", bz2Tree.list("Docs Folder").single().name)
        val extracted = tempFolder.newFile("extracted_joliet.txt")
        reader.extractEntry("test.iso.bz2", "Docs Folder/Hello Joliet World.txt", extracted, { bz2File.inputStream() }, bz2File)
        assertEquals("Hello from Joliet ISO 9660 disc image!", extracted.readText())
    }

    @Test
    fun testFat16WithVfatLongFileNamesAndGzip() {
        val rawFat = buildSyntheticFat16WithVfat(
            longName = "LongFileName_VFAT_Document.txt",
            content = "VFAT Long File Name content inside FAT16!".toByteArray(StandardCharsets.UTF_8)
        )
        val gzFile = tempFolder.newFile("disk.vfat.gz")
        gzFile.outputStream().use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                gzos.write(rawFat)
            }
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_fat"))
        val tree = reader.buildTree(null, "disk.vfat.gz", { gzFile.inputStream() }, gzFile)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("LongFileName_VFAT_Document.txt", rootItems[0].name)

        val text = reader.openEntryStream("disk.vfat.gz", "LongFileName_VFAT_Document.txt", { gzFile.inputStream() }, gzFile)
            .bufferedReader().readText()
        assertEquals("VFAT Long File Name content inside FAT16!", text)
    }

    @Test
    fun testExFatImage() {
        val rawExFat = buildSyntheticExFat(
            fileName = "exFAT_Sample_File.txt",
            content = "Payload stored inside exFAT cluster heap!".toByteArray(StandardCharsets.UTF_8)
        )
        val exFatFile = tempFolder.newFile("sdcard.exfat")
        exFatFile.writeBytes(rawExFat)

        val reader = ArchiveReader(tempFolder.newFolder("temp_exfat"))
        val tree = reader.buildTree(null, "sdcard.exfat", { exFatFile.inputStream() }, exFatFile)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("exFAT_Sample_File.txt", rootItems[0].name)

        val text = reader.openEntryStream("sdcard.exfat", "exFAT_Sample_File.txt", { exFatFile.inputStream() }, exFatFile)
            .bufferedReader().readText()
        assertEquals("Payload stored inside exFAT cluster heap!", text)
    }

    @Test
    fun testExt4ExtentTreeAndMbrPartitionAndBzip2() {
        val rawExt4 = buildSyntheticExt4(
            fileName = "ext4_extent_hello.txt",
            content = "Hello from an ext4 extent-based inode!".toByteArray(StandardCharsets.UTF_8)
        )

        // Wrap the ext4 filesystem inside an MBR-partitioned disk image at LBA 8 (offset 4096)
        val mbrDisk = ByteArray(4096 + rawExt4.size)
        // Partition 1 entry at offset 446
        val partEntry = ByteBuffer.wrap(mbrDisk, 446, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        mbrDisk[446] = 0x80.toByte()
        mbrDisk[446 + 4] = 0x83.toByte() // Linux filesystem
        partEntry.putInt(8, 8) // startLba = 8 (4096 bytes)
        partEntry.putInt(12, rawExt4.size / 512)
        mbrDisk[510] = 0x55.toByte()
        mbrDisk[511] = 0xAA.toByte()
        System.arraycopy(rawExt4, 0, mbrDisk, 4096, rawExt4.size)

        val bz2Img = tempFolder.newFile("rootfs.ext4.bz2")
        bz2Img.outputStream().use { fos ->
            BZip2CompressorOutputStream(fos).use { bzos ->
                bzos.write(mbrDisk)
            }
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_ext4"))
        val tree = reader.buildTree(null, "rootfs.ext4.bz2", { bz2Img.inputStream() }, bz2Img)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("ext4_extent_hello.txt", rootItems[0].name)

        val text = reader.openEntryStream("rootfs.ext4.bz2", "ext4_extent_hello.txt", { bz2Img.inputStream() }, bz2Img)
            .bufferedReader().readText()
        assertEquals("Hello from an ext4 extent-based inode!", text)
    }

    // =========================================================================
    // Synthetic Filesystem Image Builders for Unit Tests
    // =========================================================================

    private fun buildSyntheticJolietIso(dirName: String, fileName: String, content: ByteArray): ByteArray {
        // Sectors:
        // 0..15: System area
        // 16: Primary Volume Descriptor
        // 17: Joliet Supplementary Volume Descriptor
        // 18: Terminator
        // 19: Root Directory (Joliet)
        // 20: Subdirectory (Joliet)
        // 21: File data
        val img = ByteArray(24 * 2048)

        fun writeIsoRecord(target: ByteArray, pos: Int, lba: Int, dataLen: Int, isDir: Boolean, nameBytes: ByteArray): Int {
            val recLen = (33 + nameBytes.size + (if (nameBytes.size % 2 == 0) 1 else 0))
            target[pos] = recLen.toByte()
            val le = ByteBuffer.wrap(target, pos, recLen).slice().order(ByteOrder.LITTLE_ENDIAN)
            le.putInt(2, lba)
            le.putInt(10, dataLen)
            target[pos + 18] = 126.toByte() // 2026
            target[pos + 19] = 10.toByte()
            target[pos + 20] = 20.toByte()
            target[pos + 25] = (if (isDir) 0x02 else 0x00).toByte()
            target[pos + 32] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, target, pos + 33, nameBytes.size)
            return recLen
        }

        // Sector 16: PVD
        val pvdOff = 16 * 2048
        img[pvdOff] = 1
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, img, pvdOff + 1, 5)
        img[pvdOff + 6] = 1
        writeIsoRecord(img, pvdOff + 156, 19, 2048, true, byteArrayOf(0))

        // Sector 17: Joliet SVD
        val svdOff = 17 * 2048
        img[svdOff] = 2
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, img, svdOff + 1, 5)
        img[svdOff + 6] = 1
        // Joliet UCS-2 Level 3 escape sequence "%/E" at bytes 88..90
        img[svdOff + 88] = 0x25
        img[svdOff + 89] = 0x2F
        img[svdOff + 90] = 0x45
        writeIsoRecord(img, svdOff + 156, 19, 2048, true, byteArrayOf(0))

        // Sector 18: Terminator
        val termOff = 18 * 2048
        img[termOff] = 255.toByte()
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, img, termOff + 1, 5)

        // Sector 19: Root directory
        var pos = 19 * 2048
        pos += writeIsoRecord(img, pos, 19, 2048, true, byteArrayOf(0))
        pos += writeIsoRecord(img, pos, 19, 2048, true, byteArrayOf(1))
        writeIsoRecord(img, pos, 20, 2048, true, dirName.toByteArray(StandardCharsets.UTF_16BE))

        // Sector 20: Subdirectory
        var subPos = 20 * 2048
        subPos += writeIsoRecord(img, subPos, 20, 2048, true, byteArrayOf(0))
        subPos += writeIsoRecord(img, subPos, 19, 2048, true, byteArrayOf(1))
        writeIsoRecord(img, subPos, 21, content.size, false, "$fileName;1".toByteArray(StandardCharsets.UTF_16BE))

        // Sector 21: File content
        System.arraycopy(content, 0, img, 21 * 2048, content.size)
        return img
    }

    private fun buildSyntheticFat16WithVfat(longName: String, content: ByteArray): ByteArray {
        val bytesPerSector = 512
        val reservedSectors = 1
        val numFats = 1
        val sectorsPerFat = 32
        val rootEntryCount = 64 // 4 sectors
        val totalSectors = 5000 // > 4085 clusters -> FAT16
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
        buf.putShort(22, sectorsPerFat.toShort())
        System.arraycopy("FAT16   ".toByteArray(StandardCharsets.US_ASCII), 0, img, 54, 8)
        img[510] = 0x55.toByte()
        img[511] = 0xAA.toByte()

        // FAT table at sector 1: cluster 2 -> EOF (0xFFFF)
        val fatOff = reservedSectors * bytesPerSector
        buf.putShort(fatOff + 2 * 2, 0xFFFF.toShort())

        // Root directory at sector 33
        val rootOff = (reservedSectors + numFats * sectorsPerFat) * bytesPerSector
        val chunks = longName.chunked(13)
        var entryPos = rootOff
        for (i in chunks.indices.reversed()) {
            val seq = (i + 1) or (if (i == chunks.lastIndex) 0x40 else 0x00)
            img[entryPos] = seq.toByte()
            img[entryPos + 11] = 0x0F.toByte()
            val part = chunks[i]
            val offsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (cIdx in 0 until 13) {
                val code = when {
                    cIdx < part.length -> part[cIdx].code
                    cIdx == part.length -> 0x0000
                    else -> 0xFFFF
                }
                buf.putShort(entryPos + offsets[cIdx], code.toShort())
            }
            entryPos += 32
        }

        // Short entry
        System.arraycopy("LONGFI~1TXT".toByteArray(StandardCharsets.US_ASCII), 0, img, entryPos, 11)
        img[entryPos + 11] = 0x20.toByte()
        buf.putShort(entryPos + 26, 2) // firstCluster = 2
        buf.putInt(entryPos + 28, content.size)

        // Data area starts at sector 37 (1 + 32 + 4)
        val dataOff = (reservedSectors + numFats * sectorsPerFat + 4) * bytesPerSector
        System.arraycopy(content, 0, img, dataOff, content.size)
        return img
    }

    private fun buildSyntheticExFat(fileName: String, content: ByteArray): ByteArray {
        val img = ByteArray(16 * 512)
        val buf = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        System.arraycopy("EXFAT   ".toByteArray(StandardCharsets.US_ASCII), 0, img, 3, 8)
        buf.putInt(80, 1) // fatOffsetSectors = 1
        buf.putInt(84, 1) // fatLengthSectors = 1
        buf.putInt(88, 2) // clusterHeapOffsetSectors = 2
        buf.putInt(92, 10) // clusterCount = 10
        buf.putInt(96, 2) // rootCluster = 2
        img[108] = 9 // 512 bytes per sector
        img[109] = 0 // 1 sector per cluster

        // FAT at sector 1: cluster 2 -> EOF, cluster 3 -> EOF
        buf.putInt(512 + 2 * 4, 0xFFFFFFFF.toInt())
        buf.putInt(512 + 3 * 4, 0xFFFFFFFF.toInt())

        // Root directory at cluster 2 (sector 2, offset 1024)
        val nameChunks = fileName.chunked(15)
        val secondaryCount = 1 + nameChunks.size
        var pos = 1024
        // 0x85 File Directory Entry
        img[pos] = 0x85.toByte()
        img[pos + 1] = secondaryCount.toByte()
        buf.putShort(pos + 4, 0x0020.toShort()) // Archive attribute
        pos += 32

        // 0xC0 Stream Extension Entry
        img[pos] = 0xC0.toByte()
        img[pos + 1] = 0x03.toByte() // AllocationPossible | NoFatChain
        img[pos + 3] = fileName.length.toByte()
        buf.putLong(pos + 8, content.size.toLong()) // ValidDataLength
        buf.putInt(pos + 20, 3) // FirstCluster = 3
        buf.putLong(pos + 24, content.size.toLong()) // DataLength
        pos += 32

        // 0xC1 File Name Entries
        for (chunk in nameChunks) {
            img[pos] = 0xC1.toByte()
            val utf16 = chunk.toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(utf16, 0, img, pos + 2, utf16.size)
            pos += 32
        }

        // File content at cluster 3 (sector 3, offset 1536)
        System.arraycopy(content, 0, img, 3 * 512, content.size)
        return img
    }

    private fun buildSyntheticExt4(fileName: String, content: ByteArray): ByteArray {
        val blockSize = 1024
        val img = ByteArray(16 * blockSize)
        val buf = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)

        // Superblock at offset 1024 (block 1)
        val sbOff = 1024
        buf.putInt(sbOff + 0, 32) // s_inodes_count
        buf.putInt(sbOff + 4, 16) // s_blocks_count_lo
        buf.putInt(sbOff + 20, 1) // s_first_data_block
        buf.putInt(sbOff + 24, 0) // s_log_block_size = 0 (1024 bytes)
        buf.putInt(sbOff + 32, 1024) // s_blocks_per_group
        buf.putInt(sbOff + 40, 32) // s_inodes_per_group
        buf.putShort(sbOff + 56, 0xEF53.toShort()) // s_magic
        buf.putInt(sbOff + 76, 1) // s_rev_level = 1
        buf.putShort(sbOff + 88, 128.toShort()) // s_inode_size = 128

        // Block Group Descriptor Table at block 2 (offset 2048)
        val gdtOff = 2048
        buf.putInt(gdtOff + 8, 3) // bg_inode_table_lo = block 3 (offset 3072)

        // Inode Table at block 3 (offset 3072)
        // Inode 2 (root directory) at offset 3072 + (2 - 1) * 128 = 3200
        val rootInodeOff = 3072 + 128
        buf.putShort(rootInodeOff + 0, 0x41ED.toShort()) // directory mode
        buf.putInt(rootInodeOff + 4, blockSize) // i_size_lo = 1024
        buf.putInt(rootInodeOff + 32, 0) // direct blocks (non-extent) for root dir
        buf.putInt(rootInodeOff + 40, 6) // direct block[0] = block 6

        // Inode 3 (file with ext4 extent tree!) at offset 3072 + (3 - 1) * 128 = 3328
        val fileInodeOff = 3072 + 2 * 128
        buf.putShort(fileInodeOff + 0, 0x81A4.toShort()) // regular file mode
        buf.putInt(fileInodeOff + 4, content.size) // i_size_lo
        buf.putInt(fileInodeOff + 32, 0x00080000) // EXT4_EXTENTS_FL
        // Extent header at fileInodeOff + 40
        buf.putShort(fileInodeOff + 40, 0xF30A.toShort()) // eh_magic
        buf.putShort(fileInodeOff + 42, 1.toShort()) // eh_entries = 1
        buf.putShort(fileInodeOff + 44, 4.toShort()) // eh_max = 4
        buf.putShort(fileInodeOff + 46, 0.toShort()) // eh_depth = 0 (leaf)
        // Leaf extent at fileInodeOff + 52
        buf.putInt(fileInodeOff + 52, 0) // ee_block = 0
        buf.putShort(fileInodeOff + 56, 1.toShort()) // ee_len = 1 block
        buf.putShort(fileInodeOff + 58, 0.toShort()) // ee_start_hi = 0
        buf.putInt(fileInodeOff + 60, 7) // ee_start_lo = block 7

        // Block 6 (offset 6144): Root directory entries
        val dirOff = 6 * blockSize
        val nameBytes = fileName.toByteArray(StandardCharsets.UTF_8)
        buf.putInt(dirOff + 0, 3) // inode 3
        buf.putShort(dirOff + 4, blockSize.toShort()) // rec_len = 1024
        img[dirOff + 6] = nameBytes.size.toByte()
        img[dirOff + 7] = 1 // regular file
        System.arraycopy(nameBytes, 0, img, dirOff + 8, nameBytes.size)

        // Block 7 (offset 7168): File content
        System.arraycopy(content, 0, img, 7 * blockSize, content.size)
        return img
    }

    @Test
    fun testMultiPartitionMbrImageWithLayerAndBrowsing() {
        val rawFat = buildSyntheticFat16WithVfat("boot_cfg.txt", "console=serial0,115200".toByteArray(StandardCharsets.UTF_8))
        val rawExt4 = buildSyntheticExt4("root_flag.txt", "Linux rootfs online".toByteArray(StandardCharsets.UTF_8))

        // Disk layout (512-byte sectors):
        // Sector 0: MBR
        // Sector 8..5007: Partition 1 (FAT16) -> 5000 sectors
        // Sector 5010..5041: Partition 2 (ext4) -> 32 sectors
        // Sector 5050..5099: Partition 3 (Swap - type 0x82) -> 50 sectors
        val totalSectors = 5120
        val disk = ByteArray(totalSectors * 512)

        // Partition 1: FAT16 at LBA 8
        val p1Entry = ByteBuffer.wrap(disk, 446, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        disk[446] = 0x80.toByte()
        disk[446 + 4] = 0x06.toByte() // FAT16
        p1Entry.putInt(8, 8)
        p1Entry.putInt(12, rawFat.size / 512)
        System.arraycopy(rawFat, 0, disk, 8 * 512, rawFat.size)

        // Partition 2: Linux ext4 at LBA 5010
        val p2Entry = ByteBuffer.wrap(disk, 446 + 16, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        disk[446 + 16] = 0x00.toByte()
        disk[446 + 16 + 4] = 0x83.toByte() // Linux native
        p2Entry.putInt(8, 5010)
        p2Entry.putInt(12, rawExt4.size / 512)
        System.arraycopy(rawExt4, 0, disk, 5010 * 512, rawExt4.size)

        // Partition 3: Linux swap at LBA 5050 (unformatted raw slice)
        val p3Entry = ByteBuffer.wrap(disk, 446 + 32, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        disk[446 + 32] = 0x00.toByte()
        disk[446 + 32 + 4] = 0x82.toByte() // Linux swap
        p3Entry.putInt(8, 5050)
        p3Entry.putInt(12, 50)
        for (i in 0 until 50 * 512) {
            disk[5050 * 512 + i] = 0x77.toByte()
        }

        disk[510] = 0x55.toByte()
        disk[511] = 0xAA.toByte()

        val diskFile = tempFolder.newFile("multi_disk.img")
        diskFile.writeBytes(disk)

        val reader = ArchiveReader(tempFolder.newFolder("temp_multi"))
        val tree = reader.buildTree(null, "multi_disk.img", { diskFile.inputStream() }, diskFile)

        // 1. Root level MUST list the partition layer, NOT flatten into a single partition!
        val rootItems = tree.list("")
        assertEquals(3, rootItems.size)
        assertTrue(rootItems.all { it.isDirectory })

        val part1 = rootItems.first { it.name.startsWith("Partition 1") }
        val part2 = rootItems.first { it.name.startsWith("Partition 2") }
        val part3 = rootItems.first { it.name.startsWith("Partition 3") }

        assertTrue(part1.name.contains("FAT16"))
        assertTrue(part2.name.contains("Linux"))
        assertTrue(part3.name.contains("swap"))

        // 2. Browse Partition 1 (FAT)
        val p1Items = tree.list(part1.name)
        assertEquals(1, p1Items.size)
        assertEquals("boot_cfg.txt", p1Items[0].name)
        val p1Text = reader.openEntryStream("multi_disk.img", "${part1.name}/boot_cfg.txt", { diskFile.inputStream() }, diskFile)
            .bufferedReader().readText()
        assertEquals("console=serial0,115200", p1Text)

        // 3. Browse Partition 2 (ext4)
        val p2Items = tree.list(part2.name)
        assertEquals(1, p2Items.size)
        assertEquals("root_flag.txt", p2Items[0].name)
        val p2Text = reader.openEntryStream("multi_disk.img", "${part2.name}/root_flag.txt", { diskFile.inputStream() }, diskFile)
            .bufferedReader().readText()
        assertEquals("Linux rootfs online", p2Text)

        // 4. Browse Partition 3 (Swap / raw partition slice)
        val p3Items = tree.list(part3.name)
        assertEquals(1, p3Items.size)
        assertTrue(p3Items[0].name.endsWith(".raw"))
        assertEquals(50L * 512L, p3Items[0].sizeBytes)

        val p3Bytes = reader.openEntryStream("multi_disk.img", "${part3.name}/${p3Items[0].name}", { diskFile.inputStream() }, diskFile)
            .readBytes()
        assertEquals(50 * 512, p3Bytes.size)
        assertTrue(p3Bytes.all { it == 0x77.toByte() })
    }

    @Test
    fun testMultiPartitionGptImageWithLayerAndBrowsing() {
        val rawFat = buildSyntheticFat16WithVfat("kernel.img", "ARM64_KERNEL_BLOB".toByteArray(StandardCharsets.UTF_8))
        val rawExt4 = buildSyntheticExt4("issue", "Welcome to Infinifolder OS".toByteArray(StandardCharsets.UTF_8))

        // Sector 0: Protective MBR
        // Sector 1: GPT Header
        // Sector 2..33: GPT Partition Entries (LBA 2)
        // Sector 34..5033: Partition 1 (FAT16 with label "boot") -> 5000 sectors
        // Sector 5040..5071: Partition 2 (ext4 with label "rootfs") -> 32 sectors
        val totalSectors = 5100
        val disk = ByteArray(totalSectors * 512)

        // Protective MBR
        disk[446 + 4] = 0xEE.toByte()
        disk[510] = 0x55.toByte()
        disk[511] = 0xAA.toByte()

        // LBA 1: GPT Header (92 bytes)
        val gptOff = 512
        val gptBuf = ByteBuffer.wrap(disk, gptOff, 92).slice().order(ByteOrder.LITTLE_ENDIAN)
        System.arraycopy("EFI PART".toByteArray(StandardCharsets.US_ASCII), 0, disk, gptOff, 8)
        gptBuf.putInt(8, 0x00010000) // revision 1.0
        gptBuf.putInt(12, 92) // headerSize
        gptBuf.putLong(24, 1L) // myLba = 1
        gptBuf.putLong(32, (totalSectors - 1).toLong()) // alternateLba
        gptBuf.putLong(40, 34L) // firstUsableLba
        gptBuf.putLong(48, (totalSectors - 34).toLong()) // lastUsableLba
        gptBuf.putLong(72, 2L) // partitionEntryLba = 2
        gptBuf.putInt(80, 128) // numberOfPartitionEntries
        gptBuf.putInt(84, 128) // sizeOfPartitionEntry

        // LBA 2: Partition 1 entry at offset 1024
        val p1Off = 1024
        val p1Buf = ByteBuffer.wrap(disk, p1Off, 128).slice().order(ByteOrder.LITTLE_ENDIAN)
        // EFI System type GUID: C12A7328-F81F-11D2-BA4B-00A0C93EC93B
        val efiGuid = byteArrayOf(
            0x28.toByte(), 0x73.toByte(), 0x2A.toByte(), 0xC1.toByte(),
            0x1F.toByte(), 0xF8.toByte(),
            0xD2.toByte(), 0x11.toByte(),
            0xBA.toByte(), 0x4B.toByte(),
            0x00.toByte(), 0xA0.toByte(), 0xC9.toByte(), 0x3E.toByte(), 0xC9.toByte(), 0x3B.toByte()
        )
        System.arraycopy(efiGuid, 0, disk, p1Off, 16)
        p1Buf.putLong(32, 34L) // firstLba = 34
        p1Buf.putLong(40, 34L + (rawFat.size / 512) - 1L) // lastLba
        val bootLabel = "boot".toByteArray(StandardCharsets.UTF_16LE)
        System.arraycopy(bootLabel, 0, disk, p1Off + 56, bootLabel.size)
        System.arraycopy(rawFat, 0, disk, 34 * 512, rawFat.size)

        // LBA 2: Partition 2 entry at offset 1024 + 128 = 1152
        val p2Off = 1024 + 128
        val p2Buf = ByteBuffer.wrap(disk, p2Off, 128).slice().order(ByteOrder.LITTLE_ENDIAN)
        // Linux filesystem type GUID: 0FC63DAF-8483-4772-8E79-3D69D8477DE4
        val linuxGuid = byteArrayOf(
            0xAF.toByte(), 0x3D.toByte(), 0xC6.toByte(), 0x0F.toByte(),
            0x83.toByte(), 0x84.toByte(),
            0x72.toByte(), 0x47.toByte(),
            0x8E.toByte(), 0x79.toByte(),
            0x3D.toByte(), 0x69.toByte(), 0xD8.toByte(), 0x47.toByte(), 0x7D.toByte(), 0xE4.toByte()
        )
        System.arraycopy(linuxGuid, 0, disk, p2Off, 16)
        p2Buf.putLong(32, 5040L) // firstLba = 5040
        p2Buf.putLong(40, 5040L + (rawExt4.size / 512) - 1L) // lastLba
        val rootfsLabel = "rootfs".toByteArray(StandardCharsets.UTF_16LE)
        System.arraycopy(rootfsLabel, 0, disk, p2Off + 56, rootfsLabel.size)
        System.arraycopy(rawExt4, 0, disk, 5040 * 512, rawExt4.size)

        val gptFile = tempFolder.newFile("gpt_multidisk.img")
        gptFile.writeBytes(disk)

        val reader = ArchiveReader(tempFolder.newFolder("temp_gpt"))
        val tree = reader.buildTree(null, "gpt_multidisk.img", { gptFile.inputStream() }, gptFile)

        // Root level lists partition layer with names:
        val rootItems = tree.list("")
        assertEquals(2, rootItems.size)
        assertTrue(rootItems.all { it.isDirectory })

        val part1 = rootItems.first { it.name.startsWith("Partition 1") }
        val part2 = rootItems.first { it.name.startsWith("Partition 2") }

        assertEquals("Partition 1 (boot)", part1.name)
        assertEquals("Partition 2 (rootfs)", part2.name)

        // Browse Partition 1 (boot)
        val p1Items = tree.list(part1.name)
        assertEquals(1, p1Items.size)
        assertEquals("kernel.img", p1Items[0].name)
        val kText = reader.openEntryStream("gpt_multidisk.img", "${part1.name}/kernel.img", { gptFile.inputStream() }, gptFile)
            .bufferedReader().readText()
        assertEquals("ARM64_KERNEL_BLOB", kText)

        // Browse Partition 2 (rootfs)
        val p2Items = tree.list(part2.name)
        assertEquals(1, p2Items.size)
        assertEquals("issue", p2Items[0].name)
        val issueText = reader.openEntryStream("gpt_multidisk.img", "${part2.name}/issue", { gptFile.inputStream() }, gptFile)
            .bufferedReader().readText()
        assertEquals("Welcome to Infinifolder OS", issueText)
    }

    @Test
    fun testStandaloneEfiSystemPartitionImage() {
        val rawFat = buildSyntheticFat16WithVfat("BOOTX64.EFI", "UEFI_LOADER_BINARY_DATA".toByteArray(StandardCharsets.UTF_8))
        val espFile = tempFolder.newFile("boot.esp")
        espFile.writeBytes(rawFat)

        val reader = ArchiveReader(tempFolder.newFolder("temp_esp"))
        val tree = reader.buildTree(null, "boot.esp", { espFile.inputStream() }, espFile)

        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("BOOTX64.EFI", rootItems[0].name)

        val text = reader.openEntryStream("boot.esp", "BOOTX64.EFI", { espFile.inputStream() }, espFile)
            .bufferedReader().readText()
        assertEquals("UEFI_LOADER_BINARY_DATA", text)
    }

    @Test
    fun testLvmLogicalVolumesStandaloneAndInsidePartition() {
        val rawExt4 = buildSyntheticExt4("lvm_hello.txt", "Hello from inside LVM logical volume!".toByteArray(StandardCharsets.UTF_8))
        val rawData = "RAW_UNFORMATTED_VOLUME_BYTES".toByteArray(StandardCharsets.UTF_8)
        val lvmPv = buildSyntheticLvm(rawExt4, rawData)

        // 1. Standalone LVM PV disk image
        val lvmFile = tempFolder.newFile("storage.lvm")
        lvmFile.writeBytes(lvmPv)

        val reader = ArchiveReader(tempFolder.newFolder("temp_lvm"))
        val tree = reader.buildTree(null, "storage.lvm", { lvmFile.inputStream() }, lvmFile)

        val rootLvs = tree.list("")
        assertEquals(2, rootLvs.size)
        assertTrue(rootLvs.any { it.name == "lv_root" })
        assertTrue(rootLvs.any { it.name == "lv_data" })

        // Check ext4 filesystem inside lv_root
        val rootFiles = tree.list("lv_root")
        assertEquals(1, rootFiles.size)
        assertEquals("lvm_hello.txt", rootFiles[0].name)

        val rootText = reader.openEntryStream("storage.lvm", "lv_root/lvm_hello.txt", { lvmFile.inputStream() }, lvmFile)
            .bufferedReader().readText()
        assertEquals("Hello from inside LVM logical volume!", rootText)

        // Check raw file inside lv_data
        val dataFiles = tree.list("lv_data")
        assertEquals(1, dataFiles.size)
        assertTrue(dataFiles[0].name.endsWith(".raw"))

        // 2. LVM PV inside an MBR-partitioned disk
        val mbrDisk = ByteArray(4096 + lvmPv.size)
        val p1Entry = ByteBuffer.wrap(mbrDisk, 446, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        mbrDisk[446] = 0x80.toByte()
        mbrDisk[446 + 4] = 0x8E.toByte() // Linux LVM
        p1Entry.putInt(8, 8) // LBA 8 = 4096
        p1Entry.putInt(12, lvmPv.size / 512)
        mbrDisk[510] = 0x55.toByte()
        mbrDisk[511] = 0xAA.toByte()
        System.arraycopy(lvmPv, 0, mbrDisk, 4096, lvmPv.size)

        val mbrLvmFile = tempFolder.newFile("mbr_lvm.img")
        mbrLvmFile.writeBytes(mbrDisk)

        val mbrTree = reader.buildTree(null, "mbr_lvm.img", { mbrLvmFile.inputStream() }, mbrLvmFile)
        val mbrRoot = mbrTree.list("")
        // Partition 1 is flattened (since partitions.size == 1) exposing lv_root and lv_data directly
        assertEquals(2, mbrRoot.size)
        assertTrue(mbrRoot.any { it.name == "lv_root" })
        assertTrue(mbrRoot.any { it.name == "lv_data" })

        val mbrExt4Text = reader.openEntryStream("mbr_lvm.img", "lv_root/lvm_hello.txt", { mbrLvmFile.inputStream() }, mbrLvmFile)
            .bufferedReader().readText()
        assertEquals("Hello from inside LVM logical volume!", mbrExt4Text)
    }

    @Test
    fun testRawPartitionTableImageNavigationApmAndDd() {
        // 1. Raw .dd image with MBR
        val rawExt4 = buildSyntheticExt4("kernel.bin", "Kernel binary data".toByteArray(StandardCharsets.UTF_8))
        val mbrDisk = ByteArray(4096 + rawExt4.size)
        val p1 = ByteBuffer.wrap(mbrDisk, 446, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        mbrDisk[446] = 0x80.toByte()
        mbrDisk[446 + 4] = 0x83.toByte() // Linux
        p1.putInt(8, 8)
        p1.putInt(12, rawExt4.size / 512)
        mbrDisk[510] = 0x55.toByte()
        mbrDisk[511] = 0xAA.toByte()
        System.arraycopy(rawExt4, 0, mbrDisk, 4096, rawExt4.size)

        val ddFile = tempFolder.newFile("sdcard_backup.dd")
        ddFile.writeBytes(mbrDisk)

        val reader = ArchiveReader(tempFolder.newFolder("temp_dd"))
        val ddTree = reader.buildTree(null, "sdcard_backup.dd", { ddFile.inputStream() }, ddFile)
        val ddItems = ddTree.list("")
        assertEquals(1, ddItems.size)
        assertEquals("kernel.bin", ddItems[0].name)

        // 2. Raw Apple Partition Map (.apm) image
        val rawFat = buildSyntheticFat16WithVfat("Finder.app", "Mac OS System Finder".toByteArray(StandardCharsets.UTF_8))
        val apmDisk = ByteArray(4096 + rawFat.size)
        // Sector 1 (512): APM Block
        apmDisk[512] = 'P'.code.toByte()
        apmDisk[513] = 'M'.code.toByte()
        val apm1 = ByteBuffer.wrap(apmDisk, 512, 512).slice().order(ByteOrder.BIG_ENDIAN)
        apm1.putInt(4, 2) // numEntries = 2
        apm1.putInt(8, 1) // startLba
        apm1.putInt(12, 1) // numSectors
        System.arraycopy("Apple_partition_map".toByteArray(StandardCharsets.US_ASCII), 0, apmDisk, 512 + 48, 19)

        // Sector 2 (1024): Second partition entry (HFS/Data)
        apmDisk[1024] = 'P'.code.toByte()
        apmDisk[1025] = 'M'.code.toByte()
        val apm2 = ByteBuffer.wrap(apmDisk, 1024, 512).slice().order(ByteOrder.BIG_ENDIAN)
        apm2.putInt(4, 2)
        apm2.putInt(8, 8) // startLba = 8 (offset 4096)
        apm2.putInt(12, rawFat.size / 512)
        System.arraycopy("Macintosh_HD".toByteArray(StandardCharsets.US_ASCII), 0, apmDisk, 1024 + 16, 12)
        System.arraycopy("Apple_HFS".toByteArray(StandardCharsets.US_ASCII), 0, apmDisk, 1024 + 48, 9)
        System.arraycopy(rawFat, 0, apmDisk, 4096, rawFat.size)

        val apmFile = tempFolder.newFile("classic_mac.apm")
        apmFile.writeBytes(apmDisk)

        val apmTree = reader.buildTree(null, "classic_mac.apm", { apmFile.inputStream() }, apmFile)
        val apmItems = apmTree.list("")
        assertEquals(1, apmItems.size)
        assertEquals("Finder.app", apmItems[0].name)
    }

    private fun buildSyntheticLvm(rootPayload: ByteArray, dataPayload: ByteArray): ByteArray {
        val rootSectors = (rootPayload.size + 511) / 512
        val dataSectors = (dataPayload.size + 511) / 512
        val peStartSector = 16 // LBA 16 = offset 8192
        val extentSectors = 32 // 16 KiB per extent
        val rootExtents = (rootSectors + extentSectors - 1) / extentSectors
        val dataExtents = (dataSectors + extentSectors - 1) / extentSectors
        val totalSectors = peStartSector + (rootExtents + dataExtents + 2) * extentSectors
        val img = ByteArray(totalSectors * 512)

        // Sector 1 (offset 512): LABELONE
        val sec1Off = 512
        System.arraycopy("LABELONE".toByteArray(StandardCharsets.US_ASCII), 0, img, sec1Off, 8)
        val lBuf = ByteBuffer.wrap(img, sec1Off, 32).slice().order(ByteOrder.LITTLE_ENDIAN)
        lBuf.putLong(8, 1L) // sector_xl = 1
        lBuf.putInt(16, 0) // crc_xl
        lBuf.putInt(20, 32) // offset_xl = 32
        System.arraycopy("LVM2 001".toByteArray(StandardCharsets.US_ASCII), 0, img, sec1Off + 24, 8)

        // PV Header at sec1Off + 32 = 544
        val pvOff = sec1Off + 32
        System.arraycopy("PVTEST00000000000000000000000001".toByteArray(StandardCharsets.US_ASCII), 0, img, pvOff, 32)
        val pvBuf = ByteBuffer.wrap(img, pvOff, 112).slice().order(ByteOrder.LITTLE_ENDIAN)
        pvBuf.putLong(32, (totalSectors * 512).toLong())
        pvBuf.putLong(40, peStartSector * 512L) // Data-area list, followed by its zero terminator.
        pvBuf.putLong(48, (totalSectors - peStartSector) * 512L)
        pvBuf.putLong(72, 4096L) // Metadata-area list, followed by its zero terminator.
        pvBuf.putLong(80, 4096L) // Ends at the first physical extent.

        // Text metadata
        val metadataText = """
vg_synth {
    id = "3cT0gR-synth-001"
    seqno = 1
    format = "lvm2"
    status = ["RESIZEABLE", "READ", "WRITE"]
    flags = []
    extent_size = $extentSectors
    max_lv = 0
    max_pv = 0

    physical_volumes {
        pv0 {
            id = "PVTEST00000000000000000000000001"
            device = "/dev/sda1"
            status = ["ALLOCATABLE"]
            flags = []
            dev_size = $totalSectors
            pe_start = $peStartSector
            pe_count = ${rootExtents + dataExtents}
        }
    }

    logical_volumes {
        lv_root {
            id = "lv-root-001"
            status = ["VISIBLE"]
            flags = []
            segment_count = 1

            segment1 {
                start_extent = 0
                extent_count = $rootExtents
                type = "striped"
                stripe_count = 1
                stripes = [
                    "pv0", 0
                ]
            }
        }

        lv_data {
            id = "lv-data-002"
            status = ["VISIBLE"]
            flags = []
            segment_count = 1

            segment1 {
                start_extent = 0
                extent_count = $dataExtents
                type = "striped"
                stripe_count = 1
                stripes = [
                    "pv0", $rootExtents
                ]
            }
        }
    }
}
""".trimIndent()

        val textBytes = metadataText.toByteArray(StandardCharsets.UTF_8)

        // MDA Header at 4096
        val mdaOff = 4096
        System.arraycopy(" LVM2 x[5A%r0N*>".toByteArray(StandardCharsets.US_ASCII), 0, img, mdaOff + 4, 16)
        val mdaBuf = ByteBuffer.wrap(img, mdaOff, 512).slice().order(ByteOrder.LITTLE_ENDIAN)
        mdaBuf.putInt(20, 1) // version = 1
        mdaBuf.putLong(24, mdaOff.toLong()) // PV-relative metadata-area origin
        mdaBuf.putLong(32, 4096L) // Matches the PV metadata-area descriptor
        mdaBuf.putLong(40, 512L) // locOffset = 512 -> text at 4096 + 512 = 4608
        mdaBuf.putLong(48, textBytes.size.toLong()) // locSize

        // Write text metadata at 4608
        System.arraycopy(textBytes, 0, img, 4608, textBytes.size)

        // Write root payload at peStartSector * 512
        val rootOff = peStartSector * 512
        System.arraycopy(rootPayload, 0, img, rootOff, rootPayload.size)

        // Write data payload at rootOff + rootExtents * extentSectors * 512
        val dataOff = rootOff + (rootExtents * extentSectors * 512)
        System.arraycopy(dataPayload, 0, img, dataOff, dataPayload.size)

        return img
    }
}
