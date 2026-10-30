package com.infinifolder.archive

import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.StorageSafety
import java.io.File
import java.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/** Rebuild into a private file, preserving entry metadata and checking every retained payload. */
internal object TarContainerEditor {
    private fun path(name: String): String {
        if (name.startsWith('/') || '\\' in name) throw IOException("TAR contains an unsafe or ambiguous path; original retained")
        var clean = name.removeSuffix("/")
        while (clean.startsWith("./")) clean = clean.removePrefix("./")
        if (clean == ".") return ""
        if (clean.isNotEmpty()) clean.split('/').forEach(StorageSafety::validateName)
        return clean
    }
    private data class Signature(val size: Long, val mode: Int, val uid: Long, val gid: Long,
        val user: String, val group: String, val time: java.nio.file.attribute.FileTime,
        val type: Byte, val link: String, val major: Int, val minor: Int, val content: ContentRevision)

    private fun signature(entry: TarArchiveEntry, stream: java.io.InputStream): Signature =
        Signature(entry.size, entry.mode, entry.longUserId, entry.longGroupId, entry.userName, entry.groupName,
            entry.lastModifiedTime, entry.linkFlag, entry.linkName, entry.devMajor, entry.devMinor,
            ContentRevision.read(stream))

    private fun validate(entry: TarArchiveEntry, input: TarArchiveInputStream) {
        path(entry.name)
        if (!entry.isCheckSumOK || !input.canReadEntryData(entry) || entry.isSparse ||
            !(entry.isFile || entry.isDirectory || entry.isSymbolicLink || entry.isLink ||
                entry.isBlockDevice || entry.isCharacterDevice || entry.isFIFO))
            throw IOException("TAR entry '${entry.name}' uses unsupported or damaged metadata; original retained")
    }

    fun rebuild(original: File, target: File, name: String, additions: List<ZipContainerEditor.Addition>,
        removals: Set<String>, cancelled: () -> Boolean, reserved: Long) {
        val codec = ContainerCompression.forName(name)
        fun <T> withTar(file: File, action: (TarArchiveInputStream) -> T): T {
            file.inputStream().buffered().use { raw ->
                val decoded = codec?.input(raw) ?: raw
                decoded.use { stream ->
                    TarArchiveInputStream(stream, 512).use { tar ->
                        val result = action(tar)
                        // Drain the codec to validate its checksum. Nonzero trailing data
                        // could be another concatenated TAR: never silently discard it.
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled()) throw InterruptedException("Container operation cancelled")
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if ((0 until n).any { buffer[it] != 0.toByte() })
                                throw IOException("TAR has trailing data or concatenated archives; original retained")
                        }
                        return result
                    }
                }
            }
        }
        fun removed(key: String) = removals.any { key == it || key.startsWith("$it/") }
        val expected = linkedMapOf<String, Signature>()
        val names = mutableSetOf<String>()
        val replaced = mutableMapOf<String, TarArchiveEntry>()
        val additionNames = additions.map { path(it.path) }.toSet()
        if (additionNames.size != additions.size) throw IOException("Duplicate staged TAR paths")
        val links = mutableListOf<Pair<String, String>>()
        // LZMA needs the uncompressed size; build TAR first, then compress once.
        val raw = if (codec == null) target else File(target.parentFile, "updated.tar")
        try {
            val guarded = ContainerSpace.guarded(raw.outputStream().buffered(), target.parentFile!!, reserved, cancelled)
            TarArchiveOutputStream(guarded).use { output ->
                output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                output.setAddPaxHeadersForNonAsciiNames(true)
                withTar(original) { source ->
                    while (true) {
                        if (cancelled()) throw InterruptedException("Container operation cancelled")
                        val entry = source.nextTarEntry ?: break
                        validate(entry, source)
                        val key = path(entry.name)
                        if (!names.add(key)) throw IOException("TAR contains duplicate paths; original retained")
                        if (removed(key)) {
                            if (key in additionNames) {
                                if (!entry.isFile || entry.isSymbolicLink || entry.isLink)
                                    throw IOException("Only ordinary TAR files can be replaced")
                                replaced[key] = entry
                            }
                            continue
                        }
                        if (entry.isLink) links += key to path(entry.linkName)
                        output.putArchiveEntry(entry)
                        // Hash while copying so verification includes retained file contents.
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancelled()) throw InterruptedException("Container operation cancelled")
                            val n = source.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n); digest.update(buffer, 0, n); size += n
                        }
                        output.closeArchiveEntry()
                        expected[key] = Signature(entry.size, entry.mode, entry.longUserId, entry.longGroupId,
                            entry.userName, entry.groupName, entry.lastModifiedTime, entry.linkFlag,
                            entry.linkName, entry.devMajor, entry.devMinor, ContentRevision(size, digest.digest().joinToString("") { "%02x".format(it) }))
                    }
                }
                for (removedPath in removals) if (names.none { it == removedPath || it.startsWith("$removedPath/") })
                    throw IOException("TAR removal target is unavailable: $removedPath")
                for ((link, targetPath) in links) if (removed(targetPath) && targetPath !in additionNames)
                    throw IOException("Cannot remove '$targetPath': retained TAR hardlink '$link' depends on it")
                for (addition in additions) {
                    val key = path(addition.path)
                    if (!names.add(key) && key !in replaced) throw IOException("TAR entry already exists: $key")
                    var ancestor = key.substringBeforeLast('/', "")
                    while (ancestor.isNotEmpty()) {
                        val existing = expected[ancestor]
                        if (existing != null && existing.type != '5'.code.toByte())
                            throw IOException("TAR destination parent is not an ordinary directory: $ancestor")
                        ancestor = ancestor.substringBeforeLast('/', "")
                    }
                    val entry = replaced[key] ?: TarArchiveEntry(key + if (addition.directory) "/" else "").apply {
                        mode = if (addition.directory) 0x1ed else 0x1a4
                        setModTime(System.currentTimeMillis() / 1000 * 1000)
                    }
                    entry.size = addition.content?.length() ?: 0
                    output.putArchiveEntry(entry)
                    addition.content?.inputStream()?.use { ContainerSpace.copy(it, output, target.parentFile!!, reserved, cancelled) }
                    output.closeArchiveEntry()
                    val content = addition.content?.inputStream()?.use(ContentRevision::read) ?: ContentRevision.of(byteArrayOf())
                    expected[key] = Signature(entry.size, entry.mode, entry.longUserId, entry.longGroupId,
                        entry.userName, entry.groupName, entry.lastModifiedTime, entry.linkFlag, entry.linkName,
                        entry.devMajor, entry.devMinor, content)
                }
            }
            if (codec != null) {
                ContainerSpace.require(target.parentFile!!, raw.length() + raw.length() / 50 + 1024 * 1024, reserved)
                codec.output(ContainerSpace.guarded(target.outputStream().buffered(), target.parentFile!!, reserved, cancelled), raw.length()).use { output ->
                    raw.inputStream().use { ContainerSpace.copy(it, output, target.parentFile!!, reserved, cancelled) }
                }
            }
            val actual = linkedMapOf<String, Signature>()
            withTar(target) { check ->
                while (true) {
                    if (cancelled()) throw InterruptedException("Container operation cancelled")
                    val entry = check.nextTarEntry ?: break
                    validate(entry, check)
                    val key = path(entry.name)
                    if (actual.put(key, signature(entry, check)) != null) throw IOException("Updated TAR has duplicate paths")
                }
            }
            if (actual != expected) throw IOException("Updated TAR failed content/metadata verification; original retained")
        } finally { if (raw != target) raw.delete() }
    }
}
