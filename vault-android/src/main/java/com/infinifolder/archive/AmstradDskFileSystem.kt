package com.infinifolder.archive

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Read-only browser for CPCEMU standard/extended, single- or double-sided Amstrad DSK images. */
internal class AmstradDskFileSystem(private val device: BlockDevice) {
    private val image = Image.read(device) ?: throw IOException("Invalid Amstrad CPC DSK image")
    private val format = image.format ?: throw IOException("Unsupported Amstrad DSK filesystem layout")

    fun entries(): List<DiscImageReader.DiscEntry> {
        val directoryBytes = format.directorySectors.flatMap { sector -> image.readSector(sector).asIterable() }.toByteArray()
        if (directoryBytes.size != DIRECTORY_BYTES) throw IOException("Amstrad CP/M directory is truncated")
        val files = linkedMapOf<Pair<Int, String>, MutableList<Extent>>()
        for (slot in 0 until DIRECTORY_ENTRIES) {
            val p = slot * ENTRY_BYTES
            val user = u8(directoryBytes, p)
            if (user == UNUSED_ENTRY) continue
            if (user !in 0..MAX_USER) throw IOException("Amstrad CP/M directory has an invalid user number")
            val stem = cpMText(directoryBytes, p + 1, 8)
            val suffix = cpMText(directoryBytes, p + 9, 3)
            if (stem.isBlank() || !stem.all(::validNameChar) || suffix.any { !validNameChar(it) })
                throw IOException("Amstrad CP/M directory contains an invalid filename")
            val name = if (suffix.isBlank()) stem else "$stem.$suffix"
            val extent = (u8(directoryBytes, p + 14) shl 5) or (u8(directoryBytes, p + 12) and 0x1f)
            val records = u8(directoryBytes, p + 15)
            if (records > RECORDS_PER_EXTENT) throw IOException("Amstrad CP/M extent has an invalid record count")
            val blocksNeeded = (records + RECORDS_PER_BLOCK - 1) / RECORDS_PER_BLOCK
            val blocks = (0 until blocksNeeded).map { u8(directoryBytes, p + 16 + it) }
            if (blocks.any { it < 2 || it >= format.blockCount }) throw IOException("Amstrad CP/M allocation block is outside file-data bounds")
            val extents = files.getOrPut(user to name) { mutableListOf() }
            if (extents.any { it.number == extent }) throw IOException("Amstrad CP/M directory repeats an extent")
            extents += Extent(extent, records, blocks)
        }

        val result = mutableListOf<DiscImageReader.DiscEntry>()
        for ((key, extents) in files) {
            val (user, name) = key
            val ordered = extents.sortedBy { it.number }
            val size = ordered.sumOf { it.records.toLong() * RECORD_SIZE }
            val path = if (user == 0) name else "USER$user/$name"
            val blocks = ordered.flatMap { extent -> extent.blocks.take((extent.records + RECORDS_PER_BLOCK - 1) / RECORDS_PER_BLOCK) }
            if (user != 0) {
                val directory = "USER$user"
                if (result.none { it.path == directory }) result += DiscImageReader.DiscEntry(directory, true, null, null,
                    recoveryNote = "Amstrad CP/M user area $user")
            }
            result += DiscImageReader.DiscEntry(path, false, size, null,
                extractor = { source, out -> copyFile(source, blocks, size, out) },
                recoveryNote = "Amstrad CP/M file; ${ordered.size} extent(s); AMSDOS 128-byte file header, if present, is retained")
            if (result.size > MAX_FILES) throw IOException("Amstrad DSK directory exceeds the file limit")
        }
        return result.sortedWith(compareBy<DiscImageReader.DiscEntry>({ it.path.substringBefore('/') }, { it.isDirectory.not() }, { it.path.lowercase() }))
    }

    private fun copyFile(source: BlockDevice, blocks: List<Int>, size: Long, out: OutputStream) {
        var remaining = size
        for (block in blocks) {
            for (sector in image.blockSectors(block)) {
                val bytes = image.readSector(source, sector)
                if (bytes.size != SECTOR_BYTES) throw IOException("Amstrad DSK data sector is truncated")
                val length = minOf(remaining, bytes.size.toLong()).toInt()
                if (length > 0) out.write(bytes, 0, length)
                remaining -= length
                if (remaining == 0L) return
            }
        }
        if (remaining != 0L) throw IOException("Amstrad CP/M file has fewer allocation blocks than its directory length")
    }

    private data class Extent(val number: Int, val records: Int, val blocks: List<Int>)
    private data class Sector(val id: Int, val nominalSize: Int, val storedSize: Int, val offset: Long)
    private data class Track(val number: Int, val side: Int, val sectors: List<Sector>)
    private data class Format(val sectorsPerTrack: Int, val reservedTracks: Int, val directorySectors: List<SectorRef>, val blockCount: Int)
    private data class SectorRef(val track: Int, val side: Int, val id: Int)

    private class Image(private val device: BlockDevice, private val tracks: List<Track>, private val sides: Int,
        val format: Format?) {
        fun readSector(ref: SectorRef): ByteArray = readSector(device, ref)
        fun readSector(source: BlockDevice, ref: SectorRef): ByteArray {
            val sector = tracks.firstOrNull { it.number == ref.track && it.side == ref.side }?.sectors?.firstOrNull { it.id == ref.id }
                ?: throw IOException("Amstrad DSK sector is missing")
            val raw = source.readBytes(sector.offset, sector.storedSize)
            if (raw.size != sector.storedSize) throw IOException("Amstrad DSK sector is truncated")
            return when {
                raw.size == sector.nominalSize -> raw
                raw.size > sector.nominalSize -> raw.copyOf(sector.nominalSize)
                else -> raw + ByteArray(sector.nominalSize - raw.size)
            }
        }

        fun blockSectors(block: Int): List<SectorRef> {
            val layout = format ?: throw IOException("Amstrad DSK filesystem is unavailable")
            if (block !in 0 until layout.blockCount) throw IOException("Amstrad CP/M block is outside the disk")
            val firstSector = layout.reservedTracks * sides * layout.sectorsPerTrack + block * SECTORS_PER_BLOCK
            val all = tracks.asSequence().filter { it.side in 0 until sides }
                .sortedWith(compareBy<Track>({ it.number }, { it.side }))
                .flatMap { track -> track.sectors.sortedBy { it.id }.asSequence().map { SectorRef(track.number, track.side, it.id) } }
                .filter { it.id >= 0 }.toList()
            return all.drop(firstSector).take(SECTORS_PER_BLOCK).also {
                if (it.size != SECTORS_PER_BLOCK) throw IOException("Amstrad CP/M block is outside the image")
            }
        }

        companion object {
            fun read(device: BlockDevice): Image? {
                return try {
                if (device.size < 512) return null
                val header = device.readBytes(0, 256)
                val standard = header.startsWith(STANDARD_MAGIC)
                val extended = header.startsWith(EXTENDED_MAGIC)
                if (!standard && !extended) return null
                val trackCount = u8(header, 0x30)
                val sides = u8(header, 0x31)
                if (trackCount !in 1..84 || sides !in 1..2 || trackCount * sides > 168) return null
                val tracks = mutableListOf<Track>()
                var offset = 256L
                val declaredTrackSize = if (standard) u16le(header, 0x32) else 0
                for (trackIndex in 0 until trackCount * sides) {
                    val length = if (standard) declaredTrackSize else u8(header, 0x34 + trackIndex) * 256
                    if (length == 0) continue
                    if (length < 256 || offset + length > device.size) return null
                    val trackHeader = device.readBytes(offset, 256)
                    if (!trackHeader.startsWith(TRACK_MAGIC)) return null
                    val trackNumber = u8(trackHeader, 0x10)
                    val side = u8(trackHeader, 0x11)
                    val count = u8(trackHeader, 0x15)
                    if (trackNumber >= trackCount || side !in 0 until sides || count !in 1..29 ||
                        tracks.any { it.number == trackNumber && it.side == side }) return null
                    val sectors = mutableListOf<Sector>()
                    var dataOffset = offset + 256
                    for (index in 0 until count) {
                        val p = 0x18 + index * 8
                        val id = u8(trackHeader, p + 2)
                        val sizeCode = u8(trackHeader, p + 3)
                        if (sizeCode > 5 || sectors.any { it.id == id }) return null
                        val nominal = 128 shl sizeCode
                        val stored = if (extended) u16le(trackHeader, p + 6).takeIf { it > 0 } ?: nominal else nominal
                        if (stored > length - 256 || dataOffset + stored > offset + length) return null
                        sectors += Sector(id, nominal, stored, dataOffset)
                        dataOffset += stored
                    }
                    tracks += Track(trackNumber, side, sectors)
                    offset += length
                }
                if (tracks.isEmpty() || offset > device.size) return null
                val sortedTracks = tracks.sortedWith(compareBy<Track>({ it.number }, { it.side }))
                val first = sortedTracks.first().sectors.map { it.id }.sorted().firstOrNull() ?: return null
                val format = when (first) {
                    0xc1, 0x41 -> {
                        val spt = sortedTracks.first().sectors.size
                        if (spt != 9 || sortedTracks.any { it.sectors.any { s -> s.nominalSize != SECTOR_BYTES } }) return null
                        val reserved = if (first == 0x41) 2 else 0
                        val logical = sortedTracks.flatMap { track -> track.sectors.sortedBy { it.id }.map { track.number to track.side to it.id } }
                        val directories = (reserved * sides * spt until reserved * sides * spt + 4).mapNotNull { idx ->
                            logical.getOrNull(idx)?.let { SectorRef(it.first.first, it.first.second, it.second) }
                        }
                        if (directories.size != 4 || directories.first().id != first) return null
                        val totalBlocks = (logical.size - reserved * sides * spt) / SECTORS_PER_BLOCK
                        if (totalBlocks < 3) return null
                        val refs = directories
                        // CP/M directory blocks 0 and 1 occupy the first four 512-byte sectors.
                        Format(spt, reserved, refs, totalBlocks)
                    }
                    else -> null
                }
                    Image(device, sortedTracks, sides, format)
                } catch (_: Exception) { null }
            }
        }
    }

    companion object {
        fun isSupported(device: BlockDevice): Boolean = try { Image.read(device)?.format != null && AmstradDskFileSystem(device).entries().size <= MAX_FILES } catch (_: Exception) { false }
        private const val SECTOR_BYTES = 512
        private const val SECTORS_PER_BLOCK = 2
        private const val RECORD_SIZE = 128L
        private const val RECORDS_PER_BLOCK = 8
        private const val RECORDS_PER_EXTENT = 128
        private const val DIRECTORY_ENTRIES = 64
        private const val ENTRY_BYTES = 32
        private const val DIRECTORY_BYTES = DIRECTORY_ENTRIES * ENTRY_BYTES
        private const val UNUSED_ENTRY = 0xe5
        private const val MAX_USER = 15
        private const val MAX_FILES = 4096
        private val STANDARD_MAGIC = "MV - CPCEMU Disk-File".toByteArray(StandardCharsets.US_ASCII)
        private val EXTENDED_MAGIC = "EXTENDED CPC DSK File".toByteArray(StandardCharsets.US_ASCII)
        private val TRACK_MAGIC = "Track-Info\r\n".toByteArray(StandardCharsets.US_ASCII)
        private fun u8(b: ByteArray, p: Int) = b[p].toInt() and 255
        private fun u16le(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8)
        private fun cpMText(b: ByteArray, p: Int, n: Int): String = String(b, p, n, StandardCharsets.ISO_8859_1)
            .map { (it.code and 0x7f).toChar() }.joinToString("").trim()
        private fun validNameChar(value: Char) = value.isLetterOrDigit() || value in "!#$%&'()-@^_{}~"
        private fun ByteArray.startsWith(magic: ByteArray) = size >= magic.size && magic.indices.all { this[it] == magic[it] }
    }
}
