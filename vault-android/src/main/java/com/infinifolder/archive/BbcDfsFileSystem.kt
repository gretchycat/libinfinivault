package com.infinifolder.archive

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Read-only Acorn/BBC DFS single- and double-sided 40/80-track sector images. */
internal class BbcDfsFileSystem(private val device: BlockDevice, doubleSided: Boolean = false) {
    private val layout = Layout.read(device, doubleSided) ?: throw IOException("Invalid BBC DFS image")

    fun entries(): List<DiscImageReader.DiscEntry> {
        val entries = mutableListOf<DiscImageReader.DiscEntry>()
        for (side in 0 until layout.sides) {
            val catalog = layout.readCatalog(device, side)
            val count = (catalog[0x105].toInt() and 255) / 8
            val sidePrefix = if (layout.sides == 1) "" else "Side ${if (side == 0) 0 else 2}"
            if (sidePrefix.isNotEmpty()) entries += DiscImageReader.DiscEntry(sidePrefix, true, null, null)
            val directories = linkedSetOf<String>()
            for (index in 0 until count) {
            val nameAt = 8 + index * 8
            val infoAt = 0x108 + index * 8
            val directoryByte = catalog[nameAt + 7].toInt() and 255
            val directory = (directoryByte and 0x7f).toChar()
            val name = ascii(catalog, nameAt, 7).trim()
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it in "!#$%&'()-@^_{}~" })
                throw IOException("BBC DFS catalog contains an invalid filename")
            val packed = catalog[infoAt + 6].toInt() and 255
            val size = (catalog[infoAt + 4].toLong() and 255) or
                ((catalog[infoAt + 5].toLong() and 255) shl 8) or
                (((packed ushr 4 and 3).toLong()) shl 16)
            val startSector = (catalog[infoAt + 7].toLong() and 255) or
                (((packed and 3).toLong()) shl 8)
            val dataSectors = (size + SECTOR_SIZE - 1) / SECTOR_SIZE
            if (startSector > layout.sectorCount || dataSectors > layout.sectorCount - startSector)
                throw IOException("BBC DFS file data lies outside the image")
            val virtualDirectory = if (directory == '$') "" else directory.toString()
            if (virtualDirectory.isNotEmpty()) directories += virtualDirectory
            val relativePath = if (virtualDirectory.isEmpty()) name else "$virtualDirectory/$name"
            val path = if (sidePrefix.isEmpty()) relativePath else "$sidePrefix/$relativePath"
            val locked = directoryByte and 0x80 != 0
            entries += DiscImageReader.DiscEntry(path, false, size, null,
                extractor = { source, output -> copyFile(source, side, startSector, size, output) },
                recoveryNote = "BBC DFS file; load address ${hex18(catalog, infoAt, 0)}; execution address ${hex18(catalog, infoAt, 2)}${if (locked) "; locked" else ""}")
            }
            for (directory in directories) {
                val path = if (sidePrefix.isEmpty()) directory else "$sidePrefix/$directory"
                entries += DiscImageReader.DiscEntry(path, true, null, null)
            }
        }
        return entries.sortedWith(compareBy<DiscImageReader.DiscEntry>({ it.path.substringBefore('/') }, { it.isDirectory.not() }, { it.path }))
    }

    private fun copyFile(source: BlockDevice, side: Int, startSector: Long, size: Long, output: OutputStream) {
        var remaining = size
        var sector = startSector
        while (remaining > 0) {
            val length = minOf(remaining, SECTOR_SIZE.toLong()).toInt()
            val bytes = source.readBytes(layout.offset(side, sector), length)
            if (bytes.size != length) throw IOException("Truncated BBC DFS file")
            output.write(bytes)
            remaining -= length
            sector++
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.ISO_8859_1).map { if (it.code in 0x20..0x7e) it else ' ' }.joinToString("")

    private fun hex18(bytes: ByteArray, offset: Int, field: Int): String {
        val packed = bytes[offset + 6].toInt() and 255
        val highBits = if (field == 0) (packed ushr 2) and 3 else (packed ushr 6) and 3
        val value = (bytes[offset + field].toInt() and 255) or ((bytes[offset + field + 1].toInt() and 255) shl 8) or
            (highBits shl 16)
        return "0x%05X".format(value)
    }

    private data class Layout(val sectorCount: Long, val sides: Int, val tracks: Int) {
        fun offset(side: Int, sector: Long): Long {
            if (side !in 0 until sides || sector !in 0 until sectorCount) throw IOException("BBC DFS sector is outside the image")
            val track = sector / SECTORS_PER_TRACK
            val sectorInTrack = sector % SECTORS_PER_TRACK
            val linearSector = if (sides == 1) sector else (track * sides + side) * SECTORS_PER_TRACK + sectorInTrack
            return linearSector * SECTOR_SIZE
        }

        fun readCatalog(device: BlockDevice, side: Int): ByteArray {
            val bytes = device.readBytes(offset(side, 0), CATALOG_BYTES)
            if (bytes.size != CATALOG_BYTES) throw IOException("BBC DFS catalog is truncated")
            return bytes
        }

        companion object {
            fun read(device: BlockDevice, doubleSided: Boolean): Layout? {
                val layout = if (doubleSided) when (device.size) {
                    40L * 2 * SECTORS_PER_TRACK * SECTOR_SIZE -> Layout(400, 2, 40)
                    80L * 2 * SECTORS_PER_TRACK * SECTOR_SIZE -> Layout(800, 2, 80)
                    else -> return null
                } else when (device.size) {
                    40L * SECTORS_PER_TRACK * SECTOR_SIZE -> Layout(400, 1, 40)
                    80L * SECTORS_PER_TRACK * SECTOR_SIZE -> Layout(800, 1, 80)
                    else -> return null
                }
                return if ((0 until layout.sides).all { side -> layout.validateCatalog(layout.readCatalog(device, side)) }) layout else null
            }

            private fun Layout.validateCatalog(catalog: ByteArray): Boolean {
                val countByte = catalog[0x105].toInt() and 255
                if (countByte % 8 != 0 || countByte / 8 > MAX_FILES) return false
                if ((catalog[0x106].toInt() and 0xcc) != 0) return false
                val sectors = (catalog[0x107].toInt() and 255) or ((catalog[0x106].toInt() and 3) shl 8)
                if (sectors !in 2..sectorCount.toInt()) return false
                val titleBytes = catalog.copyOfRange(0, 8) + catalog.copyOfRange(0x100, 0x104)
                if (titleBytes.any { (it.toInt() and 255) !in 0x00..0x7e }) return false
                // Validate each directory extent before accepting the image. DFS has no
                // fixed magic value, so a coherent catalog and matching geometry are
                // the content confirmation instead of the filename extension.
                val count = countByte / 8
                for (i in 0 until count) {
                    val p = 0x108 + i * 8
                    val packed = catalog[p + 6].toInt() and 255
                    val size = (catalog[p + 4].toLong() and 255) or
                        ((catalog[p + 5].toLong() and 255) shl 8) or (((packed ushr 4 and 3).toLong()) shl 16)
                    val start = (catalog[p + 7].toLong() and 255) or (((packed and 3).toLong()) shl 8)
                    val nameAt = 8 + i * 8
                    val rawName = catalog.copyOfRange(nameAt, nameAt + 7)
                    val directory = catalog[nameAt + 7].toInt() and 0x7f
                    if (rawName.all { it == 0.toByte() || it == 0x20.toByte() } ||
                        rawName.any { (it.toInt() and 255) !in 0x20..0x7e } || directory !in 0x20..0x7e) return false
                    if (start > sectors || size > 0 && (start < 2 || (size + SECTOR_SIZE - 1) / SECTOR_SIZE > sectors - start)) return false
                }
                // Overlap is legal for damaged/deleted catalog entries; do not reject
                // the disk solely on that basis. Only the bounds and names are proof.
                return true
            }
        }
    }

    companion object {
        private const val SECTOR_SIZE = 256
        private const val SECTORS_PER_TRACK = 10
        private const val CATALOG_BYTES = 512
        private const val MAX_FILES = 31

        fun isSupported(device: BlockDevice, doubleSided: Boolean = false): Boolean = Layout.read(device, doubleSided) != null
    }
}
