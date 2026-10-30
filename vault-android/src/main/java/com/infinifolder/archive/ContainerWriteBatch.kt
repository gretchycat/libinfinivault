package com.infinifolder.archive

import android.content.Context
import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.storage.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** One isolated snapshot per container mutation batch. Public refs never expose staging URIs. */
internal class ContainerWriteBatch private constructor(
    override val provider: StorageProvider,
    private val storage: StorageProvider,
    private val backing: ResourceRef,
    private val work: File,
    private val snapshot: File,
    private val revision: ContentRevision,
    private val recoveryRoot: File,
    private val overlay: ArchiveBatchView?,
    private val sourceName: String,
    private val imageCompression: ContainerCompression?,
    private val dirty: () -> Boolean,
    private val cancelled: () -> Boolean,
    private val sourceFile: File?,
    private val relocation: ((List<StorageRelocation>, ResourceRef) -> ProviderResult<List<ResourceEntry>>)?,
    private val requireHealthy: () -> Unit,
    private val published: () -> Unit
) : StorageWriteBatch {
    private var closed = false
    private var committed = false
    private var relocationFailed = false

    override val supportsRelocation: Boolean get() = relocation != null
    override fun relocate(entries: List<StorageRelocation>, destination: ResourceRef): ProviderResult<List<ResourceEntry>> {
        check(!closed && !committed) { "Container batch is no longer active" }
        if (cancelled()) throw InterruptedException("Container operation cancelled")
        return try {
            (relocation?.invoke(entries, destination) ?: ProviderResult.Unsupported(ResourceCapability.MOVE)).also {
                if (it is ProviderResult.Failure) relocationFailed = true
            }
        } catch (error: Exception) {
            relocationFailed = true
            throw error
        }
    }

    override fun commit() {
        check(!closed && !committed) { "Container batch is no longer active" }
        check(!relocationFailed) { "Cannot publish a failed container relocation; original retained" }
        requireHealthy()
        if (cancelled()) throw InterruptedException("Container operation cancelled")
        if (!dirty()) { committed = true; return }
        val updated = if (overlay != null) {
            File(work, "updated-container").also {
                DiagnosticLog.record("container.batch.rebuild", "batch=${work.name} format=${ArchiveFormats.containerExtension(sourceName)} additions=${overlay.additions().size} removals=${overlay.removals().size}")
                ContainerSpace.require(work, snapshot.length(), revision.size)
                if (ContainerCompression.isTar(sourceName))
                    TarContainerEditor.rebuild(snapshot, it, sourceName, overlay.additions(), overlay.removals(), cancelled, revision.size)
                else ZipContainerEditor.appendBatch(snapshot, it, overlay.additions(), cancelled, overlay.removals())
            }
        } else if (imageCompression != null) {
            File(work, "updated-compressed-image").also { compressed ->
                ContainerSpace.require(work, snapshot.length() + snapshot.length() / 50 + 1024 * 1024, revision.size)
                DiagnosticLog.record("container.batch.recompress", "batch=${work.name} format=$imageCompression rawBytes=${snapshot.length()}")
                imageCompression.output(ContainerSpace.guarded(compressed.outputStream().buffered(), work, revision.size, cancelled), snapshot.length()).use { output ->
                    snapshot.inputStream().use { ContainerSpace.copy(it, output, work, revision.size, cancelled) }
                }
                val expected = snapshot.inputStream().use(ContentRevision::read)
                val actual = imageCompression.input(compressed.inputStream().buffered()).use { input ->
                    ContentRevision.read(object : java.io.FilterInputStream(input) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            if (cancelled()) throw InterruptedException("Container operation cancelled")
                            return super.read(bytes, offset, length)
                        }
                    })
                }
                if (actual != expected) throw IOException("Recompressed image failed verification; original retained")
            }
        } else snapshot
        if (cancelled()) throw InterruptedException("Container operation cancelled")
        sourceFile?.let {
            val mounted = MountedPartitionDetector.checkMounted(it)
            if (mounted.isMounted) throw MountedPartitionException(it.name, mounted.reason)
        }
        java.io.RandomAccessFile(updated, "rw").use { it.fd.sync() }
        DiagnosticLog.record("container.batch.publish", "batch=${work.name} provider=${backing.providerId} bytes=${updated.length()}")
        // Once publication begins, finish verification/rollback even if cancellation is requested.
        when (val result = ContentReplacement.replaceFromFile(storage, backing, updated, recoveryRoot, revision)) {
            is ProviderResult.Success -> {
                committed = true
                published()
                DiagnosticLog.record("container.batch.committed", "batch=${work.name} provider=${backing.providerId} bytes=${updated.length()}")
            }
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
            is ProviderResult.Unsupported -> throw IOException("The backing location cannot publish the container batch")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        DiagnosticLog.record("container.batch.closed", "batch=${work.name} committed=$committed")
        work.deleteRecursively()
    }

    companion object {
        fun open(context: Context, local: LocalStorageProvider?, storage: StorageProvider, destination: ResourceRef,
            sourceName: String, cancelled: () -> Boolean, published: () -> Unit): ContainerWriteBatch {
            val source = ArchiveUri.getSourceUri(Uri.parse(destination.documentId))
            if (ArchiveUri.isArchiveUri(source)) throw IOException("Nested container batches are read-only")
            val backing = storage.refForUri(source) ?: throw IOException("Container backing provider is unavailable")
            val work = File(context.cacheDir, "container_batches/${UUID.randomUUID()}")
            if (!work.mkdirs()) throw IOException("Cannot allocate container working directory")
            try {
                StorageSafety.validateName(sourceName)
                val sourceDirectory = File(work, "source").apply { if (!mkdirs()) throw IOException("Cannot allocate container snapshot") }
                val snapshot = File(sourceDirectory, sourceName)
                DiagnosticLog.record("container.batch.stage", "batch=${work.name} provider=${backing.providerId} format=${ArchiveFormats.containerExtension(sourceName)}")
                when (val opened = storage.openRead(backing)) {
                    is ProviderResult.Success -> opened.value.use { input ->
                        snapshot.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (cancelled()) throw InterruptedException("Container operation cancelled")
                                val n = input.read(buffer); if (n < 0) break
                                ContainerSpace.require(work, n.toLong())
                                output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        }
                    }
                    is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
                    is ProviderResult.Unsupported -> throw IOException("Cannot snapshot this container")
                }
                val revision = snapshot.inputStream().use(ContentRevision::read)
                val imageCompression = if (ContainerCompression.isCompressedImage(sourceName)) ContainerCompression.forName(sourceName) else null
                val working = if (imageCompression != null) {
                    // Retain the compressed original snapshot for revision/recovery accounting.
                    File(sourceDirectory, sourceName.substringBeforeLast('.')).also { raw ->
                        imageCompression.input(snapshot.inputStream().buffered()).use { input ->
                            raw.outputStream().use { output -> ContainerSpace.copy(input, output, work, revision.size, cancelled) }
                        }
                        ContainerSpace.require(work, raw.length() + raw.length() / 50 + 1024 * 1024, revision.size)
                    }
                } else snapshot
                val scratch = ArchiveStorageProvider(context, local, File(work, "reader_cache")).apply { sourceProvider = storage }
                val streamArchive = ArchiveFormats.containerExtension(sourceName) == "zip" || ContainerCompression.isTar(sourceName)
                if (!streamArchive) {
                    scratch.trustPrivateBatchSource(Uri.fromFile(working))
                }
                var imageDirty = false
                val mapped = MappedContainerView(storage, scratch, source, Uri.fromFile(working)) { imageDirty = true }
                val overlay = if (streamArchive) ArchiveBatchView(mapped, source, work) else null
                val view: StorageProvider = overlay ?: mapped
                // The snapshot may differ from the preflight view; recheck names/capacity inside it.
                val target = view.stat(destination)
                if (target !is ProviderResult.Success || !target.value.metadata.isDirectory || target.value.metadata.isSymbolicLink)
                    throw IOException("Container destination is no longer an ordinary directory")
                // Probe the actual private raw image, not merely the public compressed source.
                if (imageCompression != null) {
                    val failure = ArchiveReader(File(work, "probe")).writableFailureReason(working.name, working,
                        entryPath = ArchiveUri.getEntryPath(Uri.parse(destination.documentId)), probeOnly = true)
                    if (failure != null) throw IOException(failure)
                }
                val nativeRelocation = !streamArchive &&
                    (view.fileSystemInfo(destination) as? ProviderResult.Success)?.value?.type?.uppercase(java.util.Locale.ROOT) in
                        setOf("FAT12", "FAT16", "FAT32", "EXT2", "EXT3", "EXT4")
                return ContainerWriteBatch(view, storage, backing, work, working, revision,
                    File(context.filesDir, "save_recovery"), overlay, sourceName, imageCompression, { overlay?.hasChanges() ?: imageDirty }, cancelled,
                    if (local?.owns(source) == true) local.fileFor(source) else if (source.scheme == "file") File(source.path ?: "") else null,
                    if (nativeRelocation) { entries, target -> mapped.relocate(entries, target, cancelled) } else null,
                    { scratch.requireHealthyPrivateBatch() },
                    published)
            } catch (e: Exception) { work.deleteRecursively(); throw e }
        }
    }
}

/** Translate just this container's refs; all unrelated source reads keep their normal backend. */
private class MappedContainerView(
    private val fallback: StorageProvider,
    private val scratch: ArchiveStorageProvider,
    private val source: Uri,
    private val working: Uri,
    private val changed: () -> Unit
) : StorageProvider by fallback {
    private fun owns(ref: ResourceRef): Boolean = ref.providerId == "archive" && runCatching {
        !ArchiveUri.isDeletedView(Uri.parse(ref.documentId)) && ArchiveUri.getSourceUri(Uri.parse(ref.documentId)) == source
    }.getOrDefault(false)
    private fun mapped(ref: ResourceRef) = ResourceRef("archive", ArchiveUri.build(working,
        ArchiveUri.getEntryPath(Uri.parse(ref.documentId))).toString())
    private fun original(ref: ResourceRef) = ResourceRef("archive", ArchiveUri.build(source,
        ArchiveUri.getEntryPath(Uri.parse(ref.documentId))).toString())
    private fun entry(entry: ResourceEntry): ResourceEntry {
        val ref = original(entry.ref)
        return entry.copy(ref = ref, uri = Uri.parse(ref.documentId))
    }
    private fun mappedEntry(result: ProviderResult<ResourceEntry>, mutation: Boolean = false): ProviderResult<ResourceEntry> = when (result) {
        is ProviderResult.Success -> result.copy(value = entry(result.value)).also { if (mutation) changed() }
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }
    override fun stat(ref: ResourceRef) = if (owns(ref)) mappedEntry(scratch.stat(mapped(ref))) else fallback.stat(ref)
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
        if (!owns(parent)) return fallback.list(parent)
        return when (val result = scratch.list(mapped(parent))) {
            is ProviderResult.Success -> result.copy(value = result.value.map(::entry))
            is ProviderResult.Failure -> result
            is ProviderResult.Unsupported -> result
        }
    }
    override fun capabilities(ref: ResourceRef) = if (owns(ref)) scratch.capabilities(mapped(ref)) else fallback.capabilities(ref)
    override fun fileSystemInfo(ref: ResourceRef) = if (owns(ref)) scratch.fileSystemInfo(mapped(ref)) else fallback.fileSystemInfo(ref)
    override fun readTransferMetadata(ref: ResourceRef) = if (owns(ref)) scratch.readTransferMetadata(mapped(ref)) else fallback.readTransferMetadata(ref)
    override fun applyTransferMetadata(ref: ResourceRef, metadata: TransferMetadata) = if (owns(ref)) scratch.applyTransferMetadata(mapped(ref), metadata).also {
        if (it is ProviderResult.Success) changed()
    } else fallback.applyTransferMetadata(ref, metadata)
    override fun openRead(ref: ResourceRef) = if (owns(ref)) scratch.openRead(mapped(ref)) else fallback.openRead(ref)
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> = if (owns(ref)) scratch.openWrite(mapped(ref)).also {
        if (it is ProviderResult.Success) changed()
    } else fallback.openWrite(ref)
    override fun createFile(parent: ResourceRef, name: String, mimeType: String) = if (owns(parent))
        mappedEntry(scratch.createFile(mapped(parent), name, mimeType), true) else fallback.createFile(parent, name, mimeType)
    override fun createDirectory(parent: ResourceRef, name: String) = if (owns(parent))
        mappedEntry(scratch.createDirectory(mapped(parent), name), true) else fallback.createDirectory(parent, name)
    override fun delete(ref: ResourceRef) = if (owns(ref)) scratch.delete(mapped(ref)).also {
        if (it is ProviderResult.Success) changed()
    } else fallback.delete(ref)
    fun relocate(entries: List<StorageRelocation>, destination: ResourceRef,
                 cancelled: () -> Boolean): ProviderResult<List<ResourceEntry>> { return try {
        if (!owns(destination) || entries.any { !owns(it.source) })
            throw IOException("All relocated entries must belong to the destination container")
        if (!scratch.canRelocatePrivateEntries(entries.map { mapped(it.source) }, mapped(destination)))
            return ProviderResult.Unsupported(ResourceCapability.MOVE)
        val result = entries.map { item ->
            if (cancelled()) throw InterruptedException("Container move cancelled")
            when (val moved = mappedEntry(scratch.relocatePrivateEntry(mapped(item.source), mapped(destination), item.name), true)) {
                is ProviderResult.Success -> moved.value
                is ProviderResult.Failure -> throw IOException(moved.message, moved.cause)
                is ProviderResult.Unsupported -> throw IOException("The private container cannot relocate this entry")
            }
        }
        ProviderResult.Success(result)
    } catch (error: Exception) { ProviderResult.Failure(error.message ?: "Container relocation failed", error) } }
    override fun close() {} // The batch owns its workspace, not the application provider graph.
}

/** Sequential archive additions and removals stay staged until one verified rebuild. */
private class ArchiveBatchView(private val base: StorageProvider, private val source: Uri, private val work: File) : StorageProvider by base {
    private data class Pending(val path: String, val directory: Boolean, val mime: String, val content: File? = null)
    private val pending = linkedMapOf<String, Pending>()
    private val removed = linkedSetOf<String>()
    private fun owns(ref: ResourceRef) = ref.providerId == "archive" && runCatching {
        !ArchiveUri.isDeletedView(Uri.parse(ref.documentId)) && ArchiveUri.getSourceUri(Uri.parse(ref.documentId)) == source
    }.getOrDefault(false)
    private fun path(ref: ResourceRef) = ArchiveUri.getEntryPath(Uri.parse(ref.documentId))
    private fun isRemoved(path: String): Boolean = removed.any { path == it || path.startsWith("$it/") }
    private fun entry(value: Pending): ResourceEntry {
        val uri = ArchiveUri.build(source, value.path)
        return ResourceEntry(ResourceRef("archive", uri.toString()), ResourceMetadata(value.path.substringAfterLast('/'),
            if (value.directory) "vnd.android.document/directory" else value.mime,
            if (value.directory) null else value.content?.length() ?: 0L, null, value.directory, canWrite = true), uri)
    }
    private fun <T> attempt(block: () -> T): ProviderResult<T> = try { ProviderResult.Success(block()) }
        catch (e: Exception) { ProviderResult.Failure(e.message ?: "Archive batch operation failed", e) }
    fun additions() = pending.values.map { ZipContainerEditor.Addition(it.path, it.directory, it.content) }
    fun removals() = removed.toSet()
    fun hasChanges() = additions().isNotEmpty() || removed.isNotEmpty()
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = if (owns(ref) && pending.containsKey(path(ref)))
        ProviderResult.Success(entry(pending.getValue(path(ref))))
    else if (owns(ref) && isRemoved(path(ref)))
        ProviderResult.Failure("Archive entry was removed in this batch") else base.stat(ref)
    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
        if (!owns(parent)) return base.list(parent)
        return attempt {
            val parentPath = path(parent)
            val parentEntry = when (val result = stat(parent)) {
                is ProviderResult.Success -> result.value
                is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                is ProviderResult.Unsupported -> throw IOException("Cannot inspect Archive parent")
            }
            if (!parentEntry.metadata.isDirectory || parentEntry.metadata.isSymbolicLink) throw IOException("Archive parent is not an ordinary directory")
            val old = if (pending.containsKey(parentPath)) emptyList() else StorageSafety.complete(base.list(parent)).filterNot { isRemoved(path(it.ref)) }
            old + pending.values.filter { it.path.substringBeforeLast('/', "") == parentPath }.map(::entry)
        }
    }
    private fun create(parent: ResourceRef, name: String, directory: Boolean, mime: String): ProviderResult<ResourceEntry> = attempt {
        StorageSafety.requireNewName(this, parent, name)
        val parentPath = path(parent)
        val value = Pending(if (parentPath.isEmpty()) name else "$parentPath/$name", directory, mime)
        pending[value.path] = value
        entry(value)
    }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String) = if (owns(parent)) create(parent, name, false, mimeType) else base.createFile(parent, name, mimeType)
    override fun createDirectory(parent: ResourceRef, name: String) = if (owns(parent)) create(parent, name, true, "vnd.android.document/directory") else base.createDirectory(parent, name)
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
        if (!owns(ref) || !pending.containsKey(path(ref))) return if (owns(ref) && isRemoved(path(ref)))
            ProviderResult.Failure("Archive entry was removed in this batch") else base.openRead(ref)
        return attempt {
            val value = pending.getValue(path(ref))
            if (value.directory) throw IOException("Cannot read an archive directory as a file")
            value.content?.inputStream() ?: byteArrayOf().inputStream()
        }
    }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
        if (!owns(ref)) return base.openWrite(ref)
        return attempt {
            val key = path(ref)
            val value = pending[key] ?: throw IOException("Batch writes may only modify newly created archive entries")
            if (value.directory) throw IOException("Cannot write an archive directory")
            val file = File.createTempFile("entry-", ".data", work)
            val output = file.outputStream()
            object : OutputStream(), PendingWrite {
                private var closed = false
                private var aborted = false
                override fun abort() { aborted = true }
                override fun write(value: Int) { try { output.write(value) } catch (e: Exception) { aborted = true; throw e } }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    try { output.write(bytes, offset, length) } catch (e: Exception) { aborted = true; throw e }
                }
                override fun flush() { try { output.flush() } catch (e: Exception) { aborted = true; throw e } }
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        output.use { if (!aborted) it.fd.sync() }
                        if (!aborted) pending[key] = value.copy(content = file)
                    } finally { if (aborted) file.delete() }
                }
            }
        }
    }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> {
        if (!owns(ref)) return base.delete(ref)
        return attempt {
            val key = path(ref)
            if (key.isEmpty()) throw IOException("Cannot delete the archive root")
            val pendingValue = pending[key]
            if (pendingValue == null) {
                when (val found = base.stat(ref)) {
                    is ProviderResult.Success -> Unit
                    is ProviderResult.Failure -> throw IOException(found.message, found.cause)
                    is ProviderResult.Unsupported -> throw IOException("Cannot verify archive entry before deleting it")
                }
                removed += key
            }
            pending.keys.filter { it == key || it.startsWith("$key/") }.toList().forEach { pending.remove(it)?.content?.delete() }
        }
    }
    override fun close() {}
}
