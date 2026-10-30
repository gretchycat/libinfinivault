package com.infinifolder.archive

import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.StorageSafety
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipShort
import org.apache.commons.compress.archivers.zip.Zip64Mode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Rewrite only into a separate file. The caller publishes it through revision-aware replacement. */
object ZipContainerEditor {
    private fun path(name: String) = name.replace('\\', '/').removePrefix("/").removeSuffix("/")
    fun rewrite(original: File, target: File, entryPath: String, directory: Boolean = false,
        content: File? = null, createNew: Boolean = false, delete: Boolean = false) {
        entryPath.split('/').forEach(StorageSafety::validateName)
        val comment = archiveComment(original)
        ZipFile.builder().setFile(original).get().use { input ->
            val entries = input.entriesInPhysicalOrder.asSequence().toList()
            if ((entries.minOfOrNull { it.localHeaderOffset } ?: 0L) != 0L)
                throw IOException("Updating self-extracting ZIP files is unsupported")
            // Re-encoding an undecodable legacy filename would silently rename an existing file.
            if (entries.any { !it.name.toByteArray(Charsets.UTF_8).contentEquals(it.rawName) })
                throw IOException("ZIP has legacy filename encoding that cannot be preserved by this writer; original retained")
            val names = entries.map { path(it.name) }
            if (names.toSet().size != names.size) throw IOException("ZIP contains ambiguous duplicate paths; original retained")
            if (entries.any { it.name.uppercase().matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) })
                throw IOException("Signed ZIP/JAR packages are read-only; updating would invalidate signatures")
            val old = entries.firstOrNull { path(it.name) == entryPath }
            if (createNew && (old != null || names.any { it.startsWith("$entryPath/") }))
                throw java.nio.file.FileAlreadyExistsException(entryPath)
            if (!createNew && old == null && !names.any { it.startsWith("$entryPath/") })
                throw IOException("ZIP entry is unavailable: $entryPath")
            if (!delete && !directory && old?.isUnixSymlink == true)
                throw IOException("Cannot replace a ZIP symbolic link with ordinary file data")
            for (entry in entries) {
                val p = path(entry.name)
                if (entryPath.startsWith("$p/") && (!entry.isDirectory || entry.isUnixSymlink))
                    throw IOException("ZIP destination parent is not an ordinary directory: $p")
            }
            fun retained(entry: ZipArchiveEntry): Boolean {
                val p = path(entry.name)
                return p != entryPath && !(delete && p.startsWith("$entryPath/"))
            }
            ZipArchiveOutputStream(target).use { output ->
                output.setUseZip64(Zip64Mode.AsNeeded)
                input.copyRawEntries(output) { retained(it) }
                if (!delete) {
                    val entry = if (old != null) ZipArchiveEntry(old) else ZipArchiveEntry(entryPath + if (directory) "/" else "")
                    entry.method = java.util.zip.ZipEntry.DEFLATED
                    entry.size = content?.length() ?: 0L
                    entry.compressedSize = -1L
                    val crc = java.util.zip.CRC32()
                    content?.inputStream()?.use { stream ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) { val n = stream.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
                    }
                    entry.crc = crc.value
                    if (entry.getExtraField(ZipShort(1)) != null) entry.removeExtraField(ZipShort(1))
                    if (old == null) entry.unixMode = if (directory) 0x41ED else 0x81A4
                    output.putArchiveEntry(entry)
                    content?.inputStream()?.use { it.copyTo(output) }
                    output.closeArchiveEntry()
                }
            }
            // Preserve the archive-level comment as raw bytes, including legacy encodings.
            if (comment.isNotEmpty()) RandomAccessFile(target, "rw").use { file ->
                file.seek(file.length() - 2)
                file.write(comment.size and 255); file.write(comment.size ushr 8)
                file.write(comment)
                file.fd.sync()
            }
            ZipFile.builder().setFile(target).get().use { check ->
                val actual = check.entries.asSequence().toList()
                val wanted = entries.filter(::retained)
                if (actual.size != wanted.size + if (delete) 0 else 1) throw IOException("Updated ZIP directory verification failed")
                for (entry in wanted) {
                    val copied = check.getEntry(entry.name) ?: throw IOException("Updated ZIP lost an existing entry")
                    if (copied.crc != entry.crc || copied.size != entry.size || copied.method != entry.method || copied.unixMode != entry.unixMode)
                        throw IOException("Existing ZIP entry metadata changed; original retained")
                }
                if (!delete && !directory) {
                    val added = check.getEntry(entryPath) ?: throw IOException("ZIP addition missing")
                    val expected = content?.inputStream()?.use(ContentRevision::read) ?: ContentRevision.of(byteArrayOf())
                    if (check.getInputStream(added).use(ContentRevision::read) != expected)
                        throw IOException("ZIP addition failed content verification")
                }
            }
        }
    }
    data class Addition(val path: String, val directory: Boolean, val content: File?)

    /** A batch adds exclusive entries and can remove original entries in the same verified rewrite. */
    fun appendBatch(original: File, target: File, additions: List<Addition>, cancelled: () -> Boolean,
        removals: Set<String> = emptySet()) {
        fun checkCancelled() { if (cancelled()) throw InterruptedException("Container operation cancelled") }
        fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
            val buffer = ByteArray(128 * 1024)
            while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        }
        checkCancelled()
        val comment = archiveComment(original)
        ZipFile.builder().setFile(original).get().use { input ->
            val originalEntries = input.entriesInPhysicalOrder.asSequence().toList()
            if ((originalEntries.minOfOrNull { it.localHeaderOffset } ?: 0L) != 0L)
                throw IOException("Updating self-extracting ZIP files is unsupported")
            if (originalEntries.any { !it.name.toByteArray(Charsets.UTF_8).contentEquals(it.rawName) })
                throw IOException("ZIP has legacy filename encoding that cannot be preserved; original retained")
            val originalNames = originalEntries.map { path(it.name) }
            if (originalNames.toSet().size != originalNames.size) throw IOException("ZIP contains ambiguous duplicate paths")
            if (originalEntries.any { it.name.uppercase().matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) })
                throw IOException("Signed ZIP/JAR packages are read-only")
            removals.forEach { removedPath ->
                removedPath.split('/').forEach(StorageSafety::validateName)
                if (originalNames.none { it == removedPath || it.startsWith("$removedPath/") })
                    throw IOException("ZIP removal target is unavailable: $removedPath")
            }
            val retainedEntries = originalEntries.filter { entry ->
                val entryPath = path(entry.name)
                removals.none { removedPath -> entryPath == removedPath || entryPath.startsWith("$removedPath/") }
            }
            val names = originalNames.toMutableSet()
            val pending = additions.associateBy { it.path }
            if (pending.size != additions.size) throw IOException("Duplicate staged ZIP paths")
            for (addition in additions) {
                addition.path.split('/').forEach(StorageSafety::validateName)
                if (!names.add(addition.path) || originalNames.any { it.startsWith("${addition.path}/") })
                    throw java.nio.file.FileAlreadyExistsException(addition.path)
                for (entry in originalEntries) {
                    if (addition.path.startsWith("${path(entry.name)}/") && (!entry.isDirectory || entry.isUnixSymlink))
                        throw IOException("ZIP parent is not an ordinary directory: ${entry.name}")
                }
                var ancestor = addition.path.substringBeforeLast('/', "")
                while (ancestor.isNotEmpty()) {
                    if (pending[ancestor]?.directory == false) throw IOException("Staged ZIP parent is not a directory")
                    ancestor = ancestor.substringBeforeLast('/', "")
                }
            }
            ZipArchiveOutputStream(target).use { output ->
                output.setUseZip64(Zip64Mode.AsNeeded)
                input.copyRawEntries(output) { entry ->
                    checkCancelled()
                    val entryPath = path(entry.name)
                    removals.none { removedPath -> entryPath == removedPath || entryPath.startsWith("$removedPath/") }
                }
                for (addition in additions) {
                    checkCancelled()
                    val entry = ZipArchiveEntry(addition.path + if (addition.directory) "/" else "")
                    entry.method = java.util.zip.ZipEntry.DEFLATED
                    entry.unixMode = if (addition.directory) 0x41ED else 0x81A4
                    output.putArchiveEntry(entry)
                    addition.content?.inputStream()?.use { copy(it, output) }
                    output.closeArchiveEntry()
                }
            }
            if (comment.isNotEmpty()) RandomAccessFile(target, "rw").use { file ->
                file.seek(file.length() - 2)
                file.write(comment.size and 255); file.write(comment.size ushr 8); file.write(comment)
            }
            RandomAccessFile(target, "rw").use { it.fd.sync() }
            checkCancelled()
            ZipFile.builder().setFile(target).get().use { check ->
                if (check.entries.asSequence().count() != retainedEntries.size + additions.size)
                    throw IOException("Batched ZIP directory verification failed")
                for (entry in retainedEntries) {
                    checkCancelled()
                    val copied = check.getEntry(entry.name) ?: throw IOException("Batched ZIP lost an existing entry")
                    if (copied.crc != entry.crc || copied.size != entry.size || copied.method != entry.method || copied.unixMode != entry.unixMode)
                        throw IOException("Existing ZIP metadata changed")
                }
                for (addition in additions) {
                    checkCancelled()
                    val entry = check.getEntry(addition.path + if (addition.directory) "/" else "")
                        ?: throw IOException("Batched ZIP addition missing")
                    if (!addition.directory) {
                        val expected = addition.content?.inputStream()?.use(ContentRevision::read) ?: ContentRevision.of(byteArrayOf())
                        if (check.getInputStream(entry).use(ContentRevision::read) != expected)
                            throw IOException("Batched ZIP addition failed verification: ${addition.path}")
                    }
                }
            }
        }
    }

    private fun archiveComment(file: File): ByteArray = RandomAccessFile(file, "r").use { input ->
        val size = minOf(input.length(), 65557L).toInt()
        val bytes = ByteArray(size)
        input.seek(input.length() - size); input.readFully(bytes)
        for (i in size - 22 downTo 0) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 5.toByte() && bytes[i + 3] == 6.toByte()) {
                val length = (bytes[i + 20].toInt() and 255) or ((bytes[i + 21].toInt() and 255) shl 8)
                if (i + 22 + length == size) {
                    var central = 0L
                    for (b in 0..3) central = central or ((bytes[i + 16 + b].toLong() and 255) shl (8 * b))
                    if (central in 16 until input.length()) {
                        val magic = ByteArray(16); input.seek(central - 16); input.readFully(magic)
                        if (magic.contentEquals("APK Sig Block 42".toByteArray(Charsets.US_ASCII)))
                            throw IOException("Signed APK containers are read-only")
                    }
                    return@use bytes.copyOfRange(i + 22, size)
                }
            }
        }
        throw IOException("ZIP end record is unavailable; split or damaged archives are read-only")
    }
}
