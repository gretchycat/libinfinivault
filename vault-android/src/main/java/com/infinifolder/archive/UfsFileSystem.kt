package com.infinifolder.archive

import com.infinifolder.storage.FileSystemInfo
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Read-only BSD UFS1/UFS2 decoder. All addresses are relative to the supplied volume. */
internal class UfsFileSystem(private val device: BlockDevice, private val base: Long = 0) {
    private val header = findSuperblock(device, base) ?: throw IOException("UFS superblock not found")
    private val order = header.order
    private val version = header.version
    private val sb = header.bytes
    private fun uint(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(order).getInt(offset).toLong() and 0xffffffffL
    private fun ushort(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(order).getShort(offset).toInt() and 0xffff
    private fun long(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(order).getLong(offset)
    private val blockSize = uint(sb, 48).toInt()
    private val fragmentSize = uint(sb, 52).toInt()
    private val fragmentsPerBlock = uint(sb, 56)
    private val inodeSize = if (version == 2) 256 else 128
    private val pointerSize = if (version == 2) 8 else 4
    private val inodeBlock = uint(sb, 16)
    private val groupOffset = uint(sb, 24)
    private val groupMask = uint(sb, 28)
    private val groups = uint(sb, 44)
    private val inodesPerGroup = uint(sb, 184)
    private val fragmentsPerGroup = uint(sb, 188)
    private val volumeFragments = if (version == 2) long(sb, 1080) else uint(sb, 36)
    private val maxSymlinkLength = ByteBuffer.wrap(sb).order(order).getInt(1320)
    private val pointersPerBlock: Long get() = blockSize.toLong() / pointerSize
    private val maxInodes: Long

    init {
        if (blockSize !in 4096..65536 || blockSize and (blockSize - 1) != 0 ||
            fragmentSize !in 512..blockSize || fragmentSize and (fragmentSize - 1) != 0 ||
            fragmentsPerBlock != (blockSize / fragmentSize).toLong() || fragmentsPerBlock !in 1..8 ||
            uint(sb, 120) != (blockSize / inodeSize).toLong() ||
            uint(sb, 116) != pointersPerBlock || groups <= 0 || inodesPerGroup <= 0 ||
            inodesPerGroup % (blockSize / inodeSize) != 0L || fragmentsPerGroup <= 0 || inodeBlock <= 0 ||
            volumeFragments <= 0 || volumeFragments > (device.size - base) / fragmentSize ||
            maxSymlinkLength > 15 * pointerSize)
            throw IOException("Invalid or unsupported UFS filesystem geometry")
        maxInodes = Math.multiplyExact(groups, inodesPerGroup)
        if (groups != (volumeFragments + fragmentsPerGroup - 1) / fragmentsPerGroup)
            throw IOException("UFS cylinder-group count does not match volume size")
    }

    fun info() = FileSystemInfo("UFS$version", null, "UFS superblock at ${base + header.offset}",
        caseSensitive = true, readOnly = true, allocationUnitBytes = fragmentSize.toLong())

    private fun read(offset: Long, size: Int): ByteArray {
        val volumeBytes = volumeFragments * fragmentSize
        if (offset < 0 || size < 0 || offset > volumeBytes || size.toLong() > volumeBytes - offset)
            throw IOException("UFS block points outside the volume")
        val bytes = device.readBytes(Math.addExact(base, offset), size)
        if (bytes.size != size) throw IOException("Truncated UFS image or incomplete storage read")
        return bytes
    }

    private fun fragmentOffset(fragment: Long): Long {
        if (fragment <= 0 || fragment >= volumeFragments) throw IOException("Invalid UFS fragment address")
        return Math.multiplyExact(fragment, fragmentSize.toLong())
    }

    private data class Inode(val number: Long, val bytes: ByteArray, val type: Int, val size: Long, val modified: Long?)
    private fun inode(number: Long): Inode {
        if (number < 2 || number >= maxInodes) throw IOException("UFS directory references an invalid inode")
        val group = number / inodesPerGroup
        val groupStart = Math.addExact(Math.multiplyExact(group, fragmentsPerGroup),
            if (version == 1) Math.multiplyExact(groupOffset, group and groupMask.inv().and(0xffffffffL)) else 0)
        val block = Math.addExact(Math.addExact(groupStart, inodeBlock),
            Math.multiplyExact((number % inodesPerGroup) / (blockSize / inodeSize), fragmentsPerBlock))
        val offset = Math.addExact(fragmentOffset(block), (number % (blockSize / inodeSize)) * inodeSize)
        val bytes = read(offset, inodeSize)
        val type = ushort(bytes, 0) and 0xf000
        val size = long(bytes, if (version == 2) 16 else 8)
        if (ushort(bytes, 2) == 0 || size < 0) throw IOException("UFS directory references a deleted or invalid inode")
        val pointerCount = 12 + pointersPerBlock + pointersPerBlock * pointersPerBlock + pointersPerBlock * pointersPerBlock * pointersPerBlock
        if (size / blockSize > pointerCount || (size / blockSize == pointerCount && size % blockSize != 0L))
            throw IOException("UFS file size exceeds its block-address capacity")
        val seconds = if (version == 2) long(bytes, 40) else uint(bytes, 24)
        val nanos = uint(bytes, if (version == 2) 64 else 28)
        if (nanos >= 1_000_000_000L) throw IOException("Invalid UFS inode timestamp")
        val modified = runCatching { Math.addExact(Math.multiplyExact(seconds, 1000), nanos / 1_000_000) }.getOrNull()
        return Inode(number, bytes, type, size, modified)
    }

    private fun pointer(bytes: ByteArray, offset: Int): Long =
        if (version == 2) long(bytes, offset) else uint(bytes, offset)

    private fun blockAddress(inode: Inode, logical: Long, source: BlockDevice = device): Long {
        val direct = if (version == 2) 112 else 40
        if (logical < 12) return pointer(inode.bytes, direct + logical.toInt() * pointerSize)
        var remaining = logical - 12
        for (level in 1..3) {
            var capacity = 1L
            repeat(level) { capacity *= pointersPerBlock }
            if (remaining >= capacity) { remaining -= capacity; continue }
            var address = pointer(inode.bytes, direct + (12 + level - 1) * pointerSize)
            var divisor = capacity
            for (depth in level downTo 1) {
                if (address == 0L) return 0 // Sparse subtree.
                divisor /= pointersPerBlock
                val slot = remaining / divisor
                remaining %= divisor
                val offset = Math.addExact(fragmentOffset(address), slot * pointerSize)
                val bytes = if (source === device) read(offset, pointerSize) else readFrom(source, offset, pointerSize)
                address = pointer(bytes, 0)
            }
            return address
        }
        throw IOException("UFS logical block is outside the inode address range")
    }

    // Extractors receive the current provider handle rather than capturing a closed listing handle.
    private fun readFrom(source: BlockDevice, offset: Long, size: Int): ByteArray {
        val limit = volumeFragments * fragmentSize
        if (offset < 0 || offset > limit || size.toLong() > limit - offset) throw IOException("UFS data lies outside the volume")
        val bytes = source.readBytes(Math.addExact(base, offset), size)
        if (bytes.size != size) throw IOException("Truncated UFS file data")
        return bytes
    }

    private fun fileChunk(inode: Inode, logical: Long, size: Int, source: BlockDevice = device): ByteArray {
        val address = blockAddress(inode, logical, source)
        return if (address == 0L) ByteArray(size) else readFrom(source, fragmentOffset(address), size)
    }

    private fun extract(inode: Inode, source: BlockDevice, output: OutputStream) {
        var remaining = inode.size
        var logical = 0L
        while (remaining > 0) {
            val length = minOf(remaining, blockSize.toLong()).toInt()
            output.write(fileChunk(inode, logical++, length, source))
            remaining -= length
        }
    }

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) { throw IOException("UFS name/link is not valid UTF-8; lossless decoding is unavailable", e) }

    private fun symlink(inode: Inode): String {
        if (inode.size <= 0 || inode.size > 65536) throw IOException("UFS symbolic link has an invalid or unsupported length")
        val offset = if (version == 2) 112 else 40
        val bytes = if (inode.size < maxSymlinkLength)
            inode.bytes.copyOfRange(offset, offset + inode.size.toInt())
        else java.io.ByteArrayOutputStream().also { extract(inode, device, it) }.toByteArray()
        return utf8(bytes).also {
            if ('\u0000' in it || '\\' in it || it.split('/').any { component -> component != component.trim() })
                throw IOException("UFS symbolic link cannot be represented losslessly by this container view")
        }
    }

    fun entries(prefix: String): List<DiscImageReader.DiscEntry> {
        val entries = mutableListOf<DiscImageReader.DiscEntry>()
        val visitedDirectories = mutableSetOf<Long>()
        val paths = mutableSetOf<String>()
        fun walk(directory: Inode, parent: String, depth: Int) {
            if (depth > 128 || !visitedDirectories.add(directory.number)) throw IOException("UFS directory cycle or excessive nesting")
            if (directory.type != 0x4000 || directory.size % 512 != 0L) throw IOException("Invalid UFS directory inode")
            var consumed = 0L
            while (consumed < directory.size) {
                val length = minOf(directory.size - consumed, blockSize.toLong()).toInt()
                val data = fileChunk(directory, consumed / blockSize, length)
                var position = 0
                while (position < data.size) {
                    if (data.size - position < 8) throw IOException("Truncated UFS directory entry")
                    val number = uint(data, position)
                    val recordLength = ushort(data, position + 4)
                    if (recordLength < 8 || recordLength % 4 != 0 || recordLength > data.size - position ||
                        recordLength > 512 - position % 512) throw IOException("Invalid UFS directory record length")
                    if (number != 0L && !(number == 1L && (data[position + 6].toInt() and 255) == 14)) {
                        val nameLength = if (maxSymlinkLength > 0) data[position + 7].toInt() and 255 else ushort(data, position + 6)
                        if (nameLength !in 1..255 || nameLength >= recordLength - 8 || data[position + 8 + nameLength] != 0.toByte())
                            throw IOException("Invalid UFS directory filename length")
                        val name = utf8(data.copyOfRange(position + 8, position + 8 + nameLength))
                        if (name != "." && name != "..") {
                            if ('/' in name || '\\' in name || '\u0000' in name || name.isBlank() || name != name.trim())
                                throw IOException("UFS filename cannot be represented losslessly by this container view")
                            val path = if (parent.isEmpty()) name else "$parent/$name"
                            if (path.length > 65536 || entries.size >= 200000 || !paths.add(path)) throw IOException("UFS listing limit or duplicate path")
                            val child = inode(number)
                            val directoryEntry = child.type == 0x4000
                            val link = if (child.type == 0xa000) symlink(child) else null
                            val extractor: ((BlockDevice, OutputStream) -> Unit)? = if (child.type == 0x8000)
                                { source, output -> extract(child, source, output) } else null
                            if (!directoryEntry && link == null && extractor == null)
                                throw IOException("UFS special files cannot be represented safely in this view")
                            entries += DiscImageReader.DiscEntry(path, directoryEntry,
                                if (directoryEntry) null else child.size, child.modified, extractor, link != null, link,
                                permissionsMode = ushort(child.bytes, 0) and 0xfff,
                                owner = uint(child.bytes, if (version == 2) 4 else 112).toString(),
                                group = uint(child.bytes, if (version == 2) 8 else 116).toString())
                            if (directoryEntry) walk(child, path, depth + 1)
                        }
                    }
                    position += recordLength
                }
                consumed += length
            }
        }
        walk(inode(2), prefix.trim('/'), 0)
        return entries
    }

    companion object {
        private data class Superblock(val version: Int, val order: ByteOrder, val bytes: ByteArray, val offset: Long)
        private fun findSuperblock(device: BlockDevice, base: Long): Superblock? {
            if (base < 0 || base > device.size) return null
            for (offset in listOf(65536L, 8192L, 0L, 262144L)) {
                if (device.size - base < offset + 1376) continue
                val bytes = device.readBytes(base + offset, 1376)
                if (bytes.size != 1376) throw IOException("Incomplete UFS superblock read")
                for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
                    val magic = ByteBuffer.wrap(bytes).order(order).getInt(1372)
                    val version = when (magic) { 0x011954 -> 1; 0x19540119 -> 2; else -> continue }
                    return Superblock(version, order, bytes, offset)
                }
            }
            return null
        }
        fun isUfs(device: BlockDevice, base: Long = 0): Boolean = findSuperblock(device, base) != null
    }
}
