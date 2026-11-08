package com.infinifolder.archive

import java.io.IOException
import java.io.OutputStream

/** Read-only Atari 8-bit ATR wrapper and Atari DOS 2.x/MyDOS-compatible root directory. */
internal class AtariAtrFileSystem(private val device: BlockDevice) {
    private val layout = Layout.from(device) ?: throw IOException("Invalid Atari ATR image")

    fun entries(): List<DiscImageReader.DiscEntry> {
        val vtoc = readSector(VTOC_SECTOR)
        if (vtoc.isEmpty() || (vtoc[0].toInt() and 255) != 2) {
            return bootSectors("ATR header is valid, but no Atari DOS 2.x VTOC was found")
        }
        val files = mutableListOf<DiscImageReader.DiscEntry>()
        for (sectorNumber in DIRECTORY_START..DIRECTORY_END) {
            val directory = readSector(sectorNumber)
            // Atari DOS 2.x directories always use the first 128 bytes, even on DD disks.
            for (slot in 0 until DIRECTORY_ENTRIES_PER_SECTOR) {
                val p = slot * DIRECTORY_ENTRY_BYTES
                val flags = directory[p].toInt() and 255
                if ((flags and FLAG_IN_USE) == 0 || (flags and FLAG_DELETED) != 0) continue
                val sectorCount = u16le(directory, p + 1)
                val firstSector = u16le(directory, p + 3)
                val name = atascii(directory, p + 5, 8)
                val extension = atascii(directory, p + 13, 3)
                if (name.isBlank() || firstSector !in 1..minOf(layout.sectorCount, MAX_DOS2_SECTOR)) continue
                val path = if (extension.isBlank()) name else "$name.$extension"
                val chain = try { readChain(firstSector, sectorCount) } catch (error: IOException) { null }
                val locked = flags and FLAG_LOCKED != 0
                val opened = flags and FLAG_OPENED != 0
                val extractor = chain?.let { data ->
                    { source: BlockDevice, out: OutputStream -> copyChain(source, data, out) }
                }
                files += DiscImageReader.DiscEntry(
                    path = path,
                    isDirectory = false,
                    sizeBytes = chain?.size,
                    modifiedAtMillis = null,
                    extractor = extractor,
                    readError = if (chain == null) "Atari DOS file sector chain is invalid or incomplete" else null,
                    recoveryNote = listOfNotNull(
                        "Atari DOS file; directory declares $sectorCount sector(s)",
                        if (locked) "Write protected" else null,
                        if (opened) "File was left open for output" else null
                    ).joinToString("; ")
                )
                if (files.size > MAX_FILES) throw IOException("Atari ATR directory exceeds the file limit")
            }
        }
        return if (files.isNotEmpty()) files else bootSectors("No active Atari DOS 2.x files were found")
    }

    private data class Chain(val sectors: List<Pair<Int, Int>>, val size: Long)

    private fun readChain(firstSector: Int, declaredSectors: Int): Chain {
        if (declaredSectors !in 1..minOf(layout.sectorCount, MAX_DOS2_SECTOR)) throw IOException("Invalid Atari DOS 2.x sector count")
        val sectors = mutableListOf<Pair<Int, Int>>()
        val visited = mutableSetOf<Int>()
        var current = firstSector
        var size = 0L
        var finalBytes = 0
        while (true) {
            if (current !in 1..minOf(layout.sectorCount, MAX_DOS2_SECTOR) || !visited.add(current)) throw IOException("Atari DOS 2.x file chain loops or leaves supported sector bounds")
            if (visited.size > declaredSectors || visited.size > MAX_CHAIN_SECTORS) throw IOException("Atari DOS file chain exceeds its directory bounds")
            val bytes = readSector(current)
            val dataCapacity = layout.sectorSize(current) - 3
            val linkAt = dataCapacity
            val used = bytes[linkAt + 2].toInt() and 255
            if (used !in 1..dataCapacity) throw IOException("Atari DOS sector has an invalid payload length")
            sectors += current to used
            size += used
            val next = ((bytes[linkAt].toInt() and 3) shl 8) or (bytes[linkAt + 1].toInt() and 255)
            if (next == 0) {
                finalBytes = used
                break
            }
            current = next
        }
        if (sectors.size != declaredSectors) throw IOException("Atari DOS chain length differs from its directory entry")
        if (size > MAX_FILE_BYTES || finalBytes <= 0) throw IOException("Atari DOS file size is outside supported bounds")
        return Chain(sectors, size)
    }

    private fun copyChain(source: BlockDevice, chain: Chain, output: OutputStream) {
        for ((sector, used) in chain.sectors) {
            val bytes = readSector(source, sector)
            output.write(bytes, 0, used)
        }
    }

    private fun bootSectors(reason: String): List<DiscImageReader.DiscEntry> = listOf(
        DiscImageReader.DiscEntry("BOOT.SEC", false, 3L * 128L, null,
            extractor = { source, out ->
                for (sector in 1..3) out.write(readSector(source, sector), 0, 128)
            }, recoveryNote = reason)
    )

    private fun readSector(number: Int): ByteArray = readSector(device, number)

    private fun readSector(source: BlockDevice, number: Int): ByteArray {
        if (number !in 1..layout.sectorCount) throw IOException("Atari sector is outside image geometry")
        val bytes = source.readBytes(layout.offset(number), layout.sectorSize(number))
        if (bytes.size != layout.sectorSize(number)) throw IOException("Truncated Atari ATR sector")
        return bytes
    }

    private fun atascii(bytes: ByteArray, offset: Int, length: Int): String = buildString {
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 255
            val char = when {
                value == 0x9b -> ' '
                value and 0x80 != 0 -> (value and 0x7f).toChar()
                value in 0x20..0x7e -> value.toChar()
                else -> ' '
            }
            append(char)
        }
    }.trim()

    private fun u16le(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)

    private data class Layout(val dataOffset: Long, val sectorCount: Int, val sectorBytes: Int) {
        fun sectorSize(number: Int): Int = if (sectorBytes == 256 && number <= 3) 128 else sectorBytes
        fun offset(number: Int): Long = when {
            sectorBytes == 128 -> dataOffset + (number - 1L) * 128L
            number <= 3 -> dataOffset + (number - 1L) * 128L
            else -> dataOffset + 3L * 128L + (number - 4L) * 256L
        }

        companion object {
            fun from(device: BlockDevice): Layout? {
                if (device.size < ATR_HEADER_SIZE + 368L * 128L) return null
                val header = device.readBytes(0, ATR_HEADER_SIZE)
                if (header.size != ATR_HEADER_SIZE || (header[0].toInt() and 255) != 0x96 ||
                    (header[1].toInt() and 255) != 0x02) return null
                val sectorBytes = (header[4].toInt() and 255) or ((header[5].toInt() and 255) shl 8)
                if (sectorBytes !in setOf(128, 256)) return null
                val dataBytes = device.size - ATR_HEADER_SIZE
                val sectors = when (sectorBytes) {
                    128 -> if (dataBytes % 128L == 0L) (dataBytes / 128L).toInt() else return null
                    else -> if (dataBytes >= 3L * 128L && (dataBytes - 3L * 128L) % 256L == 0L)
                        (3L + (dataBytes - 3L * 128L) / 256L).toInt() else return null
                }
                if (sectors < DIRECTORY_END || sectors > MAX_ATR_SECTORS) return null
                return Layout(ATR_HEADER_SIZE.toLong(), sectors, sectorBytes)
            }
        }
    }

    companion object {
        private const val ATR_HEADER_SIZE = 16
        private const val VTOC_SECTOR = 360
        private const val DIRECTORY_START = 361
        private const val DIRECTORY_END = 368
        private const val DIRECTORY_ENTRY_BYTES = 16
        private const val DIRECTORY_ENTRIES_PER_SECTOR = 8
        private const val FLAG_IN_USE = 0x40
        private const val FLAG_DELETED = 0x80
        private const val FLAG_LOCKED = 0x20
        private const val FLAG_OPENED = 0x01
        private const val MAX_ATR_SECTORS = 0xffff
        private const val MAX_DOS2_SECTOR = 1023
        private const val MAX_CHAIN_SECTORS = 1024
        private const val MAX_FILES = 4096
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024

        fun isSupported(device: BlockDevice): Boolean = Layout.from(device) != null
    }
}
