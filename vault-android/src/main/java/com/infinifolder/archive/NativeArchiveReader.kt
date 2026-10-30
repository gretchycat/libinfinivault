package com.infinifolder.archive

import net.sf.sevenzipjbinding.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

/** A provider supplies sibling handles; the decoder never guesses filesystem paths. */
class ArchiveVolumeSet(val names: List<String>, val revision: String, val primaryName: String? = null, val open: (String) -> BlockDevice?)

internal class NativeArchiveReader(private val tempDir: File) {
    data class Entry(val path: String, val directory: Boolean, val size: Long?, val modified: Long?,
        val mode: Int?, val link: String?, val error: String?)
    data class Catalog(val entries: List<Entry>, val warnings: List<String>)
    private data class Part(val session: Session, val index: Int, val entry: Entry,
        val before: Boolean = false, val after: Boolean = false, val position: Long = 0)
    private class DeviceStream(val device: BlockDevice) : IInStream {
        private var position = 0L
        override fun read(data: ByteArray): Int {
            checkCancelled()
            val n = device.readAt(position, data)
            if (n > 0) position += n
            return n.coerceAtLeast(0)
        }
        override fun seek(offset: Long, seekOrigin: Int): Long {
            position = Math.addExact(when (seekOrigin) { 0 -> 0; 1 -> position; 2 -> device.size; else -> throw IOException("Invalid archive seek") }, offset)
            if (position < 0) throw IOException("Negative archive seek")
            return position
        }
        override fun close() = device.close()
    }
    private class Session(val archive: IInArchive, val handles: MutableList<DeviceStream>, val name: String) : Closeable {
        override fun close() { try { archive.close() } finally { handles.forEach { runCatching { it.close() } } } }
    }
    private fun open(set: ArchiveVolumeSet, name: String, warnings: MutableList<String>): Session {
        val handles = mutableListOf<DeviceStream>()
        fun stream(requested: String): DeviceStream? {
            if (requested.contains('/') || requested.contains('\\') || requested == "." || requested == "..")
                throw IOException("Archive requested an unsafe volume name")
            val exact = set.names.firstOrNull { it == requested }
            val matches = set.names.filter { it.equals(requested, true) }
            val match = exact ?: matches.singleOrNull()
            if (match == null) {
                warnings.add("Missing archive volume: $requested")
                com.infinifolder.storage.DiagnosticLog.record("archive.volume.missing", requested)
                return null
            }
            if (handles.size >= 4096) throw IOException("Too many archive volume handles")
            return try {
                val device = set.open(match)
                if (device == null) warnings.add("Cannot read archive volume: $match")
                device?.let { DeviceStream(it).also(handles::add) }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted || e is InterruptedException || e is java.io.InterruptedIOException) throw e
                warnings.add("Cannot read archive volume $match: ${e.message}")
                com.infinifolder.storage.DiagnosticLog.record("archive.volume.unreadable", match, e)
                null
            }
        }
        val first = stream(name) ?: throw IOException("Cannot open archive volume $name")
        val callback = object : IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword {
            override fun setTotal(files: Long?, bytes: Long?) = checkCancelled()
            override fun setCompleted(files: Long?, bytes: Long?) = checkCancelled()
            override fun getProperty(propID: PropID): Any? = when (propID) {
                PropID.NAME -> name; PropID.SIZE -> first.device.size; PropID.IS_FOLDER -> false; else -> null
            }
            override fun getStream(filename: String): IInStream? = stream(filename)
            override fun cryptoGetTextPassword(): String = "" // Password UI is a separate feature.
        }
        return try { Session(SevenZip.openInArchive(null, first, callback), handles, name) }
            catch (e: Throwable) { handles.forEach { runCatching { it.close() } }; throw IOException("Cannot read archive headers in $name: ${e.message}", e) }
    }
    private fun inspect(session: Session, flags: List<ArjFlags>?, warnings: MutableList<String>, verify: Boolean): List<Part> {
        val arc = session.archive
        if (arc.numberOfItems > 200000) throw IOException("Archive has too many entries")
        val entries = (0 until arc.numberOfItems).map { i ->
            fun p(id: PropID) = arc.getProperty(i, id)
            val raw = p(PropID.PATH) as? String
            var nameError: String? = null
            val path = try { safePath(raw ?: throw IOException("Archive entry has no name")) }
                catch (e: IOException) {
                    nameError = e.message
                    warnings.add("Entry ${i + 1} has an unreadable or unsafe name")
                    "__unreadable_entries__/entry-${i + 1}"
                }
            val encrypted = p(PropID.ENCRYPTED) == true
            val link = p(PropID.SYM_LINK) as? String
            val hardlink = (p(PropID.HARD_LINK) as? String)?.isNotEmpty() == true
            Entry(path, nameError == null && p(PropID.IS_FOLDER) == true, (p(PropID.SIZE) as? Number)?.toLong()?.takeIf { it >= 0 },
                (p(PropID.LAST_MODIFICATION_TIME) as? Date)?.time,
                (p(PropID.POSIX_ATTRIB) as? Number)?.toInt(), link,
                nameError ?: if (encrypted) "Encrypted file: password required" else if (hardlink) "Archive hardlink resolution is unavailable" else null)
        }
        val errors = Array<String?>(entries.size) { if (!verify || entries[it].directory) null else "File integrity could not be checked" }
        var index = -1
        val callback = object : IArchiveExtractCallback, ICryptoGetTextPassword {
            override fun getStream(i: Int, mode: ExtractAskMode): ISequentialOutStream? { index = i; checkCancelled(); return null }
            override fun prepareOperation(mode: ExtractAskMode) = checkCancelled()
            override fun setOperationResult(result: ExtractOperationResult) {
                if (index in errors.indices) errors[index] = if (result == ExtractOperationResult.OK) null else "Archive entry unreadable: $result"
            }
            override fun setTotal(total: Long) = checkCancelled()
            override fun setCompleted(complete: Long) = checkCancelled()
            override fun cryptoGetTextPassword(): String = ""
        }
        try { if (verify) arc.extract(null, true, callback) }
        catch (e: Exception) { checkCancelled(); warnings.add("Some archive data could not be checked: ${e.message}") }
        (arc.getArchiveProperty(PropID.ERROR) as? String)?.takeIf { it.isNotBlank() }?.let(warnings::add)
        val archiveError = (arc.getArchiveProperty(PropID.ERROR_FLAGS) as? Number)?.toLong() ?: 0
        if (archiveError != 0L) warnings.add("Archive headers are incomplete or damaged (flags $archiveError); only identifiable entries are shown")
        if (flags != null && flags.size != entries.size) throw IOException("ARJ header index does not match decoder index")
        return entries.mapIndexed { i, entry ->
            val f = flags?.get(i)
            Part(session, i, entry.copy(error = entry.error ?: errors[i]), f?.before ?: false, f?.after ?: false, f?.position ?: 0)
        }
    }
    private data class ArjFlags(val before: Boolean, val after: Boolean, val position: Long)
    /** ARJ volumes are separate archives in 7-Zip. Read continuation flags to join verified plaintext segments. */
    private fun arjFlags(device: BlockDevice): List<ArjFlags> {
        var offset = 0L
        fun bytes(n: Int): ByteArray {
            val b = device.readBytes(offset, n)
            if (b.size != n) throw IOException("Truncated ARJ header")
            offset += n
            return b
        }
        fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
        fun u32(b: ByteArray, p: Int) = u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)
        fun header(): ByteArray? {
            val signature = bytes(4)
            if (u16(signature, 0) != 0xea60) throw IOException("Invalid ARJ header signature")
            val length = u16(signature, 2)
            if (length == 0) return null
            if (length !in 30..65535) throw IOException("Invalid ARJ header length")
            val b = bytes(length)
            val crc = u32(bytes(4), 0)
            if (CRC32().apply { update(b) }.value != crc) throw IOException("ARJ header checksum error")
            var extended = 0
            while (true) {
                val count = u16(bytes(2), 0)
                if (count == 0) break
                if (++extended > 4096) throw IOException("Too many ARJ extended headers")
                val data = bytes(count)
                if (CRC32().apply { update(data) }.value != u32(bytes(4), 0)) throw IOException("ARJ extended header checksum error")
            }
            return b
        }
        header() ?: throw IOException("ARJ main header missing")
        val result = mutableListOf<ArjFlags>()
        // Preserve the safely indexed prefix if a later header is broken.
        while (offset < device.size && result.size < 200000) {
            val b = try { header() } catch (_: IOException) { break } ?: break
            val flags = b[4].toInt() and 255
            val before = flags and 8 != 0
            if (before && (b[0].toInt() and 255) < 34) break
            result.add(ArjFlags(before, flags and 4 != 0, if (before) u32(b, 30) else 0))
            val packed = u32(b, 12)
            if (packed > device.size - offset) break
            offset += packed
        }
        return result
    }
    private class Opened(val sessions: List<Session>, val members: List<List<Part>>, val warnings: List<String>) : Closeable {
        override fun close() { sessions.forEach { runCatching { it.close() } } }
    }
    private fun load(set: ArchiveVolumeSet, verify: Boolean = true): Opened {
        val warnings = mutableListOf<String>()
        val sessions = mutableListOf<Session>()
        try {
            val ordered = set.names.sortedWith(compareBy<String> { volumeNumber(it) }.thenBy { it })
            if (ordered.isEmpty()) throw IOException("Archive volume set is empty")
            val selected = set.primaryName?.takeIf { it in set.names } ?: ordered.first()
            val arj = family(selected).second == "arj"
            val parts = mutableListOf<Part>()
            for (name in if (arj) ordered else listOf(selected)) {
                val session = try { open(set, name, warnings) } catch (e: IOException) {
                    if (!arj) throw e
                    warnings.add(e.message ?: "Cannot open ARJ volume $name"); continue
                }
                sessions.add(session)
                try {
                    val flags = if (session.archive.archiveFormat == ArchiveFormat.ARJ) session.handles.first().device.let(::arjFlags) else null
                    parts.addAll(inspect(session, flags, warnings, verify))
                } catch (e: IOException) {
                    checkCancelled()
                    if (!arj) throw e
                    warnings.add("Cannot inspect ARJ volume $name: ${e.message}")
                }
            }
            if (parts.isEmpty() && sessions.isEmpty()) throw IOException(warnings.joinToString("; "))
            val members = parts.groupBy { it.entry.path }.values.map { group -> group.sortedBy { it.position } }
            return Opened(sessions, members, warnings)
        } catch (e: Throwable) { sessions.forEach { runCatching { it.close() } }; throw e }
    }
    private fun combined(parts: List<Part>): Entry {
        val first = parts.first()
        var error = parts.firstNotNullOfOrNull { it.entry.error }
        var length = 0L
        var previous: Part? = null
        for (part in parts) {
            if (part.position != length || (previous == null && part.before) ||
                (previous != null && (!previous.after || !part.before))) error = error ?: "Missing, duplicate or out-of-order archive segment"
            length = Math.addExact(length, part.entry.size ?: 0)
            previous = part
        }
        if (parts.last().after) error = error ?: "Missing continuation volume"
        return first.entry.copy(size = if (first.entry.directory) null else length, error = error)
    }
    fun catalog(set: ArchiveVolumeSet): Catalog = load(set).use { opened ->
        val entries = opened.members.map(::combined)
        com.infinifolder.storage.DiagnosticLog.record("archive.integrity",
            "volumes=${set.names.size} entries=${entries.size} unreadable=${entries.count { it.error != null }} warnings=${opened.warnings.distinct().joinToString("; ")}")
        for (entry in entries.filter { it.error != null })
            com.infinifolder.storage.DiagnosticLog.record("archive.entry.unreadable", "${entry.path}: ${entry.error}")
        Catalog(entries, opened.warnings.distinct())
    }
    fun openEntry(set: ArchiveVolumeSet, path: String): InputStream {
        tempDir.mkdirs()
        val staged = File.createTempFile("verified-entry-", ".tmp", tempDir)
        try {
            load(set, verify = false).use { opened ->
                val parts = opened.members.singleOrNull { it.first().entry.path == path } ?: throw IOException("Archive entry not found: $path")
                val entry = combined(parts)
                if (entry.directory) throw IOException("Cannot open a directory as file data")
                entry.error?.let { throw IOException(it) }
                ContainerSpace.require(tempDir, entry.size ?: 0)
                staged.outputStream().use { out ->
                    for (part in parts) {
                        var written = 0L
                        val result = part.session.archive.extractSlow(part.index, ISequentialOutStream { bytes ->
                            checkCancelled(); out.write(bytes); written += bytes.size; bytes.size
                        })
                        if (result != ExtractOperationResult.OK || (part.entry.size != null && written != part.entry.size))
                            throw IOException("Archive member failed verification: $result")
                    }
                }
            }
            return object : java.io.FilterInputStream(staged.inputStream()) {
                override fun close() { try { super.close() } finally { staged.delete() } }
            }
        } catch (e: Throwable) { staged.delete(); throw e }
    }
    companion object {
        fun isSupported(name: String) = NativeArchiveNames.isSupported(name)
        fun family(name: String): Pair<String, String> = NativeArchiveNames.family(name)
        private fun volumeNumber(name: String): Int {
            Regex("(?i)\\.([0-9]{3,})\\.rar$").find(name)?.let { return it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
            Regex("(?i)\\.part([0-9]+)\\.rar$").find(name)?.let { return it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
            Regex("(?i)\\.[ra]([0-9]{2})$").find(name)?.let { return it.groupValues[1].toInt() + 2 }
            return 0
        }
        private fun checkCancelled() { if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Archive operation cancelled") }
        private fun safePath(raw: String): String {
            val path = raw.replace('\\', '/').removeSuffix("/")
            if (path.isEmpty() || path != path.trim() || path.startsWith('/') || path.contains('\u0000') ||
                path.split('/').any { it.isEmpty() || it == "." || it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(path))
                throw IOException("Archive name cannot be represented safely: $raw")
            return path
        }
    }
}
