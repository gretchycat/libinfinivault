package com.infinifolder.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.BitSet
import java.security.MessageDigest

/** Read-only candidate recovery. A freed FAT chain does not prove original allocation or contents. */
internal class FatDeletedFiles(private val fs: FatFileSystem) {
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, p: Int) = u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)
    private fun checkCancelled() = RecoveryCancellation.check()
    private fun fail(message: String): Nothing = throw IOException("FAT recovery: $message")
    private val tables: List<ByteArray>
    private val live = BitSet()
    private val activeTable: Int
    private val mirrored: Boolean
    private val clusterLimit get() = minOf(fs.totalClusters + 2, when (fs.fatType) {
        FatFileSystem.FatType.FAT12 -> 0xff0L; FatFileSystem.FatType.FAT16 -> 0xfff0L; else -> 0xffffff0L
    })
    private var chainSteps = 0
    private var candidateChecks = 0
    private data class Region(val offset: Long, val length: Int, val digest: ByteArray)
    private val directoryRegions = mutableListOf<Region>()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private data class Deleted(val bytes: ByteArray, val offset: Long, val parent: String, val lfn: List<ByteArray>)
    private val deleted = mutableListOf<Deleted>()
    init {
        if (fs.bytesPerSector !in listOf(512, 1024, 2048, 4096) || fs.sectorsPerCluster !in 1..128 ||
            fs.sectorsPerCluster and (fs.sectorsPerCluster - 1) != 0 || fs.totalClusters !in 1..16000000 ||
            fs.numFats !in 1..4 || fs.reservedSectors < 1 || fs.sectorsPerFat <= 0 ||
            fs.totalSectors > (fs.dev.size - fs.baseOffset) / fs.bytesPerSector) fail("Invalid or unsupported allocation geometry")
        if (u16(fs.bpb, 510) != 0xaa55 || fs.totalSectors <= fs.firstDataSector || fs.baseOffset < 0)
            fail("Invalid boot sector or volume bounds")
        val detectedType = when { fs.totalClusters < 4085 -> FatFileSystem.FatType.FAT12
            fs.totalClusters < 65525 -> FatFileSystem.FatType.FAT16; else -> FatFileSystem.FatType.FAT32 }
        if (detectedType != fs.fatType) fail("FAT type disagrees with cluster geometry")
        if (fs.fatType == FatFileSystem.FatType.FAT32) {
            if (u16(fs.bpb, 22) != 0 || fs.rootEntryCount != 0 || u16(fs.bpb, 42) != 0)
                fail("Unsupported FAT32 layout or version")
            val flags = u16(fs.bpb, 40)
            if (flags and 0xff70 != 0) fail("Invalid FAT32 allocation flags")
            mirrored = flags and 0x80 == 0
            activeTable = if (mirrored) 0 else flags and 15
            if (activeTable >= fs.numFats) fail("Active FAT index is outside the volume")
        } else {
            if (fs.rootEntryCount == 0 || fs.rootEntryCount * 32 % fs.bytesPerSector != 0)
                fail("Invalid fixed root directory")
            mirrored = true
            activeTable = 0
        }
        val length = fs.sectorsPerFat * fs.bytesPerSector
        if (length * fs.numFats > 64L * 1024 * 1024) fail("Allocation tables exceed the scan memory limit")
        tables = (0 until fs.numFats).map { exact(fs.dev, fs.fatStartOffset + it * length, length.toInt()) }
        value(tables.first(), fs.totalClusters + 1) // Establish that the table covers its geometry.
        val mask = when (fs.fatType) { FatFileSystem.FatType.FAT12 -> 0xfffL; FatFileSystem.FatType.FAT16 -> 0xffffL; else -> 0xfffffffL }
        val media = fs.bpb[21].toInt() and 255
        if (media != 0xf0 && media !in 0xf8..0xff || value(tables[activeTable], 0) != ((mask and 0xffL.inv()) or media.toLong()))
            fail("Invalid FAT media descriptor or reserved allocation header")
        val status = value(tables[activeTable], 1)
        val cleanMask = when (fs.fatType) { FatFileSystem.FatType.FAT16 -> 0x8000L; FatFileSystem.FatType.FAT32 -> 0x8000000L; else -> 0L }
        val errorMask = cleanMask shr 1
        val reservedMask = mask and (cleanMask or errorMask).inv()
        if (status and reservedMask != reservedMask) fail("Invalid reserved FAT allocation entry")
        if (cleanMask != 0L && (status and cleanMask == 0L || status and errorMask == 0L))
            fail("Volume is marked dirty or has recorded I/O errors; recovery requires a stable, clean image. Keep the original unchanged and inspect a separate copy")
    }
    private fun exact(source: BlockDevice, offset: Long, length: Int): ByteArray {
        checkCancelled()
        if (offset < 0 || length < 0 || offset > source.size || length.toLong() > source.size - offset) fail("Read outside image")
        return source.readBytes(offset, length).also { if (it.size != length) fail("Incomplete storage read") }
    }
    private fun value(table: ByteArray, c: Long): Long {
        val p = when (fs.fatType) {
            FatFileSystem.FatType.FAT12 -> c + c / 2
            FatFileSystem.FatType.FAT16 -> c * 2
            FatFileSystem.FatType.FAT32 -> c * 4
        }
        val width = if (fs.fatType == FatFileSystem.FatType.FAT32) 4 else 2
        if (p < 0 || p > table.size - width) fail("Allocation entry outside table")
        return when (fs.fatType) {
            FatFileSystem.FatType.FAT12 -> (u16(table, p.toInt()) shr if (c and 1L == 0L) 0 else 4).toLong() and 0xfff
            FatFileSystem.FatType.FAT16 -> u16(table, p.toInt()).toLong()
            FatFileSystem.FatType.FAT32 -> u32(table, p.toInt()) and 0xfffffff
        }
    }
    private data class Chain(val count: Long, val addresses: List<Long>)
    private fun chain(start: Long, collect: Boolean): Chain {
        val addresses = mutableListOf<Long>()
        var count = 0L
        var c = start
        val end = when (fs.fatType) { FatFileSystem.FatType.FAT12 -> 0xff8L; FatFileSystem.FatType.FAT16 -> 0xfff8L; else -> 0xffffff8L }
        while (true) {
            checkCancelled()
            if (c !in 2 until clusterLimit || ++chainSteps > 4000000) fail("Invalid or excessive live cluster chains")
            if (live[c.toInt()]) fail("Cyclic or overlapping live cluster chains")
            live.set(c.toInt()); count++
            if (collect) {
                if (count * fs.clusterSize > 8 * 1024 * 1024) fail("Directory exceeds scan limit")
                addresses.add(c)
            }
            val next = value(tables[activeTable], c)
            if (mirrored && tables.any { value(it, c) != next }) fail("Mirrored FAT copies disagree on a live chain")
            if (next >= end) return Chain(count, addresses)
            if (next < 2) fail("Live allocation is inconsistent; safe overlap checks are unavailable")
            c = next
        }
    }
    private fun offset(c: Long) = fs.dataStartOffset + (c - 2) * fs.clusterSize
    private fun cluster(b: ByteArray) = (if (fs.fatType == FatFileSystem.FatType.FAT32) u16(b, 20).toLong() shl 16 else 0) or u16(b, 26).toLong()
    private fun shortName(b: ByteArray, missing: Boolean): String {
        val copy = b.copyOfRange(0, 11)
        if (missing) copy[0] = '_'.code.toByte()
        else if (copy[0] == 5.toByte()) copy[0] = 0xe5.toByte()
        val base = String(copy, 0, 8, Charsets.ISO_8859_1).trimEnd()
        val ext = String(copy, 8, 3, Charsets.ISO_8859_1).trimEnd()
        return if (ext.isEmpty()) base else "$base.$ext"
    }
    fun entries(): List<DiscImageReader.DiscEntry> {
        if (directoryRegions.isNotEmpty()) fail("A recovery scanner cannot be reused")
        val directories = HashSet<Long>()
        var records = 0
        fun visit(start: Long, parent: String, depth: Int) {
            if (depth > 128 || !directories.add(start) || directories.size > 50000) fail("Cyclic or excessive live directory tree")
            val fixed = fs.fatType != FatFileSystem.FatType.FAT32 && start == 0L
            val addresses = if (fixed) emptyList() else chain(start, collect = true).addresses
            if (addresses.size.toLong() * fs.clusterSize > 8 * 1024 * 1024) fail("Directory exceeds scan limit")
            val chunks = if (fixed) listOf(fs.rootOffset to fs.rootEntryCount * 32) else addresses.map { offset(it) to fs.clusterSize }
            val lfn = mutableListOf<ByteArray>()
            var ended = false
            for ((address, length) in chunks) {
                if (ended) break
                val bytes = exact(fs.dev, address, length)
                directoryRegions.add(Region(address, length, digest(bytes)))
                for (p in bytes.indices step 32) {
                    checkCancelled()
                    if (++records > 500000) fail("Too many directory records")
                    val b = bytes.copyOfRange(p, p + 32)
                    val first = b[0].toInt() and 255
                    if (first == 0) { ended = true; break }
                    val attr = b[11].toInt() and 255
                    if (first == 0xe5 && attr == 0xf) {
                        if (lfn.size == 20) lfn.clear()
                        lfn.add(b); continue
                    }
                    if (first == 0xe5 && attr and 0xd8 == 0 && validShortName(b)) {
                        if (deleted.size >= 50000) fail("Too many deleted-file candidates")
                        deleted.add(Deleted(b, address + p, parent, lfn.toList()))
                    }
                    lfn.clear()
                    if (first == 0xe5 || attr == 0xf || attr and 8 != 0) continue
                    val rawName = String(b, 0, 11, Charsets.ISO_8859_1)
                    if (rawName == ".          " || rawName == "..         ") continue
                    if (attr and 0xc0 != 0 || !validShortName(b, liveEntry = true)) fail("Invalid live directory record")
                    val c = cluster(b)
                    if (attr and 0x10 != 0) {
                        if (c < 2 || u32(b, 28) != 0L) fail("Invalid live subdirectory allocation")
                        visit(c, "$parent/${shortName(b, false)}", depth + 1)
                    }
                    else if (c >= 2) {
                        val allocated = chain(c, collect = false)
                        if (allocated.count * fs.clusterSize < u32(b, 28)) fail("Live file has an incomplete chain")
                    } else if (u32(b, 28) != 0L) fail("Live file has no allocation")
                }
            }
        }
        visit(if (fs.fatType == FatFileSystem.FatType.FAT32) fs.rootCluster else 0, "", 0)
        val bootHash = digest(fs.bpb)
        val fatHashes = tables.map(::digest)
        fun verifySnapshot(source: BlockDevice) {
            if (!digest(exact(source, fs.baseOffset, 512)).contentEquals(bootHash)) fail("Source boot sector changed; refresh and retry")
            val length = fs.sectorsPerFat * fs.bytesPerSector
            for (i in tables.indices) {
                val hash = MessageDigest.getInstance("SHA-256")
                var position = 0L
                while (position < length) {
                    val n = minOf(65536L, length - position).toInt()
                    hash.update(exact(source, fs.fatStartOffset + i * length + position, n)); position += n
                }
                if (!hash.digest().contentEquals(fatHashes[i])) fail("Source allocation changed during recovery; refresh and retry")
            }
            for (region in directoryRegions)
                if (!digest(exact(source, region.offset, region.length)).contentEquals(region.digest))
                    fail("Source directory changed during recovery; refresh and retry")
        }
        val allocationTables = if (mirrored) tables else listOf(tables[activeTable])
        return deleted.map { candidate ->
            val b = candidate.bytes
            val size = u32(b, 28)
            val start = cluster(b)
            val count = (size + fs.clusterSize - 1) / fs.clusterSize
            var error: String? = null
            if (fs.fatType != FatFileSystem.FatType.FAT32 && u16(b, 20) != 0 ||
                fs.fatType == FatFileSystem.FatType.FAT32 && u16(b, 20) and 0xf000 != 0)
                error = "Deleted entry has invalid reserved cluster bits"
            if (size == 0L && start != 0L) error = "Empty deleted entry has an unexpected allocation"
            if (error == null && count > 0 && (start < 2 || start >= clusterLimit || count > clusterLimit - start)) error = "Deleted entry points outside the volume"
            if (error == null && count > 0) {
                for (c in start until start + count) {
                    checkCancelled()
                    if (++candidateChecks > 8000000) fail("Candidate allocation checks exceed the scan limit")
                    if (live[c.toInt()] || allocationTables.any { value(it, c) != 0L }) { error = "Candidate overlaps allocated or reused clusters"; break }
                }
            }
            val name = longName(candidate) ?: shortName(b, true)
            val sanitized = name.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim()
            val cleanName = buildString {
                var bytes = 0
                for (codePoint in sanitized.codePoints().toArray()) {
                    val text = String(Character.toChars(codePoint))
                    val length = text.toByteArray(Charsets.UTF_8).size
                    if (bytes + length > 160) break
                    append(text); bytes += length
                }
            }.ifEmpty { "unknown-file" }
            val identity = MessageDigest.getInstance("SHA-256").apply {
                update(fs.bpb); update(b); candidate.lfn.forEach { update(it) }
            }.digest().take(16).joinToString("") { "%02x".format(it) }
            val path = "deleted-${candidate.offset.toString(16)}-${identity}_$cleanName"
            val note = "Unverified deleted-file candidate in ${candidate.parent.ifEmpty { "/" }}. " +
                (if (size == 0L) "Empty file." else if (count == 1L) "Single free cluster; original contents may have been overwritten." else
                    "Assumes $count contiguous free clusters. Original fragmentation and overwritten contents cannot be determined.") +
                " A leading underscore replaces a lost short-name character. Long-name associations, when present, are tentative."
            val validClusterBits = if (fs.fatType == FatFileSystem.FatType.FAT32) u16(b, 20) and 0xf000 == 0 else u16(b, 20) == 0
            val partialAllowed = validClusterBits && (size == 0L && start == 0L || size > 0 && start in 2 until clusterLimit)
            DiscImageReader.DiscEntry(path, false, size, null, extractor = { source, out ->
                error?.let { throw IOException(it) }
                verifySnapshot(source)
                val payloadHash = MessageDigest.getInstance("SHA-256")
                var position = offset(start)
                var remaining = size
                while (remaining > 0) {
                    checkCancelled()
                    val n = minOf(65536L, remaining).toInt()
                    val bytes = exact(source, position, n)
                    payloadHash.update(bytes); out.write(bytes); position += n; remaining -= n
                }
                // This detects observed source changes, not whether bytes survived the original deletion.
                val rereadHash = MessageDigest.getInstance("SHA-256")
                position = offset(start); remaining = size
                while (remaining > 0) {
                    val n = minOf(65536L, remaining).toInt()
                    rereadHash.update(exact(source, position, n)); position += n; remaining -= n
                }
                if (!payloadHash.digest().contentEquals(rereadHash.digest())) fail("Source data changed during recovery; refresh and retry")
                verifySnapshot(source)
            }, readError = error, recoveryNote = note, partialExtractor = if (!partialAllowed) null else { source, out ->
                FatPartialRecovery.extract(source, out, path, size, start, fs,
                    { c ->
                        val badMark = when (fs.fatType) { FatFileSystem.FatType.FAT12 -> 0xff7L; FatFileSystem.FatType.FAT16 -> 0xfff7L; else -> 0xffffff7L }
                        when {
                            allocationTables.any { value(it, c) == badMark } -> "marked_bad_cluster"
                            live[c.toInt()] || allocationTables.any { value(it, c) != 0L } -> "allocated_or_reused_cluster"
                            else -> null
                        }
                    }, clusterLimit, ::verifySnapshot, note)
            })
        }
    }
    private fun validShortName(b: ByteArray, liveEntry: Boolean = false): Boolean {
        val forbidden = "\"*+,./:;<=>?[\\]|"
        val range = if (liveEntry) 0..10 else 1..10
        return range.all { p ->
            val v = b[p].toInt() and 255
            (p == 0 && v == 5) || (v >= 32 && v != 127 && v.toChar() !in forbidden)
        }
    }

    private fun longName(candidate: Deleted): String? {
        val parts = candidate.lfn
        if (parts.isEmpty() || parts.any { it[12] != 0.toByte() || u16(it, 26) != 0 || it[13] != parts[0][13] }) return null
        val short = candidate.bytes.copyOfRange(0, 11)
        val possible = (1..255).count { first ->
            short[0] = first.toByte()
            var sum = 0
            for (v in short) sum = (((sum and 1) shl 7) + (sum shr 1) + (v.toInt() and 255)) and 255
            sum == (parts[0][13].toInt() and 255)
        }
        if (possible != 1) return null
        val bytes = java.io.ByteArrayOutputStream()
        for (part in parts.asReversed()) for (p in listOf(1,3,5,7,9,14,16,18,20,22,24,28,30)) bytes.write(part, p, 2)
        val encoded = bytes.toByteArray()
        val units = encoded.indices.step(2).map { u16(encoded, it) }
        val terminator = units.indexOf(0).let { if (it < 0) units.size else it }
        if (terminator !in 1..255 || units.take(terminator).any { it == 0xffff } ||
            units.drop(terminator + 1).any { it != 0xffff }) return null
        return runCatching {
            Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded, 0, terminator * 2))
                .toString().substringBefore('\u0000').substringBefore('\uffff').takeIf { it.isNotEmpty() && it != "." && it != ".." }
        }.getOrNull()
    }
}
