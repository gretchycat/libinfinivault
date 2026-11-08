package com.infinifolder.archive

import java.io.IOException
import java.io.OutputStream

/** Read-only Commodore 1541/1571/1581 D64/D71/D81 directory and file reader. */
internal class CommodoreDosFileSystem(private val device: BlockDevice) {
    private data class Sector(val track: Int, val sector: Int)
    private data class FileChain(val sectors: List<Sector>, val length: Long)
    private val geometry = Geometry.forSize(device.size) ?: throw IOException("Unsupported Commodore disk-image geometry")

    fun entries(): List<DiscImageReader.DiscEntry> {
        val directoryStart = if (geometry.d81) {
            val header = readSector(geometry, Sector(40, 0))
            if ((header[2].toInt() and 255) != 'D'.code ||
                (header[25].toInt() and 255) != '3'.code || (header[26].toInt() and 255) != 'D'.code) {
                throw IOException("Commodore 1581 header signature is invalid")
            }
            Sector(40, 3) // The 1581 directory always begins here; the header pointer is not authoritative.
        } else {
            val bam = readSector(geometry, Sector(18, 0))
            if (bam[0].toInt() and 255 != 18 || bam[1].toInt() and 255 !in 0 until geometry.sectorsOnTrack(18) ||
                bam[3].toInt() and 255 != 'A'.code) throw IOException("Commodore BAM signature or directory link is invalid")
            Sector(bam[0].toInt() and 255, bam[1].toInt() and 255)
        }

        val result = mutableListOf<DiscImageReader.DiscEntry>()
        val visited = mutableSetOf<Sector>()
        var sector = directoryStart
        while (true) {
            if (!visited.add(sector)) throw IOException("Commodore directory contains a sector loop")
            if (visited.size > MAX_DIRECTORY_SECTORS) throw IOException("Commodore directory exceeds the sector limit")
            val block = readSector(geometry, sector)
            for (slot in 0 until 8) {
                val p = 2 + slot * 32
                val type = block[p].toInt() and 7
                if (type !in 1..4) continue
                val start = Sector(block[p + 1].toInt() and 255, block[p + 2].toInt() and 255)
                val name = petscii(block, p + 3, 16).ifBlank { "unnamed-${result.size + 1}" }
                    .replace('/', '_').replace('\\', '_')
                // Each Commodore record occupies a 32-byte slot, but its defined
                // fields are 30 bytes; the two padding bytes separate adjacent slots.
                val blocks = ((block[p + 28].toInt() and 255) or ((block[p + 29].toInt() and 255) shl 8)).toLong()
                val chain = try { fileChain(geometry, start) } catch (e: IOException) { null }
                val ext = when (type) { 1 -> "SEQ"; 2 -> "PRG"; 3 -> "USR"; else -> "REL" }
                val finalName = if (name.substringAfterLast('.', "").equals(ext, true)) name else "$name.$ext"
                val extractor = chain?.let { file ->
                    { source: BlockDevice, out: OutputStream -> copyFile(source, geometry, file, out) }
                }
                result += DiscImageReader.DiscEntry(
                    path = finalName,
                    isDirectory = false,
                    sizeBytes = chain?.length,
                    modifiedAtMillis = null,
                    extractor = extractor,
                    readError = if (chain == null) "File sector chain is invalid or incomplete" else null,
                    recoveryNote = "${blocks} allocated block(s); Commodore DOS file type $ext"
                )
                if (result.size > MAX_FILES) throw IOException("Commodore directory exceeds the file limit")
            }
            val nextTrack = block[0].toInt() and 255
            if (nextTrack == 0) break
            val nextSector = block[1].toInt() and 255
            validateSector(geometry, Sector(nextTrack, nextSector))
            sector = Sector(nextTrack, nextSector)
        }
        return result
    }

    private fun fileChain(geometry: Geometry, first: Sector): FileChain {
        validateSector(geometry, first)
        val sectors = mutableListOf<Sector>()
        val visited = mutableSetOf<Sector>()
        var current = first
        var finalUsed: Int? = null
        while (true) {
            if (!visited.add(current)) throw IOException("Commodore file contains a sector loop")
            if (visited.size > geometry.dataSectorCount) throw IOException("Commodore file chain exceeds disk geometry")
            sectors += current
            val block = readSector(geometry, current)
            val nextTrack = block[0].toInt() and 255
            if (nextTrack == 0) {
                val countByte = block[1].toInt() and 255
                finalUsed = (countByte - 1).coerceIn(0, 254)
                break
            }
            current = Sector(nextTrack, block[1].toInt() and 255)
            validateSector(geometry, current)
        }
        val length = (sectors.size - 1).toLong() * DATA_BYTES_PER_SECTOR + finalUsed!!.toLong()
        if (length > MAX_FILE_BYTES) throw IOException("Commodore file exceeds the extraction limit")
        return FileChain(sectors, length)
    }

    private fun copyFile(source: BlockDevice, geometry: Geometry, chain: FileChain, output: OutputStream) {
        var remaining = chain.length
        for (sector in chain.sectors) {
            if (remaining <= 0) break
            val block = readSector(source, geometry, sector)
            val count = minOf(DATA_BYTES_PER_SECTOR.toLong(), remaining).toInt()
            output.write(block, 2, count)
            remaining -= count
        }
        if (remaining != 0L) throw IOException("Commodore file ended before its directory-declared sector chain")
    }

    private fun readSector(geometry: Geometry, sector: Sector): ByteArray {
        return readSector(device, geometry, sector)
    }

    private fun readSector(source: BlockDevice, geometry: Geometry, sector: Sector): ByteArray {
        validateSector(geometry, sector)
        val offset = geometry.sectorOffset(sector.track, sector.sector)
        val bytes = source.readBytes(offset, SECTOR_SIZE)
        if (bytes.size != SECTOR_SIZE) throw IOException("Truncated Commodore disk sector")
        return bytes
    }

    private fun validateSector(geometry: Geometry, sector: Sector) {
        if (sector.track !in 1..geometry.tracks || sector.sector !in 0 until geometry.sectorsOnTrack(sector.track))
            throw IOException("Commodore sector address is outside disk geometry")
    }

    private fun petscii(bytes: ByteArray, offset: Int, length: Int): String = buildString {
        for (i in offset until offset + length) {
            val value = bytes[i].toInt() and 255
            val char = when {
                value == 0xa0 || value == 0 -> ' '
                value in 0xc1..0xda -> (value - 0x80).toChar()
                value in 0x20..0x7e -> value.toChar()
                else -> ' '
            }
            append(char)
        }
    }.trim()

    private data class Geometry(val tracks: Int, val dataSectorCount: Int, val hasErrorTable: Boolean, val d81: Boolean = false) {
        fun sectorsOnTrack(track: Int): Int {
            if (d81) return 40
            val normalized = if (tracks == 70 && track > 35) track - 35 else track
            return when (normalized) { in 1..17 -> 21; in 18..24 -> 19; in 25..30 -> 18; in 31..40 -> 17; else -> 0 }
        }
        fun sectorOffset(track: Int, sector: Int): Long {
            if (d81) return ((track - 1L) * 40L + sector) * SECTOR_SIZE
            var index = 0
            for (current in 1 until track) index += sectorsOnTrack(current)
            index += sector
            return index.toLong() * SECTOR_SIZE
        }
        companion object {
            fun forSize(size: Long): Geometry? = KNOWN_GEOMETRIES.firstOrNull { size == it.dataSectorCount.toLong() * SECTOR_SIZE + if (it.hasErrorTable) it.dataSectorCount else 0 }
        }
    }

    companion object {
        private const val SECTOR_SIZE = 256
        private const val DATA_BYTES_PER_SECTOR = 254
        private const val MAX_DIRECTORY_SECTORS = 128
        private const val MAX_FILES = 4096
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private val KNOWN_GEOMETRIES = listOf(
            Geometry(35, 683, false), Geometry(35, 683, true),
            Geometry(40, 768, false), Geometry(40, 768, true),
            Geometry(70, 1366, false), Geometry(70, 1366, true),
            Geometry(80, 3200, false, d81 = true), Geometry(80, 3200, true, d81 = true)
        )

        fun isSupported(device: BlockDevice): Boolean {
            val geometry = Geometry.forSize(device.size) ?: return false
            return try {
                if (geometry.d81) {
                    val header = device.readBytes(geometry.sectorOffset(40, 0), SECTOR_SIZE)
                    header.size == SECTOR_SIZE && (header[2].toInt() and 255) == 'D'.code &&
                        (header[25].toInt() and 255) == '3'.code && (header[26].toInt() and 255) == 'D'.code
                } else {
                    val bam = device.readBytes(geometry.sectorOffset(18, 0), SECTOR_SIZE)
                    bam.size == SECTOR_SIZE && bam[0].toInt() and 255 == 18 && bam[1].toInt() and 255 in 0 until geometry.sectorsOnTrack(18) &&
                        bam[3].toInt() and 255 == 'A'.code
                }
            } catch (_: Exception) { false }
        }
    }
}
