package com.infinifolder.archive

import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceRef
import com.infinifolder.storage.StorageProvider
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Date

object ArchiveWriter {

    private val SUPPORTED_FORMATS = setOf(
        "zip", "jar", "apk", "cbz",
        "tar", "cbt",
        "tar.gz", "tgz",
        "tar.bz2", "tbz", "tbz2",
        "tar.xz", "txz",
        "7z", "cb7",
        "cpio",
        "ar"
    )

    fun normalizeArchiveName(input: String): String {
        val trimmed = input.trim()
        val nameOnly = trimmed.substringAfterLast('/')
        return if (!nameOnly.contains('.')) {
            "$trimmed.zip"
        } else {
            trimmed
        }
    }

    fun isSupportedFormat(archiveName: String): Boolean {
        val ext = ArchiveFormats.archiveExtension(archiveName)
        return SUPPORTED_FORMATS.contains(ext)
    }

    fun supportedFormatsDescription(): String =
        ".zip, .tar, .tar.gz, .tar.bz2, .tar.xz, .7z, .cpio, .ar"

    private data class ItemToArchive(
        val archivePath: String,
        val entry: ResourceEntry,
        val isDirectory: Boolean
    )

    class ProgressTracker(
        val totalBytes: Long?,
        val onByteProgress: ((Long, Long?, String, Long) -> Unit)?
    ) {
        var totalWritten: Long = 0L
        var lastTime: Long = System.currentTimeMillis()
        var bytesSinceLast: Long = 0L

        fun onBytes(written: Int, currentPath: String) {
            totalWritten += written
            bytesSinceLast += written
            val now = System.currentTimeMillis()
            if (now - lastTime >= 300) {
                val speed = (bytesSinceLast * 1000) / (now - lastTime).coerceAtLeast(1)
                lastTime = now
                bytesSinceLast = 0L
                onByteProgress?.invoke(totalWritten, totalBytes, currentPath, speed)
            }
        }
    }

    private fun copyStream(
        input: java.io.InputStream,
        output: OutputStream,
        currentPath: String,
        tracker: ProgressTracker?,
        isCancelled: () -> Boolean
    ) {
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (input.read(buffer).also { read = it } >= 0) {
            if (isCancelled()) throw InterruptedException("Archive creation cancelled")
            output.write(buffer, 0, read)
            tracker?.onBytes(read, currentPath)
        }
    }

    fun createArchive(
        targetStream: OutputStream,
        archiveName: String,
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false },
        onProgress: ((String) -> Unit)? = null,
        onByteProgress: ((Long, Long?, String, Long) -> Unit)? = null,
        tempDir: File? = null
    ) {
        val ext = ArchiveFormats.archiveExtension(archiveName)
        require(isSupportedFormat(archiveName)) { "Unsupported archive format: $ext" }

        val items = collectItems(entries, provider, isCancelled)
        val totalBytes = items.filter { !it.isDirectory }.mapNotNull { it.entry.metadata.sizeBytes }.sum().takeIf { it > 0 }
        val tracker = if (onByteProgress != null) ProgressTracker(totalBytes, onByteProgress) else null

        when {
            ext == "zip" || ext == "jar" || ext == "apk" || ext == "cbz" -> {
                writeZip(targetStream, items, provider, isCancelled, onProgress, tracker)
            }
            ext == "7z" || ext == "cb7" -> {
                write7zToStream(targetStream, items, provider, isCancelled, onProgress, tracker, tempDir)
            }
            ext == "cpio" -> {
                writeCpio(targetStream, items, provider, isCancelled, onProgress, tracker)
            }
            ext == "ar" -> {
                writeAr(targetStream, items, provider, isCancelled, onProgress, tracker)
            }
            else -> {
                writeTar(targetStream, ext, items, provider, isCancelled, onProgress, tracker)
            }
        }
    }

    fun createArchive(
        targetFile: File,
        archiveName: String,
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false },
        onProgress: ((String) -> Unit)? = null,
        onByteProgress: ((Long, Long?, String, Long) -> Unit)? = null
    ) {
        val ext = ArchiveFormats.archiveExtension(archiveName)
        require(isSupportedFormat(archiveName)) { "Unsupported archive format: $ext" }

        targetFile.parentFile?.mkdirs()

        if (ext == "7z" || ext == "cb7") {
            val items = collectItems(entries, provider, isCancelled)
            val totalBytes = items.filter { !it.isDirectory }.mapNotNull { it.entry.metadata.sizeBytes }.sum().takeIf { it > 0 }
            val tracker = if (onByteProgress != null) ProgressTracker(totalBytes, onByteProgress) else null
            write7z(targetFile, items, provider, isCancelled, onProgress, tracker)
            return
        }

        BufferedOutputStream(targetFile.outputStream()).use { stream ->
            createArchive(
                targetStream = stream,
                archiveName = archiveName,
                entries = entries,
                provider = provider,
                isCancelled = isCancelled,
                onProgress = onProgress,
                onByteProgress = onByteProgress,
                tempDir = targetFile.parentFile
            )
        }
    }

    private fun collectItems(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean
    ): List<ItemToArchive> {
        val items = mutableListOf<ItemToArchive>()
        fun collect(entry: ResourceEntry, relativePath: String) {
            if (isCancelled()) throw InterruptedException("Archive creation cancelled")
            if (entry.metadata.isDirectory) {
                items.add(ItemToArchive("$relativePath/", entry, true))
                val listResult = provider.list(entry.ref)
                if (listResult is ProviderResult.Success) {
                    for (child in listResult.value) {
                        collect(child, "$relativePath/${child.metadata.name}")
                    }
                }
            } else {
                val finalPath = if (entry.ref.providerId == "apps" && !relativePath.endsWith(".apk", ignoreCase = true)) {
                    "$relativePath.apk"
                } else {
                    relativePath
                }
                items.add(ItemToArchive(finalPath, entry, false))
            }
        }
        for (entry in entries) {
            collect(entry, entry.metadata.name)
        }
        return items
    }

    private fun writeZip(
        targetStream: OutputStream,
        items: List<ItemToArchive>,
        provider: StorageProvider,
        isCancelled: () -> Boolean,
        onProgress: ((String) -> Unit)?,
        tracker: ProgressTracker?
    ) {
        ZipArchiveOutputStream(BufferedOutputStream(targetStream)).use { zos ->
            for (item in items) {
                if (isCancelled()) throw InterruptedException("Archive creation cancelled")
                onProgress?.invoke(item.archivePath)
                val zae = ZipArchiveEntry(item.archivePath)
                if (item.isDirectory) {
                    zos.putArchiveEntry(zae)
                    zos.closeArchiveEntry()
                } else {
                    item.entry.metadata.sizeBytes?.let { zae.size = it }
                    item.entry.metadata.modifiedAtMillis?.let { zae.time = it }
                    zos.putArchiveEntry(zae)
                    val readRes = provider.openRead(item.entry.ref)
                    if (readRes is ProviderResult.Success) {
                        readRes.value.use { input -> copyStream(input, zos, item.archivePath, tracker, isCancelled) }
                    }
                    zos.closeArchiveEntry()
                }
            }
        }
    }

    private fun writeTar(
        targetStream: OutputStream,
        ext: String,
        items: List<ItemToArchive>,
        provider: StorageProvider,
        isCancelled: () -> Boolean,
        onProgress: ((String) -> Unit)?,
        tracker: ProgressTracker?
    ) {
        val buffered = BufferedOutputStream(targetStream)
        val compressor: OutputStream = when (ext) {
            "tar.gz", "tgz" -> GzipCompressorOutputStream(buffered)
            "tar.bz2", "tbz", "tbz2" -> BZip2CompressorOutputStream(buffered)
            "tar.xz", "txz" -> XZCompressorOutputStream(buffered)
            else -> buffered
        }

        TarArchiveOutputStream(compressor).use { tos ->
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (item in items) {
                if (isCancelled()) throw InterruptedException("Archive creation cancelled")
                onProgress?.invoke(item.archivePath)
                val tae = TarArchiveEntry(item.archivePath)
                if (item.isDirectory) {
                    tos.putArchiveEntry(tae)
                    tos.closeArchiveEntry()
                } else {
                    val size = item.entry.metadata.sizeBytes
                    val readRes = provider.openRead(item.entry.ref)
                    if (readRes is ProviderResult.Success) {
                        readRes.value.use { input ->
                            if (size != null) {
                                tae.size = size
                                item.entry.metadata.modifiedAtMillis?.let { tae.modTime = Date(it) }
                                tos.putArchiveEntry(tae)
                                copyStream(input, tos, item.archivePath, tracker, isCancelled)
                            } else {
                                val bytes = input.readBytes()
                                tae.size = bytes.size.toLong()
                                item.entry.metadata.modifiedAtMillis?.let { tae.modTime = Date(it) }
                                tos.putArchiveEntry(tae)
                                tos.write(bytes)
                                tracker?.onBytes(bytes.size, item.archivePath)
                            }
                        }
                    }
                    tos.closeArchiveEntry()
                }
            }
        }
    }

    private fun write7zToStream(
        targetStream: OutputStream,
        items: List<ItemToArchive>,
        provider: StorageProvider,
        isCancelled: () -> Boolean,
        onProgress: ((String) -> Unit)?,
        tracker: ProgressTracker?,
        tempDir: File?
    ) {
        val temp7z = File.createTempFile("tmp_7z_", ".7z", tempDir)
        try {
            write7z(temp7z, items, provider, isCancelled, onProgress, tracker)
            temp7z.inputStream().buffered().use { inStream ->
                val buffer = ByteArray(64 * 1024)
                var r: Int
                while (inStream.read(buffer).also { r = it } >= 0) {
                    if (isCancelled()) throw InterruptedException("Archive creation cancelled")
                    targetStream.write(buffer, 0, r)
                }
            }
        } finally {
            temp7z.delete()
        }
    }

    private fun write7z(
        targetFile: File,
        items: List<ItemToArchive>,
        provider: StorageProvider,
        isCancelled: () -> Boolean,
        onProgress: ((String) -> Unit)?,
        tracker: ProgressTracker?
    ) {
        SevenZOutputFile(targetFile).use { sz ->
            for (item in items) {
                if (isCancelled()) throw InterruptedException("Archive creation cancelled")
                onProgress?.invoke(item.archivePath)
                val dummy = File(item.archivePath)
                val sae = sz.createArchiveEntry(dummy, item.archivePath)
                if (item.isDirectory) {
                    sz.putArchiveEntry(sae)
                    sz.closeArchiveEntry()
                } else {
                    val bytes = provider.openRead(item.entry.ref).let {
                        if (it is ProviderResult.Success) it.value.use { input -> input.readBytes() } else ByteArray(0)
                    }
                    sae.size = bytes.size.toLong()
                    item.entry.metadata.modifiedAtMillis?.let { sae.lastModifiedDate = Date(it) }
                    sz.putArchiveEntry(sae)
                    sz.write(bytes)
                    tracker?.onBytes(bytes.size, item.archivePath)
                    sz.closeArchiveEntry()
                }
            }
        }
    }

    private fun writeCpio(
        targetStream: OutputStream,
        items: List<ItemToArchive>,
        provider: StorageProvider,
        isCancelled: () -> Boolean,
        onProgress: ((String) -> Unit)?,
        tracker: ProgressTracker?
    ) {
        CpioArchiveOutputStream(BufferedOutputStream(targetStream)).use { cos ->
            for (item in items) {
                if (isCancelled()) throw InterruptedException("Archive creation cancelled")
                onProgress?.invoke(item.archivePath)
                if (item.isDirectory) {
                    val entry = CpioArchiveEntry(CpioConstants.FORMAT_NEW, item.archivePath, 0)
                    cos.putArchiveEntry(entry)
                    cos.closeArchiveEntry()
                } else {
                    val size = item.entry.metadata.sizeBytes
                    val readRes = provider.openRead(item.entry.ref)
                    if (readRes is ProviderResult.Success) {
                        readRes.value.use { input ->
                            if (size != null) {
                                val entry = CpioArchiveEntry(CpioConstants.FORMAT_NEW, item.archivePath, size)
                                item.entry.metadata.modifiedAtMillis?.let { entry.time = it / 1000 }
                                cos.putArchiveEntry(entry)
                                copyStream(input, cos, item.archivePath, tracker, isCancelled)
                            } else {
                                val bytes = input.readBytes()
                                val entry = CpioArchiveEntry(CpioConstants.FORMAT_NEW, item.archivePath, bytes.size.toLong())
                                item.entry.metadata.modifiedAtMillis?.let { entry.time = it / 1000 }
                                cos.putArchiveEntry(entry)
                                cos.write(bytes)
                                tracker?.onBytes(bytes.size, item.archivePath)
                            }
                        }
                    }
                    cos.closeArchiveEntry()
                }
            }
        }
    }

    private fun writeAr(
        targetStream: OutputStream,
        items: List<ItemToArchive>,
        provider: StorageProvider,
        isCancelled: () -> Boolean,
        onProgress: ((String) -> Unit)?,
        tracker: ProgressTracker?
    ) {
        ArArchiveOutputStream(BufferedOutputStream(targetStream)).use { aos ->
            for (item in items) {
                if (isCancelled()) throw InterruptedException("Archive creation cancelled")
                if (!item.isDirectory) {
                    onProgress?.invoke(item.archivePath)
                    val size = item.entry.metadata.sizeBytes
                    val leafName = item.archivePath.substringAfterLast('/')
                    val readRes = provider.openRead(item.entry.ref)
                    if (readRes is ProviderResult.Success) {
                        readRes.value.use { input ->
                            if (size != null) {
                                val entry = ArArchiveEntry(leafName, size)
                                aos.putArchiveEntry(entry)
                                copyStream(input, aos, item.archivePath, tracker, isCancelled)
                            } else {
                                val bytes = input.readBytes()
                                val entry = ArArchiveEntry(leafName, bytes.size.toLong())
                                aos.putArchiveEntry(entry)
                                aos.write(bytes)
                                tracker?.onBytes(bytes.size, item.archivePath)
                            }
                        }
                    }
                    aos.closeArchiveEntry()
                }
            }
        }
    }
}
