package com.infinifolder.archive

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Read-only Apple II DOS 3.3 and ProDOS 140 KiB sector-image browser. */
internal class AppleTwoFileSystem(private val device: BlockDevice, private val order: SectorOrder) {
    enum class SectorOrder { DOS, PRODOS }

    fun entries(): List<DiscImageReader.DiscEntry> = when {
        ProDos.isSupported(device) -> ProDos(device).entries()
        Dos33.isSupported(device, order) -> Dos33(device, order).entries()
        else -> throw IOException("Unsupported or invalid Apple II disk image")
    }

    private class Dos33(private val device: BlockDevice, private val order: SectorOrder) {
        private fun sector(track: Int, number: Int): ByteArray {
            if (track !in 0 until TRACKS || number !in 0 until SECTORS) throw IOException("Apple DOS sector is out of range")
            val imageSector = if (order == SectorOrder.DOS) DOS_ORDER.indexOf(number) else PRODOS_ORDER.indexOf(number)
            if (imageSector < 0) throw IOException("Apple II sector order is invalid")
            val result = device.readBytes(((track * SECTORS + imageSector) * SECTOR_SIZE).toLong(), SECTOR_SIZE)
            if (result.size != SECTOR_SIZE) throw IOException("Apple DOS sector is truncated")
            return result
        }

        fun entries(): List<DiscImageReader.DiscEntry> {
            val vtoc = sector(VTOC_TRACK, VTOC_SECTOR)
            val firstTrack = u8(vtoc, 1)
            val firstSector = u8(vtoc, 2)
            if (firstTrack !in 1 until TRACKS || firstSector !in 0 until SECTORS) throw IOException("Apple DOS catalog pointer is invalid")
            val result = mutableListOf<DiscImageReader.DiscEntry>()
            val visited = mutableSetOf<Pair<Int, Int>>()
            var track = firstTrack
            var number = firstSector
            while (track != 0) {
                val key = track to number
                if (!visited.add(key) || visited.size > MAX_CATALOG_SECTORS) throw IOException("Apple DOS catalog chain loops or exceeds its limit")
                val catalog = sector(track, number)
                val nextTrack = u8(catalog, 1)
                val nextSector = u8(catalog, 2)
                for (slot in 0 until FILES_PER_CATALOG) {
                    val p = CATALOG_FIRST_ENTRY + slot * CATALOG_ENTRY_SIZE
                    val tsTrack = u8(catalog, p)
                    if (tsTrack == 0xff) continue // deleted directory entry
                    val tsSector = u8(catalog, p + 1)
                    val type = u8(catalog, p + 2)
                    val name = appleText(catalog, p + 3, 30).trim()
                    val sectors = u16le(catalog, p + 33)
                    if (name.isBlank() || !name.all { it.isLetterOrDigit() || it in " ._$-" } || sectors == 0 || tsTrack !in 1 until TRACKS || tsSector !in 0 until SECTORS)
                        throw IOException("Apple DOS catalog entry is malformed")
                    val fileSectors = readTsLists(tsTrack, tsSector, sectors)
                    val path = name
                    result += DiscImageReader.DiscEntry(path, false, sectors * SECTOR_SIZE.toLong(), null,
                        extractor = { source, out -> copyTsSectors(source, fileSectors, out) },
                        recoveryNote = "Apple DOS 3.3 file type ${FILE_TYPES[type and 0x7f] ?: "0x%02X".format(type and 0x7f)}${if (type and 0x80 != 0) "; locked" else ""}")
                    if (result.size > MAX_FILES) throw IOException("Apple DOS catalog exceeds the file limit")
                }
                if (nextTrack == 0) break
                if (nextTrack !in 1 until TRACKS || nextSector !in 0 until SECTORS) throw IOException("Apple DOS next-catalog pointer is invalid")
                track = nextTrack
                number = nextSector
            }
            return result.sortedBy { it.path.lowercase() }
        }

        private fun readTsLists(firstTrack: Int, firstSector: Int, declaredSectors: Int): List<Pair<Int, Int>> {
            val result = mutableListOf<Pair<Int, Int>>()
            val visited = mutableSetOf<Pair<Int, Int>>()
            var listTrack = firstTrack
            var listSector = firstSector
            while (result.size < declaredSectors) {
                val key = listTrack to listSector
                if (!visited.add(key) || visited.size > MAX_TS_LISTS) throw IOException("Apple DOS file T/S list loops or exceeds its limit")
                val list = sector(listTrack, listSector)
                for (offset in TS_PAIRS_START until SECTOR_SIZE step 2) {
                    val track = u8(list, offset)
                    val number = u8(list, offset + 1)
                    if (track == 0 && number == 0) continue
                    if (track !in 1 until TRACKS || number !in 0 until SECTORS) throw IOException("Apple DOS file sector pointer is invalid")
                    result += track to number
                    if (result.size == declaredSectors) break
                }
                if (result.size < declaredSectors) {
                    listTrack = u8(list, 1)
                    listSector = u8(list, 2)
                    if (listTrack == 0 || listTrack !in 1 until TRACKS || listSector !in 0 until SECTORS)
                        throw IOException("Apple DOS file T/S list chain is truncated")
                }
            }
            return result
        }

        private fun copyTsSectors(source: BlockDevice, sectors: List<Pair<Int, Int>>, out: OutputStream) {
            for ((track, number) in sectors) {
                val imageSector = if (order == SectorOrder.DOS) DOS_ORDER.indexOf(number) else PRODOS_ORDER.indexOf(number)
                val bytes = source.readBytes(((track * SECTORS + imageSector) * SECTOR_SIZE).toLong(), SECTOR_SIZE)
                if (bytes.size != SECTOR_SIZE) throw IOException("Apple DOS file sector is truncated")
                out.write(bytes)
            }
        }

        companion object {
            fun isSupported(device: BlockDevice, order: SectorOrder): Boolean {
                return try {
                    if (device.size != IMAGE_SIZE) return false
                    val fs = Dos33(device, order)
                    val vtoc = fs.sector(VTOC_TRACK, VTOC_SECTOR)
                    val firstTrack = u8(vtoc, 1)
                    val firstSector = u8(vtoc, 2)
                    val tracks = u8(vtoc, 0x34)
                    val sectors = u8(vtoc, 0x35)
                    val sectorSize = u16le(vtoc, 0x36)
                    if (u8(vtoc, 3) != 3 || tracks != TRACKS || sectors != SECTORS || sectorSize != SECTOR_SIZE ||
                        firstTrack !in 1 until TRACKS || firstSector !in 0 until SECTORS) return false
                    val catalog = fs.sector(firstTrack, firstSector)
                    (0 until FILES_PER_CATALOG).any { u8(catalog, CATALOG_FIRST_ENTRY + it * CATALOG_ENTRY_SIZE) in 1 until TRACKS }
                } catch (_: Exception) { false }
            }

            private const val VTOC_TRACK = 17
            private const val VTOC_SECTOR = 0
            private const val MAX_CATALOG_SECTORS = 16
            private const val MAX_TS_LISTS = 16
            private const val MAX_FILES = 105
            private const val FILES_PER_CATALOG = 7
            private const val CATALOG_FIRST_ENTRY = 0x0b
            private const val CATALOG_ENTRY_SIZE = 35
            private const val TS_PAIRS_START = 0x0c
            private val FILE_TYPES = mapOf(0 to "Text", 1 to "Integer BASIC", 2 to "Applesoft BASIC", 4 to "Binary", 8 to "S", 0x10 to "Relocatable", 0x20 to "A", 0x40 to "B")
        }
    }

    private class ProDos(private val device: BlockDevice) {
        fun entries(): List<DiscImageReader.DiscEntry> = readDirectory(ROOT_BLOCK, "", 0, mutableSetOf(), mutableListOf())

        private fun readDirectory(block: Int, prefix: String, depth: Int, active: MutableSet<Int>, result: MutableList<DiscImageReader.DiscEntry>): List<DiscImageReader.DiscEntry> {
            if (depth > MAX_DEPTH || !active.add(block)) throw IOException("ProDOS directory nesting limit or loop detected")
            try {
                var current = block
                val visitedBlocks = mutableSetOf<Int>()
                var first = true
                while (current != 0) {
                    if (!visitedBlocks.add(current) || visitedBlocks.size > MAX_DIRECTORY_BLOCKS) throw IOException("ProDOS directory block chain loops or exceeds its limit")
                    val bytes = readBlock(current)
                    val next = u16le(bytes, 0)
                    for (slot in 0 until ENTRIES_PER_BLOCK) {
                        if (first && slot == 0) continue // directory header entry
                        val p = DIRECTORY_FIRST_ENTRY + slot * ENTRY_SIZE
                        val storage = u8(bytes, p) ushr 4
                        val nameLength = u8(bytes, p) and 0x0f
                        if (storage == 0 || storage == 0xe) continue
                        if (nameLength !in 1..15) throw IOException("ProDOS directory entry has invalid name length")
                        val name = String(bytes, p + 1, nameLength, StandardCharsets.US_ASCII).filter { it.isLetterOrDigit() || it in "!#$%&'()-@^_{}~." }
                        if (name.isBlank()) throw IOException("ProDOS directory entry has an invalid name")
                        val key = u16le(bytes, p + 17)
                        val size = u24le(bytes, p + 21)
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (result.size >= MAX_FILES) throw IOException("ProDOS image exceeds the entry limit")
                        when (storage) {
                            STORAGE_SUBDIRECTORY -> {
                                result += DiscImageReader.DiscEntry(path, true, null, null, recoveryNote = "ProDOS directory")
                                readDirectory(key, path, depth + 1, active, result)
                            }
                            STORAGE_SEEDLING, STORAGE_SAPLING, STORAGE_TREE -> {
                                val fileType = u8(bytes, p + 16)
                                val access = u8(bytes, p + 28)
                                result += DiscImageReader.DiscEntry(path, false, size, null,
                                    extractor = { source, out -> copyProDosFile(source, key, storage, size, out) },
                                    recoveryNote = "ProDOS file type 0x%02X, auxiliary type 0x%04X%s".format(fileType, u16le(bytes, p + 31), if (access and 2 != 0) "; locked" else ""))
                            }
                        }
                    }
                    current = next
                    first = false
                }
                return result
            } finally { active.remove(block) }
        }

        private fun copyProDosFile(source: BlockDevice, key: Int, storage: Int, size: Long, out: OutputStream) {
            if (size !in 0..MAX_FILE_SIZE) throw IOException("ProDOS file size is outside supported bounds")
            var remaining = size
            fun writeBlock(block: Int) {
                if (block !in 0 until (source.size / BLOCK_SIZE).toInt()) throw IOException("ProDOS file block is outside the image")
                val bytes = source.readBytes(block * BLOCK_SIZE.toLong(), BLOCK_SIZE)
                if (bytes.size != BLOCK_SIZE) throw IOException("ProDOS data block is truncated")
                val n = minOf(remaining, BLOCK_SIZE.toLong()).toInt()
                if (n > 0) out.write(bytes, 0, n)
                remaining -= n
            }
            when (storage) {
                STORAGE_SEEDLING -> writeBlock(key)
                STORAGE_SAPLING -> {
                    val index = readBlock(key)
                    for (i in 0 until POINTERS_PER_BLOCK) {
                        val block = u16le(index, i * 2)
                        if (block != 0 && remaining > 0) writeBlock(block)
                    }
                }
                STORAGE_TREE -> {
                    val master = readBlock(key)
                    for (i in 0 until POINTERS_PER_BLOCK) {
                        val indexBlock = u16le(master, i * 2)
                        if (indexBlock == 0 || remaining == 0L) continue
                        val index = readBlock(indexBlock)
                        for (j in 0 until POINTERS_PER_BLOCK) {
                            val block = u16le(index, j * 2)
                            if (block != 0 && remaining > 0) writeBlock(block)
                        }
                    }
                }
                else -> throw IOException("Unsupported ProDOS storage type")
            }
            if (remaining != 0L) throw IOException("ProDOS file has fewer data blocks than its EOF length")
        }

        private fun readBlock(block: Int): ByteArray {
            if (block !in 0 until (device.size / BLOCK_SIZE).toInt()) throw IOException("ProDOS block is outside the image")
            val bytes = device.readBytes(block * BLOCK_SIZE.toLong(), BLOCK_SIZE)
            if (bytes.size != BLOCK_SIZE) throw IOException("ProDOS block is truncated")
            return bytes
        }

        companion object {
            fun isSupported(device: BlockDevice): Boolean {
                return try {
                    if (device.size != IMAGE_SIZE) return false
                    val bytes = ProDos(device).readBlock(ROOT_BLOCK)
                    val storage = u8(bytes, DIRECTORY_FIRST_ENTRY) ushr 4
                    val nameLength = u8(bytes, DIRECTORY_FIRST_ENTRY) and 0x0f
                    storage == STORAGE_VOLUME && nameLength in 1..15 &&
                        u8(bytes, DIRECTORY_FIRST_ENTRY + 0x23) == ENTRY_SIZE &&
                        u8(bytes, DIRECTORY_FIRST_ENTRY + 0x24) == ENTRIES_PER_BLOCK
                } catch (_: Exception) { false }
            }

            private const val ROOT_BLOCK = 2
            private const val BLOCK_SIZE = 512
            private const val DIRECTORY_FIRST_ENTRY = 4
            private const val ENTRY_SIZE = 39
            private const val ENTRIES_PER_BLOCK = 13
            private const val POINTERS_PER_BLOCK = 256
            private const val STORAGE_VOLUME = 0x0f
            private const val STORAGE_SEEDLING = 1
            private const val STORAGE_SAPLING = 2
            private const val STORAGE_TREE = 3
            private const val STORAGE_SUBDIRECTORY = 0x0d
            private const val MAX_DEPTH = 12
            private const val MAX_DIRECTORY_BLOCKS = 64
            private const val MAX_FILES = 4096
            private const val MAX_FILE_SIZE = 32L * 1024 * 1024
        }
    }

    companion object {
        fun isSupported(device: BlockDevice, order: SectorOrder): Boolean =
            device.size == IMAGE_SIZE && (ProDos.isSupported(device) || Dos33.isSupported(device, order))

        private const val IMAGE_SIZE = 35L * 16 * 256
        private const val TRACKS = 35
        private const val SECTORS = 16
        private const val SECTOR_SIZE = 256
        private val DOS_ORDER = intArrayOf(0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15)
        private val PRODOS_ORDER = intArrayOf(0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15)
        private fun appleText(bytes: ByteArray, offset: Int, length: Int): String = String(bytes, offset, length, StandardCharsets.ISO_8859_1)
            .map { (it.code and 0x7f).toChar() }.joinToString("").filterNot(Char::isISOControl)
        private fun u8(bytes: ByteArray, p: Int) = bytes[p].toInt() and 255
        private fun u16le(bytes: ByteArray, p: Int) = u8(bytes, p) or (u8(bytes, p + 1) shl 8)
        private fun u24le(bytes: ByteArray, p: Int) = u8(bytes, p).toLong() or (u8(bytes, p + 1).toLong() shl 8) or (u8(bytes, p + 2).toLong() shl 16)
    }
}
