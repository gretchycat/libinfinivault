package com.infinifolder.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin FAT12, FAT16, and FAT32 reader and writer supporting:
 * - VFAT Long File Names (LFN)
 * - File and directory creation
 * - In-place and appending writes
 * - File and directory deletion
 * - Multi-FAT synchronization
 */
class FatFileSystem(
    val dev: BlockDevice,
    val baseOffset: Long = 0L,
    val prefix: String = ""
) : WritableImageFileSystem {

    enum class FatType { FAT12, FAT16, FAT32 }

    val bpb: ByteArray
    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val reservedSectors: Int
    val numFats: Int
    val rootEntryCount: Int
    val totalSectors: Long
    val sectorsPerFat: Long
    val rootDirSectors: Long
    val firstDataSector: Long
    val dataSectors: Long
    val totalClusters: Long
    val fatType: FatType
    val fatStartOffset: Long
    val clusterSize: Int
    val dataStartOffset: Long
    val rootOffset: Long
    val rootCluster: Long

    init {
        bpb = dev.readBytes(baseOffset, 512)
        if (bpb.size < 512) throw IOException("Failed to read FAT BPB at offset $baseOffset")
        bytesPerSector = u16le(bpb, 11)
        sectorsPerCluster = u8(bpb, 13)
        reservedSectors = u16le(bpb, 14)
        numFats = u8(bpb, 16)
        rootEntryCount = u16le(bpb, 17)
        val totalSectors16 = u16le(bpb, 19)
        val sectorsPerFat16 = u16le(bpb, 22)
        val totalSectors32 = u32le(bpb, 32)
        totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32
        sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16.toLong() else u32le(bpb, 36)

        rootDirSectors = if (bytesPerSector > 0) (((rootEntryCount * 32) + (bytesPerSector - 1)) / bytesPerSector).toLong() else 0L
        firstDataSector = reservedSectors.toLong() + (numFats.toLong() * sectorsPerFat) + rootDirSectors
        dataSectors = (totalSectors - firstDataSector).coerceAtLeast(0L)
        totalClusters = if (sectorsPerCluster > 0) dataSectors / sectorsPerCluster else 0L

        fatType = when {
            sectorsPerFat16 == 0 && rootEntryCount == 0 -> FatType.FAT32
            totalClusters < 4085 -> FatType.FAT12
            totalClusters < 65525 -> FatType.FAT16
            else -> FatType.FAT32
        }

        fatStartOffset = baseOffset + reservedSectors.toLong() * bytesPerSector
        clusterSize = bytesPerSector * sectorsPerCluster
        dataStartOffset = baseOffset + firstDataSector * bytesPerSector
        rootOffset = baseOffset + (reservedSectors.toLong() + numFats.toLong() * sectorsPerFat) * bytesPerSector
        rootCluster = if (fatType == FatType.FAT32) (u32le(bpb, 44) and 0x0FFFFFFFL) else 0L
    }

    val fileSystemInfo: com.infinifolder.storage.FileSystemInfo
        get() = com.infinifolder.storage.FileSystemInfo(fatType.name,
            com.infinifolder.storage.FileSystemInfo.FAT_MAX_FILE_SIZE, "FAT boot-sector geometry",
            com.infinifolder.storage.FileNameRules.VFAT, caseSensitive = false,
            allocationUnitBytes = clusterSize.toLong())

    fun fileSystemInfoWithSpace(): com.infinifolder.storage.FileSystemInfo =
        fileSystemInfo.copy(availableBytes = freeClusterBytes())

    /** Scan the allocation table in bounded chunks, not one network read per cluster. */
    private fun freeClusterBytes(): Long {
        if (clusterSize <= 0 || totalClusters < 0 || fatStartOffset < 0)
            throw IOException("Invalid FAT allocation geometry")
        val tableBytes = sectorsPerFat * bytesPerSector
        var windowStart = -1L
        var window = ByteArray(0)
        var free = 0L
        for (cluster in 2L until totalClusters + 2L) {
            val relative = when (fatType) {
                FatType.FAT12 -> cluster + cluster / 2
                FatType.FAT16 -> cluster * 2
                FatType.FAT32 -> cluster * 4
            }
            val width = if (fatType == FatType.FAT32) 4 else 2
            if (relative > tableBytes - width) throw IOException("FAT allocation table is shorter than its volume geometry")
            if (relative < windowStart || relative + width > windowStart + window.size) {
                windowStart = relative
                window = dev.readBytes(fatStartOffset + relative, minOf(64 * 1024L, tableBytes - relative).toInt())
            }
            val index = (relative - windowStart).toInt()
            if (index < 0 || index + width > window.size) throw IOException("Cannot read FAT free-space information")
            val value = when (fatType) {
                FatType.FAT12 -> {
                    val pair = u16le(window, index)
                    if (cluster % 2L == 0L) pair and 0x0FFF else pair ushr 4
                }
                FatType.FAT16 -> u16le(window, index)
                FatType.FAT32 -> (u32le(window, index) and 0x0FFFFFFF).toInt()
            }
            if (value == 0) free++
        }
        return Math.multiplyExact(free, clusterSize.toLong())
    }

    // =========================================================================
    // Cluster Chain Navigation and Modification
    // =========================================================================

    fun getNextCluster(cluster: Long): Long? {
        if (cluster < 2L) return null
        return when (fatType) {
            FatType.FAT12 -> {
                val entOffset = fatStartOffset + cluster + (cluster / 2)
                val pair = dev.readBytes(entOffset, 2)
                if (pair.size < 2) return null
                val raw = u16le(pair, 0)
                val next = if (cluster % 2L == 0L) (raw and 0x0FFF) else (raw ushr 4)
                if (next in 2..0xFF6) next.toLong() else null
            }
            FatType.FAT16 -> {
                val entOffset = fatStartOffset + cluster * 2L
                val pair = dev.readBytes(entOffset, 2)
                if (pair.size < 2) return null
                val next = u16le(pair, 0)
                if (next in 2..0xFFF6) next.toLong() else null
            }
            FatType.FAT32 -> {
                val entOffset = fatStartOffset + cluster * 4L
                val quad = dev.readBytes(entOffset, 4)
                if (quad.size < 4) return null
                val next = u32le(quad, 0) and 0x0FFFFFFFL
                if (next in 2L..0x0FFFFFF6L) next else null
            }
        }
    }

    fun getClusterChain(startCluster: Long, maxClusters: Int = 262144): List<Long> {
        if (startCluster < 2L) return emptyList()
        val chain = mutableListOf<Long>()
        val visited = HashSet<Long>()
        var curr: Long? = startCluster
        while (curr != null && curr >= 2L && visited.add(curr) && chain.size < maxClusters) {
            chain += curr
            curr = getNextCluster(curr)
        }
        return chain
    }

    fun setNextCluster(cluster: Long, next: Long) {
        if (cluster < 2L) return
        for (f in 0 until numFats) {
            val fatOffset = fatStartOffset + (f * sectorsPerFat * bytesPerSector)
            when (fatType) {
                FatType.FAT12 -> {
                    val entOffset = fatOffset + cluster + (cluster / 2)
                    val pair = dev.readBytes(entOffset, 2)
                    var raw = if (pair.size >= 2) u16le(pair, 0) else 0
                    val n = (next and 0x0FFF).toInt()
                    raw = if (cluster % 2L == 0L) {
                        (raw and 0xF000) or n
                    } else {
                        (raw and 0x000F) or (n shl 4)
                    }
                    val outBuf = byteArrayOf((raw and 0xFF).toByte(), ((raw ushr 8) and 0xFF).toByte())
                    dev.writeBytes(entOffset, outBuf)
                }
                FatType.FAT16 -> {
                    val entOffset = fatOffset + cluster * 2L
                    val outBuf = byteArrayOf((next and 0xFF).toByte(), ((next ushr 8) and 0xFF).toByte())
                    dev.writeBytes(entOffset, outBuf)
                }
                FatType.FAT32 -> {
                    val entOffset = fatOffset + cluster * 4L
                    val oldBuf = dev.readBytes(entOffset, 4)
                    val oldVal = if (oldBuf.size >= 4) u32le(oldBuf, 0) else 0L
                    val newVal = (oldVal and 0xF0000000L) or (next and 0x0FFFFFFFL)
                    val outBuf = ByteArray(4)
                    outBuf[0] = (newVal and 0xFF).toByte()
                    outBuf[1] = ((newVal ushr 8) and 0xFF).toByte()
                    outBuf[2] = ((newVal ushr 16) and 0xFF).toByte()
                    outBuf[3] = ((newVal ushr 24) and 0xFF).toByte()
                    dev.writeBytes(entOffset, outBuf)
                }
            }
        }
    }

    private fun isClusterFree(cluster: Long): Boolean {
        if (cluster < 2L) return false
        val entOffset = when (fatType) {
            FatType.FAT12 -> fatStartOffset + cluster + (cluster / 2)
            FatType.FAT16 -> fatStartOffset + cluster * 2L
            FatType.FAT32 -> fatStartOffset + cluster * 4L
        }
        val bytes = dev.readBytes(entOffset, if (fatType == FatType.FAT32) 4 else 2)
        return when (fatType) {
            FatType.FAT12 -> {
                if (bytes.size < 2) false
                else {
                    val raw = u16le(bytes, 0)
                    val v = if (cluster % 2L == 0L) (raw and 0x0FFF) else (raw ushr 4)
                    v == 0
                }
            }
            FatType.FAT16 -> {
                if (bytes.size < 2) false else u16le(bytes, 0) == 0
            }
            FatType.FAT32 -> {
                if (bytes.size < 4) false else (u32le(bytes, 0) and 0x0FFFFFFFL) == 0L
            }
        }
    }

    fun allocateClusters(count: Int): List<Long> {
        if (count <= 0) return emptyList()
        val allocated = mutableListOf<Long>()
        val maxCluster = totalClusters + 2L
        val tableBytes = sectorsPerFat * bytesPerSector
        val windowBytes = 64 * 1024L
        var windowStart = -1L
        var window = ByteArray(0)

        // Read sequential FAT windows rather than issuing one random device read
        // per candidate cluster. Repeated allocations otherwise rescan thousands
        // of entries through tiny seek/read calls.
        fun clusterIsFree(cluster: Long): Boolean {
            val relative = when (fatType) {
                FatType.FAT12 -> cluster + cluster / 2
                FatType.FAT16 -> cluster * 2L
                FatType.FAT32 -> cluster * 4L
            }
            val width = if (fatType == FatType.FAT32) 4 else 2
            if (relative < 0 || relative > tableBytes - width) {
                throw IOException("FAT allocation table is shorter than its volume geometry")
            }
            if (relative < windowStart || relative + width > windowStart + window.size) {
                windowStart = (relative / windowBytes) * windowBytes
                val length = minOf(windowBytes + 4L, tableBytes - windowStart).toInt()
                window = dev.readBytes(fatStartOffset + windowStart, length)
                if (window.size < length) throw IOException("Could not read FAT allocation table")
            }
            val index = (relative - windowStart).toInt()
            return when (fatType) {
                FatType.FAT12 -> {
                    val raw = u16le(window, index)
                    val value = if (cluster % 2L == 0L) raw and 0x0FFF else raw ushr 4
                    value == 0
                }
                FatType.FAT16 -> u16le(window, index) == 0
                FatType.FAT32 -> (u32le(window, index) and 0x0FFFFFFFL) == 0L
            }
        }

        var c = 2L
        while (c < maxCluster && allocated.size < count) {
            if (clusterIsFree(c)) {
                allocated += c
            }
            c++
        }
        if (allocated.size < count) {
            throw IOException("Disk full: insufficient free clusters (needed $count, found ${allocated.size})")
        }

        val eofMarker = when (fatType) {
            FatType.FAT12 -> 0x0FFFL
            FatType.FAT16 -> 0xFFFFL
            FatType.FAT32 -> 0x0FFFFFFFL
        }

        for (i in 0 until allocated.size - 1) {
            setNextCluster(allocated[i], allocated[i + 1])
        }
        setNextCluster(allocated.last(), eofMarker)
        return allocated
    }

    fun freeClusterChain(startCluster: Long) {
        val chain = getClusterChain(startCluster)
        for (c in chain) {
            setNextCluster(c, 0L)
        }
    }

    // =========================================================================
    // Reading Entries
    // =========================================================================

    fun readEntries(): List<DiscImageReader.DiscEntry> {
        val entries = mutableListOf<DiscImageReader.DiscEntry>()
        val visitedDirs = HashSet<Long>()

        fun readClusterChainBytes(startCluster: Long, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
            val chain = getClusterChain(startCluster, (maxBytes / clusterSize).coerceAtLeast(16))
            val out = ByteArrayOutputStream(chain.size * clusterSize)
            for (c in chain) {
                val cOffset = dataStartOffset + (c - 2L) * clusterSize
                val chunk = dev.readBytes(cOffset, clusterSize)
                out.write(chunk)
            }
            return out.toByteArray()
        }

        fun parseDirectoryBytes(dirBytes: ByteArray, currentPath: String) {
            val lfnParts = sortedMapOf<Int, String>()
            var pos = 0
            while (pos + 32 <= dirBytes.size) {
                val firstByte = u8(dirBytes, pos)
                if (firstByte == 0x00) break
                if (firstByte == 0xE5) {
                    lfnParts.clear()
                    pos += 32
                    continue
                }
                val attr = u8(dirBytes, pos + 11)
                if (attr == 0x0F) {
                    val seq = firstByte and 0x1F
                    if ((firstByte and 0x40) != 0) {
                        lfnParts.clear()
                    }
                    lfnParts[seq] = decodeVfatLfnEntry(dirBytes, pos)
                    pos += 32
                    continue
                }
                if ((attr and 0x08) != 0) {
                    lfnParts.clear()
                    pos += 32
                    continue
                }

                val name = if (lfnParts.isNotEmpty()) {
                    val joined = lfnParts.values.joinToString("")
                    lfnParts.clear()
                    joined.substringBefore('\u0000').substringBefore('\uFFFF').trim()
                } else {
                    decodeFatShortName(dirBytes, pos)
                }

                val isDir = (attr and 0x10) != 0
                val clusterHi = if (fatType == FatType.FAT32) u16le(dirBytes, pos + 20).toLong() else 0L
                val clusterLo = u16le(dirBytes, pos + 26).toLong()
                val startCluster = (clusterHi shl 16) or clusterLo
                val fileSize = u32le(dirBytes, pos + 28)
                val wrtTime = u16le(dirBytes, pos + 22)
                val wrtDate = u16le(dirBytes, pos + 24)
                val modifiedAtMillis = decodeDosDateTime(wrtDate, wrtTime)

                pos += 32

                if (name.isEmpty() || name == "." || name == "..") continue
                val childPath = joinPath(currentPath, name)

                if (isDir) {
                    entries += DiscImageReader.DiscEntry(
                        path = childPath,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = modifiedAtMillis
                    )
                    if (startCluster >= 2L && visitedDirs.add(startCluster)) {
                        val subDirBytes = readClusterChainBytes(startCluster)
                        parseDirectoryBytes(subDirBytes, childPath)
                    }
                } else {
                    entries += DiscImageReader.DiscEntry(
                        path = childPath,
                        isDirectory = false,
                        sizeBytes = fileSize,
                        modifiedAtMillis = modifiedAtMillis,
                        extractor = { d, out ->
                            var remaining = fileSize
                            if (remaining > 0L && startCluster >= 2L) {
                                val chain = getClusterChain(startCluster)
                                for (c in chain) {
                                    if (remaining <= 0L) break
                                    val cOffset = dataStartOffset + (c - 2L) * clusterSize
                                    val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
                                    val chunk = d.readBytes(cOffset, toRead)
                                    out.write(chunk)
                                    remaining -= toRead
                                }
                            }
                        }
                    )
                }
            }
        }

        if (fatType == FatType.FAT32) {
            visitedDirs.add(rootCluster)
            val rootBytes = readClusterChainBytes(rootCluster)
            parseDirectoryBytes(rootBytes, prefix)
        } else {
            val rootBytes = dev.readBytes(rootOffset, rootEntryCount * 32)
            parseDirectoryBytes(rootBytes, prefix)
        }

        return entries
    }

    /** Extract one regular file by walking only its parent directories and FAT chain. */
    fun extractFile(relativePath: String, output: OutputStream) {
        fileSystemInfo.requirePath(relativePath)
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) throw IOException("Empty file name")
        val parentCluster = findParentDirectoryCluster(parentPath)
        val item = listDirectoryItems(parentCluster).firstOrNull {
            it.name.equals(fileName, ignoreCase = true)
        } ?: throw NoSuchElementException("File not found: $relativePath")
        if (item.isDirectory) throw IOException("Cannot extract a directory as a file: $relativePath")

        var remaining = item.fileSize
        if (remaining <= 0L) return
        if (item.startCluster < 2L) throw IOException("File has no valid FAT cluster chain: $relativePath")
        for (cluster in getClusterChain(item.startCluster)) {
            if (remaining <= 0L) break
            val offset = dataStartOffset + (cluster - 2L) * clusterSize
            val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
            val bytes = dev.readBytes(offset, toRead)
            if (bytes.size != toRead) throw IOException("FAT file data is truncated: $relativePath")
            output.write(bytes)
            remaining -= toRead
        }
        if (remaining != 0L) throw IOException("FAT cluster chain is shorter than the file size: $relativePath")
    }

    // =========================================================================
    // Directory Management and Free Slot Allocation
    // =========================================================================

    private data class ParsedDirItem(
        val name: String,
        val isDirectory: Boolean,
        val startCluster: Long,
        val fileSize: Long,
        val slotDevOffset: Long,
        val entrySlotOffsets: List<Long>,
        val shortName11: String
    )

    private fun listDirectoryItems(dirCluster: Long): List<ParsedDirItem> {
        val items = mutableListOf<ParsedDirItem>()
        val lfnParts = sortedMapOf<Int, String>()
        val lfnSlotOffsets = mutableListOf<Long>()

        val (dirBytes, slotOffsets) = readDirectoryBytesWithOffsets(dirCluster)
        var pos = 0
        while (pos + 32 <= dirBytes.size) {
            val slotOffset = slotOffsets[pos / 32]
            val firstByte = u8(dirBytes, pos)
            if (firstByte == 0x00) break
            if (firstByte == 0xE5) {
                lfnParts.clear()
                lfnSlotOffsets.clear()
                pos += 32
                continue
            }
            val attr = u8(dirBytes, pos + 11)
            if (attr == 0x0F) {
                val seq = firstByte and 0x1F
                if ((firstByte and 0x40) != 0) {
                    lfnParts.clear()
                    lfnSlotOffsets.clear()
                }
                lfnParts[seq] = decodeVfatLfnEntry(dirBytes, pos)
                lfnSlotOffsets.add(slotOffset)
                pos += 32
                continue
            }
            if ((attr and 0x08) != 0) {
                lfnParts.clear()
                lfnSlotOffsets.clear()
                pos += 32
                continue
            }

            val name = if (lfnParts.isNotEmpty()) {
                val joined = lfnParts.values.joinToString("")
                joined.substringBefore('\u0000').substringBefore('\uFFFF').trim()
            } else {
                decodeFatShortName(dirBytes, pos)
            }

            val isDir = (attr and 0x10) != 0
            val clusterHi = if (fatType == FatType.FAT32) u16le(dirBytes, pos + 20).toLong() else 0L
            val clusterLo = u16le(dirBytes, pos + 26).toLong()
            val startCluster = (clusterHi shl 16) or clusterLo
            val fileSize = u32le(dirBytes, pos + 28)

            val entrySlots = lfnSlotOffsets.toList() + slotOffset
            val shortName11 = String(dirBytes, pos, 11, StandardCharsets.US_ASCII)
            lfnParts.clear()
            lfnSlotOffsets.clear()

            pos += 32
            if (name.isNotEmpty() && name != "." && name != "..") {
                items += ParsedDirItem(name, isDir, startCluster, fileSize, slotOffset, entrySlots, shortName11)
            }
        }
        return items
    }

    private fun readDirectoryBytesWithOffsets(dirCluster: Long): Pair<ByteArray, List<Long>> {
        val isRoot = (fatType != FatType.FAT32 && dirCluster == 0L)
        if (isRoot) {
            val totalBytes = rootEntryCount * 32
            val bytes = dev.readBytes(rootOffset, totalBytes)
            val offsets = List(rootEntryCount) { i -> rootOffset + i * 32L }
            return Pair(bytes, offsets)
        }

        val chain = getClusterChain(if (dirCluster == 0L) rootCluster else dirCluster)
        val out = ByteArrayOutputStream(chain.size * clusterSize)
        val offsets = mutableListOf<Long>()
        for (c in chain) {
            val cOffset = dataStartOffset + (c - 2L) * clusterSize
            val chunk = dev.readBytes(cOffset, clusterSize)
            out.write(chunk)
            val entriesInCluster = clusterSize / 32
            for (i in 0 until entriesInCluster) {
                offsets += cOffset + i * 32L
            }
        }
        return Pair(out.toByteArray(), offsets)
    }

    private fun findParentDirectoryCluster(dirPath: String): Long {
        val segments = dirPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var currentCluster = if (fatType == FatType.FAT32) rootCluster else 0L

        for (segment in segments) {
            val items = listDirectoryItems(currentCluster)
            val match = items.firstOrNull { it.name.equals(segment, ignoreCase = true) && it.isDirectory }
            if (match == null) {
                val visibleDirectories = items.filter { it.isDirectory }.take(24)
                    .joinToString(",") { "${it.name}@${it.startCluster}" }
                com.infinifolder.storage.DiagnosticLog.record(
                    "image.directory.lookup.failed",
                    "type=$fatType path=$dirPath segment=$segment currentCluster=$currentCluster " +
                        "visibleDirectories=$visibleDirectories"
                )
                throw NoSuchElementException("Directory not found: $segment in path $dirPath")
            }
            currentCluster = match.startCluster
        }
        return currentCluster
    }

    private fun allocateDirectorySlots(dirCluster: Long, slotsNeeded: Int): List<Long> {
        val (dirBytes, slotOffsets) = readDirectoryBytesWithOffsets(dirCluster)
        var consecutiveCount = 0
        var startIndex = -1

        for (i in slotOffsets.indices) {
            val firstByte = if (i * 32 < dirBytes.size) u8(dirBytes, i * 32) else 0x00
            if (firstByte == 0x00 || firstByte == 0xE5) {
                if (consecutiveCount == 0) startIndex = i
                consecutiveCount++
                if (consecutiveCount == slotsNeeded) {
                    return (startIndex until startIndex + slotsNeeded).map { slotOffsets[it] }
                }
            } else {
                consecutiveCount = 0
                startIndex = -1
            }
        }

        val isRoot16 = (fatType != FatType.FAT32 && dirCluster == 0L)
        if (isRoot16) {
            throw IOException("Root directory is full (FAT12/FAT16 root has maximum $rootEntryCount entries)")
        }

        // An LFN plus its short entry can span several clusters. Allocate each actual slot;
        // arithmetic past the end of one cluster would overwrite unrelated file data.
        val slotsPerCluster = clusterSize / 32
        if (slotsPerCluster == 0) throw IOException("Invalid FAT directory cluster size")
        val neededClusters = (slotsNeeded + slotsPerCluster - 1) / slotsPerCluster
        val newChain = allocateClusters(neededClusters)
        for (cluster in newChain) {
            dev.writeBytes(dataStartOffset + (cluster - 2L) * clusterSize, ByteArray(clusterSize))
        }
        val targetCluster = if (dirCluster == 0L) rootCluster else dirCluster
        val oldChain = getClusterChain(targetCluster)
        if (oldChain.isEmpty()) throw IOException("Invalid FAT parent directory chain")
        setNextCluster(oldChain.last(), newChain.first())

        // Directory readers stop at the first 0x00 slot, even when the FAT chain
        // continues. Once a new cluster is linked, convert the old end markers to
        // deleted slots so entries in the appended cluster remain visible.
        var clearedEndMarkers = 0
        for (index in slotOffsets.indices) {
            if (u8(dirBytes, index * 32) == 0x00) {
                dev.writeBytes(slotOffsets[index], byteArrayOf(0xE5.toByte()))
                clearedEndMarkers++
            }
        }
        com.infinifolder.storage.DiagnosticLog.record(
            "image.directory.expand",
            "type=$fatType parentCluster=$dirCluster oldClusters=${oldChain.size} " +
                "newClusters=${newChain.size} clearedEndMarkers=$clearedEndMarkers"
        )
        return newChain.flatMap { cluster ->
            val offset = dataStartOffset + (cluster - 2L) * clusterSize
            (0 until slotsPerCluster).map { offset + it * 32L }
        }.take(slotsNeeded)
    }

    // =========================================================================
    // Write Operations: Create File, Create Directory, Delete Entry
    // =========================================================================

    override fun createFile(relativePath: String, data: ByteArray) = createFileFrom(relativePath, data.size.toLong()) { data.inputStream() }
    private fun createFileFrom(relativePath: String, dataSize: Long, open: () -> java.io.InputStream) {
        fileSystemInfo.requirePath(relativePath)
        fileSystemInfo.requireFileFits(relativePath, dataSize)
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val fileName = cleanPath.substringAfterLast('/')
        if (fileName.isEmpty()) throw IllegalArgumentException("Empty file name")

        val parentCluster = findParentDirectoryCluster(parentPath)
        val existingItems = listDirectoryItems(parentCluster)
        val existingNames = existingItems.map { it.shortName11 }.toSet()

        // Create-new must not replace a known entry, including a case-only name collision.
        val existing = existingItems.firstOrNull { it.name.equals(fileName, ignoreCase = true) ||
            decodeFatShortName(it.shortName11.toByteArray(StandardCharsets.US_ASCII), 0).equals(fileName, ignoreCase = true) }
        if (existing != null) {
            throw java.nio.file.FileAlreadyExistsException(fileName)
        }

        // Generate 8.3 name and VFAT entries
        val shortName11 = generate83Name(fileName, existingNames)
        val lfnEntries = if (needsLfn(fileName, shortName11)) createVfatEntries(fileName, shortName11) else emptyList()
        val totalSlotsNeeded = lfnEntries.size + 1
        val slotOffsets = allocateDirectorySlots(parentCluster, totalSlotsNeeded)

        // Allocate clusters for data
        val clustersNeeded = if (dataSize == 0L) 0 else ((dataSize + clusterSize - 1) / clusterSize).toInt()
        val chain = if (clustersNeeded > 0) allocateClusters(clustersNeeded) else emptyList()
        val startCluster = if (chain.isNotEmpty()) chain.first() else 0L

        writeDataChain(chain, dataSize, open)


        // Write LFN slots in order
        for (i in lfnEntries.indices) {
            dev.writeBytes(slotOffsets[i], lfnEntries[i])
        }

        // Write main 32-byte dir entry
        val mainSlotOffset = slotOffsets.last()
        val dirEntryBytes = createDirEntryBytes(
            shortName11 = shortName11,
            attr = 0x20, // Archive
            startCluster = startCluster,
            fileSize = dataSize
        )
        dev.writeBytes(mainSlotOffset, dirEntryBytes)
        dev.flush()
    }

    override fun createDirectory(relativePath: String) {
        fileSystemInfo.requirePath(relativePath)
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val dirName = cleanPath.substringAfterLast('/')
        if (dirName.isEmpty()) throw IllegalArgumentException("Empty directory name")

        com.infinifolder.storage.DiagnosticLog.record(
            "image.directory.create.start",
            "type=$fatType path=$cleanPath parentPath=$parentPath"
        )
        val parentCluster = findParentDirectoryCluster(parentPath)
        val existingItems = listDirectoryItems(parentCluster)
        if (existingItems.any { it.name.equals(dirName, ignoreCase = true) ||
            decodeFatShortName(it.shortName11.toByteArray(StandardCharsets.US_ASCII), 0).equals(dirName, ignoreCase = true) }) {
            throw IOException("File or directory already exists: $dirName")
        }

        // Generate 8.3 name and VFAT entries in parent directory
        val shortName11 = generate83Name(dirName, existingItems.map { it.shortName11 }.toSet())
        val lfnEntries = if (needsLfn(dirName, shortName11)) createVfatEntries(dirName, shortName11) else emptyList()
        val totalSlots = lfnEntries.size + 1
        val slotOffsets = allocateDirectorySlots(parentCluster, totalSlots)

        // Allocate 1 cluster for the new directory
        val newCluster = allocateClusters(1).first()
        val clusterOffset = dataStartOffset + (newCluster - 2L) * clusterSize
        val zeroBlock = ByteArray(clusterSize)
        dev.writeBytes(clusterOffset, zeroBlock)

        // Initialize "." and ".." inside the new directory
        val dotName = ".".padEnd(11, ' ').toByteArray(StandardCharsets.US_ASCII)
        val dotDotName = "..".padEnd(11, ' ').toByteArray(StandardCharsets.US_ASCII)
        val dotEntry = createDirEntryBytes(dotName, attr = 0x10, startCluster = newCluster, fileSize = 0L)
        val dotDotCluster = if (parentCluster == rootCluster) 0L else parentCluster
        val dotDotEntry = createDirEntryBytes(dotDotName, attr = 0x10, startCluster = dotDotCluster, fileSize = 0L)

        dev.writeBytes(clusterOffset, dotEntry)
        dev.writeBytes(clusterOffset + 32L, dotDotEntry)


        for (i in lfnEntries.indices) {
            dev.writeBytes(slotOffsets[i], lfnEntries[i])
        }

        val mainDirEntry = createDirEntryBytes(
            shortName11 = shortName11,
            attr = 0x10, // Directory
            startCluster = newCluster,
            fileSize = 0L
        )
        dev.writeBytes(slotOffsets.last(), mainDirEntry)
        dev.flush()

        val created = listDirectoryItems(parentCluster).firstOrNull {
            it.name.equals(dirName, ignoreCase = true) && it.isDirectory
        }
        com.infinifolder.storage.DiagnosticLog.record(
            if (created == null) "image.directory.create.verify-failed" else "image.directory.create.success",
            "type=$fatType path=$cleanPath parentCluster=$parentCluster expectedCluster=$newCluster " +
                "visibleCluster=${created?.startCluster}"
        )
        if (created == null || created.startCluster != newCluster) {
            throw IOException("Created directory '$dirName' could not be read back from the FAT directory")
        }
    }

    override fun writeEntry(relativePath: String, data: ByteArray) = writeEntryFrom(relativePath, data.size.toLong()) { data.inputStream() }
    override fun writeEntryFromFile(relativePath: String, file: java.io.File) = writeEntryFrom(relativePath, file.length()) { file.inputStream() }
    private fun writeEntryFrom(relativePath: String, dataSize: Long, open: () -> java.io.InputStream) {
        fileSystemInfo.requirePath(relativePath)
        fileSystemInfo.requireFileFits(relativePath, dataSize)
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val fileName = cleanPath.substringAfterLast('/')

        val parentCluster = findParentDirectoryCluster(parentPath)
        val existingItems = listDirectoryItems(parentCluster)
        val match = existingItems.firstOrNull { it.name.equals(fileName, ignoreCase = true) ||
            decodeFatShortName(it.shortName11.toByteArray(StandardCharsets.US_ASCII), 0).equals(fileName, ignoreCase = true) }

        if (match == null) {
            createFileFrom(relativePath, dataSize, open)
            return
        }
        if (match.isDirectory) {
            throw IOException("Cannot write file content to directory '$fileName'")
        }

        // Keep the old chain allocated until the new bytes and directory entry are synced.
        // A full image must fail without first freeing the original content.
        // Allocate new clusters
        val clustersNeeded = if (dataSize == 0L) 0 else ((dataSize + clusterSize - 1) / clusterSize).toInt()
        val chain = if (clustersNeeded > 0) allocateClusters(clustersNeeded) else emptyList()
        val newStartCluster = if (chain.isNotEmpty()) chain.first() else 0L

        writeDataChain(chain, dataSize, open)

        // Update directory entry on dev
        val dirEntry = dev.readBytes(match.slotDevOffset, 32)
        val (dosDate, dosTime) = currentDosDateTime()
        // Attributes: retain or set archive
        dirEntry[11] = 0x20
        // Write Time & Date
        dirEntry[22] = (dosTime and 0xFF).toByte()
        dirEntry[23] = ((dosTime ushr 8) and 0xFF).toByte()
        dirEntry[24] = (dosDate and 0xFF).toByte()
        dirEntry[25] = ((dosDate ushr 8) and 0xFF).toByte()
        // Cluster Hi & Lo
        val hi = (newStartCluster ushr 16) and 0xFFFFL
        val lo = newStartCluster and 0xFFFFL
        if (fatType == FatType.FAT32) {
            dirEntry[20] = (hi and 0xFF).toByte()
            dirEntry[21] = ((hi ushr 8) and 0xFF).toByte()
        }
        dirEntry[26] = (lo and 0xFF).toByte()
        dirEntry[27] = ((lo ushr 8) and 0xFF).toByte()
        // File size
        val sizeL = dataSize
        dirEntry[28] = (sizeL and 0xFF).toByte()
        dirEntry[29] = ((sizeL ushr 8) and 0xFF).toByte()
        dirEntry[30] = ((sizeL ushr 16) and 0xFF).toByte()
        dirEntry[31] = ((sizeL ushr 24) and 0xFF).toByte()

        dev.flush()
        dev.writeBytes(match.slotDevOffset, dirEntry)
        dev.flush()
        if (match.startCluster >= 2L) {
            freeClusterChain(match.startCluster)
            dev.flush()
        }
    }

    private fun writeDataChain(chain: List<Long>, size: Long, open: () -> java.io.InputStream) {
        open().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(clusterSize)
            var remaining = size
            for (cluster in chain) {
                val count = minOf(remaining, clusterSize.toLong()).toInt()
                var read = 0
                while (read < count) {
                    val n = input.read(buffer, read, count - read)
                    if (n < 0) throw IOException("Staged image content was truncated; original allocation retained")
                    if (n == 0) continue
                    read += n
                }
                dev.writeAt(dataStartOffset + (cluster - 2L) * clusterSize, buffer, 0, count)
                remaining -= count
            }
            if (remaining != 0L || input.read() >= 0) throw IOException("Staged image content size changed")
        }
    }

    /** Relocate directory records in an unpublished snapshot. File chains and child
     * directory contents stay allocated; only a moved directory's '..' changes.
     * The caller must discard the snapshot on any failure, never publish it partially.
     */
    override fun relocateEntry(relativePath: String, targetPath: String) {
        fileSystemInfo.requirePath(relativePath)
        fileSystemInfo.requirePath(targetPath)
        val source = normalizePath(relativePath)
        val target = normalizePath(targetPath)
        if (source.isEmpty() || target.isEmpty()) throw IOException("Cannot move a FAT volume root")
        if (source.equals(target, ignoreCase = true)) return
        val sourceParent = findParentDirectoryCluster(source.substringBeforeLast('/', ""))
        val item = listDirectoryItems(sourceParent).firstOrNull {
            it.name.equals(source.substringAfterLast('/'), ignoreCase = true)
        } ?: throw IOException("FAT move source is no longer present: $source")
        val targetParentPath = target.substringBeforeLast('/', "")
        val targetParent = findParentDirectoryCluster(targetParentPath)
        if (item.isDirectory) {
            // Resolve real directory clusters rather than trusting path spelling.
            val segments = targetParentPath.split('/').filter { it.isNotEmpty() }
            for (index in 0..segments.size) {
                val cluster = findParentDirectoryCluster(segments.take(index).joinToString("/"))
                if (cluster == item.startCluster) throw IOException("Cannot move a folder into itself or its descendants")
            }
        }
        val targetName = target.substringAfterLast('/')
        val targetItems = listDirectoryItems(targetParent)
        if (targetItems.any { it.name.equals(targetName, ignoreCase = true) ||
                decodeFatShortName(it.shortName11.toByteArray(StandardCharsets.US_ASCII), 0).equals(targetName, ignoreCase = true) })
            throw java.nio.file.FileAlreadyExistsException(targetName)
        val original = dev.readBytes(item.slotDevOffset, 32)
        if (original.size != 32) throw IOException("Cannot read FAT move source metadata")
        val shortName = generate83Name(targetName, targetItems.map { it.shortName11 }.toSet())
        val longEntries = if (needsLfn(targetName, shortName)) createVfatEntries(targetName, shortName) else emptyList()
        val replacement = original.copyOf()
        shortName.copyInto(replacement, endIndex = 11)
        replacement[12] = 0 // New short alias has no inherited lowercase-name flags.
        var dotDotOffset: Long? = null
        var dotDot: ByteArray? = null
        if (item.isDirectory && sourceParent != targetParent) {
            if (item.startCluster < 2L) throw IOException("FAT directory has no valid allocation")
            val offset = dataStartOffset + (item.startCluster - 2L) * clusterSize
            val header = dev.readBytes(offset, 64)
            if (header.size != 64 || decodeFatShortName(header, 0) != "." ||
                decodeFatShortName(header, 32) != ".." || (u8(header, 11) and 0x10) == 0 || (u8(header, 43) and 0x10) == 0)
                throw IOException("FAT directory parent records are invalid; source retained")
            fun clusterAt(position: Int) = u16le(header, position + 26).toLong() or
                (if (fatType == FatType.FAT32) u16le(header, position + 20).toLong() shl 16 else 0L)
            val oldParent = if (sourceParent == rootCluster) 0L else sourceParent
            val recordedParent = clusterAt(32)
            val validParent = recordedParent == oldParent || (sourceParent == rootCluster && recordedParent == rootCluster)
            if (clusterAt(0) != item.startCluster || !validParent)
                throw IOException("FAT directory parent identity changed; source retained")
            val parent = if (targetParent == rootCluster) 0L else targetParent
            dotDotOffset = offset + 32
            dotDot = header.copyOfRange(32, 64).apply {
                this[26] = parent.toByte(); this[27] = (parent ushr 8).toByte()
                if (fatType == FatType.FAT32) {
                    this[20] = (parent ushr 16).toByte(); this[21] = (parent ushr 24).toByte()
                }
            }
        }
        val slots = allocateDirectorySlots(targetParent, longEntries.size + 1)
        longEntries.forEachIndexed { index, bytes -> dev.writeBytes(slots[index], bytes) }
        dev.writeBytes(slots.last(), replacement)
        if (dotDotOffset != null && dotDot != null) dev.writeBytes(dotDotOffset, dotDot)
        dev.flush()
        if (!dev.readBytes(slots.last(), 32).contentEquals(replacement) ||
            (dotDotOffset != null && !dev.readBytes(dotDotOffset, 32).contentEquals(dotDot)))
            throw IOException("FAT move metadata failed verification; source retained")
        // Unlink only the old name. Never free its file or directory cluster chain.
        item.entrySlotOffsets.asReversed().forEach { dev.writeBytes(it, byteArrayOf(0xE5.toByte())) }
        dev.flush()
        val moved = listDirectoryItems(targetParent).firstOrNull { it.name == targetName }
        if (moved == null || moved.startCluster != item.startCluster || moved.fileSize != item.fileSize ||
            moved.isDirectory != item.isDirectory || listDirectoryItems(sourceParent).any { it.slotDevOffset == item.slotDevOffset })
            throw IOException("FAT relocated entry failed verification; source retained")
        com.infinifolder.storage.DiagnosticLog.record("image.entry.relocated",
            "type=$fatType source=$source target=$target cluster=${item.startCluster} bytes=${item.fileSize} payloadCopied=false")
    }

    override fun deleteEntry(relativePath: String) {
        val cleanPath = normalizePath(relativePath)
        val parentPath = cleanPath.substringBeforeLast('/', "")
        val entryName = cleanPath.substringAfterLast('/')

        val parentCluster = findParentDirectoryCluster(parentPath)
        val items = listDirectoryItems(parentCluster)
        val match = items.firstOrNull { it.name.equals(entryName, ignoreCase = true) }
            ?: throw NoSuchElementException("Entry not found to delete: $relativePath")

        if (match.isDirectory) {
            val subItems = listDirectoryItems(match.startCluster)
            if (subItems.isNotEmpty()) {
                throw IOException("Directory is not empty: $entryName")
            }
        }

        // Publish the deletion before making data clusters reusable. Slot addresses may be
        // noncontiguous when a long name spans fragmented directory clusters.
        for (offset in match.entrySlotOffsets.asReversed()) {
            dev.writeBytes(offset, byteArrayOf(0xE5.toByte()))
        }
        dev.flush()
        if (match.startCluster >= 2L) {
            freeClusterChain(match.startCluster)
            dev.flush()
        }
    }

    // =========================================================================
    // Encoding & Helpers
    // =========================================================================

    private fun normalizePath(p: String): String =
        p.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")

    private fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

    private fun generate83Name(fileName: String, existingNames: Set<String>): ByteArray {
        val clean = fileName.trim()
        val dot = clean.lastIndexOf('.')
        val base = (if (dot >= 0) clean.substring(0, dot) else clean)
            .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            .uppercase(Locale.ROOT).ifEmpty { "_" }
        val ext = (if (dot >= 0) clean.substring(dot + 1) else "")
            .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            .uppercase(Locale.ROOT)

        var candidate = (base.take(8)).padEnd(8, ' ') + ext.take(3).padEnd(3, ' ')
        if (!existingNames.any { it.equals(candidate, ignoreCase = true) }) {
            return candidate.toByteArray(StandardCharsets.US_ASCII)
        }

        var num = 1
        while (num < 1_000_000) {
            val numStr = "~$num"
            val prefix = base.take(8 - numStr.length)
            candidate = (prefix + numStr).padEnd(8, ' ') + ext.take(3).padEnd(3, ' ')
            if (!existingNames.any { it.equals(candidate, ignoreCase = true) }) {
                return candidate.toByteArray(StandardCharsets.US_ASCII)
            }
            num++
        }
        throw IOException("No unique FAT short-name alias is available for '$fileName'")
    }

    private fun needsLfn(fileName: String, shortName11: ByteArray): Boolean {
        val reconstructed = decodeFatShortName(shortName11, 0)
        return !fileName.equals(reconstructed, ignoreCase = false)
    }

    private fun lfnChecksum(shortName11: ByteArray): Int {
        var sum = 0
        for (i in 0 until 11) {
            sum = (((sum and 1) shl 7) or (sum ushr 1)) + (shortName11[i].toInt() and 0xFF)
            sum = sum and 0xFF
        }
        return sum
    }

    private fun createVfatEntries(fullName: String, shortName11: ByteArray): List<ByteArray> {
        val chk = lfnChecksum(shortName11).toByte()
        val charList = fullName.toCharArray()
        val totalChars = charList.size
        val numEntries = (totalChars + 12) / 13
        val entries = mutableListOf<ByteArray>()

        for (e in numEntries downTo 1) {
            val entry = ByteArray(32)
            val seq = if (e == numEntries) (e or 0x40) else e
            entry[0] = seq.toByte()
            entry[11] = 0x0F // VFAT attribute
            entry[12] = 0x00
            entry[13] = chk
            entry[26] = 0x00
            entry[27] = 0x00

            val startChar = (e - 1) * 13
            val charOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (ci in 0 until 13) {
                val cIdx = startChar + ci
                val chVal = when {
                    cIdx < totalChars -> charList[cIdx].code
                    cIdx == totalChars -> 0x0000
                    else -> 0xFFFF
                }
                val bo = charOffsets[ci]
                entry[bo] = (chVal and 0xFF).toByte()
                entry[bo + 1] = ((chVal ushr 8) and 0xFF).toByte()
            }
            entries += entry
        }
        return entries
    }

    private fun currentDosDateTime(): Pair<Int, Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis()
        val year = cal.get(Calendar.YEAR).coerceAtLeast(1980)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val sec = cal.get(Calendar.SECOND)
        val dosDate = (((year - 1980) and 0x7F) shl 9) or ((month and 0x0F) shl 5) or (day and 0x1F)
        val dosTime = ((hour and 0x1F) shl 11) or ((min and 0x3F) shl 5) or ((sec / 2) and 0x1F)
        return Pair(dosDate, dosTime)
    }

    private fun createDirEntryBytes(
        shortName11: ByteArray,
        attr: Int,
        startCluster: Long,
        fileSize: Long
    ): ByteArray {
        val entry = ByteArray(32)
        System.arraycopy(shortName11, 0, entry, 0, 11)
        entry[11] = attr.toByte()

        val (dosDate, dosTime) = currentDosDateTime()
        // Create time & date
        entry[14] = (dosTime and 0xFF).toByte()
        entry[15] = ((dosTime ushr 8) and 0xFF).toByte()
        entry[16] = (dosDate and 0xFF).toByte()
        entry[17] = ((dosDate ushr 8) and 0xFF).toByte()
        // Last access date
        entry[18] = (dosDate and 0xFF).toByte()
        entry[19] = ((dosDate ushr 8) and 0xFF).toByte()
        // Cluster Hi (FAT32)
        val hi = (startCluster ushr 16) and 0xFFFFL
        entry[20] = (hi and 0xFF).toByte()
        entry[21] = ((hi ushr 8) and 0xFF).toByte()
        // Last write time & date
        entry[22] = (dosTime and 0xFF).toByte()
        entry[23] = ((dosTime ushr 8) and 0xFF).toByte()
        entry[24] = (dosDate and 0xFF).toByte()
        entry[25] = ((dosDate ushr 8) and 0xFF).toByte()
        // Cluster Lo
        val lo = startCluster and 0xFFFFL
        entry[26] = (lo and 0xFF).toByte()
        entry[27] = ((lo ushr 8) and 0xFF).toByte()
        // File Size
        entry[28] = (fileSize and 0xFF).toByte()
        entry[29] = ((fileSize ushr 8) and 0xFF).toByte()
        entry[30] = ((fileSize ushr 16) and 0xFF).toByte()
        entry[31] = ((fileSize ushr 24) and 0xFF).toByte()

        return entry
    }

    private fun decodeFatShortName(buf: ByteArray, offset: Int): String {
        val base = String(buf, offset, 8, StandardCharsets.US_ASCII).trimEnd()
        val ext = String(buf, offset + 8, 3, StandardCharsets.US_ASCII).trimEnd()
        return if (ext.isNotEmpty()) "$base.$ext" else base
    }

    private fun decodeVfatLfnEntry(buf: ByteArray, offset: Int): String {
        val chars = CharArray(13)
        val byteOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        var len = 0
        for (bo in byteOffsets) {
            val code = u16le(buf, offset + bo)
            if (code == 0x0000 || code == 0xFFFF) break
            chars[len++] = code.toChar()
        }
        return String(chars, 0, len)
    }

    private fun decodeDosDateTime(dosDate: Int, dosTime: Int): Long {
        if (dosDate == 0) return 0L
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val month = ((dosDate ushr 5) and 0x0F) - 1
        val day = dosDate and 0x1F
        val hour = (dosTime ushr 11) and 0x1F
        val min = (dosTime ushr 5) and 0x3F
        val sec = (dosTime and 0x1F) * 2
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month.coerceIn(0, 11), day.coerceIn(1, 31), hour.coerceIn(0, 23), min.coerceIn(0, 59), sec.coerceIn(0, 59))
        return cal.timeInMillis
    }

    private fun u8(buf: ByteArray, offset: Int): Int = buf[offset].toInt() and 0xFF

    private fun u16le(buf: ByteArray, offset: Int): Int =
        (u8(buf, offset)) or (u8(buf, offset + 1) shl 8)

    private fun u32le(buf: ByteArray, offset: Int): Long =
        ((u8(buf, offset).toLong()) or
            (u8(buf, offset + 1).toLong() shl 8) or
            (u8(buf, offset + 2).toLong() shl 16) or
            (u8(buf, offset + 3).toLong() shl 24)) and 0xFFFFFFFFL
}
