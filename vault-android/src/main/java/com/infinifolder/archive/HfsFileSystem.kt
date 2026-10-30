package com.infinifolder.archive

import com.infinifolder.storage.FileSystemInfo
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Read-only HFS/HFS+/HFSX catalogs. Addresses remain relative to the supplied device. */
internal class HfsFileSystem(private val device: BlockDevice, base: Long = 0) {
    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    private fun u32(b: ByteArray, p: Int) = ((u16(b, p).toLong() shl 16) or u16(b, p + 2).toLong())
    private fun u64(b: ByteArray, p: Int): Long {
        if (b[p] < 0) fail("File size exceeds signed 64-bit addressing")
        return (u32(b, p) shl 32) or u32(b, p + 4)
    }
    private fun fail(message: String): Nothing = throw IOException("HFS: $message")
    private fun exact(source: BlockDevice, offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset > source.size || length.toLong() > source.size - offset) fail("Address outside image")
        return source.readBytes(offset, length).also { if (it.size != length) fail("Truncated storage read") }
    }
    private var volumeBase = base
    private var header = exact(device, base + 1024, 512)
    private val plus: Boolean
    private val blockSize: Long
    private val totalBlocks: Long
    private val allocationBase: Long
    private val type: String
    private val overflow = HashMap<ExtentKey, List<Extent>>()
    private data class Extent(val start: Long, val count: Long)
    private data class ExtentKey(val id: Long, val resource: Boolean, val logical: Long)
    private data class Fork(val size: Long, val blocks: Long, val extents: List<Extent>)
    private data class Item(val id: Long, val parent: Long, val name: String, val directory: Boolean,
        val modified: Long?, val fork: Fork?, val resourceSize: Long = 0, val mode: Int? = null,
        val owner: String? = null, val group: String? = null, val unsupported: String? = null)

    init {
        if (u16(header, 0) == 0x4244 && u16(header, 124) in listOf(0x482b, 0x4858)) {
            val unit = u32(header, 20)
            val start = u16(header, 126).toLong()
            val count = u16(header, 128).toLong()
            if (unit < 512 || unit and (unit - 1) != 0L || count == 0L) fail("Invalid embedded volume extent")
            volumeBase = Math.addExact(base + u16(header, 28) * 512L, Math.multiplyExact(start, unit))
            val length = Math.multiplyExact(count, unit)
            if (volumeBase < base || length > device.size - volumeBase) fail("Embedded volume outside image")
            header = exact(device, volumeBase + 1024, 512)
            if (u16(header, 0) !in listOf(0x482b, 0x4858)) fail("Invalid embedded HFS+ header")
            // The inner geometry below must also fit inside its wrapper allocation extent.
            if (u32(header, 40) * u32(header, 44) > length) fail("Embedded volume exceeds wrapper extent")
        }
        val signature = u16(header, 0)
        plus = signature != 0x4244
        if (signature !in listOf(0x4244, 0x482b, 0x4858)) fail("Volume signature not found")
        if (plus && u16(header, 2) != if (signature == 0x4858) 5 else 4) fail("Unsupported volume version")
        type = when (signature) { 0x4244 -> "HFS"; 0x482b -> "HFS+"; else -> "HFSX" }
        blockSize = u32(header, if (plus) 40 else 20)
        totalBlocks = if (plus) u32(header, 44) else u16(header, 18).toLong()
        allocationBase = volumeBase + if (plus) 0 else u16(header, 28) * 512L
        if (blockSize !in 512L..(16L * 1024 * 1024) || blockSize and (blockSize - 1) != 0L || totalBlocks == 0L ||
            allocationBase < 0 || allocationBase > device.size || totalBlocks > (device.size - allocationBase) / blockSize)
            fail("Invalid allocation geometry")
        val attributes = if (plus) u32(header, 4) else u16(header, 10).toLong()
        if (attributes and (1L shl 8) == 0L || (plus && attributes and (1L shl 11) != 0L))
            fail("Volume is not cleanly unmounted; journal replay/recovery is unavailable")
    }

    fun info() = FileSystemInfo(type, null, "$type volume header at ${volumeBase + 1024}",
        caseSensitive = if (type == "HFSX") null else false, readOnly = true, allocationUnitBytes = blockSize)

    private fun extents(b: ByteArray, p: Int): List<Extent> {
        val result = ArrayList<Extent>()
        var ended = false
        repeat(if (plus) 8 else 3) { i ->
            val at = p + i * (if (plus) 8 else 4)
            val start = if (plus) u32(b, at) else u16(b, at).toLong()
            val count = if (plus) u32(b, at + 4) else u16(b, at + 2).toLong()
            if (count == 0L) { if (start != 0L) fail("Invalid empty extent"); ended = true }
            else {
                if (ended || start >= totalBlocks || count > totalBlocks - start) fail("Invalid allocation extent $i: start=$start count=$count volumeBlocks=$totalBlocks afterEmpty=$ended")
                result.add(Extent(start, count))
            }
        }
        return result
    }
    private fun fork(b: ByteArray, p: Int): Fork = Fork(u64(b, p), u32(b, p + 12), extents(b, p + 16))
    private fun classicFork(size: Long, b: ByteArray, p: Int, physical: Long = size): Fork =
        Fork(size, (physical + blockSize - 1) / blockSize, extents(b, p))
    private fun mapped(fork: Fork, id: Long, resource: Boolean = false, bootstrap: Boolean = false): Fork {
        val spans = fork.extents.toMutableList()
        var covered = spans.sumOf { it.count }
        if (fork.size < 0 || fork.blocks > totalBlocks || fork.size > fork.blocks * blockSize || covered > fork.blocks)
            fail("Fork size does not match allocation")
        var records = 0
        while (covered < fork.blocks) {
            if (bootstrap) fail("Extents-overflow tree exceeds its inline extents; bootstrap is unsupported")
            if (++records > 65536) fail("Too many overflow extents")
            val more = overflow[ExtentKey(id, resource, covered)] ?: fail("Missing overflow extents for catalog ID $id")
            if (more.isEmpty()) fail("Empty overflow extent record")
            spans.addAll(more)
            covered += more.sumOf { it.count }
            if (covered > fork.blocks) fail("Overflow extents exceed fork allocation")
        }
        return fork.copy(extents = spans)
    }
    private fun readFork(fork: Fork, offset: Long, length: Int, source: BlockDevice = device): ByteArray {
        if (offset < 0 || length < 0 || offset > fork.size || length.toLong() > fork.size - offset) fail("Read outside fork")
        val result = ByteArray(length)
        var logical = offset / blockSize
        var within = offset % blockSize
        var written = 0
        for (extent in fork.extents) {
            if (logical >= extent.count) { logical -= extent.count; continue }
            val bytes = minOf((extent.count - logical) * blockSize - within, (length - written).toLong()).toInt()
            exact(source, allocationBase + (extent.start + logical) * blockSize + within, bytes).copyInto(result, written)
            written += bytes
            if (written == length) return result
            logical = 0; within = 0
        }
        if (written != length) fail("Incomplete fork mapping")
        return result
    }
    private fun extract(fork: Fork, source: BlockDevice, output: OutputStream) {
        var position = 0L
        while (position < fork.size) {
            val length = minOf(65536L, fork.size - position).toInt()
            output.write(readFork(fork, position, length, source))
            position += length
        }
    }

    /** Follow only the live leaf chain, checking record boundaries and accounting. */
    private fun leaves(fork: Fork, consume: (ByteArray) -> Unit) {
        if (fork.size < 34) fail("Truncated catalog tree")
        val probe = readFork(fork, 0, 34)
        val nodeSize = u16(probe, 32)
        if (nodeSize !in 512..32768 || nodeSize and (nodeSize - 1) != 0) fail("Invalid B-tree node size")
        fun records(node: ByteArray): List<ByteArray> {
            val count = u16(node, 10)
            val table = nodeSize - (count + 1) * 2
            if (table < 14) fail("Invalid B-tree record count")
            val offsets = (0..count).map { u16(node, nodeSize - (it + 1) * 2) }
            if (offsets.first() < 14 || offsets.last() > table || offsets.zipWithNext().any { it.first > it.second })
                fail("Invalid B-tree record offsets")
            return (0 until count).map { node.copyOfRange(offsets[it], offsets[it + 1]) }
        }
        val first = readFork(fork, 0, nodeSize)
        if (first[8].toInt() != 1) fail("B-tree header node missing")
        val h = records(first).firstOrNull() ?: fail("B-tree header missing")
        if (h.size < 42) fail("Truncated B-tree header")
        val nodes = u32(h, 22)
        val expected = u32(h, 6)
        if (nodes == 0L || nodes > fork.size / nodeSize || expected > 400000) fail("Unsupported B-tree size")
        var next = u32(h, 10)
        val last = u32(h, 14)
        var previous = 0L
        var seen = 0L
        val visited = HashSet<Long>()
        while (next != 0L) {
            if (next >= nodes || !visited.add(next) || visited.size > 400000) fail("Invalid B-tree leaf chain")
            val node = readFork(fork, next * nodeSize, nodeSize)
            if (node[8].toInt() != -1 || node[9].toInt() != 1 || u32(node, 4) != previous) fail("Invalid B-tree leaf descriptor")
            for (record in records(node)) {
                if (++seen > expected) fail("B-tree record count mismatch")
                consume(record)
            }
            previous = next
            next = u32(node, 0)
        }
        if (seen != expected || previous != last) fail("Incomplete B-tree leaf chain")
    }

    private fun text(b: ByteArray, p: Int, length: Int): String {
        if (p < 0 || length < 0 || p > b.size || length > b.size - p) fail("Truncated catalog name")
        return if (plus) try {
            Charsets.UTF_16BE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, p, length)).toString()
        } catch (e: Exception) { throw IOException("HFS: Invalid Unicode catalog name", e) }
        else buildString { for (i in p until p + length) { val v = b[i].toInt() and 255; append(if (v < 128) v.toChar() else MAC_ROMAN[v - 128]) } }
    }
    private fun date(value: Long): Long? = if (value == 0L) null else (value - 2082844800L) * 1000

    fun entries(prefix: String): List<DiscImageReader.DiscEntry> {
        overflow.clear()
        val overflowFork = if (plus) fork(header, PLUS_EXTENTS_FORK_OFFSET) else classicFork(u32(header, 130), header, 134)
        if (overflowFork.size != 0L) leaves(mapped(overflowFork, 3, bootstrap = true)) { b ->
            val keySize = if (plus) { if (b.size < 12) fail("Truncated extent key"); u16(b, 0) + 2 }
                else { if (b.size < 8) fail("Truncated extent key"); (b[0].toInt() and 255) + 1 }
            if (keySize != (if (plus) 12 else 8) || b.size < keySize + (if (plus) 64 else 12)) fail("Invalid extent record")
            val resource = b[if (plus) 2 else 1].toInt() and 255
            if (resource != 0 && resource != 255) fail("Unknown fork type")
            val key = ExtentKey(u32(b, if (plus) 4 else 2), resource == 255,
                if (plus) u32(b, 8) else u16(b, 6).toLong())
            if (overflow.put(key, extents(b, keySize)) != null) fail("Duplicate overflow extent key")
        }
        val catalog = mapped(if (plus) fork(header, PLUS_CATALOG_FORK_OFFSET) else classicFork(u32(header, 146), header, 150), 4)
        val items = LinkedHashMap<Long, Item>()
        leaves(catalog) { b ->
            val keySize: Int
            val parent: Long
            val name: String
            if (plus) {
                if (b.size < 8) fail("Truncated catalog key")
                keySize = u16(b, 0) + 2
                val count = u16(b, 6)
                if (count > 255 || keySize != 8 + count * 2) fail("Invalid Unicode key length")
                parent = u32(b, 2); name = text(b, 8, count * 2)
            } else {
                if (b.size < 7) fail("Truncated catalog key")
                keySize = ((b[0].toInt() and 255) + 2) and -2
                val count = b[6].toInt() and 255
                if (count > 31 || 7 + count > keySize) fail("Invalid classic catalog key")
                parent = u32(b, 2); name = text(b, 7, count)
            }
            if (keySize > b.size - 2) fail("Missing catalog record")
            val r = b.copyOfRange(keySize, b.size)
            val kind = if (plus) u16(r, 0) else r[0].toInt() and 255
            if (kind == 3 || kind == 4) return@leaves // Thread records are not directory entries.
            if (kind != 1 && kind != 2) fail("Unknown catalog record type")
            val dir = kind == 1
            val minimum = if (plus) { if (dir) 88 else 248 } else { if (dir) 70 else 102 }
            if (r.size < minimum) fail("Truncated catalog record")
            val id = u32(r, if (plus) 8 else if (dir) 6 else 20)
            val mode = if (plus) u16(r, 42).takeIf { it != 0 } else null
            val modeType = mode?.and(0xf000) ?: 0
            if ((dir && modeType != 0 && modeType != 0x4000) || (!dir && modeType == 0x4000))
                fail("Catalog record type conflicts with BSD file mode")
            val data = if (dir) null else if (plus) fork(r, 88) else classicFork(u32(r, 26), r, 74, u32(r, 30))
            val resource = if (dir) 0 else if (plus) u64(r, 168) else u32(r, 36)
            val finder = if (plus) 48 else 4
            val unsupported = when {
                !dir && plus && (r[41].toInt() and 0x20) != 0 -> "HFS+ compressed file extraction is unsupported"
                !dir && u32(r, finder) == 0x686c6e6bL -> "HFS+ hardlink resolution is unsupported"
                plus && (u16(r, 2) and 0x20) != 0 -> "HFS+ hardlink chain resolution is unsupported"
                plus && (u16(r, 2) and 0x0c) != 0 -> "Catalog has extended attributes or ACLs; lossless extraction is unsupported"
                plus && (u16(r, 2) and 0x1000) != 0 -> "Expanded HFS timestamps are unsupported"
                !dir && (u16(r, finder + 8) and 0x8000) != 0 -> "Finder alias resolution is unsupported"
                mode != null && (mode and 0xf000) !in listOf(0, 0x4000, 0x8000, 0xa000) -> "Special file extraction is unsupported"
                else -> null
            }
            val item = Item(id, parent, name, dir, date(u32(r, if (plus) 16 else if (dir) 14 else 48)),
                data, resource, mode, if (plus) u32(r, 32).toString() else null,
                if (plus) u32(r, 36).toString() else null, unsupported)
            if (id < 2 || items.put(id, item) != null || items.size > 200000) fail("Invalid, duplicate or excessive catalog IDs")
        }
        val root = items[2] ?: fail("Root folder missing")
        if (!root.directory || root.parent != 1L) fail("Invalid root folder")
        val paths = HashMap<Long, String>()
        paths[2] = prefix.trim('/')
        fun path(item: Item, seen: MutableSet<Long> = HashSet()): String {
            paths[item.id]?.let { return it }
            if (!seen.add(item.id) || seen.size > 128) fail("Cyclic or excessive directory depth")
            val n = item.name
            if (n.isEmpty() || n == "." || n == ".." || n != n.trim() || n.any { it == '/' || it == '\\' || it == '\u0000' })
                fail("Catalog name cannot be represented losslessly: ID ${item.id}")
            val parent = items[item.parent] ?: fail("Catalog parent missing")
            if (!parent.directory) fail("Catalog parent is not a directory")
            val p = path(parent, seen)
            return (if (p.isEmpty()) n else "$p/$n").also { paths[item.id] = it }
        }
        // macOS internal inode directories are not user-visible entries. Hardlink
        // placeholders remain visible but extraction fails until inode resolution exists.
        val internalRoots = if (plus) items.values.filter { it.parent == 2L && it.directory &&
            (it.name == "\u0000\u0000\u0000\u0000HFS+ Private Data" ||
             it.name == "\u2400\u2400\u2400\u2400HFS+ Private Data" ||
             it.name == ".HFS+ Private Directory Data\r") }.map { it.id }.toSet() else emptySet()
        fun internal(item: Item): Boolean {
            var current = item
            val seen = HashSet<Long>()
            while (current.id != 2L) {
                if (current.id in internalRoots) return true
                if (!seen.add(current.id) || seen.size > 128) fail("Cyclic or excessive directory depth")
                current = items[current.parent] ?: fail("Catalog parent missing")
            }
            return false
        }
        val result = ArrayList<DiscImageReader.DiscEntry>()
        val unique = HashSet<String>()
        for (item in items.values) {
            if (item.id == 2L || internal(item)) continue
            if (item.directory && item.unsupported != null) fail(item.unsupported)
            val p = path(item)
            if (!unique.add(p)) fail("Duplicate catalog path")
            val mapped = item.fork?.let { mapped(it, item.id) }
            val link = item.mode?.and(0xf000) == 0xa000
            val reason = item.unsupported ?: if (item.resourceSize > 0) "Resource fork would be lost; extraction is unsupported" else null
            var target: String? = null
            if (link && reason == null && mapped != null) {
                if (mapped.size !in 1..65536) fail("Invalid symbolic link length")
                target = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readFork(mapped, 0, mapped.size.toInt()))).toString() }
                    catch (e: Exception) { throw IOException("HFS: Invalid symbolic link text", e) }
                if (target.contains('\u0000')) fail("Symbolic link contains NUL")
            }
            result.add(DiscImageReader.DiscEntry(p, item.directory, if (item.directory) null else mapped?.size,
                item.modified, if (item.directory) null else { source, output ->
                    if (reason != null) fail(reason)
                    extract(mapped ?: fail("Missing data fork"), source, output)
                }, link || item.unsupported?.contains("alias") == true, target, item.mode, item.owner, item.group))
        }
        return result
    }

    companion object {
        // HFSPlusVolumeHeader: encodingsBitmap ends at 80, then 32 bytes of
        // Finder information, allocationFile at 112, and 80-byte fork records.
        private const val PLUS_EXTENTS_FORK_OFFSET = 192
        private const val PLUS_CATALOG_FORK_OFFSET = 272

        fun isHfs(device: BlockDevice, base: Long = 0): Boolean {
            if (base < 0 || base > device.size || device.size - base < 1536) return false
            val b = device.readBytes(base + 1024, 2)
            return b.size == 2 && ((b[0].toInt() and 255) shl 8 or (b[1].toInt() and 255)) in listOf(0x4244, 0x482b, 0x4858)
        }
        private const val MAC_ROMAN = "\u00c4\u00c5\u00c7\u00c9\u00d1\u00d6\u00dc\u00e1\u00e0\u00e2\u00e4\u00e3\u00e5\u00e7\u00e9\u00e8\u00ea\u00eb\u00ed\u00ec\u00ee\u00ef\u00f1\u00f3\u00f2\u00f4\u00f6\u00f5\u00fa\u00f9\u00fb\u00fc\u2020\u00b0\u00a2\u00a3\u00a7\u2022\u00b6\u00df\u00ae\u00a9\u2122\u00b4\u00a8\u2260\u00c6\u00d8\u221e\u00b1\u2264\u2265\u00a5\u00b5\u2202\u2211\u220f\u03c0\u222b\u00aa\u00ba\u03a9\u00e6\u00f8\u00bf\u00a1\u00ac\u221a\u0192\u2248\u2206\u00ab\u00bb\u2026\u00a0\u00c0\u00c3\u00d5\u0152\u0153\u2013\u2014\u201c\u201d\u2018\u2019\u00f7\u25ca\u00ff\u0178\u2044\u20ac\u2039\u203a\ufb01\ufb02\u2021\u00b7\u201a\u201e\u2030\u00c2\u00ca\u00c1\u00cb\u00c8\u00cd\u00ce\u00cf\u00cc\u00d3\u00d4\uf8ff\u00d2\u00da\u00db\u00d9\u0131\u02c6\u02dc\u00af\u02d8\u02d9\u02da\u00b8\u02dd\u02db\u02c7"
    }
}
