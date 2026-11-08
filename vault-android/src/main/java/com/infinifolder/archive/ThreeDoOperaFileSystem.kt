package com.infinifolder.archive

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Read-only browser for cooked 2048-byte-block 3DO Opera volume images. */
internal class ThreeDoOperaFileSystem(private val device: BlockDevice) {
    private val volume = Volume.read(device) ?: throw IOException("Invalid 3DO Opera volume")

    fun entries(): List<DiscImageReader.DiscEntry> {
        val result = mutableListOf<DiscImageReader.DiscEntry>()
        val activeDirectories = mutableSetOf<Long>()
        val rootCopies = volume.copies(0x64, volume.copiesLast)
        walkDirectory(rootCopies.first(), volume.rootBlocks, "", 0, activeDirectories, result)
        return result
    }

    private fun readBlock(number: Long): ByteArray {
        if (number < 0 || number >= volume.blockCount) throw IOException("3DO Opera block is outside volume")
        val bytes = device.readBytes(number * volume.blockSize, volume.blockSize.toInt())
        if (bytes.size != volume.blockSize.toInt()) throw IOException("Truncated 3DO Opera block")
        return bytes
    }

    private fun walkDirectory(firstBlock: Long, blockCount: Long, prefix: String, depth: Int,
        activeDirectories: MutableSet<Long>, result: MutableList<DiscImageReader.DiscEntry>) {
        if (depth > MAX_DEPTH) throw IOException("3DO Opera directory nesting limit exceeded")
        if (!activeDirectories.add(firstBlock)) throw IOException("3DO Opera directory loop detected")
        try {
            if (blockCount !in 1..MAX_DIRECTORY_BLOCKS || firstBlock <= 0 ||
                firstBlock + blockCount > volume.blockCount) throw IOException("3DO Opera directory range is invalid")
            for (blockIndex in 0 until blockCount) {
                val blockNumber = firstBlock + blockIndex
                val block = readBlock(blockNumber)
                val end = u32be(block, 12).toInt()
                var offset = u32be(block, 16).toInt()
                var lastInDirectory = false
                if (end !in DIRECTORY_HEADER_BYTES..block.size || offset !in DIRECTORY_HEADER_BYTES..end)
                    throw IOException("3DO Opera directory block has invalid entry bounds")
                while (offset + MIN_ENTRY_BYTES <= end) {
                    val flags = u32be(block, offset)
                    if (flags == 0xffff_ffffL) break
                    val copiesLast = u32be(block, offset + 64)
                    if (copiesLast !in 0..MAX_COPIES_LAST_INDEX) throw IOException("3DO Opera entry copy count is invalid")
                    val copyCount = copiesLast.toInt() + 1
                    val entryBytes = MIN_ENTRY_BYTES + copyCount * 4
                    if (offset + entryBytes > end) throw IOException("3DO Opera directory entry is truncated")
                    val type = (flags and 0xff).toInt()
                    val id = u32be(block, offset + 4)
                    val fileBlockSize = u32be(block, offset + 12)
                    val size = u32be(block, offset + 16)
                    val allocatedBlocks = u32be(block, offset + 20)
                    val name = safeName(readText(block, offset + 32, 32))
                    val copies = (0 until copyCount).map { u32be(block, offset + 68 + it * 4) }
                    if (name.isNotEmpty() && type in setOf(TYPE_FILE, TYPE_DIRECTORY)) {
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (result.size >= MAX_ENTRIES) throw IOException("3DO Opera image exceeds the entry limit")
                        when (type) {
                            TYPE_DIRECTORY -> {
                                if (copies.any { it <= 0 || it >= volume.blockCount })
                                    throw IOException("3DO Opera directory copy is outside the volume")
                                val directory = DiscImageReader.DiscEntry(path, true, size, null,
                                    recoveryNote = "Opera directory object $id")
                                result += directory
                                walkDirectory(copies.first(), allocatedBlocks.coerceAtLeast(1), path, depth + 1,
                                    activeDirectories, result)
                            }
                            TYPE_FILE -> {
                                val start = copies.first()
                                val blockSize = fileBlockSize.takeIf { it in 1..MAX_BLOCK_SIZE } ?: volume.blockSize
                                val requiredBlocks = if (size == 0L) 0 else (size + blockSize - 1) / blockSize
                                val rangeValid = size == 0L || (blockSize == volume.blockSize && start > 0 && requiredBlocks <= allocatedBlocks &&
                                    start < volume.blockCount && requiredBlocks <= volume.blockCount - start &&
                                    size <= MAX_FILE_BYTES)
                                val extract = if (rangeValid) ({ source: BlockDevice, out: OutputStream ->
                                    copyFile(source, start, size, blockSize, out)
                                }) else null
                                result += DiscImageReader.DiscEntry(path, false, size, null, extractor = extract,
                                    readError = if (rangeValid) null else "3DO Opera file data range is invalid",
                                    recoveryNote = "Opera file object $id; ${allocatedBlocks} allocated block(s)")
                            }
                        }
                    }
                    offset += entryBytes
                    if (flags and LAST_IN_DIRECTORY != 0L) {
                        lastInDirectory = true
                        break
                    }
                    if (flags and LAST_IN_BLOCK != 0L) break
                }
                if (lastInDirectory) break
            }
        } finally {
            activeDirectories.remove(firstBlock)
        }
    }

    private fun copyFile(source: BlockDevice, startBlock: Long, length: Long, blockSize: Long, output: OutputStream) {
        var remaining = length
        var block = startBlock
        while (remaining > 0) {
            val count = minOf(remaining, blockSize).toInt()
            val bytes = source.readBytes(block * volume.blockSize, count)
            if (bytes.size != count) throw IOException("3DO Opera file data is truncated")
            output.write(bytes)
            remaining -= count
            block++
        }
    }

    private fun safeName(value: String): String {
        val clean = value.trim().replace('/', '_').replace('\\', '_').filterNot(Char::isISOControl)
        return if (clean == "." || clean == "..") "" else clean
    }

    private fun readText(bytes: ByteArray, offset: Int, size: Int): String =
        String(bytes, offset, size, StandardCharsets.ISO_8859_1).substringBefore('\u0000')

    private data class Volume(val blockSize: Long, val blockCount: Long, val rootBlocks: Long,
        val copiesLast: Int, private val header: ByteArray) {
        fun copies(offset: Int, last: Int): List<Long> = (0..last).map { u32be(header, offset + it * 4) }

        companion object {
            fun read(device: BlockDevice): Volume? {
                val header = device.readBytes(0, BLOCK_SIZE.toInt())
                if (header.size != BLOCK_SIZE.toInt() || header[0].toInt() != 1 ||
                    !header.copyOfRange(1, 6).contentEquals("ZZZZZ".toByteArray(StandardCharsets.US_ASCII))) return null
                val blockSize = u32be(header, 0x4c)
                val blockCount = u32be(header, 0x50)
                val rootBlocks = u32be(header, 0x58)
                val copiesLast = u32be(header, 0x60)
                if (blockSize != BLOCK_SIZE || blockCount < 2 || blockCount > device.size / blockSize ||
                    rootBlocks !in 1..MAX_DIRECTORY_BLOCKS || copiesLast !in 0..MAX_COPIES_LAST_INDEX) return null
                val copies = (0..copiesLast.toInt()).map { u32be(header, 0x64 + it * 4) }
                if (copies.any { it <= 0 || it >= blockCount || it + rootBlocks > blockCount }) return null
                return Volume(blockSize, blockCount, rootBlocks, copiesLast.toInt(), header)
            }
        }
    }

    companion object {
        private const val BLOCK_SIZE = 2048L
        private const val DIRECTORY_HEADER_BYTES = 20
        private const val MIN_ENTRY_BYTES = 72
        private const val TYPE_FILE = 2
        private const val TYPE_DIRECTORY = 7
        private const val LAST_IN_BLOCK = 1L shl 30
        private const val LAST_IN_DIRECTORY = 1L shl 31
        private const val MAX_COPIES_LAST_INDEX = 15L
        private const val MAX_DIRECTORY_BLOCKS = 4096L
        private const val MAX_BLOCK_SIZE = 1024L * 1024
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_DEPTH = 64
        private const val MAX_ENTRIES = 100_000

        private fun u32be(bytes: ByteArray, offset: Int): Long =
            (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }

        fun isSupported(device: BlockDevice): Boolean = Volume.read(device) != null
    }
}
