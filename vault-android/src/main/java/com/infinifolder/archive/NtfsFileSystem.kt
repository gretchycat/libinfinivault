package com.infinifolder.archive

import com.infinifolder.storage.FileSystemInfo
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Read-only NTFS 3.x, using live $I30 directory indexes rather than an MFT scan. */
internal class NtfsFileSystem(private val device: BlockDevice, private val base: Long = 0) {
    private val boot = exact(device, base, 512)
    private val sectorSize = u16(boot, 11)
    private val sectorsPerCluster = boot[13].toInt() and 255
    private val clusterSize = sectorSize.toLong() * sectorsPerCluster
    private val sectors = i64(boot, 40)
    private val mftCluster = i64(boot, 48)
    private val recordSize = encodedSize(boot[64].toInt())
    private val indexSize = encodedSize(boot[68].toInt())
    private val volumeBytes: Long
    private lateinit var mft: Stream
    private var volumeVersion = "NTFS"
    private val recordCache = object : LinkedHashMap<Long, Record>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Record>?) = size > 512
    }

    private data class Attribute(val type: Long, val name: String, val bytes: ByteArray, val flags: Int, val nonresident: Boolean)
    private data class Record(val number: Long, val sequence: Int, val flags: Int, val attributes: List<Attribute>) {
        val directory: Boolean get() = flags and 2 != 0
    }
    private data class Run(val vcn: Long, val count: Long, val lcn: Long?)
    private data class Child(val reference: Long, val name: String, val modified: Long?)

    init {
        if (String(boot, 3, 8, Charsets.US_ASCII) != "NTFS    " || u16(boot, 510) != 0xaa55 ||
            sectorSize !in 512..4096 || sectorSize and (sectorSize - 1) != 0 ||
            sectorsPerCluster !in 1..128 || sectorsPerCluster and (sectorsPerCluster - 1) != 0 ||
            sectors <= 0 || sectors > (device.size - base) / sectorSize ||
            recordSize !in 512..65536 || recordSize and (recordSize - 1) != 0 ||
            indexSize !in 512..1048576 || indexSize and (indexSize - 1) != 0 ||
            mftCluster <= 0 || mftCluster >= sectors / sectorsPerCluster)
            throw IOException("Invalid or unsupported NTFS boot geometry")
        volumeBytes = Math.multiplyExact(sectors, sectorSize.toLong())
        val first = parseRecord(0, readVolume(device, Math.multiplyExact(mftCluster, clusterSize), recordSize))
        mft = stream(first, 0x80, "") ?: throw IOException("NTFS master file table data is missing")
        if (mft.resident != null || mft.runs.any { it.lcn == null } || mft.size % recordSize != 0L)
            throw IOException("Invalid NTFS master file table mapping")
        recordCache[0] = first
        val volume = stream(record(3), 0x70, "")?.smallValue()
            ?: throw IOException("NTFS volume information is missing")
        if (volume.size < 12 || volume[8].toInt() != 3 || (volume[9].toInt() and 255) !in 0..1)
            throw IOException("Only NTFS 3.0 and 3.1 filesystem images are supported")
        if (u16(volume, 10) and 1 != 0) throw IOException("NTFS volume is dirty; journal replay is unavailable. Repair or cleanly unmount a copy first")
        volumeVersion = "NTFS 3.${volume[9].toInt() and 255}"
    }

    fun info() = FileSystemInfo(volumeVersion, null, "NTFS boot sector, MFT and volume information",
        caseSensitive = false, readOnly = true, allocationUnitBytes = clusterSize)

    private fun encodedSize(value: Int): Int = when {
        value in 1..127 -> (value * clusterSize).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0
        value in -20..-1 -> 1 shl -value
        else -> 0
    }

    private fun readVolume(source: BlockDevice, offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset > volumeBytes || length.toLong() > volumeBytes - offset)
            throw IOException("NTFS address lies outside the volume")
        return exact(source, Math.addExact(base, offset), length)
    }

    private fun fixed(bytes: ByteArray, magic: String): ByteArray {
        if (bytes.size < 24 || String(bytes, 0, 4, Charsets.US_ASCII) != magic)
            throw IOException("Invalid NTFS $magic record signature")
        val offset = u16(bytes, 4)
        val count = u16(bytes, 6)
        if (count < 2 || bytes.size % (count - 1) != 0 || offset < 8 || offset + count * 2 > bytes.size)
            throw IOException("Invalid NTFS update-sequence array")
        val stride = bytes.size / (count - 1)
        if (stride != 512 && stride != sectorSize) throw IOException("Unsupported NTFS fixup sector layout")
        val sequence = u16(bytes, offset)
        if (sequence == 0) throw IOException("Invalid NTFS fixup sequence")
        val result = bytes.copyOf()
        for (sector in 1 until count) {
            val end = sector * stride - 2
            if (u16(bytes, end) != sequence) throw IOException("NTFS record fixup failed; image may be corrupt or incompletely read")
            result[end] = bytes[offset + sector * 2]
            result[end + 1] = bytes[offset + sector * 2 + 1]
        }
        return result
    }

    private fun parseRecord(number: Long, raw: ByteArray): Record {
        val bytes = fixed(raw, "FILE")
        if (bytes.size < 48 || u16(bytes, 22) and 1 == 0) throw IOException("NTFS index references a deleted record")
        if (i64(bytes, 32) != 0L) throw IOException("NTFS attribute extension records are not supported")
        val used = u32(bytes, 24)
        val allocated = u32(bytes, 28)
        var position = u16(bytes, 20)
        if (used > bytes.size || allocated > bytes.size || allocated < used || position < 48 || position % 8 != 0)
            throw IOException("Invalid NTFS file-record bounds")
        val attributes = mutableListOf<Attribute>()
        var terminated = false
        while (position.toLong() + 4 <= used) {
            val type = u32(bytes, position)
            if (type == 0xffffffffL) { terminated = true; break }
            if (position.toLong() + 24 > used) throw IOException("Truncated NTFS attribute header")
            val length = u32(bytes, position + 4)
            if (type == 0L || length < 24 || length % 8 != 0L || length > used - position)
                throw IOException("Invalid NTFS attribute bounds")
            val value = bytes.copyOfRange(position, position + length.toInt())
            val nonresident = value[8].toInt() and 255
            if (nonresident !in 0..1) throw IOException("Invalid NTFS attribute storage type")
            val nameLength = value[9].toInt() and 255
            val nameOffset = u16(value, 10)
            if (nameLength > 0 && (nameOffset < (if (nonresident == 1) 64 else 24) || nameOffset + nameLength * 2 > value.size))
                throw IOException("Invalid NTFS attribute name")
            val name = if (nameLength == 0) "" else utf16(value.copyOfRange(nameOffset, nameOffset + nameLength * 2))
            attributes += Attribute(type, name, value, u16(value, 12), nonresident == 1)
            position += length.toInt()
        }
        if (!terminated) throw IOException("NTFS file record has no attribute terminator")
        if (attributes.any { it.type == 0x20L }) throw IOException("NTFS attribute lists are not yet supported; the file may use extension records")
        return Record(number, u16(bytes, 16), u16(bytes, 22), attributes)
    }

    private fun record(number: Long): Record {
        recordCache[number]?.let { return it }
        if (number < 0 || number >= mft.size / recordSize) throw IOException("NTFS record reference lies outside the master file table")
        return parseRecord(number, mft.read(device, Math.multiplyExact(number, recordSize.toLong()), recordSize)).also { recordCache[number] = it }
    }

    private fun stream(record: Record, type: Long, name: String): Stream? {
        val matches = record.attributes.filter { it.type == type && it.name == name }
        if (matches.size > 1) throw IOException("Split NTFS attributes are not yet supported")
        return matches.singleOrNull()?.let(::Stream)
    }

    private inner class Stream(private val attribute: Attribute) {
        val resident: ByteArray?
        val runs: List<Run>
        val size: Long
        private val initialized: Long
        init {
            val bytes = attribute.bytes
            if (!attribute.nonresident) {
                val length = u32(bytes, 16)
                val offset = u16(bytes, 20)
                if (offset < 24 || length > bytes.size - offset) throw IOException("Invalid resident NTFS data bounds")
                resident = bytes.copyOfRange(offset, offset + length.toInt())
                size = length; initialized = length; runs = emptyList()
            } else {
                if (bytes.size < 64 || i64(bytes, 16) != 0L) throw IOException("NTFS attribute continuation is unsupported")
                size = i64(bytes, 48); initialized = i64(bytes, 56)
                if (size < 0 || initialized < 0 || initialized > size) throw IOException("Invalid NTFS stream length")
                val highest = i64(bytes, 24)
                var position = u16(bytes, 32)
                if (position < 64 || position >= bytes.size) throw IOException("Invalid NTFS runlist offset")
                val mapping = mutableListOf<Run>()
                var vcn = 0L
                var lcn = 0L
                var terminated = false
                while (position < bytes.size) {
                    val layout = bytes[position++].toInt() and 255
                    if (layout == 0) { terminated = true; break }
                    val countBytes = layout and 15
                    val offsetBytes = layout ushr 4
                    if (countBytes !in 1..8 || offsetBytes !in 0..8 || countBytes + offsetBytes > bytes.size - position)
                        throw IOException("Invalid NTFS runlist encoding")
                    val count = variable(bytes, position, countBytes, false); position += countBytes
                    if (count <= 0 || count > Long.MAX_VALUE / clusterSize - vcn) throw IOException("NTFS runlist length overflow")
                    val physical = if (offsetBytes == 0) null else {
                        lcn = Math.addExact(lcn, variable(bytes, position, offsetBytes, true))
                        if (lcn < 0 || lcn > volumeBytes / clusterSize || count > volumeBytes / clusterSize - lcn)
                            throw IOException("NTFS data run lies outside the volume")
                        lcn
                    }
                    position += offsetBytes
                    mapping += Run(vcn, count, physical)
                    vcn += count
                    if (mapping.size > 65536) throw IOException("NTFS runlist exceeds the supported limit")
                }
                if (!terminated || highest != vcn - 1 || size > vcn * clusterSize)
                    throw IOException("Incomplete NTFS data-run mapping")
                resident = null; runs = mapping
            }
        }

        fun read(source: BlockDevice, offset: Long, length: Int): ByteArray {
            if (attribute.flags and 0x4001 != 0) throw IOException("Compressed or encrypted NTFS streams are not yet supported")
            if (offset < 0 || length < 0 || offset > size || length.toLong() > size - offset)
                throw IOException("NTFS read lies outside the file stream")
            resident?.let { return it.copyOfRange(offset.toInt(), offset.toInt() + length) }
            val output = ByteArray(length)
            var copied = 0
            while (copied < length && offset + copied < initialized) {
                val location = offset + copied
                val run = runs.firstOrNull { location / clusterSize >= it.vcn && location / clusterSize - it.vcn < it.count }
                    ?: throw IOException("Gap in NTFS data runs")
                val within = location - run.vcn * clusterSize
                val amount = minOf((length - copied).toLong(), run.count * clusterSize - within, initialized - location).toInt()
                if (run.lcn != null) readVolume(source, run.lcn * clusterSize + within, amount).copyInto(output, copied)
                copied += amount
            }
            return output // Sparse runs and the uninitialized tail are zero-filled.
        }

        fun smallValue(): ByteArray {
            if (size > 1048576) throw IOException("NTFS metadata stream exceeds the supported size")
            return read(device, 0, size.toInt())
        }

        fun extract(source: BlockDevice, output: OutputStream) {
            var offset = 0L
            while (offset < size) {
                val amount = minOf(size - offset, 128L * 1024).toInt()
                output.write(read(source, offset, amount)); offset += amount
            }
            // Even an empty stream must reject unsupported compression/encryption.
            if (size == 0L) read(source, 0, 0)
        }
    }

    private fun children(directory: Record): List<Child> {
        val root = stream(directory, 0x90, "\$I30")?.smallValue() ?: throw IOException("NTFS directory index is missing")
        if (root.size < 32 || u32(root, 0) != 0x30L || u32(root, 4) != 1L || u32(root, 8) != indexSize.toLong())
            throw IOException("Unsupported NTFS directory index layout")
        val allocation = stream(directory, 0xa0, "\$I30")
        val bitmap = stream(directory, 0xb0, "\$I30")
        val visited = mutableSetOf<Long>()
        val result = mutableListOf<Child>()
        fun node(bytes: ByteArray, header: Int, depth: Int) {
            if (depth > 64 || header + 16 > bytes.size) throw IOException("NTFS index depth or header is invalid")
            val first = u32(bytes, header)
            val used = u32(bytes, header + 4)
            val allocated = u32(bytes, header + 8)
            if (first < 16 || first % 8 != 0L || used < first || used > bytes.size - header || allocated < used || allocated > bytes.size - header)
                throw IOException("Invalid NTFS index-node bounds")
            var position = header + first.toInt()
            val end = header + used.toInt()
            var last = false
            while (position < end) {
                if (end - position < 16) throw IOException("Truncated NTFS index entry")
                val length = u16(bytes, position + 8)
                val keyLength = u16(bytes, position + 10)
                val flags = u16(bytes, position + 12)
                val childNode = flags and 1 != 0
                if (flags and 3.inv() != 0 || length < (if (childNode) 24 else 16) || length % 8 != 0 || length > end - position || keyLength > length - 16 - (if (childNode) 8 else 0))
                    throw IOException("Invalid NTFS index-entry bounds")
                if (childNode) {
                    val vcn = i64(bytes, position + length - 8)
                    if (vcn < 0 || visited.size >= 200000 || !visited.add(vcn)) throw IOException("NTFS directory index cycle or node limit")
                    val unit = if (clusterSize <= indexSize) clusterSize else sectorSize.toLong()
                    val offset = Math.multiplyExact(vcn, unit)
                    if (offset % indexSize != 0L || allocation == null || bitmap == null)
                        throw IOException("NTFS directory index allocation is unavailable")
                    val bit = offset / indexSize
                    val active = bitmap.read(device, bit / 8, 1)[0].toInt() and (1 shl (bit % 8).toInt()) != 0
                    if (!active) throw IOException("NTFS index references an unallocated node")
                    val child = fixed(allocation.read(device, offset, indexSize), "INDX")
                    if (i64(child, 16) != vcn) throw IOException("NTFS index node VCN mismatch")
                    node(child, 24, depth + 1)
                }
                if (flags and 2 != 0) { last = true; break }
                if (keyLength < 66) throw IOException("NTFS directory key is incomplete")
                val key = position + 16
                val nameLength = bytes[key + 64].toInt() and 255
                val namespace = bytes[key + 65].toInt() and 255
                if (nameLength == 0 || keyLength != 66 + nameLength * 2 || namespace !in 0..3 ||
                    referenceNumber(i64(bytes, key)) != directory.number || referenceSequence(i64(bytes, key)) != directory.sequence)
                    throw IOException("Invalid NTFS directory filename reference")
                if (namespace != 2) {
                    val name = utf16(bytes.copyOfRange(key + 66, key + 66 + nameLength * 2))
                    if (name != ".") {
                        if (name == ".." || name.isBlank() || name != name.trim() || name.any { it == '/' || it == '\\' || it == '\u0000' })
                            throw IOException("NTFS filename cannot be represented losslessly in this view")
                        result += Child(i64(bytes, position), name, timestamp(i64(bytes, key + 16)))
                        if (result.size > 200000) throw IOException("NTFS directory exceeds the supported entry limit")
                    }
                }
                position += length
            }
            if (!last) throw IOException("NTFS index node has no terminal entry")
        }
        node(root, 16, 0)
        return result
    }

    private fun relativeLink(value: ByteArray): String? {
        if (value.size < 8 || u16(value, 4) > value.size - 8) throw IOException("Invalid NTFS reparse data")
        if (u32(value, 0) != 0xa000000cL) return null
        if (value.size < 20 || u32(value, 16) != 1L) return null // Absolute Windows paths need a volume-aware resolver.
        val offset = u16(value, 8)
        val length = u16(value, 10)
        if (offset % 2 != 0 || length == 0 || length % 2 != 0 || offset + length > u16(value, 4) - 12)
            throw IOException("Invalid NTFS symbolic-link target")
        val target = utf16(value.copyOfRange(20 + offset, 20 + offset + length)).replace('\\', '/')
        if (target.startsWith('/') || ':' in target || '\u0000' in target || target.split('/').any { it != it.trim() }) return null
        return target
    }

    fun entries(prefix: String): List<DiscImageReader.DiscEntry> {
        val result = mutableListOf<DiscImageReader.DiscEntry>()
        val visited = mutableSetOf<Long>()
        val paths = mutableSetOf<String>()
        fun walk(directory: Record, parent: String, depth: Int) {
            if (depth > 128 || !visited.add(directory.number)) throw IOException("NTFS directory cycle or excessive depth")
            for (child in children(directory)) {
                val number = referenceNumber(child.reference)
                if (number < 16) continue // NTFS metadata is not ordinary user content.
                val record = record(number)
                if (record.sequence != referenceSequence(child.reference)) throw IOException("Stale NTFS directory reference")
                val path = if (parent.isEmpty()) child.name else "$parent/${child.name}"
                if (path.length > 65536 || result.size >= 200000 || !paths.add(path)) throw IOException("NTFS listing limit or duplicate path")
                val reparse = stream(record, 0xc0, "")?.smallValue()
                val link = reparse?.let(::relativeLink)
                val knownLink = reparse != null && u32(reparse, 0) in setOf(0xa000000cL, 0xa0000003L)
                val isDirectory = record.directory && reparse == null
                val data = if (!record.directory && reparse == null) stream(record, 0x80, "") else null
                if (!isDirectory && reparse == null && data == null) throw IOException("NTFS file has no default data stream")
                val standard = stream(record, 0x10, "")?.smallValue()
                val modified = standard?.takeIf { it.size >= 16 }?.let { timestamp(i64(it, 8)) } ?: child.modified
                val extraStreams = record.attributes.any { it.type == 0x80L && it.name.isNotEmpty() }
                val extractor: ((BlockDevice, OutputStream) -> Unit)? = if (isDirectory || knownLink) null else { source, output ->
                    if (reparse != null) throw IOException("This NTFS reparse format is not yet supported")
                    if (extraStreams) throw IOException("NTFS alternate data streams are not yet supported; extraction would omit them")
                    data!!.extract(source, output)
                }
                result += DiscImageReader.DiscEntry(path, isDirectory, if (isDirectory) null else data?.size,
                    modified, extractor, knownLink, link)
                if (isDirectory) walk(record, path, depth + 1)
            }
        }
        val root = record(5)
        if (!root.directory) throw IOException("NTFS root record is not a directory")
        walk(root, prefix.trim('/'), 0)
        return result
    }

    companion object {
        private fun u16(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt() and 0xffff
        private fun u32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xffffffffL
        private fun i64(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(offset)
        private fun referenceNumber(value: Long) = value and 0xffffffffffffL
        private fun referenceSequence(value: Long) = (value ushr 48).toInt()
        private fun timestamp(value: Long): Long? = if (value <= 0) null else value / 10000 - 11644473600000L
        private fun utf16(bytes: ByteArray): String = try {
            Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) { throw IOException("NTFS filename/link cannot be decoded losslessly", e) }
        private fun variable(bytes: ByteArray, offset: Int, length: Int, signed: Boolean): Long {
            var value = 0L
            for (index in 0 until length) value = value or ((bytes[offset + index].toLong() and 255) shl (index * 8))
            if (signed && length < 8 && bytes[offset + length - 1].toInt() < 0) value = value or (-1L shl (length * 8))
            return value
        }
        private fun exact(device: BlockDevice, offset: Long, length: Int): ByteArray {
            if (offset < 0 || length < 0 || offset > device.size || length.toLong() > device.size - offset)
                throw IOException("NTFS read lies outside its backing image")
            return device.readBytes(offset, length).also { if (it.size != length) throw IOException("Incomplete NTFS image read") }
        }
        fun isNtfs(device: BlockDevice, base: Long = 0): Boolean {
            if (base < 0 || base > device.size || device.size - base < 512) return false
            val bytes = exact(device, base, 512)
            return String(bytes, 3, 8, Charsets.US_ASCII) == "NTFS    " && u16(bytes, 510) == 0xaa55
        }
    }
}
