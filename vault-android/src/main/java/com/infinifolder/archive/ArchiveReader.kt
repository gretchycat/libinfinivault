package com.infinifolder.archive

import android.net.Uri
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import com.infinifolder.storage.FileMagicDetector
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

class ArchiveReader(private val tempDir: File, private val diskCache: com.infinifolder.storage.DiskCache? = null) {

    private val discImageReader = DiscImageReader(tempDir, diskCache)
    internal fun <T> withFreshReads(block: () -> T): T = discImageReader.withFreshReads(block)
    private val nativeReader = NativeArchiveReader(tempDir)

    private fun nativeVolumes(name: String, input: () -> InputStream, local: File?, device: BlockDevice?, supplied: ArchiveVolumeSet?): ArchiveVolumeSet {
        if (supplied != null) return supplied
        return ArchiveVolumeSet(listOf(name), "") {
            when {
                device != null -> object : BlockDevice by device { override fun close() {} }
                local != null -> FileBlockDevice(java.io.RandomAccessFile(local, "r"), true, ownsFile = true)
                else -> {
                    val staged = materializeTempArchive(input)
                    val backing = FileBlockDevice(java.io.RandomAccessFile(staged, "r"), true, ownsFile = true)
                    object : BlockDevice by backing { override fun close() { try { backing.close() } finally { staged.delete() } } }
                }
            }
        }
    }

    private fun resolveFormat(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?
    ): Pair<String, Boolean> {
        val rawExt = ArchiveFormats.containerExtension(archiveName)
        if (!FileMagicDetector.isAmbiguousOrUnknown(archiveName, "application/x-known")) {
            return rawExt to ArchiveFormats.isDiscImage(archiveName)
        }
        val magic = if (localFile != null && localFile.exists()) {
            FileMagicDetector.detect(localFile, peekCompressedPayload = true)
        } else {
            FileMagicDetector.detectFromStream(openStream)
        }
        return when (magic?.mimeType) {
            "application/x-iso9660-image", "application/x-raw-disk-image",
            "application/x-virtualbox-vdi", "application/x-qemu-disk",
            "application/x-vmdk", "application/x-vhd", "application/x-vhdx" -> rawExt to true
            "application/vnd.rar", "application/x-rar-compressed" -> "rar" to false
            "application/x-arj" -> "arj" to false
            "application/vnd.ms-cab-compressed", "application/x-cabinet" -> "cab" to false
            "application/x-7z-compressed" -> "7z" to false
            "application/zip" -> "zip" to false
            "application/x-tar" -> (if (rawExt in setOf("gz", "bz2", "bz", "xz", "lzma", "zst")) "tar.$rawExt" else "tar") to false
            "application/x-cpio" -> "cpio" to false
            "application/x-archive" -> "ar" to false
            else -> rawExt to ArchiveFormats.isDiscImage(archiveName)
        }
    }

    fun fileSystemInfo(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File? = null, device: BlockDevice? = null): com.infinifolder.storage.FileSystemInfo? {
        if (!resolveFormat(archiveName, openStream, localFile).second) return null
        return discImageReader.fileSystemInfo(archiveName, entryPath, openStream, localFile, device)
    }

    fun isWritable(archiveName: String, localFile: File?, device: BlockDevice? = null, entryPath: String? = null): Boolean =
        writableFailureReason(archiveName, localFile, device, entryPath) == null

    fun compressedWritableFailureReason(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File?, device: BlockDevice?): String? =
        discImageReader.compressedWritableFailureReason(archiveName, entryPath, openStream, localFile, device)

    fun readTransferMetadata(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File?, device: BlockDevice?) = discImageReader.readTransferMetadata(archiveName, entryPath, openStream, localFile, device)
    fun applyTransferMetadata(archiveName: String, localFile: File?, device: BlockDevice?, entryPath: String,
        metadata: com.infinifolder.model.TransferMetadata) = discImageReader.applyTransferMetadata(archiveName, localFile, device, entryPath, metadata)

    fun writableFailureReason(archiveName: String, localFile: File?, device: BlockDevice? = null, entryPath: String? = null, probeOnly: Boolean = false): String? =
        if (NativeArchiveReader.isSupported(archiveName)) "RAR/ARJ/CAB archive views are read-only"
        else discImageReader.writableFailureReason(archiveName, localFile, device, entryPath, probeOnly)

    fun checkMounted(archiveName: String, localFile: File?): MountCheckResult =
        MountedPartitionDetector.checkMounted(localFile)

    fun createFile(archiveName: String, localFile: File?, entryPath: String, data: ByteArray, device: BlockDevice? = null) =
        discImageReader.createFile(archiveName, localFile, entryPath, data, device)

    fun createDirectory(archiveName: String, localFile: File?, entryPath: String, device: BlockDevice? = null) =
        discImageReader.createDirectory(archiveName, localFile, entryPath, device)

    fun writeEntry(archiveName: String, localFile: File?, entryPath: String, data: ByteArray, device: BlockDevice? = null) {
        val effectiveDev = device ?: localFile?.let { if (it.exists() && it.canWrite()) FileBlockDevice(java.io.RandomAccessFile(it, "rw"), false) else null }
        if (effectiveDev != null && (ArchiveFormats.containerExtension(archiveName) == "zip")) {
            if (ZipInPlaceModifier.canModifyInPlace(effectiveDev, entryPath)) {
                ZipInPlaceModifier.modifyStoredEntry(effectiveDev, entryPath, data)
                return
            }
        }
        discImageReader.writeEntry(archiveName, localFile, entryPath, data, device)
    }

    fun writeEntryFromFile(archiveName: String, localFile: File?, entryPath: String, content: File, device: BlockDevice? = null) =
        discImageReader.writeEntryFromFile(archiveName, localFile, entryPath, content, device)

    internal fun relocateEntry(archiveName: String, localFile: File?, entryPath: String, targetPath: String, device: BlockDevice?) =
        discImageReader.relocateEntry(archiveName, localFile, entryPath, targetPath, device)
    internal fun sameRelocationFilesystem(archiveName: String, localFile: File?, device: BlockDevice?, paths: List<String>) =
        discImageReader.sameRelocationFilesystem(archiveName, localFile, device, paths)

    fun deleteEntry(archiveName: String, localFile: File?, entryPath: String, device: BlockDevice? = null) =
        discImageReader.deleteEntry(archiveName, localFile, entryPath, device)

    fun getEntryBlockDevice(
        archiveName: String,
        entryPath: String,
        device: BlockDevice
    ): BlockDevice? {
        val cleanTarget = normalizePath(entryPath)
        val ext = ArchiveFormats.containerExtension(archiveName)
        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            try {
                ZipFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { zf ->
                    val entry = zipEntry(zf, cleanTarget)
                    if (entry != null && entry.method == org.apache.commons.compress.archivers.zip.ZipArchiveEntry.STORED && entry.size >= 0) {
                        val localOffset = entry.localHeaderOffset
                        val lfh = device.readBytes(localOffset, 30)
                        if (lfh.size >= 30) {
                            val nLen = (lfh[26].toInt() and 0xFF) or ((lfh[27].toInt() and 0xFF) shl 8)
                            val eLen = (lfh[28].toInt() and 0xFF) or ((lfh[29].toInt() and 0xFF) shl 8)
                            val dataOffset = localOffset + 30L + nLen + eLen
                            return PartitionSliceBlockDevice(device, dataOffset, entry.size, device.isReadOnly)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val slice = discImageReader.getPartitionSlice(device, cleanTarget)
        if (slice != null) return slice
        return null
    }

    private data class RawEntry(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val modifiedAtMillis: Long?,
        val isSymbolicLink: Boolean = false,
        val linkTarget: String? = null,
        val permissionsMode: Int? = null,
        val owner: String? = null,
        val group: String? = null,
        val readError: String? = null,
        val recoveryNote: String? = null,
        val partialRecoveryAvailable: Boolean = false
    )

    /** Prefer exact ZIP names; case-insensitive fallback must identify a single member. */
    private fun zipEntry(zip: ZipFile, target: String): ZipArchiveEntry? {
        val exact = zip.getEntries(target).asSequence().take(2).toList()
        if (exact.size > 1) throw java.io.IOException("Ambiguous duplicate ZIP entry: $target")
        exact.singleOrNull()?.let { return it }
        val normalized = zip.entries.asSequence().filter { normalizePath(it.name) == target }.take(2).toList()
        if (normalized.size > 1) throw java.io.IOException("Ambiguous normalized ZIP entry: $target")
        normalized.singleOrNull()?.let { return it }
        val folded = zip.entries.asSequence().filter { normalizePath(it.name).equals(target, ignoreCase = true) }.take(2).toList()
        if (folded.size > 1) throw java.io.IOException("Ambiguous ZIP entry case: $target")
        return folded.singleOrNull()
    }

    private fun normalizePath(rawName: String): String =
        rawName.replace('\\', '/').trim().removePrefix("/").removePrefix("./").trim('/')

    fun supportsFatRecovery(name: String, input: () -> InputStream, file: File? = null, device: BlockDevice? = null) =
        discImageReader.supportsFatRecovery(name, input, file, device)

    fun buildDeletedFatTree(source: Uri, name: String, input: () -> InputStream, file: File? = null, device: BlockDevice? = null): ArchiveTree {
        val entries = discImageReader.readDeletedFatEntries(name, input, file, device)
        return buildTreeFromRawEntries(source, entries.map {
            RawEntry(it.path, it.isDirectory, it.sizeBytes, it.modifiedAtMillis, readError = it.readError, recoveryNote = it.recoveryNote, partialRecoveryAvailable = it.partialExtractor != null)
        }, listOf("Deleted files — read-only, unverified recovery candidates. Names/content may be incomplete or overwritten. Multi-cluster files assume contiguous allocation. Deleted directory trees are not scanned. Undelete copies to a separate location."))
    }
    fun openDeletedFatEntry(name: String, path: String, input: () -> InputStream, file: File? = null, device: BlockDevice? = null) =
        discImageReader.openDeletedFatEntry(name, path, input, file, device)

    fun openPartialDeletedFatEntry(name: String, path: String, input: () -> InputStream, file: File? = null, device: BlockDevice? = null) =
        discImageReader.openPartialDeletedFatEntry(name, path, input, file, device)

    fun buildTree(
        sourceUri: Uri?,
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null,
        volumes: ArchiveVolumeSet? = null,
        directoryPath: String? = null
    ): ArchiveTree {
        if (NativeArchiveReader.isSupported(archiveName) || resolveFormat(archiveName, openStream, localFile).first in setOf("rar", "arj", "cab")) {
            val catalog = nativeReader.catalog(nativeVolumes(archiveName, openStream, localFile, device, volumes))
            return buildTreeFromRawEntries(sourceUri, catalog.entries.map {
                RawEntry(it.path, it.directory, it.size, it.modified, it.link != null, it.link, it.mode, readError = it.error)
            }, catalog.warnings)
        }
        val rawEntries = readEntries(archiveName, openStream, localFile, device, directoryPath)
        return buildTreeFromRawEntries(sourceUri, rawEntries)
    }

    private fun readEntries(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?,
        device: BlockDevice? = null,
        directoryPath: String? = null
    ): List<RawEntry> {
        val effectiveDev = device ?: localFile?.let { if (it.exists()) FileBlockDevice(java.io.RandomAccessFile(it, "r"), true) else null }
        val (ext, isDisc) = resolveFormat(archiveName, openStream, localFile)
        if (isDisc) {
            val discEntries = if (directoryPath == null) discImageReader.readEntries(archiveName, openStream, localFile, effectiveDev)
                else discImageReader.readDirectoryEntries(archiveName, directoryPath, openStream, localFile, effectiveDev)
            return discEntries.map {
                RawEntry(
                    name = it.path,
                    isDirectory = it.isDirectory,
                    sizeBytes = it.sizeBytes,
                    modifiedAtMillis = it.modifiedAtMillis,
                    isSymbolicLink = it.isSymbolicLink,
                    linkTarget = it.linkTarget,
                    permissionsMode = it.permissionsMode, owner = it.owner, group = it.group
                )
            }
        }

        if (ArchiveFormats.isPlaylist(archiveName)) {
            val content = openStream().use { it.bufferedReader(Charsets.UTF_8).readText() }
            val playlist = com.infinifolder.playlist.PlaylistParser.parse(archiveName, content)
            val entries = mutableListOf<RawEntry>()
            for ((idx, item) in playlist.withIndex()) {
                val numPrefix = String.format(java.util.Locale.US, "%02d. ", idx + 1)
                val targetExt = if (item.pathOrUri.endsWith(".bin", ignoreCase = true)) "cdda" else item.pathOrUri.substringAfterLast('.', "")
                val cleanTitle = item.title.replace('/', '-').replace('\\', '-').trim()
                val entryName = if (targetExt.isNotEmpty() && !cleanTitle.endsWith(".$targetExt", ignoreCase = true)) {
                    "$numPrefix$cleanTitle.$targetExt"
                } else if (cleanTitle.startsWith(numPrefix)) {
                    cleanTitle
                } else {
                    "$numPrefix$cleanTitle"
                }
                entries.add(
                    RawEntry(
                        name = entryName,
                        isDirectory = false,
                        sizeBytes = if (item.lengthBytes > 0) item.lengthBytes else null,
                        modifiedAtMillis = System.currentTimeMillis(),
                        isSymbolicLink = true,
                        linkTarget = item.pathOrUri
                    )
                )
            }
            return entries
        }

        if (ext == "zst") {
            var size = 0L
            ZstdCompressorInputStream(BufferedInputStream(openStream())).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Zstandard reading cancelled")
                    size = Math.addExact(size, n.toLong())
                }
            }
            return listOf(RawEntry(zstdPayloadName(archiveName), false, size, null))
        }
        if (ext == "7z" || ext == "cb7") {
            if (effectiveDev != null) {
                return read7zEntries(effectiveDev)
            }
            val file = localFile ?: materializeTempArchive(openStream)
            return try {
                read7zEntries(FileBlockDevice(java.io.RandomAccessFile(file, "r"), true))
            } finally {
                if (localFile == null) file.delete()
            }
        }

        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            if (effectiveDev != null) {
                return readZipEntries(effectiveDev)
            }
            if (localFile != null) {
                return readZipFileEntries(localFile)
            }
        }

        return try {
            openArchiveInputStream(openStream(), ext).use { ais ->
                val list = mutableListOf<RawEntry>()
                var entry = ais.nextEntry
                while (entry != null) {
                    val isSymlink = when (entry) {
                        is TarArchiveEntry -> entry.isSymbolicLink
                        is CpioArchiveEntry -> entry.isSymbolicLink
                        else -> false
                    }
                    val linkTarget = when {
                        entry is TarArchiveEntry && isSymlink -> entry.linkName
                        entry is CpioArchiveEntry && isSymlink -> {
                            if (entry.size !in 1..65536) throw java.io.IOException("Invalid CPIO symbolic-link length")
                            val bytes = ais.readBytes()
                            if (bytes.size.toLong() != entry.size) throw java.io.IOException("Truncated CPIO symbolic link")
                            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                        }
                        else -> null
                    }
                    list += RawEntry(
                        name = entry.name,
                        isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                        sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                        modifiedAtMillis = entry.lastModifiedDate?.time,
                        isSymbolicLink = isSymlink,
                        linkTarget = linkTarget,
                        permissionsMode = (entry as? TarArchiveEntry)?.mode ?: (entry as? CpioArchiveEntry)?.mode?.toInt() ?: (entry as? ZipArchiveEntry)?.let {
                            if (it.platform == ZipArchiveEntry.PLATFORM_UNIX) it.unixMode else null
                        },
                        owner = (entry as? TarArchiveEntry)?.let { it.userName.takeIf(String::isNotBlank) ?: it.longUserId.toString() } ?: (entry as? CpioArchiveEntry)?.uid?.toString(),
                        group = (entry as? TarArchiveEntry)?.let { it.groupName.takeIf(String::isNotBlank) ?: it.longGroupId.toString() } ?: (entry as? CpioArchiveEntry)?.gid?.toString()
                    )
                    entry = ais.nextEntry
                }
                if (list.isEmpty()) {
                    // Check if it is actually a disc image with a generic name/extension
                    try {
                        return discImageReader.readEntries(archiveName, openStream, localFile).map {
                            RawEntry(it.path, it.isDirectory, it.sizeBytes, it.modifiedAtMillis, it.isSymbolicLink, it.linkTarget,
                                it.permissionsMode, it.owner, it.group)
                        }
                    } catch (_: Exception) {}
                }
                list
            }
        } catch (primaryError: Exception) {
            try {
                discImageReader.readEntries(archiveName, openStream, localFile).map {
                    RawEntry(
                        name = it.path,
                        isDirectory = it.isDirectory,
                        sizeBytes = it.sizeBytes,
                        modifiedAtMillis = it.modifiedAtMillis,
                    isSymbolicLink = it.isSymbolicLink,
                    linkTarget = it.linkTarget,
                    permissionsMode = it.permissionsMode, owner = it.owner, group = it.group
                    )
                }
            } catch (_: Exception) {
                throw primaryError
            }
        }
    }

    private fun read7zEntries(device: BlockDevice): List<RawEntry> {
        return SevenZFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { sz ->
            val list = mutableListOf<RawEntry>()
            var entry = sz.nextEntry
            while (entry != null) {
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time
                )
                entry = sz.nextEntry
            }
            list
        }
    }

    private fun read7zEntries(file: File): List<RawEntry> {
        return SevenZFile.builder().setFile(file).get().use { sz ->
            val list = mutableListOf<RawEntry>()
            var entry = sz.nextEntry
            while (entry != null) {
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time
                )
                entry = sz.nextEntry
            }
            list
        }
    }

    private fun readZipEntries(device: BlockDevice): List<RawEntry> {
        return ZipFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { zf ->
            val list = mutableListOf<RawEntry>()
            val en = zf.entries
            while (en.hasMoreElements()) {
                val entry = en.nextElement()
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time,
                    isSymbolicLink = entry.isUnixSymlink,
                    linkTarget = if (entry.isUnixSymlink && entry.size <= 1024 * 1024) zf.getUnixSymlink(entry) else null,
                    permissionsMode = if (entry.platform == ZipArchiveEntry.PLATFORM_UNIX) entry.unixMode else null
                )
            }
            list
        }
    }

    private fun readZipFileEntries(file: File): List<RawEntry> {
        return ZipFile.builder().setFile(file).get().use { zf ->
            val list = mutableListOf<RawEntry>()
            val en = zf.entries
            while (en.hasMoreElements()) {
                val entry = en.nextElement()
                list += RawEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\'),
                    sizeBytes = if (entry.isDirectory) null else entry.size.takeIf { it >= 0 },
                    modifiedAtMillis = entry.lastModifiedDate?.time,
                    isSymbolicLink = entry.isUnixSymlink,
                    linkTarget = if (entry.isUnixSymlink && entry.size <= 1024 * 1024) zf.getUnixSymlink(entry) else null,
                    permissionsMode = if (entry.platform == ZipArchiveEntry.PLATFORM_UNIX) entry.unixMode else null
                )
            }
            list
        }
    }

    fun extractEntry(
        archiveName: String,
        entryPath: String,
        targetFile: File,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null,
        volumes: ArchiveVolumeSet? = null
    ): File {
        if (NativeArchiveReader.isSupported(archiveName) || resolveFormat(archiveName, openStream, localFile).first in setOf("rar", "arj", "cab")) {
            val stream = nativeReader.openEntry(nativeVolumes(archiveName, openStream, localFile, device, volumes), normalizePath(entryPath))
            return stream.use { input -> targetFile.outputStream().use { input.copyTo(it) }; targetFile }
        }
        if (ArchiveFormats.isPlaylist(archiveName)) {
            val stream = openEntryStream(archiveName, entryPath, openStream, localFile, device, volumes)
            targetFile.parentFile?.mkdirs()
            return stream.use { input -> targetFile.outputStream().use { input.copyTo(it) }; targetFile }
        }
        if (resolveFormat(archiveName, openStream, localFile).first == "zst") {
            if (normalizePath(entryPath) != zstdPayloadName(archiveName)) throw java.io.IOException("Compressed file entry not found")
            val stream = ZstdCompressorInputStream(BufferedInputStream(openStream()))
            return stream.use { input -> targetFile.outputStream().use { input.copyTo(it) }; targetFile }
        }
        val cleanTarget = normalizePath(entryPath)
        targetFile.parentFile?.mkdirs()
        val effectiveDev = device ?: localFile?.let { if (it.exists()) FileBlockDevice(java.io.RandomAccessFile(it, "r"), true) else null }

        val (ext, isDisc) = resolveFormat(archiveName, openStream, localFile)
        if (isDisc) {
            return discImageReader.extractEntry(archiveName, cleanTarget, targetFile, openStream, localFile, effectiveDev)
        }

        if (ext == "7z" || ext == "cb7") {
            if (effectiveDev != null) {
                SevenZFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get().use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val norm = normalizePath(entry.name)
                        if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                            targetFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var count: Int
                                while (sz.read(buffer).also { count = it } > 0) {
                                    out.write(buffer, 0, count)
                                }
                            }
                            return targetFile
                        }
                        entry = sz.nextEntry
                    }
                }
                throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
            }
            val file = localFile ?: materializeTempArchive(openStream)
            try {
                SevenZFile.builder().setFile(file).get().use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val norm = normalizePath(entry.name)
                        if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                            targetFile.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var count: Int
                                while (sz.read(buffer).also { count = it } > 0) {
                                    out.write(buffer, 0, count)
                                }
                            }
                            return targetFile
                        }
                        entry = sz.nextEntry
                    }
                }
            } finally {
                if (localFile == null) file.delete()
            }
            throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
        }

        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            if (effectiveDev != null) {
                ZipFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get().use { zf ->
                    val entry = zipEntry(zf, cleanTarget)
                        ?: throw NoSuchElementException("Entry not found in zip archive: $entryPath")
                    zf.getInputStream(entry).use { inStream ->
                        targetFile.outputStream().use { inStream.copyTo(it) }
                    }
                }
                return targetFile
            }
            if (localFile != null) {
                ZipFile.builder().setFile(localFile).get().use { zf ->
                    val entry = zipEntry(zf, cleanTarget)
                        ?: throw NoSuchElementException("Entry not found in zip archive: $entryPath")
                    zf.getInputStream(entry).use { inStream ->
                        targetFile.outputStream().use { inStream.copyTo(it) }
                    }
                }
                return targetFile
            }
        }

        try {
            openArchiveInputStream(openStream(), ext).use { ais ->
                var entry = ais.nextEntry
                while (entry != null) {
                    val norm = normalizePath(entry.name)
                    if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                        targetFile.outputStream().use { ais.copyTo(it) }
                        return targetFile
                    }
                    entry = ais.nextEntry
                }
            }
        } catch (primaryError: Exception) {
            return try {
                discImageReader.extractEntry(archiveName, cleanTarget, targetFile, openStream, localFile, effectiveDev)
            } catch (_: Exception) {
                throw primaryError
            }
        }
        return try {
            discImageReader.extractEntry(archiveName, cleanTarget, targetFile, openStream, localFile, effectiveDev)
        } catch (_: Exception) {
            throw NoSuchElementException("Entry not found in archive: $entryPath")
        }
    }

    /**
     * Reads a single entry's bytes directly in-memory without saving anything to the cache directory.
     */
    fun openEntryStream(
        archiveName: String,
        entryPath: String,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null,
        volumes: ArchiveVolumeSet? = null
    ): InputStream {
        if (NativeArchiveReader.isSupported(archiveName) || resolveFormat(archiveName, openStream, localFile).first in setOf("rar", "arj", "cab")) {
            val stream = nativeReader.openEntry(nativeVolumes(archiveName, openStream, localFile, device, volumes), normalizePath(entryPath))
            return stream
        }
        val cleanTarget = normalizePath(entryPath)
        if (ArchiveFormats.isPlaylist(archiveName)) {
            val content = openStream().use { it.bufferedReader(Charsets.UTF_8).readText() }
            val playlist = com.infinifolder.playlist.PlaylistParser.parse(archiveName, content)
            val matched = playlist.firstOrNull {
                val cleanTitle = it.title.replace('/', '-').replace('\\', '-').trim()
                cleanTarget.contains(cleanTitle, ignoreCase = true) || cleanTarget.equals(it.pathOrUri, ignoreCase = true) || cleanTarget.endsWith(it.pathOrUri, ignoreCase = true)
            }
            val target = matched?.pathOrUri ?: cleanTarget
            if (target.contains("://")) {
                return java.net.URL(target).openStream()
            }
            if (localFile != null) {
                val parentFile = localFile.parentFile
                var f = File(parentFile, target).canonicalFile
                if (!f.exists()) {
                    val sibling = File(parentFile, File(target).name)
                    if (sibling.exists()) {
                        f = sibling.canonicalFile
                    } else {
                        val parentParent = parentFile?.parentFile
                        val audioSibling = if (parentParent != null) File(parentParent, "audio/${File(target).name}") else null
                        if (audioSibling?.exists() == true) {
                            f = audioSibling.canonicalFile
                        } else {
                            val audioSub = File(parentFile, "audio/${File(target).name}")
                            if (audioSub.exists()) {
                                f = audioSub.canonicalFile
                            }
                        }
                    }
                }
                if (f.exists()) {
                    val rawStream = f.inputStream()
                    if (matched != null && (matched.startByte > 0L || matched.lengthBytes > 0L)) {
                        if (matched.startByte > 0L) {
                            rawStream.skip(matched.startByte)
                        }
                        if (matched.lengthBytes > 0L) {
                            val limit = matched.lengthBytes
                            return object : java.io.InputStream() {
                                private var readCount = 0L
                                override fun read(): Int {
                                    if (readCount >= limit) return -1
                                    val b = rawStream.read()
                                    if (b != -1) readCount++
                                    return b
                                }
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    if (readCount >= limit) return -1
                                    val toRead = minOf(len.toLong(), limit - readCount).toInt()
                                    val r = rawStream.read(b, off, toRead)
                                    if (r > 0) readCount += r
                                    return r
                                }
                                override fun close() { rawStream.close() }
                            }
                        }
                    }
                    return rawStream
                }
            }
            throw java.io.FileNotFoundException("Target media file not found: $target")
        }
        if (resolveFormat(archiveName, openStream, localFile).first == "zst") {
            if (normalizePath(entryPath) != zstdPayloadName(archiveName)) throw java.io.IOException("Compressed file entry not found")
            val stream = ZstdCompressorInputStream(BufferedInputStream(openStream()))
            return stream
        }
        val effectiveDev = device ?: localFile?.let { if (it.exists()) FileBlockDevice(java.io.RandomAccessFile(it, "r"), true) else null }
        val (ext, isDisc) = resolveFormat(archiveName, openStream, localFile)
        if (isDisc) {
            return discImageReader.openEntryStream(archiveName, cleanTarget, openStream, localFile, effectiveDev)
        }

        if (ext == "7z" || ext == "cb7") {
            if (effectiveDev != null) {
                val sz = SevenZFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get()
                try {
                    var entry = sz.nextEntry
                    while (entry != null) {
                        val norm = normalizePath(entry.name)
                        if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                            val content = ByteArray(entry.size.toInt().coerceAtLeast(0))
                            var read = 0
                            while (read < content.size) {
                                val count = sz.read(content, read, content.size - read)
                                if (count <= 0) break
                                read += count
                            }
                            return java.io.ByteArrayInputStream(content)
                        }
                        entry = sz.nextEntry
                    }
                } finally {
                    sz.close()
                }
                throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
            }
            val file = localFile ?: materializeTempArchive(openStream)
            val sz = SevenZFile.builder().setFile(file).get()
            try {
                var entry = sz.nextEntry
                while (entry != null) {
                    val norm = normalizePath(entry.name)
                    if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                        val content = ByteArray(entry.size.toInt().coerceAtLeast(0))
                        var read = 0
                        while (read < content.size) {
                            val count = sz.read(content, read, content.size - read)
                            if (count <= 0) break
                            read += count
                        }
                        return java.io.ByteArrayInputStream(content)
                    }
                    entry = sz.nextEntry
                }
            } finally {
                sz.close()
                if (localFile == null) file.delete()
            }
            throw NoSuchElementException("Entry not found in 7z archive: $entryPath")
        }

        if (ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz") {
            val zip = when {
                effectiveDev != null -> ZipFile.builder().setSeekableByteChannel(effectiveDev.asSeekableByteChannel()).get()
                localFile != null -> ZipFile.builder().setFile(localFile).get()
                else -> null
            }
            if (zip != null) {
                try {
                    val entry = zipEntry(zip, cleanTarget)
                        ?: throw NoSuchElementException("Entry not found in ZIP: $entryPath")
                    return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
                        override fun close() { try { super.close() } finally { zip.close() } }
                    }
                } catch (e: Exception) { zip.close(); throw e }
            }
        }

        try {
            openArchiveInputStream(openStream(), ext).use { ais ->
                var entry = ais.nextEntry
                while (entry != null) {
                    val norm = normalizePath(entry.name)
                    if (norm == cleanTarget || norm.equals(cleanTarget, ignoreCase = true)) {
                        val bytes = ais.readBytes()
                        return java.io.ByteArrayInputStream(bytes)
                    }
                    entry = ais.nextEntry
                }
            }
        } catch (primaryError: Exception) {
            return try {
                discImageReader.openEntryStream(archiveName, cleanTarget, openStream, localFile)
            } catch (_: Exception) {
                throw primaryError
            }
        }
        return try {
            discImageReader.openEntryStream(archiveName, cleanTarget, openStream, localFile)
        } catch (_: Exception) {
            throw NoSuchElementException("Entry not found in archive: $entryPath")
        }
    }

    private fun zstdPayloadName(name: String): String = name.substringBeforeLast('.').substringAfterLast('/').takeIf { it.isNotBlank() } ?: "decompressed-file"

    private fun openArchiveInputStream(raw: InputStream, ext: String): ArchiveInputStream<*> {
        val bis = BufferedInputStream(raw)
        return when {
            ext == "tar.gz" || ext == "tgz" ->
                TarArchiveInputStream(GzipCompressorInputStream(bis))
            ext == "tar.z" || ext == "taz" -> TarArchiveInputStream(ZCompressorInputStream(bis))
            ext == "tar.bz2" || ext == "tbz" || ext == "tbz2" || ext == "tar.bz" ->
                TarArchiveInputStream(BZip2CompressorInputStream(bis))
            ext == "tar.xz" || ext == "txz" ->
                TarArchiveInputStream(XZCompressorInputStream(bis))
            ext == "tar.zst" || ext == "tzst" -> TarArchiveInputStream(ZstdCompressorInputStream(bis))
            ext == "tar.lzma" || ext == "tlz" ->
                TarArchiveInputStream(LZMACompressorInputStream(bis))
            ext == "tar" || ext == "cbt" ->
                TarArchiveInputStream(bis)
            ext == "zip" || ext == "jar" || ext == "apk" || ext == "aar" || ext == "cbz" ->
                ZipArchiveInputStream(bis)
            ext == "cpio" ->
                CpioArchiveInputStream(bis, "UTF-8")
            ext == "ar" || ext == "deb" ->
                ArArchiveInputStream(bis)
            else -> {
                // Try decompressor first, then archiver
                val decompressed = try {
                    CompressorStreamFactory().createCompressorInputStream(bis)
                } catch (_: Exception) {
                    bis
                }
                val bufferedDecompressed = BufferedInputStream(decompressed)
                try {
                    ArchiveStreamFactory("UTF-8").createArchiveInputStream(bufferedDecompressed)
                } catch (_: Exception) {
                    // Fall back to TarArchiveInputStream or ZipArchiveInputStream
                    TarArchiveInputStream(bufferedDecompressed)
                }
            }
        }
    }

    private fun materializeTempArchive(openStream: () -> InputStream): File {
        if (diskCache != null) {
            val lease = diskCache.stage("archive.tmp", input = openStream)
            // Existing readers delete this transient source at close; the manager drops deleted entries.
            return lease.file
        }
        tempDir.mkdirs()
        val temp = File.createTempFile("inf_archive_", ".tmp", tempDir)
        openStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return temp
    }

    private fun buildTreeFromRawEntries(sourceUri: Uri?, rawEntries: List<RawEntry>, warnings: List<String> = emptyList()): ArchiveTree {
        val intermediateDirs = mutableMapOf<String, ArchiveEntry>()
        val directChildren = mutableMapOf<String, MutableMap<String, ArchiveEntry>>()
        val allEntriesByPath = mutableMapOf<String, ArchiveEntry>()

        fun ensureParentDir(parentPath: String, childName: String, dirEntry: ArchiveEntry) {
            directChildren.getOrPut(parentPath) { mutableMapOf() }[childName] = dirEntry
        }

        for (entry in rawEntries) {
            val clean = normalizePath(entry.name)
            if (clean.isEmpty()) continue
            val isDir = entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\')
            val segments = clean.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue

            var currPath = ""
            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                val nextPath = if (currPath.isEmpty()) seg else "$currPath/$seg"
                if (!intermediateDirs.containsKey(nextPath)) {
                    val synthetic = ArchiveEntry(
                        path = nextPath,
                        name = seg,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = null,
                        mimeType = ArchiveMimeTypes.MIME_TYPE_DIR
                    )
                    intermediateDirs[nextPath] = synthetic
                    allEntriesByPath[nextPath] = synthetic
                    ensureParentDir(currPath, seg, synthetic)
                }
                currPath = nextPath
            }

            val leafName = segments.last()
            val leafEntry = ArchiveEntry(
                path = clean,
                name = leafName,
                isDirectory = isDir,
                sizeBytes = if (isDir) null else entry.sizeBytes,
                modifiedAtMillis = entry.modifiedAtMillis,
                mimeType = ArchiveMimeTypes.guessMimeType(leafName, isDir),
                isSymbolicLink = entry.isSymbolicLink,
                linkTarget = entry.linkTarget,
                permissionsMode = entry.permissionsMode, owner = entry.owner, group = entry.group, readError = entry.readError, recoveryNote = entry.recoveryNote, partialRecoveryAvailable = entry.partialRecoveryAvailable
            )

            if (isDir) {
                intermediateDirs[clean] = leafEntry
            }
            allEntriesByPath[clean] = leafEntry
            ensureParentDir(currPath, leafName, leafEntry)
        }

        val entriesByParent = directChildren.mapValues { (_, map) -> map.values.toList() }
        return ArchiveTree(sourceUri, entriesByParent, allEntriesByPath, warnings)
    }
}
