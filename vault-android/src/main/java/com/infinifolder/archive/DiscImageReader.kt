package com.infinifolder.archive

import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin user-space reader for raw and compressed filesystem disc images:
 * - ISO 9660 + Joliet + Rock Ridge (.iso, .cdr)
 * - FAT12 / FAT16 / FAT32 + VFAT Long File Names (.fat, .vfat, .img, .ima, .flp)
 * - exFAT (.exfat, .img)
 * - ext2 / ext3 / ext4 with both direct/indirect blocks and ext4 extent trees (.ext2, .ext3, .ext4, .img)
 * - Read-only BSD UFS1 / UFS2, little- and big-endian (.ufs, .ufs1, .ufs2, .img)
 * - Read-only NTFS 3.0/3.1 resident, nonresident and sparse data (.ntfs, .img)
 * - MBR and GPT partitioned disk images (.img, .raw)
 * - Transparent decompression for .gz, .bz2, .bz, .xz, .lzma, .zst wrapped disc images
 */
class DiscImageReader(private val tempDir: File, private val diskCache: com.infinifolder.storage.DiskCache? = null) {
    private val freshReads = ThreadLocal.withInitial { false }
    internal fun <T> withFreshReads(block: () -> T): T {
        val previous = freshReads.get()
        freshReads.set(true)
        return try { block() } finally { freshReads.set(previous) }
    }
    private class UnrecognizedFilesystem(offset: Long) : IOException("Unrecognized filesystem at offset $offset")

    data class DiscEntry(
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val modifiedAtMillis: Long?,
        internal val extractor: ((BlockDevice, OutputStream) -> Unit)? = null,
        val isSymbolicLink: Boolean = false,
        val linkTarget: String? = null,
        val permissionsMode: Int? = null,
        val owner: String? = null,
        val group: String? = null,
        val readError: String? = null,
        val recoveryNote: String? = null,
        val partialExtractor: ((BlockDevice, OutputStream) -> com.infinifolder.model.RecoveryReport)? = null
    )

    fun readEntries(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null
    ): List<DiscEntry> {
        return withBlockDevice(archiveName, openStream, localFile, writable = false, device = device) { dev ->
            parseDiscImage(dev)
        }
    }

    fun supportsFatRecovery(archiveName: String, openStream: () -> InputStream, localFile: File? = null, device: BlockDevice? = null): Boolean =
        withBlockDevice(archiveName, openStream, localFile, writable = false, device = device, freshSnapshot = true) { dev ->
            isFatFs(dev, 0L) || detectPartitions(dev).any { p ->
                p.offsetBytes >= 0 && p.sizeBytes > 0 && p.offsetBytes <= dev.size && p.sizeBytes <= dev.size - p.offsetBytes &&
                    isFatFs(PartitionSliceBlockDevice(dev, p.offsetBytes, p.sizeBytes), 0L)
            }
        }

    /** Browse only the requested image directory; partition/LVM roots list volumes. */
    fun readDirectoryEntries(archiveName: String, directoryPath: String, openStream: () -> InputStream,
        localFile: File? = null, device: BlockDevice? = null): List<DiscEntry> =
        withBlockDevice(archiveName, openStream, localFile, writable = false, device = device) { dev ->
            directoryEntries(dev, normalizePath(directoryPath))
        }

    private fun directoryEntries(dev: BlockDevice, requested: String): List<DiscEntry> {
        fun join(prefix: String, path: String) = if (prefix.isEmpty()) path else if (path.isEmpty()) prefix else "$prefix/$path"
        fun relativeTo(path: String, prefix: String): String? = when {
            path == prefix -> ""
            path.startsWith("$prefix/") -> path.removePrefix("$prefix/")
            else -> null
        }
        fun filesystem(source: BlockDevice, relative: String, prefix: String,
            resolve: (BlockDevice) -> BlockDevice, depth: Int): List<DiscEntry> {
            RecoveryCancellation.check()
            if (depth > 16) throw IOException("Image volume nesting limit exceeded")
            if (LvmDecoder.isLvm(source)) {
                val volumes = LvmDecoder.decode(source)
                if (relative.isEmpty()) return volumes.map { DiscEntry(join(prefix, it.name), true, it.sizeBytes, null) }
                val volume = volumes.firstOrNull { relativeTo(relative, it.name) != null }
                    ?: throw IOException("Logical volume not found: $relative")
                val childPath = relativeTo(relative, volume.name)!!
                val childPrefix = join(prefix, volume.name)
                return filesystem(volume.device, childPath, childPrefix, { root ->
                    LvmDecoder.decode(resolve(root)).firstOrNull { it.name == volume.name && it.uuid == volume.uuid }
                        ?.device ?: throw IOException("Logical volume changed; refresh the image")
                }, depth + 1)
            }
            val entries = try {
                if (isExtFs(source, 0L)) parseExtFs(source, 0L, "", directoryPath = relative)
                else parseFilesystemAtOffset(source, 0L, "").filter {
                    normalizePath(it.path).substringBeforeLast('/', "") == relative
                }
            } catch (_: UnrecognizedFilesystem) {
                val rawName = prefix.substringAfterLast('/').ifEmpty { "volume" } + ".raw"
                if (relative.isNotEmpty()) throw IOException("No browsable filesystem in this volume")
                listOf(DiscEntry(rawName, false, source.size, null, extractor = { d, out ->
                    copyRangeToStream(d, 0L, d.size, out)
                }))
            }
            return entries.map { entry ->
                val extract = entry.extractor
                entry.copy(path = join(prefix, entry.path),
                    linkTarget = entry.linkTarget?.let { if (it.startsWith('/') && prefix.isNotEmpty()) "/$prefix$it" else it },
                    extractor = extract?.let { fn -> { root, out -> fn(resolve(root), out) } })
            }
        }

        // Direct filesystems must be checked before partition-table heuristics.
        val direct = LvmDecoder.isLvm(dev) || isExtFs(dev, 0L) || isFatFs(dev, 0L) ||
            isExFat(dev, 0L) || isIso9660(dev, 0L) || NtfsFileSystem.isNtfs(dev) ||
            HfsFileSystem.isHfs(dev) || UfsFileSystem.isUfs(dev)
        if (direct) return filesystem(dev, requested, "", { it }, 0)
        val partitions = detectPartitions(dev)
        if (partitions.isEmpty()) throw IOException("Unsupported or unrecognized disc image format")
        if (partitions.size > 1 && requested.isEmpty()) {
            return partitions.map { DiscEntry(it.name, true, it.sizeBytes, null) }
        }
        val part = if (partitions.size == 1) partitions.single() else
            partitions.firstOrNull { relativeTo(requested, it.name) != null }
                ?: throw IOException("Partition not found: $requested")
        val prefix = if (partitions.size > 1) part.name else ""
        val relative = if (prefix.isEmpty()) requested else relativeTo(requested, prefix)!!
        val slice = PartitionSliceBlockDevice(dev, part.offsetBytes, part.sizeBytes, dev.isReadOnly)
        return filesystem(slice, relative, prefix, { root ->
            PartitionSliceBlockDevice(root, part.offsetBytes, part.sizeBytes, root.isReadOnly)
        }, 0)
    }

    private fun deletedFatEntries(dev: BlockDevice): List<DiscEntry> {
        if (isFatFs(dev, 0L)) return FatDeletedFiles(FatFileSystem(dev)).entries()
        val result = mutableListOf<DiscEntry>()
        var fatVolumes = 0
        FatRecoveryPartitions.validate(dev)
        val partitions = detectPartitions(dev)
        val ordered = partitions.sortedBy { it.offsetBytes }
        if (ordered.zipWithNext().any { (a, b) -> a.sizeBytes > b.offsetBytes - a.offsetBytes })
            throw IOException("Overlapping partitions; recovery scan refused")
        for (p in partitions) {
            if (p.offsetBytes < 0 || p.sizeBytes <= 0 || p.offsetBytes > dev.size || p.sizeBytes > dev.size - p.offsetBytes)
                throw IOException("Invalid partition bounds; recovery scan refused")
            val slice = PartitionSliceBlockDevice(dev, p.offsetBytes, p.sizeBytes)
            if (!isFatFs(slice, 0L)) continue
            fatVolumes++
            result.add(DiscEntry(p.name, true, null, null))
            for (entry in FatDeletedFiles(FatFileSystem(slice)).entries()) {
                val extract = entry.extractor
                fun checkLayout(source: BlockDevice) {
                    FatRecoveryPartitions.validate(source)
                    if (detectPartitions(source) != partitions) throw IOException("Partition layout changed during recovery; refresh and retry")
                }
                result.add(entry.copy(path = "${p.name}/at-${p.offsetBytes.toString(16)}-${entry.path}", extractor = extract?.let { fn ->
                    { source, out ->
                        checkLayout(source)
                        fn(PartitionSliceBlockDevice(source, p.offsetBytes, p.sizeBytes), out)
                        checkLayout(source)
                    }
                }, partialExtractor = entry.partialExtractor?.let { fn -> { source, out ->
                    checkLayout(source)
                    val report = fn(PartitionSliceBlockDevice(source, p.offsetBytes, p.sizeBytes), out)
                    checkLayout(source)
                    report.copy(partitionOffset = p.offsetBytes)
                } }))
            }
        }
        if (fatVolumes == 0) throw IOException("Deleted-file scanning requires a FAT12, FAT16 or FAT32 volume")
        return result
    }

    fun readDeletedFatEntries(archiveName: String, openStream: () -> InputStream, localFile: File? = null, device: BlockDevice? = null): List<DiscEntry> =
        withBlockDevice(archiveName, openStream, localFile, writable = false, device = device, freshSnapshot = true) { deletedFatEntries(it) }

    fun openDeletedFatEntry(archiveName: String, path: String, openStream: () -> InputStream, localFile: File? = null, device: BlockDevice? = null): InputStream {
        tempDir.mkdirs()
        val staged = File.createTempFile("fat-recovery-", ".tmp", tempDir)
        try {
            withBlockDevice(archiveName, openStream, localFile, writable = false, device = device, freshSnapshot = true) { dev ->
                RecoveryCancellation.check()
                val entry = deletedFatEntries(dev).singleOrNull { it.path == path } ?: throw IOException("Deleted-file candidate no longer exists")
                entry.readError?.let { throw IOException(it) }
                val extract = entry.extractor ?: throw IOException("Cannot recover a directory")
                ContainerSpace.require(tempDir, entry.sizeBytes ?: 0)
                staged.outputStream().use { extract(dev, it) }
            }
            return object : java.io.FilterInputStream(staged.inputStream()) {
                override fun close() { try { super.close() } finally { staged.delete() } }
            }
        } catch (e: Throwable) { staged.delete(); throw e }
    }

    fun openPartialDeletedFatEntry(archiveName: String, path: String, openStream: () -> InputStream,
        localFile: File? = null, device: BlockDevice? = null): com.infinifolder.model.RecoveryExport {
        tempDir.mkdirs()
        val staged = File.createTempFile("fat-partial-", ".tmp", tempDir)
        try {
            var report = withBlockDevice(archiveName, openStream, localFile, writable = false, device = device, freshSnapshot = true) { dev ->
                val entry = deletedFatEntries(dev).singleOrNull { it.path == path } ?: throw IOException("Deleted-file candidate changed or no longer exists")
                val extract = entry.partialExtractor ?: throw IOException("Deleted metadata does not support partial recovery")
                ContainerSpace.require(tempDir, entry.sizeBytes ?: 0)
                staged.outputStream().buffered(128 * 1024).use { extract(dev, it) }
            }
            val copiedPrefix = report.ranges.firstOrNull {
                it.disposition == com.infinifolder.model.RecoveryDisposition.ZERO_FILLED
            }?.offset ?: staged.length()
            val headerLength = minOf(copiedPrefix, staged.length(), 2048L).toInt()
            val magic = if (headerLength > 0) staged.inputStream().use { input ->
                val bytes = ByteArray(headerLength)
                var read = 0
                while (read < bytes.size) { val n = input.read(bytes, read, bytes.size - read); if (n < 0) break; read += n }
                com.infinifolder.storage.FileMagicDetector.detectFromBytes(bytes.copyOf(read))
            } else null
            val expectedMime = com.infinifolder.mime.MimeRegistry.guess(path.substringAfterLast('/'), false)
            val expectedContainer = com.infinifolder.mime.MimeRegistry.packageFormat(path.substringAfterLast('/'), expectedMime)
                ?.let { com.infinifolder.mime.MimeRegistry.guess("container.${it.container}") }
            val compatibleTypes = listOfNotNull(expectedMime, expectedContainer).map { com.infinifolder.mime.MimeRegistry.normalize(it) }
            val mismatch = if (magic == null || com.infinifolder.storage.FileMagicDetector.isAmbiguousOrUnknown(path.substringAfterLast('/'), expectedMime)) null
                else com.infinifolder.mime.MimeRegistry.normalize(magic.mimeType) !in compatibleTypes
            if (mismatch == true) report = report.copy(ranges = report.ranges.map { range ->
                if (range.grade == com.infinifolder.model.RecoveryGrade.POSSIBLY_GOOD)
                    range.copy(grade = com.infinifolder.model.RecoveryGrade.POSSIBLY_BAD,
                        reason = range.reason + "; detected signature differs from filename type (heuristic)") else range
            })
            report = report.copy(candidate = path, sourceImage = archiveName, magicMime = magic?.mimeType,
                magicDescription = magic?.description, expectedMime = expectedMime, expectedContainerMime = expectedContainer, magicInspectedBytes = headerLength,
                magicMismatch = mismatch)
            val stream = object : java.io.FilterInputStream(staged.inputStream()) {
                override fun close() { try { super.close() } finally { staged.delete() } }
            }
            return com.infinifolder.model.RecoveryExport(stream, report)
        } catch (e: Throwable) { staged.delete(); throw e }
    }

    fun extractEntry(
        archiveName: String,
        entryPath: String,
        targetFile: File,
        openStream: () -> InputStream,
        localFile: File? = null,
        device: BlockDevice? = null
    ): File {
        val cleanTarget = normalizePath(entryPath)
        targetFile.parentFile?.mkdirs()
        withBlockDevice(archiveName, openStream, localFile, writable = false, device = device) { dev ->
            // File verification should not parse every directory in a FAT image
            // when it only needs to read one file. Walk the requested path and
            // its cluster chain directly; other image formats keep the generic
            // full-tree extractor below.
            if (isFatFs(dev, 0L)) {
                targetFile.outputStream().use { FatFileSystem(dev).extractFile(cleanTarget, it) }
                return@withBlockDevice
            }
            val entries = directoryEntries(dev, cleanTarget.substringBeforeLast('/', ""))
            val match = entries.firstOrNull { normalizePath(it.path) == cleanTarget }
                ?: entries.firstOrNull { normalizePath(it.path).equals(cleanTarget, ignoreCase = true) }
                ?: throw NoSuchElementException("Entry not found in disc image: $entryPath")
            val extractor = match.extractor
                ?: throw IOException("Cannot extract directory entry: $entryPath")
            targetFile.outputStream().use { out ->
                extractor(dev, out)
            }
        }
        return targetFile
    }

    fun openEntryStream(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File? = null, device: BlockDevice? = null): InputStream {
        tempDir.mkdirs()
        val staged = File.createTempFile("disc-entry-", ".bin", tempDir)
        return try {
            extractEntry(archiveName, entryPath, staged, openStream, localFile, device)
            object : java.io.FilterInputStream(staged.inputStream()) {
                override fun close() { try { super.close() } finally { staged.delete() } }
            }
        } catch (e: Exception) { staged.delete(); throw e }
    }

    fun fileSystemInfo(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File? = null, device: BlockDevice? = null): com.infinifolder.storage.FileSystemInfo? =
        withBlockDevice(archiveName, openStream, localFile, device = device) { dev ->
            fun identify(target: BlockDevice): com.infinifolder.storage.FileSystemInfo? = when {
                isIso9660(target, 0L) -> com.infinifolder.storage.FileSystemInfo("ISO 9660", null, "Image volume descriptor")
                isFatFs(target, 0L) -> FatFileSystem(target).fileSystemInfoWithSpace()
                isExFat(target, 0L) -> com.infinifolder.storage.FileSystemInfo("exfat", null, "Image boot sector")
                isExtFs(target, 0L) -> ExtFileSystem.info(target)
                NtfsFileSystem.isNtfs(target) -> NtfsFileSystem(target).info()
                HfsFileSystem.isHfs(target) -> HfsFileSystem(target).info()
                UfsFileSystem.isUfs(target) -> UfsFileSystem(target).info()
                else -> null
            }
            identify(dev)?.let { return@withBlockDevice it }
            val selected = getPartitionSlice(dev, entryPath)
            if (selected != null) return@withBlockDevice identify(selected)
            val parts = detectPartitions(dev)
            if (parts.size == 1) identify(PartitionSliceBlockDevice(dev, parts[0].offsetBytes, parts[0].sizeBytes)) else null
        }

    fun isWritable(archiveName: String, localFile: File?, device: BlockDevice? = null, entryPath: String? = null): Boolean =
        writableFailureReason(archiveName, localFile, device, entryPath) == null

    fun readTransferMetadata(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File?, device: BlockDevice?): com.infinifolder.model.TransferMetadata? =
        withBlockDevice(archiveName, openStream, localFile, device = device) { raw ->
            val type = fileSystemInfo("metadata.img", entryPath, { throw IOException("Device required") }, device = raw)?.type
            if (type !in setOf("ext2", "ext3", "ext4")) null else
                withWritableFilesystem("metadata.img", null, raw, entryPath, probeOnly = true) { fs, relative -> fs.readTransferMetadata(relative) }
        }

    fun applyTransferMetadata(archiveName: String, localFile: File?, device: BlockDevice?, entryPath: String,
        metadata: com.infinifolder.model.TransferMetadata) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fs, relative -> fs.applyTransferMetadata(relative, metadata) }
    }

    /** Compressed images use a decompressed, read-only probe before staging any edits. */
    fun compressedWritableFailureReason(archiveName: String, entryPath: String, openStream: () -> InputStream,
        localFile: File?, device: BlockDevice?): String? =
        withBlockDevice(archiveName, openStream, localFile, device = device) { raw ->
            writableFailureReason("decompressed.img", null, raw, entryPath, probeOnly = true)
        }

    /** Probe exactly the filesystem selection used by writes, without modifying any bytes. */
    fun writableFailureReason(archiveName: String, localFile: File?, device: BlockDevice? = null,
        entryPath: String? = null, probeOnly: Boolean = false): String? = try {
        val lower = archiveName.lowercase(Locale.ROOT)
        if (listOf(".gz", ".bz2", ".bz", ".xz", ".lzma", ".zst", ".z").any { lower.endsWith(it) } ||
            (localFile != null && isCompressedFileMagic(localFile)))
            throw IOException("Compressed disk images are read-only; decompress the image before editing")
        withWritableFilesystem(archiveName, localFile, device, entryPath.orEmpty(), probeOnly) { _, _ -> Unit }
        null
    } catch (e: Exception) {
        e.message ?: "Cannot open this filesystem for writing"
    }

    private fun <T> withWritableFilesystem(
        archiveName: String,
        localFile: File?,
        device: BlockDevice? = null,
        entryPath: String,
        probeOnly: Boolean = false,
        block: (WritableImageFileSystem, String) -> T
    ): T {
        // ArchiveStorageProvider supplies this marker only for its private,
        // unpublished batch snapshot. The original backing image is checked
        // again at publication; spawning losetup for every staged entry is unnecessary.
        val privateSnapshot = device is PrivateBatchBlockDevice && localFile != null && device.belongsTo(localFile)
        if (localFile != null && !privateSnapshot) {
            val mountCheck = MountedPartitionDetector.checkMounted(localFile)
            if (mountCheck.isMounted) {
                throw MountedPartitionException(localFile.name, mountCheck.reason)
            }
        }
        require((localFile != null && localFile.exists()) || (device != null && (probeOnly || !device.isReadOnly))) {
            "Writable file or BlockDevice required for disc writing"
        }
        val result = try { withBlockDevice(archiveName, { throw IOException("Writable target required") }, localFile, writable = !probeOnly, device = device) { dev ->
            if (!probeOnly && dev.isReadOnly) throw IOException("The backing provider or image decoder supplies read-only random access")
            if (isIso9660(dev, 0L)) throw IOException("ISO filesystem views are read-only")
            if (isFatFs(dev, 0L)) {
                val fatFs = FatFileSystem(dev, 0L, "")
                return@withBlockDevice block(fatFs, entryPath)
            }
            if (isExtFs(dev, 0L)) {
                val extFs = ExtFileSystem(dev, tempDir, privateSnapshot).also { it.probe() }
                return@withBlockDevice block(extFs, entryPath)
            }
            rejectReadOnlyImageFilesystem(dev)
            val partitions = detectPartitions(dev)
            if (partitions.isNotEmpty()) {
                val cleanPath = normalizePath(entryPath)
                if (partitions.size == 1) {
                    val p = partitions.first()
                    val slice = PartitionSliceBlockDevice(dev, p.offsetBytes, p.sizeBytes, dev.isReadOnly)
                    if (isFatFs(slice, 0L)) {
                        val subPath = when {
                            cleanPath == p.name -> ""
                            cleanPath.startsWith("${p.name}/") -> cleanPath.removePrefix("${p.name}/")
                            else -> cleanPath
                        }
                        val fatFs = FatFileSystem(slice, 0L, "")
                        return@withBlockDevice block(fatFs, subPath)
                    }
                    if (isExtFs(slice, 0L)) {
                        val subPath = when {
                            cleanPath == p.name -> ""
                            cleanPath.startsWith("${p.name}/") -> cleanPath.removePrefix("${p.name}/")
                            else -> cleanPath
                        }
                        val extFs = ExtFileSystem(slice, tempDir, privateSnapshot).also { it.probe() }
                        return@withBlockDevice block(extFs, subPath)
                    }
                    rejectReadOnlyImageFilesystem(slice)
                } else {
                    for (p in partitions) {
                        if (cleanPath == p.name || cleanPath.startsWith("${p.name}/")) {
                            val slice = PartitionSliceBlockDevice(dev, p.offsetBytes, p.sizeBytes, dev.isReadOnly)
                            if (isFatFs(slice, 0L)) {
                                val subPath = cleanPath.removePrefix("${p.name}/").removePrefix(p.name)
                                val fatFs = FatFileSystem(slice, 0L, "")
                                return@withBlockDevice block(fatFs, subPath)
                            }
                            if (isExtFs(slice, 0L)) {
                                val subPath = if (cleanPath == p.name) "" else cleanPath.removePrefix("${p.name}/")
                                val extFs = ExtFileSystem(slice, tempDir, privateSnapshot).also { it.probe() }
                                return@withBlockDevice block(extFs, subPath)
                            }
                            rejectReadOnlyImageFilesystem(slice)
                        }
                    }
                }
            }
            throw IOException("This image location does not support writing. Select a supported writable filesystem directory.")
        } } catch (error: Exception) {
            if (!probeOnly) (device as? PrivateBatchBlockDevice)?.markFailed(error)
            throw error
        }
        // Intermediate FAT flushes must never publish an incomplete remote transaction.
        if (!probeOnly) (device as? com.infinifolder.storage.StagedBlockCommit)?.commit()
        return result
    }

    /** Share format rejection between whole-volume and selected-partition probes.
     * These formats can be read, but supplying writable backing bytes does not
     * supply the missing filesystem metadata/allocation writer.
     */
    private fun rejectReadOnlyImageFilesystem(dev: BlockDevice) {
        val format = when {
            isIso9660(dev, 0L) -> "ISO"
            isExFat(dev, 0L) -> "exFAT"
            HfsFileSystem.isHfs(dev) -> "HFS/HFS+/HFSX"
            NtfsFileSystem.isNtfs(dev) -> "NTFS"
            UfsFileSystem.isUfs(dev) -> "UFS"
            LvmDecoder.isLvm(dev) -> "LVM logical-volume"
            else -> return
        }
        throw IOException("$format image views are read-only in InfiniFolder; writing into this filesystem is not implemented. You can browse and copy files out.")
    }

    fun createFile(archiveName: String, localFile: File? = null, entryPath: String, data: ByteArray = ByteArray(0), device: BlockDevice? = null) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fatFs, relPath ->
            fatFs.createFile(relPath, data)
        }
    }

    fun writeEntryFromFile(archiveName: String, localFile: File?, entryPath: String, content: File, device: BlockDevice? = null) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fs, path -> fs.writeEntryFromFile(path, content) }
    }

    fun createDirectory(archiveName: String, localFile: File? = null, entryPath: String, device: BlockDevice? = null) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fatFs, relPath ->
            fatFs.createDirectory(relPath)
        }
    }

    fun writeEntry(archiveName: String, localFile: File? = null, entryPath: String, data: ByteArray, device: BlockDevice? = null) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fatFs, relPath ->
            fatFs.writeEntry(relPath, data)
        }
    }

    internal fun relocateEntry(archiveName: String, localFile: File?, entryPath: String,
                              destinationPath: String, device: BlockDevice?) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fs, relativeSource ->
            val source = normalizePath(entryPath)
            val relative = normalizePath(relativeSource)
            val prefix = source.removeSuffix(relative).trimEnd('/')
            val destination = normalizePath(destinationPath)
            val target = when {
                prefix.isEmpty() -> destination
                destination == prefix -> ""
                destination.startsWith("$prefix/") -> destination.substring(prefix.length + 1)
                else -> throw IOException("Cannot relocate between filesystem partitions; use Copy instead. Sources retained.")
            }
            fs.relocateEntry(relative, target)
        }
    }

    /** Preflight the entire relocation set before changing any partition. */
    internal fun sameRelocationFilesystem(archiveName: String, localFile: File?, device: BlockDevice?, paths: List<String>): Boolean =
        withBlockDevice(archiveName, { throw IOException("Image device required") }, localFile, device = device) { dev ->
            if (isFatFs(dev, 0L) || isExtFs(dev, 0L)) true else {
                val partitions = detectPartitions(dev)
                if (partitions.size == 1) true else {
                    val owners = paths.map { path ->
                        val clean = normalizePath(path)
                        partitions.indexOfFirst { clean == it.name || clean.startsWith("${it.name}/") }
                    }
                    owners.isNotEmpty() && owners.all { it >= 0 && it == owners.first() }
                }
            }
        }

    fun deleteEntry(archiveName: String, localFile: File? = null, entryPath: String, device: BlockDevice? = null) {
        withWritableFilesystem(archiveName, localFile, device, entryPath) { fatFs, relPath ->
            fatFs.deleteEntry(relPath)
        }
    }

    private fun <T> withBlockDevice(
        archiveName: String,
        openStream: () -> InputStream,
        localFile: File?,
        writable: Boolean = false,
        device: BlockDevice? = null,
        freshSnapshot: Boolean = freshReads.get(),
        block: (BlockDevice) -> T
    ): T {
        val lowerName = archiveName.lowercase(Locale.ROOT)
        val isCompressedByName = lowerName.endsWith(".gz") ||
            lowerName.endsWith(".bz2") ||
            lowerName.endsWith(".bz") ||
            lowerName.endsWith(".xz") ||
            lowerName.endsWith(".lzma") ||
            lowerName.endsWith(".zst") || lowerName.endsWith(".z")

        if (device != null && !isCompressedByName) {
            val dev = VmDiskDecoder.open(device, isReadOnly = !writable, baseFile = localFile) ?: device
            val result = block(dev)
            if (writable) dev.flush()
            return result
        }

        if (localFile != null && localFile.exists() && !isCompressedByName && !isCompressedFileMagic(localFile)) {
            val mode = if (writable) "rw" else "r"
            return RandomAccessFile(localFile, mode).use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = !writable, baseFile = localFile)
                    ?: FileBlockDevice(raf, isReadOnly = !writable)
                dev.use { d ->
                    val result = block(d)
                    if (writable) d.flush()
                    result
                }
            }
        }

        if (writable) {
            throw IOException("Cannot write to compressed or stream-only disc image")
        }

        if (diskCache != null) {
            val key = if (!freshSnapshot && localFile != null && localFile.isFile)
                "decompressed:${localFile.canonicalPath}:${localFile.length()}:${localFile.lastModified()}" else null
            val lease = diskCache.stage("decompressed-image.raw", key = key) {
                openDecompressedStream(if (localFile != null && localFile.exists()) localFile.inputStream() else openStream(), lowerName)
            }
            return lease.use {
                RandomAccessFile(lease.file, "r").use { raf ->
                    val dev = VmDiskDecoder.open(raf, isReadOnly = true, baseFile = lease.file)
                        ?: FileBlockDevice(raf, isReadOnly = true)
                    dev.use(block)
                }
            }
        }

        tempDir.mkdirs()
        if (!freshSnapshot && localFile != null && localFile.exists()) {
            val cacheKey = sha256("${localFile.absolutePath}:${localFile.length()}:${localFile.lastModified()}")
            val cachedRaw = File(tempDir, "disc_cache_$cacheKey.raw")
            if (!cachedRaw.exists() || cachedRaw.length() == 0L) {
                val tmpOut = File(tempDir, "disc_cache_${cacheKey}_${System.nanoTime()}.tmp")
                try {
                    localFile.inputStream().use { rawIn ->
                        openDecompressedStream(rawIn, lowerName).use { decIn ->
                            tmpOut.outputStream().use { out ->
                                ContainerSpace.copy(decIn, out, tempDir, cancelled = { RecoveryCancellation.check(); false })
                            }
                        }
                    }
                    if (!tmpOut.renameTo(cachedRaw)) {
                        tmpOut.copyTo(cachedRaw, overwrite = true)
                        tmpOut.delete()
                    }
                } finally {
                    if (tmpOut.exists()) tmpOut.delete()
                }
            }
            return RandomAccessFile(cachedRaw, "r").use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = true, baseFile = cachedRaw)
                    ?: FileBlockDevice(raf, isReadOnly = true)
                dev.use(block)
            }
        }

        val tempFile = File.createTempFile("inf_disc_", ".raw", tempDir)
        try {
            (if (localFile != null && localFile.exists()) localFile.inputStream() else openStream()).use { rawIn ->
                openDecompressedStream(rawIn, lowerName).use { decIn ->
                    tempFile.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        var bytesCopied = 0L
                        var read = decIn.read(buffer)
                        while (read >= 0) {
                            RecoveryCancellation.check()
                            bytesCopied += read
                            if (localFile == null && bytesCopied > 512L * 1024L * 1024L) {
                                throw IOException("Disk image '$archiveName' exceeds 512 MB streaming limit. Download the file locally to inspect its contents.")
                            }
                            ContainerSpace.require(tempDir, read.toLong())
                            out.write(buffer, 0, read)
                            read = decIn.read(buffer)
                        }
                    }
                }
            }
            return RandomAccessFile(tempFile, "r").use { raf ->
                val dev = VmDiskDecoder.open(raf, isReadOnly = true, baseFile = tempFile)
                    ?: FileBlockDevice(raf, isReadOnly = true)
                dev.use(block)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun isCompressedFileMagic(file: File): Boolean {
        if (file.length() < 4) return false
        val header = ByteArray(6)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(header)
        }
        // Gzip: 1F 8B
        if (u8(header, 0) == 0x1F && u8(header, 1) == 0x8B) return true
        if (u8(header, 0) == 0x1F && u8(header, 1) == 0x9D) return true
        // BZip2: 'B' 'Z' 'h'
        if (header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) return true
        // XZ: FD '7' 'z' 'X' 'Z' 00
        if (u8(header, 0) == 0xFD && header[1] == '7'.code.toByte() && header[2] == 'z'.code.toByte()) return true
        // Zstd: 28 B5 2F FD
        if (u8(header, 0) == 0x28 && u8(header, 1) == 0xB5 && u8(header, 2) == 0x2F && u8(header, 3) == 0xFD) return true
        return false
    }

    private fun openDecompressedStream(raw: InputStream, lowerName: String): InputStream {
        val bis = BufferedInputStream(raw, 65536)
        bis.mark(16)
        val header = ByteArray(6)
        val read = bis.read(header)
        bis.reset()

        if (read >= 2 && u8(header, 0) == 0x1F && u8(header, 1) == 0x8B) {
            return GzipCompressorInputStream(bis, true)
        }
        if (read >= 2 && u8(header, 0) == 0x1F && u8(header, 1) == 0x9D) return ZCompressorInputStream(bis)
        if (read >= 3 && header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) {
            return BZip2CompressorInputStream(bis, true)
        }
        if (read >= 6 && u8(header, 0) == 0xFD && header[1] == '7'.code.toByte() && header[2] == 'z'.code.toByte()) {
            return XZCompressorInputStream(bis, true)
        }
        if (read >= 4 && u8(header, 0) == 0x28 && u8(header, 1) == 0xB5 && u8(header, 2) == 0x2F && u8(header, 3) == 0xFD) {
            return CompressorStreamFactory().createCompressorInputStream(bis)
        }

        return when {
            lowerName.endsWith(".gz") -> GzipCompressorInputStream(bis, true)
            lowerName.endsWith(".bz2") || lowerName.endsWith(".bz") -> BZip2CompressorInputStream(bis, true)
            lowerName.endsWith(".xz") -> XZCompressorInputStream(bis, true)
            lowerName.endsWith(".lzma") -> LZMACompressorInputStream(bis)
            lowerName.endsWith(".zst") -> CompressorStreamFactory().createCompressorInputStream(bis)
            lowerName.endsWith(".z") -> ZCompressorInputStream(bis)
            else -> bis
        }
    }

    private fun parseDiscImage(dev: BlockDevice): List<DiscEntry> {
        val length = dev.size
        if (length < 512) throw IOException("Disc image is too small ($length bytes)")

        // 1. Check ISO 9660 / Joliet first (supports hybrid ISOs that also have an MBR at sector 0)
        if (isIso9660(dev, 0L)) {
            return parseIso9660(dev, 0L, "")
        }

        // 2. Check standalone LVM Physical Volume
        if (LvmDecoder.isLvm(dev, 0L)) {
            val lvs = LvmDecoder.decode(dev, 0L)
            if (lvs.isNotEmpty()) {
                val combined = mutableListOf<DiscEntry>()
                for (lv in lvs) {
                    val lvPrefix = lv.name
                    combined += DiscEntry(
                        path = lvPrefix,
                        isDirectory = true,
                        sizeBytes = lv.sizeBytes,
                        modifiedAtMillis = null
                    )
                    val lvEntries = try {
                        parseFilesystemAtOffset(lv.device, 0L, "")
                    } catch (_: UnrecognizedFilesystem) {
                        null
                    }
                    if (lvEntries != null) {
                        for (entry in lvEntries) {
                            val origExtractor = entry.extractor
                            val wrappedExtractor = if (origExtractor != null) {
                                { _: BlockDevice, out: OutputStream ->
                                    origExtractor(lv.device, out)
                                }
                            } else null
                            combined += entry.copy(
                                path = "$lvPrefix/${entry.path}",
                                linkTarget = entry.linkTarget?.let { if (it.startsWith('/')) "/$lvPrefix$it" else it },
                                extractor = wrappedExtractor
                            )
                        }
                    } else {
                        combined += DiscEntry(
                            path = "$lvPrefix/${lv.name}.raw",
                            isDirectory = false,
                            sizeBytes = lv.sizeBytes,
                            modifiedAtMillis = null,
                            extractor = { _, out ->
                                copyRangeToStream(lv.device, 0L, lv.sizeBytes, out)
                            }
                        )
                    }
                }
                return combined
            }
        }

        // 3. Check direct filesystems at offset 0 (ext2/3/4, exFAT, FAT12/16/32 / EFI)
        if (isExtFs(dev, 0L)) {
            return parseExtFs(dev, 0L, "")
        }
        if (isExFat(dev, 0L)) {
            return parseExFat(dev, 0L, "")
        }
        if (isFatFs(dev, 0L)) {
            return parseFatFs(dev, 0L, "")
        }
        if (NtfsFileSystem.isNtfs(dev)) return NtfsFileSystem(dev).entries("")
        if (HfsFileSystem.isHfs(dev)) return HfsFileSystem(dev).entries("")
        if (UfsFileSystem.isUfs(dev)) return UfsFileSystem(dev).entries("")

        // 4. Check GPT or MBR partition tables
        val partitions = detectPartitions(dev)
        if (partitions.isNotEmpty()) {
            if (partitions.size == 1) {
                val part = partitions.first()
                val slice = PartitionSliceBlockDevice(dev, part.offsetBytes, part.sizeBytes)
                val decodedLvm = decodePartitionLvm(slice, part)
                if (decodedLvm != null) {
                    val lvs = decodedLvm
                    if (lvs.isNotEmpty()) {
                        val combined = mutableListOf<DiscEntry>()
                        for (lv in lvs) {
                            val lvPrefix = lv.name
                            combined += DiscEntry(
                                path = lvPrefix,
                                isDirectory = true,
                                sizeBytes = lv.sizeBytes,
                                modifiedAtMillis = null
                            )
                            val lvEntries = try {
                                parseFilesystemAtOffset(lv.device, 0L, "")
                            } catch (_: UnrecognizedFilesystem) {
                                null
                            }
                            if (lvEntries != null) {
                                for (entry in lvEntries) {
                                    val origExtractor = entry.extractor
                                    val wrappedExtractor = if (origExtractor != null) {
                                        { _: BlockDevice, out: OutputStream ->
                                            origExtractor(lv.device, out)
                                        }
                                    } else null
                                    combined += entry.copy(
                                        path = "$lvPrefix/${entry.path}",
                                        linkTarget = entry.linkTarget?.let { if (it.startsWith('/')) "/$lvPrefix$it" else it },
                                        extractor = wrappedExtractor
                                    )
                                }
                            } else {
                                combined += DiscEntry(
                                    path = "$lvPrefix/${lv.name}.raw",
                                    isDirectory = false,
                                    sizeBytes = lv.sizeBytes,
                                    modifiedAtMillis = null,
                                    extractor = { _, out ->
                                        copyRangeToStream(lv.device, 0L, lv.sizeBytes, out)
                                    }
                                )
                            }
                        }
                        return combined
                    }
                }
                val entries = try {
                    parseFilesystemAtOffset(slice, 0L, "")
                } catch (_: UnrecognizedFilesystem) {
                    null
                }
                if (entries != null) {
                    return entries.map { entry ->
                        val origExtractor = entry.extractor
                        if (origExtractor != null) {
                            entry.copy(extractor = { parentDev, out ->
                                val partSlice = PartitionSliceBlockDevice(parentDev, part.offsetBytes, part.sizeBytes)
                                origExtractor(partSlice, out)
                            })
                        } else entry
                    }
                } else {
                    return listOf(
                        DiscEntry(
                            path = part.name,
                            isDirectory = true,
                            sizeBytes = part.sizeBytes,
                            modifiedAtMillis = null
                        ),
                        DiscEntry(
                            path = "${part.name}/${part.name}.raw",
                            isDirectory = false,
                            sizeBytes = part.sizeBytes,
                            modifiedAtMillis = null,
                            extractor = { parentDev, out ->
                                copyRangeToStream(parentDev, part.offsetBytes, part.sizeBytes, out)
                            }
                        )
                    )
                }
            } else {
                // Multiple partitions: provide an intermediate layer listing each partition by name
                val combined = mutableListOf<DiscEntry>()
                for (part in partitions) {
                    val prefix = part.name
                    combined += DiscEntry(
                        path = prefix,
                        isDirectory = true,
                        sizeBytes = part.sizeBytes,
                        modifiedAtMillis = null
                    )
                    val slice = PartitionSliceBlockDevice(dev, part.offsetBytes, part.sizeBytes)

                    // Check if partition is an LVM PV
                    val decodedLvm = decodePartitionLvm(slice, part)
                    if (decodedLvm != null) {
                        val lvs = decodedLvm
                        if (lvs.isNotEmpty()) {
                            for (lv in lvs) {
                                val lvPrefix = "$prefix/${lv.name}"
                                combined += DiscEntry(
                                    path = lvPrefix,
                                    isDirectory = true,
                                    sizeBytes = lv.sizeBytes,
                                    modifiedAtMillis = null
                                )
                                val lvEntries = try {
                                    parseFilesystemAtOffset(lv.device, 0L, "")
                                } catch (_: UnrecognizedFilesystem) {
                                    null
                                }
                                if (lvEntries != null) {
                                    for (entry in lvEntries) {
                                        val origExtractor = entry.extractor
                                        val wrappedExtractor = if (origExtractor != null) {
                                            { _: BlockDevice, out: OutputStream ->
                                                origExtractor(lv.device, out)
                                            }
                                        } else null
                                        combined += entry.copy(
                                            path = "$lvPrefix/${entry.path}",
                                            linkTarget = entry.linkTarget?.let { if (it.startsWith('/')) "/$lvPrefix$it" else it },
                                            extractor = wrappedExtractor
                                        )
                                    }
                                } else {
                                    combined += DiscEntry(
                                        path = "$lvPrefix/${lv.name}.raw",
                                        isDirectory = false,
                                        sizeBytes = lv.sizeBytes,
                                        modifiedAtMillis = null,
                                        extractor = { _, out ->
                                            copyRangeToStream(lv.device, 0L, lv.sizeBytes, out)
                                        }
                                    )
                                }
                            }
                            continue
                        }
                    }

                    val entries = try {
                        parseFilesystemAtOffset(slice, 0L, "")
                    } catch (_: UnrecognizedFilesystem) {
                        null
                    }
                    if (entries != null) {
                        for (entry in entries) {
                            val origExtractor = entry.extractor
                            val wrappedExtractor: ((BlockDevice, OutputStream) -> Unit)? = if (origExtractor != null) {
                                { parentDev, out ->
                                    val partSlice = PartitionSliceBlockDevice(parentDev, part.offsetBytes, part.sizeBytes)
                                    origExtractor(partSlice, out)
                                }
                            } else null
                            combined += entry.copy(
                                path = "$prefix/${entry.path}",
                                linkTarget = entry.linkTarget?.let { if (it.startsWith('/')) "/$prefix$it" else it },
                                extractor = wrappedExtractor
                            )
                        }
                    } else {
                        // Unrecognized, unformatted, or unsupported partition filesystem: expose raw partition slice
                        com.infinifolder.storage.DiagnosticLog.record("disc.partition.raw-fallback", "name=${part.name} offset=${part.offsetBytes} bytes=${part.sizeBytes} reason=unrecognized-filesystem-or-no-decoded-lvm")
                        val rawName = "${part.name}.raw"
                        combined += DiscEntry(
                            path = "$prefix/$rawName",
                            isDirectory = false,
                            sizeBytes = part.sizeBytes,
                            modifiedAtMillis = null,
                            extractor = { parentDev, out ->
                                copyRangeToStream(parentDev, part.offsetBytes, part.sizeBytes, out)
                            }
                        )
                    }
                }
                return combined
            }
        }

        throw IOException("Unsupported or unrecognized disc image format")
    }

    fun getPartitionSlice(dev: BlockDevice, path: String): BlockDevice? {
        val clean = normalizePath(path)
        if (LvmDecoder.isLvm(dev, 0L)) {
            val lvs = LvmDecoder.decode(dev, 0L)
            for (lv in lvs) {
                val norm = normalizePath(lv.name)
                if (clean == norm || clean == "$norm/${norm}.raw" || clean.startsWith("$norm/")) {
                    return lv.device
                }
            }
        }
        val partitions = detectPartitions(dev)
        for (part in partitions) {
            val partNorm = normalizePath(part.name)
            val slice = PartitionSliceBlockDevice(dev, part.offsetBytes, part.sizeBytes, dev.isReadOnly)
            val decodedLvm = decodePartitionLvm(slice, part)
            if (decodedLvm != null) {
                val lvs = decodedLvm
                for (lv in lvs) {
                    val lvFull = "$partNorm/${normalizePath(lv.name)}"
                    if (clean == lvFull || clean == "$lvFull/${lv.name}.raw" || clean.startsWith("$lvFull/")) {
                        return lv.device
                    }
                }
            }
            if (clean == partNorm || clean == "$partNorm/${partNorm}.raw" || clean.startsWith("$partNorm/")) {
                return slice
            }
        }
        return null
    }

    private fun decodePartitionLvm(slice: BlockDevice, part: PartitionSlice): List<LvmDecoder.LogicalVolume>? {
        val detected = LvmDecoder.isLvm(slice, 0L)
        com.infinifolder.storage.DiagnosticLog.record(
            "disc.partition.probe",
            "name=${part.name} type=${part.typeName} offset=${part.offsetBytes} bytes=${part.sizeBytes} lvmSignature=$detected"
        )
        if (!detected && part.typeName != "Linux LVM") return null
        return try {
            LvmDecoder.decode(slice, 0L).also {
                com.infinifolder.storage.DiagnosticLog.record("disc.partition.lvm-result", "name=${part.name} volumes=${it.size}")
            }
        } catch (e: Exception) {
            com.infinifolder.storage.DiagnosticLog.record("disc.partition.lvm-failed", "name=${part.name} offset=${part.offsetBytes} rawFallbackPossible=true", e)
            emptyList()
        }
    }

    private fun parseFilesystemAtOffset(dev: BlockDevice, offset: Long, prefix: String): List<DiscEntry> {
        return when {
            isIso9660(dev, offset) -> parseIso9660(dev, offset, prefix)
            isExtFs(dev, offset) -> parseExtFs(dev, offset, prefix)
            isExFat(dev, offset) -> parseExFat(dev, offset, prefix)
            isFatFs(dev, offset) -> parseFatFs(dev, offset, prefix)
            NtfsFileSystem.isNtfs(dev, offset) -> NtfsFileSystem(dev, offset).entries(prefix)
            HfsFileSystem.isHfs(dev, offset) -> HfsFileSystem(dev, offset).entries(prefix)
            UfsFileSystem.isUfs(dev, offset) -> UfsFileSystem(dev, offset).entries(prefix)
            else -> throw UnrecognizedFilesystem(offset)
        }
    }

    // =========================================================================
    // Partition Table Detection (GPT & MBR)
    // =========================================================================

    data class PartitionSlice(
        val name: String,
        val offsetBytes: Long,
        val sizeBytes: Long,
        val typeName: String? = null,
        val label: String? = null
    )

    private val GPT_TYPE_NAMES = mapOf(
        "c12a7328-f81f-11d2-ba4b-00a0c93ec93b" to "EFI System",
        "ebd0a0a2-b9e5-4433-87c0-68b6b72699c7" to "Basic Data",
        "0fc63daf-8483-4772-8e79-3d69d8477de4" to "Linux filesystem",
        "0657fd6d-a4ab-43c4-84e5-0933c84b4f4f" to "Linux swap",
        "4f68bce3-e8cd-4db1-96e7-fbcaf984b709" to "Linux root (x86_64)",
        "b921b045-1df0-41c3-afac-4474932e523c" to "Linux root (arm64)",
        "69dad710-2ce4-4e3c-b16c-21a1d49abed3" to "Linux root (x86)",
        "933ac7e2-d2a2-4e10-8611-239fa390b08f" to "Linux home",
        "3b8f8425-20e0-4f3b-907f-1a25a76f98e8" to "Linux srv",
        "7ffc8e0f-1323-4869-8857-57f6499e6144" to "Linux plain dm-crypt",
        "ca7d7ccb-63ed-4c53-861c-1742536059cc" to "Linux LUKS",
        "e6d6d379-f507-44c2-a23c-238f2a3df928" to "Linux LVM",
        "21686148-6449-6e6f-744e-656564454649" to "BIOS Boot",
        "e3c9e316-0b5c-4db8-817d-f92df00215ae" to "Microsoft Reserved",
        "de94bba4-06d1-4d40-a16a-bfd50179d6ac" to "Windows Recovery",
        "48465300-0000-11aa-aa11-00306543ecac" to "Apple HFS+",
        "7c3457ef-0000-11aa-aa11-00306543ecac" to "Apple APFS"
    )

    private fun formatGptGuid(bytes: ByteArray, offset: Int): String {
        if (bytes.size < offset + 16) return ""
        val d1 = String.format("%02x%02x%02x%02x", bytes[offset + 3], bytes[offset + 2], bytes[offset + 1], bytes[offset])
        val d2 = String.format("%02x%02x", bytes[offset + 5], bytes[offset + 4])
        val d3 = String.format("%02x%02x", bytes[offset + 7], bytes[offset + 6])
        val d4 = String.format("%02x%02x", bytes[offset + 8], bytes[offset + 9])
        val d5 = (10..15).joinToString("") { String.format("%02x", bytes[offset + it]) }
        return "$d1-$d2-$d3-$d4-$d5".lowercase(Locale.ROOT)
    }

    private fun mbrTypeName(type: Int): String? = when (type) {
        0x01 -> "FAT12"
        0x04, 0x06, 0x0E -> "FAT16"
        0x07 -> "NTFS/exFAT"
        0x0B, 0x0C -> "FAT32"
        0x1B, 0x1C -> "FAT32 (Hidden)"
        0x27 -> "Windows Recovery"
        0x82 -> "Linux swap"
        0x83 -> "Linux"
        0x8E -> "Linux LVM"
        0xEF -> "EFI System"
        0xA5 -> "FreeBSD"
        0xA6 -> "OpenBSD"
        0xA9 -> "NetBSD"
        0xAF -> "macOS"
        else -> null
    }

    private fun detectVolumeLabel(dev: BlockDevice, offsetBytes: Long, sizeBytes: Long): String? {
        if (sizeBytes < 1024L) return null
        return try {
            // 1. FAT
            if (isFatFs(dev, offsetBytes)) {
                val bpb = readBytesAt(dev, offsetBytes, 512)
                if (bpb.size == 512) {
                    val fat32Magic = String(bpb, 82, 8, StandardCharsets.US_ASCII)
                    if (fat32Magic.startsWith("FAT32")) {
                        val extSig = u8(bpb, 66)
                        if (extSig == 0x28 || extSig == 0x29) {
                            val label = String(bpb, 71, 11, StandardCharsets.US_ASCII).substringBefore('\u0000').trim()
                            if (label.isNotEmpty() && !label.equals("NO NAME", ignoreCase = true) && label.all { it in ' '..'~' } && label.any { it.isLetterOrDigit() }) {
                                return label
                            }
                        }
                    }
                    val fat16Magic = String(bpb, 54, 8, StandardCharsets.US_ASCII)
                    if (fat16Magic.startsWith("FAT")) {
                        val extSig = u8(bpb, 38)
                        if (extSig == 0x28 || extSig == 0x29) {
                            val label = String(bpb, 43, 11, StandardCharsets.US_ASCII).substringBefore('\u0000').trim()
                            if (label.isNotEmpty() && !label.equals("NO NAME", ignoreCase = true) && label.all { it in ' '..'~' } && label.any { it.isLetterOrDigit() }) {
                                return label
                            }
                        }
                    }
                }
            }
            // 2. EXT
            if (sizeBytes >= 2048L && isExtFs(dev, offsetBytes)) {
                val sb = readBytesAt(dev, offsetBytes + 1024L, 256)
                if (sb.size >= 136 && u16le(sb, 56) == 0xEF53) {
                    val volName = String(sb, 120, 16, StandardCharsets.UTF_8).substringBefore('\u0000').trim()
                    if (volName.isNotEmpty() && volName.all { it in ' '..'~' } && volName.any { it.isLetterOrDigit() }) {
                        return volName
                    }
                }
            }
            // 3. ISO9660
            if (sizeBytes >= 34816L && isIso9660(dev, offsetBytes)) {
                val pvd = readBytesAt(dev, offsetBytes + 16 * 2048L, 2048)
                if (pvd.size >= 72 && String(pvd, 1, 5, StandardCharsets.US_ASCII) == "CD001") {
                    val volId = String(pvd, 40, 32, StandardCharsets.US_ASCII).substringBefore('\u0000').trim()
                    if (volId.isNotEmpty() && volId.all { it in ' '..'~' } && volId.any { it.isLetterOrDigit() }) {
                        return volId
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun formatPartitionName(partNum: Int, label: String?, typeName: String?): String {
        val cleanLabel = label?.let { sanitizeSegment(it) }?.takeIf { it.isNotEmpty() }
        val cleanType = typeName?.let { sanitizeSegment(it) }?.takeIf { it.isNotEmpty() }

        return when {
            cleanLabel != null && cleanLabel.startsWith("Partition", ignoreCase = true) -> cleanLabel
            cleanLabel != null -> "Partition $partNum ($cleanLabel)"
            cleanType != null -> "Partition $partNum ($cleanType)"
            else -> "Partition $partNum"
        }
    }

    private fun walkEbr(
        dev: BlockDevice,
        extendedStartLba: Long,
        currentEbrLba: Long,
        fileLen: Long,
        depth: Int,
        slices: MutableList<PartitionSlice>
    ) {
        if (depth > 32) return
        val ebrOffset = currentEbrLba * 512L
        if (ebrOffset + 512L > fileLen) return
        val ebr = readBytesAt(dev, ebrOffset, 512)
        if (ebr.size < 512 || u8(ebr, 510) != 0x55 || u8(ebr, 511) != 0xAA) return

        val type1 = u8(ebr, 446 + 4)
        val startLba1 = u32le(ebr, 446 + 8)
        val numSectors1 = u32le(ebr, 446 + 12)
        if (type1 != 0x00 && numSectors1 > 0L) {
            val partStartBytes = (currentEbrLba + startLba1) * 512L
            val partSizeBytes = numSectors1 * 512L
            if (partStartBytes < fileLen) {
                val partNum = slices.size + 1
                val typeName = mbrTypeName(type1)
                val fsLabel = detectVolumeLabel(dev, partStartBytes, partSizeBytes)
                val name = formatPartitionName(partNum, fsLabel, typeName)
                slices += PartitionSlice(name, partStartBytes, partSizeBytes, typeName, fsLabel)
            }
        }

        val type2 = u8(ebr, 462 + 4)
        val startLba2 = u32le(ebr, 462 + 8)
        if ((type2 == 0x05 || type2 == 0x0F) && startLba2 > 0L) {
            walkEbr(dev, extendedStartLba, extendedStartLba + startLba2, fileLen, depth + 1, slices)
        }
    }

    private fun detectPartitions(dev: BlockDevice): List<PartitionSlice> {
        val fileLen = dev.size
        if (fileLen < 1024) return emptyList()

        // Check GPT header at LBA 1 (offset 512)
        val gptHeader = readBytesAt(dev, 512L, 92)
        if (gptHeader.size == 92 && String(gptHeader, 0, 8, StandardCharsets.US_ASCII) == "EFI PART") {
            val partEntryLba = i64le(gptHeader, 72)
            val numPartEntries = u32le(gptHeader, 80).toInt().coerceIn(0, 128)
            val partEntrySize = u32le(gptHeader, 84).toInt().coerceIn(128, 512)
            val slices = mutableListOf<PartitionSlice>()
            for (i in 0 until numPartEntries) {
                val entryOffset = partEntryLba * 512L + i.toLong() * partEntrySize
                if (entryOffset + partEntrySize > fileLen) break
                val entryBytes = readBytesAt(dev, entryOffset, partEntrySize)
                if (entryBytes.take(16).all { it == 0.toByte() }) continue
                val firstLba = i64le(entryBytes, 32)
                val lastLba = i64le(entryBytes, 40)
                if (firstLba <= 0L || lastLba < firstLba) continue
                val startBytes = firstLba * 512L
                val sizeBytes = (lastLba - firstLba + 1L) * 512L
                if (startBytes >= fileLen) continue
                val rawLabel = String(entryBytes, 56, 72, StandardCharsets.UTF_16LE).substringBefore('\u0000').trim()
                val guidStr = formatGptGuid(entryBytes, 0)
                val typeName = GPT_TYPE_NAMES[guidStr]
                val fsLabel = detectVolumeLabel(dev, startBytes, sizeBytes)
                val effectiveLabel = rawLabel.takeIf { it.isNotEmpty() } ?: fsLabel
                val partNum = slices.size + 1
                val name = formatPartitionName(partNum, effectiveLabel, typeName)
                slices += PartitionSlice(name, startBytes, sizeBytes, typeName, effectiveLabel)
            }
            if (slices.isNotEmpty()) return slices
        }

        // Check Apple Partition Map (APM) at sector 1
        val apmSector = readBytesAt(dev, 512L, 512)
        if (apmSector.size == 512 && apmSector[0] == 'P'.code.toByte() && apmSector[1] == 'M'.code.toByte()) {
            val numEntries = u32be(apmSector, 4).toInt().coerceIn(1, 64)
            val slices = mutableListOf<PartitionSlice>()
            for (i in 0 until numEntries) {
                val entryOffset = (i + 1) * 512L
                if (entryOffset + 512L > fileLen) break
                val entry = readBytesAt(dev, entryOffset, 512)
                if (entry.size < 512 || entry[0] != 'P'.code.toByte() || entry[1] != 'M'.code.toByte()) break
                val startLba = u32be(entry, 8)
                val numSectors = u32be(entry, 12)
                val partName = String(entry, 16, 32, StandardCharsets.US_ASCII).substringBefore('\u0000').trim()
                val partType = String(entry, 48, 32, StandardCharsets.US_ASCII).substringBefore('\u0000').trim()
                if (startLba > 0L && numSectors > 0L && partType != "Apple_partition_map" && partType != "Apple_Free") {
                    val startBytes = startLba * 512L
                    val sizeBytes = numSectors * 512L
                    val partNum = slices.size + 1
                    val label = partName.ifEmpty { partType }
                    val name = formatPartitionName(partNum, label, partType)
                    slices += PartitionSlice(name, startBytes, sizeBytes, partType, label)
                }
            }
            if (slices.isNotEmpty()) return slices
        }

        // Check MBR at sector 0
        val mbr = readBytesAt(dev, 0L, 512)
        if (mbr.size == 512 && u8(mbr, 510) == 0x55 && u8(mbr, 511) == 0xAA) {
            val slices = mutableListOf<PartitionSlice>()
            for (i in 0 until 4) {
                val pos = 446 + i * 16
                val status = u8(mbr, pos)
                if (status != 0x00 && status != 0x80) continue
                val type = u8(mbr, pos + 4)
                if (type == 0x00 || type == 0xEE) continue
                val startLba = u32le(mbr, pos + 8)
                val numSectors = u32le(mbr, pos + 12)
                if (startLba <= 0L || numSectors <= 0L) continue
                val startBytes = startLba * 512L
                val sizeBytes = numSectors * 512L
                if (startBytes + 512L > fileLen) continue

                if (type == 0x05 || type == 0x0F) {
                    walkEbr(dev, startLba, startLba, fileLen, 0, slices)
                } else {
                    val partNum = slices.size + 1
                    val typeName = mbrTypeName(type)
                    val fsLabel = detectVolumeLabel(dev, startBytes, sizeBytes)
                    val name = formatPartitionName(partNum, fsLabel, typeName)
                    slices += PartitionSlice(name, startBytes, sizeBytes, typeName, fsLabel)
                }
            }
            if (slices.isNotEmpty()) return slices
        }

        return emptyList()
    }

    // =========================================================================
    // 1. ISO 9660 + Joliet + Rock Ridge
    // =========================================================================

    private fun isIso9660(dev: BlockDevice, baseOffset: Long): Boolean {
        val fileLen = dev.size
        for (sector in 16..20) {
            val vdOffset = baseOffset + sector * 2048L
            if (vdOffset + 7 > fileLen) break
            val magic = readBytesAt(dev, vdOffset + 1, 5)
            if (magic.contentEquals("CD001".toByteArray(StandardCharsets.US_ASCII))) {
                return true
            }
        }
        return false
    }

    private data class IsoDirRecord(
        val extentLba: Long,
        val dataLen: Long,
        val modifiedAtMillis: Long?,
        val isDirectory: Boolean,
        val name: String
    )

    private fun parseIso9660(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        val fileLen = dev.size
        var pvdRoot: ByteArray? = null
        var jolietRoot: ByteArray? = null

        for (sector in 16..64) {
            val vdOffset = baseOffset + sector * 2048L
            if (vdOffset + 2048 > fileLen) break
            val vd = readBytesAt(dev, vdOffset, 2048)
            val type = u8(vd, 0)
            val id = String(vd, 1, 5, StandardCharsets.US_ASCII)
            if (id != "CD001") continue
            when (type) {
                1 -> if (pvdRoot == null) {
                    pvdRoot = vd.copyOfRange(156, 156 + 34)
                }
                2 -> {
                    // Check Joliet UCS-2 escape sequences at bytes 88..119 (%/@, %/C, %/E)
                    val esc0 = u8(vd, 88)
                    val esc1 = u8(vd, 89)
                    val esc2 = u8(vd, 90)
                    if (esc0 == 0x25 && esc1 == 0x2F && (esc2 == 0x40 || esc2 == 0x43 || esc2 == 0x45)) {
                        jolietRoot = vd.copyOfRange(156, 156 + 34)
                    }
                }
                255 -> break
            }
        }

        val rootRecordBytes = jolietRoot ?: pvdRoot
            ?: throw IOException("ISO 9660 Primary Volume Descriptor not found")
        val isJoliet = jolietRoot != null
        val rootRecord = parseIsoDirectoryRecord(rootRecordBytes, 0, isJoliet)
            ?: throw IOException("Invalid ISO 9660 root directory record")

        val entries = mutableListOf<DiscEntry>()
        val visitedExtents = mutableSetOf<Long>()

        fun walkDirectory(dirLba: Long, dirLen: Long, currentPath: String) {
            if (dirLba <= 0L || dirLen <= 0L || !visitedExtents.add(dirLba)) return
            val dirStart = baseOffset + dirLba * 2048L
            if (dirStart >= fileLen) return
            val safeLen = dirLen.coerceAtMost(fileLen - dirStart).coerceAtMost(16 * 1024 * 1024L).toInt()
            val dirBytes = readBytesAt(dev, dirStart, safeLen)

            var pos = 0
            while (pos < dirBytes.size) {
                val recLen = u8(dirBytes, pos)
                if (recLen == 0) {
                    // Advance to next 2048-byte sector boundary
                    val nextSector = ((pos / 2048) + 1) * 2048
                    if (nextSector <= pos) break
                    pos = nextSector
                    continue
                }
                if (pos + recLen > dirBytes.size) break
                val rec = parseIsoDirectoryRecord(dirBytes, pos, isJoliet)
                pos += recLen
                if (rec == null || rec.name.isEmpty() || rec.name == "." || rec.name == "..") continue

                val childPath = joinPath(currentPath, rec.name)
                if (rec.isDirectory) {
                    entries += DiscEntry(
                        path = childPath,
                        isDirectory = true,
                        sizeBytes = null,
                        modifiedAtMillis = rec.modifiedAtMillis
                    )
                    walkDirectory(rec.extentLba, rec.dataLen, childPath)
                } else {
                    val fileOffset = baseOffset + rec.extentLba * 2048L
                    val fileSize = rec.dataLen
                    entries += DiscEntry(
                        path = childPath,
                        isDirectory = false,
                        sizeBytes = fileSize,
                        modifiedAtMillis = rec.modifiedAtMillis,
                        extractor = { d, out ->
                            copyRangeToStream(d, fileOffset, fileSize, out)
                        }
                    )
                }
            }
        }

        walkDirectory(rootRecord.extentLba, rootRecord.dataLen, prefix)
        return entries
    }

    private fun parseIsoDirectoryRecord(buf: ByteArray, offset: Int, isJoliet: Boolean): IsoDirRecord? {
        val recLen = u8(buf, offset)
        if (recLen < 34 || offset + recLen > buf.size) return null
        val extentLba = u32le(buf, offset + 2)
        val dataLen = u32le(buf, offset + 10)

        val year = u8(buf, offset + 18) + 1900
        val month = u8(buf, offset + 19).coerceIn(1, 12)
        val day = u8(buf, offset + 20).coerceIn(1, 31)
        val hour = u8(buf, offset + 21).coerceIn(0, 23)
        val minute = u8(buf, offset + 22).coerceIn(0, 59)
        val second = u8(buf, offset + 23).coerceIn(0, 59)
        val modifiedAtMillis = toUtcMillis(year, month, day, hour, minute, second)

        val flags = u8(buf, offset + 25)
        val isDir = (flags and 0x02) != 0
        val nameLen = u8(buf, offset + 32)
        if (offset + 33 + nameLen > offset + recLen) return null

        if (nameLen == 1) {
            val b0 = u8(buf, offset + 33)
            if (b0 == 0) return IsoDirRecord(extentLba, dataLen, modifiedAtMillis, isDir, ".")
            if (b0 == 1) return IsoDirRecord(extentLba, dataLen, modifiedAtMillis, isDir, "..")
        }

        // Check Rock Ridge NM (Alternate Name) in System Use area
        val suStart = 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
        val rockRidgeName = if (suStart + 5 <= recLen) {
            parseRockRidgeName(buf, offset + suStart, offset + recLen)
        } else null

        val rawDecoded = when {
            !rockRidgeName.isNullOrEmpty() -> rockRidgeName
            isJoliet -> {
                val str = String(buf, offset + 33, nameLen, StandardCharsets.UTF_16BE)
                cleanIsoFileName(str)
            }
            else -> {
                val str = String(buf, offset + 33, nameLen, StandardCharsets.ISO_8859_1)
                cleanIsoFileName(str)
            }
        }
        return IsoDirRecord(extentLba, dataLen, modifiedAtMillis, isDir, rawDecoded)
    }

    private fun parseRockRidgeName(buf: ByteArray, start: Int, end: Int): String? {
        val sb = StringBuilder()
        var pos = start
        while (pos + 4 <= end) {
            val sig0 = u8(buf, pos)
            val sig1 = u8(buf, pos + 1)
            val len = u8(buf, pos + 2)
            if (len < 4 || pos + len > end) break
            if (sig0 == 'N'.code && sig1 == 'M'.code && len > 5) {
                val flags = u8(buf, pos + 4)
                if ((flags and 0x06) == 0) {
                    sb.append(String(buf, pos + 5, len - 5, StandardCharsets.UTF_8))
                }
            }
            pos += len
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun cleanIsoFileName(raw: String): String {
        var s = raw.substringBefore('\u0000')
        val semiIdx = s.lastIndexOf(';')
        if (semiIdx > 0 && s.substring(semiIdx + 1).all { it.isDigit() }) {
            s = s.substring(0, semiIdx)
        }
        if (s.endsWith('.')) {
            s = s.dropLast(1)
        }
        return s.trim()
    }

    // =========================================================================
    // 2. FAT12 / FAT16 / FAT32 + VFAT Long File Names
    // =========================================================================

    private fun isFatFs(dev: BlockDevice, baseOffset: Long): Boolean {
        if (dev.size < baseOffset + 512L) return false
        val bpb = readBytesAt(dev, baseOffset, 512)
        if (bpb.size < 512) return false
        if (String(bpb, 3, 8, StandardCharsets.US_ASCII) == "EXFAT   ") return false

        val bytesPerSector = u16le(bpb, 11)
        if (bytesPerSector !in setOf(512, 1024, 2048, 4096)) return false
        val sectorsPerCluster = u8(bpb, 13)
        if (sectorsPerCluster == 0 || (sectorsPerCluster and (sectorsPerCluster - 1)) != 0 || sectorsPerCluster > 128) {
            return false
        }
        val reservedSectors = u16le(bpb, 14)
        if (reservedSectors <= 0) return false
        val numFats = u8(bpb, 16)
        if (numFats !in 1..4) return false

        val totalSectors16 = u16le(bpb, 19)
        val totalSectors32 = u32le(bpb, 32)
        val totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32
        if (totalSectors <= reservedSectors) return false

        val sectorsPerFat16 = u16le(bpb, 22)
        val sectorsPerFat32 = u32le(bpb, 36)
        val sectorsPerFat = if (sectorsPerFat16 != 0) sectorsPerFat16.toLong() else sectorsPerFat32
        if (sectorsPerFat <= 0L) return false

        val fsType16 = String(bpb, 54, 8, StandardCharsets.US_ASCII)
        val fsType32 = String(bpb, 82, 8, StandardCharsets.US_ASCII)
        if (fsType16.startsWith("FAT") || fsType32.startsWith("FAT")) return true

        val jump0 = u8(bpb, 0)
        val hasBootSig510 = u8(bpb, 510) == 0x55 && u8(bpb, 511) == 0xAA
        val hasBootSigAtSec = if (bytesPerSector > 512 && dev.size >= baseOffset + bytesPerSector) {
            val secEnd = readBytesAt(dev, baseOffset + bytesPerSector - 2, 2)
            secEnd.size == 2 && u8(secEnd, 0) == 0x55 && u8(secEnd, 1) == 0xAA
        } else false
        val hasBootSig = hasBootSig510 || hasBootSigAtSec
        if ((jump0 == 0xEB || jump0 == 0xE9) && hasBootSig) return true

        // Accept EFI System Partitions and non-standard FAT with valid BPB geometry and signature
        return hasBootSig || (jump0 == 0xEB || jump0 == 0xE9)
    }

    private fun parseFatFs(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        return FatFileSystem(dev, baseOffset, prefix).readEntries()
    }

    // =========================================================================
    // 3. exFAT
    // =========================================================================

    private fun isExFat(dev: BlockDevice, baseOffset: Long): Boolean {
        if (dev.size < baseOffset + 512L) return false
        val header = readBytesAt(dev, baseOffset, 12)
        return header.size >= 11 && String(header, 3, 8, StandardCharsets.US_ASCII) == "EXFAT   "
    }

    private fun parseExFat(dev: BlockDevice, baseOffset: Long, prefix: String): List<DiscEntry> {
        val boot = readBytesAt(dev, baseOffset, 512)
        val fatOffsetSectors = u32le(boot, 80)
        val clusterHeapOffsetSectors = u32le(boot, 88)
        val rootCluster = u32le(boot, 96)
        val bytesPerSectorShift = u8(boot, 108).coerceIn(9, 12)
        val sectorsPerClusterShift = u8(boot, 109).coerceIn(0, 25 - bytesPerSectorShift)

        val bytesPerSector = 1L shl bytesPerSectorShift
        val clusterSize = (1L shl (bytesPerSectorShift + sectorsPerClusterShift)).toInt()
        val fatStartOffset = baseOffset + fatOffsetSectors * bytesPerSector
        val clusterHeapOffset = baseOffset + clusterHeapOffsetSectors * bytesPerSector

        fun getNextExFatCluster(cluster: Long): Long? {
            if (cluster < 2L) return null
            val entOffset = fatStartOffset + cluster * 4L
            val quad = readBytesAt(dev, entOffset, 4)
            if (quad.size < 4) return null
            val next = u32le(quad, 0)
            return if (next in 2L..0xFFFFFFF6L) next else null
        }

        fun getExFatClusterList(startCluster: Long, byteLength: Long, noFatChain: Boolean): List<Long> {
            if (startCluster < 2L || byteLength <= 0L) return emptyList()
            val numClusters = ((byteLength + clusterSize - 1L) / clusterSize).coerceAtMost(262144L).toInt()
            if (noFatChain) {
                return (0 until numClusters).map { startCluster + it }
            }
            val list = mutableListOf<Long>()
            val visited = HashSet<Long>()
            var curr: Long? = startCluster
            while (curr != null && curr >= 2L && visited.add(curr) && list.size < numClusters) {
                list += curr
                curr = getNextExFatCluster(curr)
            }
            return list
        }

        fun readExFatData(startCluster: Long, byteLength: Long, noFatChain: Boolean): ByteArray {
            val safeLen = byteLength.coerceIn(0L, 16 * 1024 * 1024L)
            val clusters = getExFatClusterList(startCluster, safeLen, noFatChain)
            val out = ByteArrayOutputStream(safeLen.toInt())
            var remaining = safeLen
            for (c in clusters) {
                if (remaining <= 0L) break
                val cOffset = clusterHeapOffset + (c - 2L) * clusterSize
                val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
                out.write(readBytesAt(dev, cOffset, toRead))
                remaining -= toRead
            }
            return out.toByteArray()
        }

        val entries = mutableListOf<DiscEntry>()
        val visitedDirs = HashSet<Long>()

        fun walkExFatDir(dirCluster: Long, dirByteLen: Long, noFatChain: Boolean, currentPath: String) {
            if (dirCluster < 2L || !visitedDirs.add(dirCluster)) return
            val dirBytes = readExFatData(dirCluster, dirByteLen, noFatChain)
            var pos = 0
            while (pos + 32 <= dirBytes.size) {
                val entryType = u8(dirBytes, pos)
                if (entryType == 0x00) break
                if (entryType == 0x85) {
                    val secondaryCount = u8(dirBytes, pos + 1)
                    if (secondaryCount >= 2 && pos + (secondaryCount + 1) * 32 <= dirBytes.size) {
                        val fileAttrs = u16le(dirBytes, pos + 4)
                        val isDir = (fileAttrs and 0x10) != 0
                        val modTimestamp = u32le(dirBytes, pos + 12).toInt()
                        val modDate = (modTimestamp ushr 16) and 0xFFFF
                        val modTime = modTimestamp and 0xFFFF
                        val modifiedAtMillis = decodeDosDateTime(modDate, modTime)

                        val streamPos = pos + 32
                        val streamType = u8(dirBytes, streamPos)
                        if (streamType == 0xC0) {
                            val genFlags = u8(dirBytes, streamPos + 1)
                            val childNoFatChain = (genFlags and 0x02) != 0
                            val nameLenChars = u8(dirBytes, streamPos + 3)
                            val validDataLen = i64le(dirBytes, streamPos + 8)
                            val firstCluster = u32le(dirBytes, streamPos + 20)
                            val dataLen = i64le(dirBytes, streamPos + 24)
                            val actualSize = if (validDataLen in 0..dataLen) validDataLen else dataLen

                            val sb = StringBuilder(nameLenChars)
                            for (s in 2..secondaryCount) {
                                val fnPos = pos + s * 32
                                if (u8(dirBytes, fnPos) != 0xC1) break
                                val charsInEntry = (nameLenChars - sb.length).coerceIn(0, 15)
                                if (charsInEntry > 0) {
                                    sb.append(String(dirBytes, fnPos + 2, charsInEntry * 2, StandardCharsets.UTF_16LE))
                                }
                            }
                            val name = sb.toString().substringBefore('\u0000').trim()
                            if (name.isNotEmpty() && name != "." && name != "..") {
                                val childPath = joinPath(currentPath, name)
                                if (isDir) {
                                    entries += DiscEntry(
                                        path = childPath,
                                        isDirectory = true,
                                        sizeBytes = null,
                                        modifiedAtMillis = modifiedAtMillis
                                    )
                                    val subDirLen = if (actualSize > 0L) actualSize else (clusterSize.toLong() * 64L)
                                    walkExFatDir(firstCluster, subDirLen, childNoFatChain, childPath)
                                } else {
                                    entries += DiscEntry(
                                        path = childPath,
                                        isDirectory = false,
                                        sizeBytes = actualSize,
                                        modifiedAtMillis = modifiedAtMillis,
                                        extractor = { d, out ->
                                            var remaining = actualSize
                                            val clusters = getExFatClusterList(firstCluster, actualSize, childNoFatChain)
                                            for (c in clusters) {
                                                if (remaining <= 0L) break
                                                val cOffset = clusterHeapOffset + (c - 2L) * clusterSize
                                                val toRead = remaining.coerceAtMost(clusterSize.toLong()).toInt()
                                                copyRangeToStream(d, cOffset, toRead.toLong(), out)
                                                remaining -= toRead
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        pos += (secondaryCount + 1) * 32
                        continue
                    }
                }
                pos += 32
            }
        }

        walkExFatDir(rootCluster, clusterSize.toLong() * 64L, false, prefix)
        return entries
    }

    // =========================================================================
    // 4. ext2 / ext3 / ext4 (Direct/Indirect Block Pointers + Extent Trees)
    // =========================================================================

    private fun isExtFs(dev: BlockDevice, baseOffset: Long): Boolean {
        if (dev.size < baseOffset + 2048L) return false
        val magicBytes = readBytesAt(dev, baseOffset + 1024L + 56L, 2)
        if (magicBytes.size < 2) return false
        return u16le(magicBytes, 0) == 0xEF53
    }

    private data class ExtRun(
        val logicalBlock: Long,
        val physBlock: Long, // 0 means sparse hole (zeros)
        val blockCount: Long
    )

    private fun parseExtFs(dev: BlockDevice, baseOffset: Long, prefix: String, directoryPath: String? = null): List<DiscEntry> {
        val sb = readBytesAt(dev, baseOffset + 1024L, 1024)
        val inodesCount = u32le(sb, 0)
        val logBlockSize = u32le(sb, 24).toInt()
        if (logBlockSize !in 0..6) throw IOException("Invalid ext* block size shift: $logBlockSize")
        val blockSize = 1024 shl logBlockSize
        val inodesPerGroup = u32le(sb, 40)
        val revLevel = u32le(sb, 76).toInt()
        val inodeSize = if (revLevel >= 1) u16le(sb, 88).coerceAtLeast(128) else 128
        val featureIncompat = u32le(sb, 96).toInt()
        val is64Bit = (featureIncompat and 0x80) != 0
        val rawDescSize = u16le(sb, 254)
        val descSize = if (is64Bit && rawDescSize >= 64) rawDescSize else 32

        val gdtOffset = baseOffset + if (blockSize == 1024) 2048L else blockSize.toLong()

        fun getInodeOffset(inodeNum: Long): Long {
            val idx = inodeNum - 1L
            val group = idx / inodesPerGroup
            val indexInGroup = idx % inodesPerGroup
            val gdBytes = readBytesAt(dev, gdtOffset + group * descSize, descSize)
            val tableLo = u32le(gdBytes, 8)
            val tableHi = if (descSize >= 64) u32le(gdBytes, 40) else 0L
            val inodeTableBlock = (tableHi shl 32) or tableLo
            return baseOffset + inodeTableBlock * blockSize + indexInGroup * inodeSize
        }

        fun parseExtentHeader(nodeBytes: ByteArray, offset: Int, maxLen: Int, outRuns: MutableList<ExtRun>, depthGuard: Int) {
            val endLimit = (offset + maxLen).coerceAtMost(nodeBytes.size)
            if (depthGuard > 8 || offset + 12 > endLimit) return
            val magic = u16le(nodeBytes, offset)
            if (magic != 0xF30A) return
            val entriesCount = u16le(nodeBytes, offset + 2)
            val depth = u16le(nodeBytes, offset + 6)

            if (depth == 0) {
                for (i in 0 until entriesCount) {
                    val pos = offset + 12 + i * 12
                    if (pos + 12 > endLimit) break
                    val eeBlock = u32le(nodeBytes, pos)
                    val rawLen = u16le(nodeBytes, pos + 4)
                    val isUninitialized = rawLen > 32768
                    val eeLen = (if (isUninitialized) rawLen - 32768 else rawLen).toLong()
                    val startHi = u16le(nodeBytes, pos + 6).toLong()
                    val startLo = u32le(nodeBytes, pos + 8)
                    val physBlock = if (isUninitialized) 0L else ((startHi shl 32) or startLo)
                    if (eeLen > 0L) {
                        outRuns += ExtRun(eeBlock, physBlock, eeLen)
                    }
                }
            } else {
                for (i in 0 until entriesCount) {
                    val pos = offset + 12 + i * 12
                    if (pos + 12 > endLimit) break
                    val leafLo = u32le(nodeBytes, pos + 4)
                    val leafHi = u16le(nodeBytes, pos + 8).toLong()
                    val leafBlock = (leafHi shl 32) or leafLo
                    val childBytes = readBytesAt(dev, baseOffset + leafBlock * blockSize, blockSize)
                    parseExtentHeader(childBytes, 0, childBytes.size, outRuns, depthGuard + 1)
                }
            }
        }

        fun collectIndirectBlocks(indBlock: Long, level: Int, maxBlocks: Int, outBlocks: MutableList<Long>) {
            if (level <= 0 || outBlocks.size >= maxBlocks) return
            val ptrsPerBlock = blockSize / 4
            if (indBlock == 0L) {
                var count = 1
                for (i in 1 until level) count *= ptrsPerBlock
                val toAdd = count.coerceAtMost(maxBlocks - outBlocks.size)
                repeat(toAdd) { outBlocks += 0L }
                return
            }
            val blockBytes = readBytesAt(dev, baseOffset + indBlock * blockSize, blockSize)
            for (i in 0 until ptrsPerBlock) {
                if (outBlocks.size >= maxBlocks || i * 4 + 4 > blockBytes.size) break
                val ptr = u32le(blockBytes, i * 4)
                if (level == 1) {
                    outBlocks += ptr
                } else {
                    collectIndirectBlocks(ptr, level - 1, maxBlocks, outBlocks)
                }
            }
        }

        fun getInodeRuns(inodeBytes: ByteArray, fileSize: Long): List<ExtRun> {
            val flags = u32le(inodeBytes, 32).toInt()
            val usesExtents = (flags and 0x00080000) != 0
            if (usesExtents) {
                val runs = mutableListOf<ExtRun>()
                parseExtentHeader(inodeBytes, 40, 60, runs, 0)
                return runs.sortedBy { it.logicalBlock }
            }

            val totalBlocksNeeded = ((fileSize + blockSize - 1L) / blockSize).coerceIn(0L, 262144L).toInt()
            if (totalBlocksNeeded == 0) return emptyList()
            val blocks = ArrayList<Long>(totalBlocksNeeded.coerceAtMost(4096))
            for (i in 0 until 12) {
                if (blocks.size >= totalBlocksNeeded) break
                blocks += u32le(inodeBytes, 40 + i * 4)
            }
            if (blocks.size < totalBlocksNeeded) {
                val ind1 = u32le(inodeBytes, 40 + 12 * 4)
                collectIndirectBlocks(ind1, 1, totalBlocksNeeded, blocks)
            }
            if (blocks.size < totalBlocksNeeded) {
                val ind2 = u32le(inodeBytes, 40 + 13 * 4)
                collectIndirectBlocks(ind2, 2, totalBlocksNeeded, blocks)
            }
            if (blocks.size < totalBlocksNeeded) {
                val ind3 = u32le(inodeBytes, 40 + 14 * 4)
                collectIndirectBlocks(ind3, 3, totalBlocksNeeded, blocks)
            }

            val runs = mutableListOf<ExtRun>()
            var idx = 0L
            for (phys in blocks) {
                val last = runs.lastOrNull()
                if (last != null && last.logicalBlock + last.blockCount == idx &&
                    ((last.physBlock == 0L && phys == 0L) || (last.physBlock != 0L && last.physBlock + last.blockCount == phys))
                ) {
                    runs[runs.lastIndex] = last.copy(blockCount = last.blockCount + 1L)
                } else {
                    runs += ExtRun(idx, phys, 1L)
                }
                idx++
            }
            return runs
        }

        fun streamInodeData(r: BlockDevice, inodeBytes: ByteArray, fileSize: Long, out: OutputStream) {
            if (fileSize <= 0L) return
            val runs = getInodeRuns(inodeBytes, fileSize)
            var currentLogicalBlock = 0L
            var remainingBytes = fileSize
            val zeroBuf = ByteArray(blockSize.coerceAtMost(65536))

            fun writeZeros(byteCount: Long) {
                var rem = byteCount
                while (rem > 0L) {
                    val chunk = rem.coerceAtMost(zeroBuf.size.toLong()).toInt()
                    out.write(zeroBuf, 0, chunk)
                    rem -= chunk
                }
            }

            for (run in runs) {
                if (remainingBytes <= 0L) break
                if (run.logicalBlock > currentLogicalBlock) {
                    val gapBlocks = run.logicalBlock - currentLogicalBlock
                    val gapBytes = (gapBlocks * blockSize).coerceAtMost(remainingBytes)
                    writeZeros(gapBytes)
                    remainingBytes -= gapBytes
                    currentLogicalBlock = run.logicalBlock
                }
                if (remainingBytes <= 0L) break
                val runBytes = (run.blockCount * blockSize).coerceAtMost(remainingBytes)
                if (run.physBlock == 0L) {
                    writeZeros(runBytes)
                } else {
                    copyRangeToStream(r, baseOffset + run.physBlock * blockSize, runBytes, out)
                }
                remainingBytes -= runBytes
                currentLogicalBlock += run.blockCount
            }
            if (remainingBytes > 0L) {
                writeZeros(remainingBytes)
            }
        }

        val entries = mutableListOf<DiscEntry>()
        val visitedInodes = HashSet<Long>()
        val scanStarted = android.os.SystemClock.uptimeMillis()
        var lastScanReport = scanStarted
        com.infinifolder.storage.DiagnosticLog.record("disc.ext.scan-begin", "deviceBytes=${dev.size} blockBytes=$blockSize inodeBytes=$inodeSize totalInodes=$inodesCount")

        val targetDirectory = directoryPath?.let(::normalizePath)
        var foundDirectory = targetDirectory == null
        fun walkExtDir(dirInodeNum: Long, currentPath: String) {
            RecoveryCancellation.check()
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastScanReport >= 5000L) {
                lastScanReport = now
                com.infinifolder.storage.DiagnosticLog.record("disc.ext.scan-progress", "elapsedMs=${now - scanStarted} directories=${visitedInodes.size} entries=${entries.size}")
            }
            if (dirInodeNum < 2L || dirInodeNum > inodesCount || !visitedInodes.add(dirInodeNum)) return
            val listing = targetDirectory == null || normalizePath(currentPath) == targetDirectory
            if (listing) foundDirectory = true
            val inodeBytes = readBytesAt(dev, getInodeOffset(dirInodeNum), inodeSize)
            val dirSize = u32le(inodeBytes, 4).coerceAtMost(16 * 1024 * 1024L)
            val baos = ByteArrayOutputStream(dirSize.toInt().coerceAtLeast(blockSize))
            streamInodeData(dev, inodeBytes, dirSize, baos)
            val dirBytes = baos.toByteArray()

            var pos = 0
            while (pos + 8 <= dirBytes.size) {
                val childInode = u32le(dirBytes, pos)
                val recLen = u16le(dirBytes, pos + 4)
                if (recLen < 8 || pos + recLen > dirBytes.size) break
                val nameLen = u8(dirBytes, pos + 6)
                if (childInode in 1L..inodesCount && nameLen > 0 && pos + 8 + nameLen <= pos + recLen) {
                    val name = String(dirBytes, pos + 8, nameLen, StandardCharsets.UTF_8)
                    if (name != "." && name != "..") {
                        val candidatePath = joinPath(currentPath, name)
                        // Follow only the ancestors of the requested folder. Do not
                        // fetch inodes for unrelated files or descend into children.
                        if (!listing && targetDirectory != candidatePath && targetDirectory?.startsWith("$candidatePath/") != true) {
                            pos += recLen
                            continue
                        }
                        val childInodeBytes = readBytesAt(dev, getInodeOffset(childInode), inodeSize)
                        val mode = u16le(childInodeBytes, 0)
                        val linuxIds = u32le(sb, 72) == 0L && childInodeBytes.size >= 124
                        val uid = u16le(childInodeBytes, 2).toLong() or (if (linuxIds) u16le(childInodeBytes, 120).toLong() shl 16 else 0L)
                        val gid = u16le(childInodeBytes, 24).toLong() or (if (linuxIds) u16le(childInodeBytes, 122).toLong() shl 16 else 0L)
                        val isDir = (mode and 0xF000) == 0x4000
                        val isLink = (mode and 0xF000) == 0xA000
                        val mtimeSec = u32le(childInodeBytes, 16)
                        val modifiedAtMillis = if (mtimeSec > 0L) mtimeSec * 1000L else null
                        val sizeLo = u32le(childInodeBytes, 4)
                        val sizeHi = if (!isDir) u32le(childInodeBytes, 108) else 0L
                        val childSize = (sizeHi shl 32) or sizeLo

                        val childPath = joinPath(currentPath, name)
                        if (isDir) {
                            entries += DiscEntry(
                                path = childPath,
                                isDirectory = true,
                                sizeBytes = null,
                                modifiedAtMillis = modifiedAtMillis,
                                permissionsMode = mode and 0xfff, owner = uid.toString(), group = gid.toString()
                            )
                            if (targetDirectory == null || !listing) walkExtDir(childInode, childPath)
                        } else if (isLink) {
                            // Fast ext symlinks store their target in i_block, not disk block pointers.
                            if (childSize > 4096) throw IOException("Invalid ext symbolic-link size: $childSize")
                            val targetBytes = if (childSize <= 60 && u32le(childInodeBytes, 28) == 0L) {
                                childInodeBytes.copyOfRange(40, 40 + childSize.toInt())
                            } else ByteArrayOutputStream().also { streamInodeData(dev, childInodeBytes, childSize, it) }.toByteArray()
                            entries += DiscEntry(childPath, false, childSize, modifiedAtMillis,
                                isSymbolicLink = true, linkTarget = String(targetBytes, StandardCharsets.UTF_8),
                                permissionsMode = mode and 0xfff, owner = uid.toString(), group = gid.toString())
                        } else {
                            val snapshotInode = childInodeBytes.copyOf()
                            entries += DiscEntry(
                                path = childPath,
                                isDirectory = false,
                                sizeBytes = childSize,
                                modifiedAtMillis = modifiedAtMillis,
                                extractor = { d, out ->
                                    streamInodeData(d, snapshotInode, childSize, out)
                                }, permissionsMode = mode and 0xfff, owner = uid.toString(), group = gid.toString()
                            )
                        }
                    }
                }
                pos += recLen
            }
        }

        walkExtDir(2L, prefix)
        if (!foundDirectory) throw IOException("Directory not found in ext filesystem: $directoryPath")
        com.infinifolder.storage.DiagnosticLog.record("disc.ext.scan-complete", "elapsedMs=${android.os.SystemClock.uptimeMillis() - scanStarted} directories=${visitedInodes.size} entries=${entries.size}")
        return if (targetDirectory == null) entries else entries.filter {
            normalizePath(it.path).substringBeforeLast('/', "") == targetDirectory
        }
    }

    // =========================================================================
    // Low-Level Little-Endian Primitives, I/O & Date Helpers
    // =========================================================================

    private fun u8(buf: ByteArray, offset: Int): Int =
        buf[offset].toInt() and 0xFF

    private fun u16le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32le(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)

    private fun u32be(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL)

    private fun i64le(buf: ByteArray, offset: Int): Long =
        u32le(buf, offset) or (u32le(buf, offset + 4) shl 32)

    private fun readBytesAt(dev: BlockDevice, offset: Long, length: Int): ByteArray {
        return dev.readBytes(offset, length)
    }

    private fun copyRangeToStream(dev: BlockDevice, offset: Long, length: Long, out: OutputStream) {
        if (length <= 0L) return
        var remaining = length
        val buffer = ByteArray(65536)
        var curOffset = offset
        while (remaining > 0L) {
            val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
            val read = dev.readAt(curOffset, buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            curOffset += read
            remaining -= read
        }
    }

    private fun decodeDosDateTime(dosDate: Int, dosTime: Int): Long? {
        if (dosDate == 0) return null
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val month = ((dosDate ushr 5) and 0x0F).coerceIn(1, 12)
        val day = (dosDate and 0x1F).coerceIn(1, 31)
        val hour = ((dosTime ushr 11) and 0x1F).coerceIn(0, 23)
        val minute = ((dosTime ushr 5) and 0x3F).coerceIn(0, 59)
        val second = ((dosTime and 0x1F) * 2).coerceIn(0, 59)
        return toUtcMillis(year, month, day, hour, minute, second)
    }

    private fun toUtcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun sanitizeSegment(name: String): String =
        name.replace('/', '_').replace('\\', '_').trim()

    private fun joinPath(parent: String, child: String): String {
        val cleanParent = normalizePath(parent)
        val cleanChild = sanitizeSegment(child)
        return if (cleanParent.isEmpty()) cleanChild else "$cleanParent/$cleanChild"
    }

    private fun normalizePath(rawName: String): String =
        rawName.replace('\\', '/').trim().removePrefix("/").removePrefix("./").trim('/')

    private fun sha256(str: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
