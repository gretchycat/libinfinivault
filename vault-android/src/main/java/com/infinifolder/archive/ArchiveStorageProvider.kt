package com.infinifolder.archive

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import com.infinifolder.model.*
import com.infinifolder.sftp.SftpUri
import com.infinifolder.storage.LocalStorageProvider
import com.infinifolder.storage.StorageProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ArchiveStorageProvider(
    private val context: Context,
    private val localProvider: LocalStorageProvider? = null,
    private val customCacheDir: File? = null
) : StorageProvider {

    override val id: String = "archive"
    override val features = BackendFeatures(randomRead = Support.SUPPORTED, randomWrite = Support.UNSUPPORTED, creation = CreationGuarantee.EXCLUSIVE, rename = Support.UNSUPPORTED, delete = Support.UNKNOWN, nativeLinks = Support.SUPPORTED)


    override fun refForUri(uri: Uri): ResourceRef? = if (ArchiveUri.isArchiveUri(uri)) ResourceRef(id, uri.toString()) else null

    override fun deviceIdentity(ref: ResourceRef): String? = runCatching {
        "${if (ArchiveUri.isDeletedView(Uri.parse(ref.documentId))) "deleted:" else ""}container:${ArchiveUri.getSourceUri(Uri.parse(ref.documentId))}"
    }.getOrNull()

    override fun beginWriteBatch(destination: ResourceRef, isCancelled: () -> Boolean): ProviderResult<com.infinifolder.storage.StorageWriteBatch> = try {
        val uri = Uri.parse(destination.documentId)
        if (!isWritable(uri)) throw IOException(writableFailures[uri.toString()] ?: "Container is read-only")
        val storage = sourceProvider ?: throw IOException("Container backing storage is unavailable")
        ProviderResult.Success(ContainerWriteBatch.open(context, localProvider, storage, destination,
            getSourceName(ArchiveUri.getSourceUri(uri)), isCancelled) {
            treeCache.evictAll(); writableHints.clear(); writableFailures.clear()
        })
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot prepare container batch", e) }

    override fun backingResource(ref: ResourceRef): ResourceRef? {
        val source = ArchiveUri.getSourceUri(Uri.parse(ref.documentId))
        return sourceProvider?.refForUri(source) ?: localProvider?.refForUri(source)
    }

    override fun recoverySource(ref: ResourceRef): ResourceRef? {
        var uri = Uri.parse(ref.documentId)
        repeat(16) {
            if (!ArchiveUri.isArchiveUri(uri)) return null
            val source = ArchiveUri.getSourceUri(uri)
            if (ArchiveUri.isDeletedView(uri)) return sourceProvider?.refForUri(source)
                ?: localProvider?.refForUri(source) ?: throw IOException("Recovery source identity is unavailable")
            uri = source
        }
        throw IOException("Recovery container nesting limit exceeded")
    }

    override fun fileSystemInfo(ref: ResourceRef): ProviderResult<com.infinifolder.storage.FileSystemInfo> = try {
        val uri = Uri.parse(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val (openStream, localFile, dev) = resolveSource(source)
        val info = try { reader.fileSystemInfo(getSourceName(source), ArchiveUri.getEntryPath(uri), openStream, localFile, dev) }
            finally { dev?.close() }
        if (info == null) ProviderResult.Unsupported(ResourceCapability.FILESYSTEM_INFO) else ProviderResult.Success(if (ArchiveUri.isDeletedView(uri)) info.copy(readOnly = true) else info)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot identify mounted filesystem", e) }

    override fun parent(ref: ResourceRef): ProviderResult<ResourceRef?> = try {
        val uri = Uri.parse(ref.documentId)
        val path = ArchiveUri.getEntryPath(uri)
        ProviderResult.Success(if (path.isEmpty()) null else ResourceRef(id,
            ArchiveUri.build(ArchiveUri.getSourceUri(uri), path.substringBeforeLast('/', ""), deleted = ArchiveUri.isDeletedView(uri)).toString()))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve archive parent", e) }

    override fun readTransferMetadata(ref: ResourceRef): ProviderResult<TransferMetadata> { return try {
        val uri = Uri.parse(ref.documentId)
        if (ArchiveUri.isDeletedView(uri)) return super<StorageProvider>.readTransferMetadata(ref)
        val source = ArchiveUri.getSourceUri(uri)
        if (!ArchiveFormats.isDiscImage(getSourceName(source))) return super<StorageProvider>.readTransferMetadata(ref)
        val (input, file, dev) = resolveSource(source)
        val metadata = try { reader.readTransferMetadata(getSourceName(source), ArchiveUri.getEntryPath(uri), input, file, dev) }
            finally { dev?.close() }
        if (metadata == null) super<StorageProvider>.readTransferMetadata(ref) else ProviderResult.Success(metadata)
    } catch (error: Exception) { ProviderResult.Failure(error.message ?: "Cannot read transfer metadata", error) } }

    override fun applyTransferMetadata(ref: ResourceRef, metadata: TransferMetadata): ProviderResult<Unit> { return try {
        val uri = Uri.parse(ref.documentId)
        if (ArchiveUri.isDeletedView(uri)) return ProviderResult.Unsupported(ResourceCapability.METADATA)
        val info = fileSystemInfo(ref)
        if ((info as? ProviderResult.Success)?.value?.type !in setOf("ext2", "ext3", "ext4"))
            return if (info is ProviderResult.Failure) info else ProviderResult.Unsupported(ResourceCapability.METADATA)
        val source = ArchiveUri.getSourceUri(uri)
        if (needsRebuild(source)) {
            val path = ArchiveUri.getEntryPath(uri)
            val parent = ResourceRef(id, ArchiveUri.build(source, path.substringBeforeLast('/', "")).toString())
            return rebuiltMutation(parent) { it.applyTransferMetadata(ref, metadata) }
        }
        val (_, file, dev) = resolveSource(source, writable = true)
        try { reader.applyTransferMetadata(getSourceName(source), file, dev, ArchiveUri.getEntryPath(uri), metadata) }
        finally { dev?.close(); treeCache.evictAll() }
        ProviderResult.Success(Unit)
    } catch (error: Exception) { ProviderResult.Failure(error.message ?: "Cannot preserve transfer metadata", error) } }

    override fun resolveLink(ref: ResourceRef): ProviderResult<ResourceRef> = try {
        val uri = ArchiveUri.uriFactory(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val path = ArchiveUri.getEntryPath(uri)
        val entry = getArchiveTree(source, directoryPath = path.substringBeforeLast('/', "")).getEntry(path) ?: throw IOException("Archive link not found")
        val target = entry.linkTarget ?: throw IOException("Archive link target is unavailable")
        if (ArchiveFormats.isPlaylist(getSourceName(source))) {
            val targetUri = resolvePlaylistTarget(source, target)
            val resolvedRef = sourceProvider?.refForUri(targetUri)
                ?: localProvider?.refForUri(targetUri)
                ?: ResourceRef(if (targetUri.scheme == "content") "saf" else "local", targetUri.toString())
            ProviderResult.Success(resolvedRef)
        } else {
            val components = mutableListOf<String>()
            val relative = if (target.startsWith('/')) target else path.substringBeforeLast('/', "") + "/" + target
            for (part in relative.split('/')) when (part) {
                "", "." -> Unit
                ".." -> { if (components.isEmpty()) throw IOException("Link escapes archive root"); components.removeAt(components.lastIndex) }
                else -> components.add(part)
            }
            ProviderResult.Success(ResourceRef(id, ArchiveUri.build(source, components.joinToString("/")).toString()))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve archive link", e) }

    private fun resolvePlaylistTarget(source: Uri, target: String): Uri {
        if (target.contains("://")) {
            return ArchiveUri.uriFactory(target)
        }

        if (target.startsWith('/') && File(target).exists()) {
            return ArchiveUri.uriFactory("file://$target")
        }

        if (source.scheme == "file") {
            val parentFile = File(source.path ?: "").parentFile
            var targetFile = File(parentFile, target).canonicalFile
            if (!targetFile.exists()) {
                val sibling = File(parentFile, File(target).name)
                if (sibling.exists()) {
                    targetFile = sibling.canonicalFile
                } else {
                    val parentParent = parentFile?.parentFile
                    val audioSibling = if (parentParent != null) File(parentParent, "audio/${File(target).name}") else null
                    if (audioSibling?.exists() == true) {
                        targetFile = audioSibling.canonicalFile
                    } else {
                        val audioSub = File(parentFile, "audio/${File(target).name}")
                        if (audioSub.exists()) {
                            targetFile = audioSub.canonicalFile
                        }
                    }
                }
            }
            return ArchiveUri.uriFactory("file://${targetFile.absolutePath}")
        }

        if (source.scheme == "content") {
            try {
                val authority = source.authority
                val isTree = (try { DocumentsContract.isTreeUri(source) } catch (_: Throwable) { false }) ||
                    source.pathSegments.contains("tree")
                val docId = try {
                    if (source.pathSegments.firstOrNull() == "document" || source.pathSegments.getOrNull(2) == "document") {
                        DocumentsContract.getDocumentId(source)
                    } else if (isTree) {
                        DocumentsContract.getTreeDocumentId(source)
                    } else null
                } catch (_: Throwable) { null } ?: source.lastPathSegment

                if (docId != null) {
                    val colonIdx = docId.indexOf(':')
                    val prefix = if (colonIdx >= 0) docId.substring(0, colonIdx + 1) else ""
                    val docPath = if (colonIdx >= 0) docId.substring(colonIdx + 1) else docId
                    val parentDocPath = docPath.substringBeforeLast('/', "")
                    val targetName = target.substringAfterLast('/').substringAfterLast('\\')

                    fun resolveRel(base: String, rel: String): String {
                        val isAbs = base.startsWith('/')
                        val segs = base.replace('\\', '/').split('/').filter { it.isNotEmpty() }.toMutableList()
                        for (p in rel.replace('\\', '/').split('/')) {
                            when (p) {
                                "", "." -> Unit
                                ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex)
                                else -> segs.add(p)
                            }
                        }
                        return (if (isAbs) "/" else "") + segs.joinToString("/")
                    }

                    val candidatePaths = listOfNotNull(
                        resolveRel(parentDocPath, target),
                        resolveRel(parentDocPath, targetName),
                        if (parentDocPath.contains('/')) resolveRel(parentDocPath.substringBeforeLast('/', ""), "audio/$targetName") else null,
                        resolveRel(parentDocPath, "audio/$targetName")
                    ).distinct()

                    for (candidate in candidatePaths) {
                        val candidateDocId = prefix + candidate
                        val targetSafUri = try {
                            if (isTree) {
                                DocumentsContract.buildDocumentUriUsingTree(source, candidateDocId)
                            } else if (authority != null) {
                                DocumentsContract.buildDocumentUri(authority, candidateDocId)
                            } else null
                        } catch (_: Throwable) { null }

                        if (targetSafUri != null) {
                            val canOpen = try {
                                context.contentResolver.openInputStream(targetSafUri)?.use { true } ?: false
                            } catch (_: Throwable) { false }
                            if (canOpen) return targetSafUri
                        }
                    }

                    val primaryDocId = prefix + candidatePaths.first()
                    val primarySafUri = try {
                        if (isTree) {
                            DocumentsContract.buildDocumentUriUsingTree(source, primaryDocId)
                        } else if (authority != null) {
                            DocumentsContract.buildDocumentUri(authority, primaryDocId)
                        } else null
                    } catch (_: Throwable) { null }
                    if (primarySafUri != null) return primarySafUri
                }
            } catch (_: Throwable) {}
        }

        val sourcePath = source.path ?: ""
        val parentPath = sourcePath.substringBeforeLast('/', "")
        val rawResolved = if (parentPath.isEmpty()) "/$target" else "$parentPath/$target"
        val normalizedPath = runCatching { java.net.URI(rawResolved).normalize().path }.getOrDefault(rawResolved)
        val resolvedStr = "${source.scheme}://${source.authority ?: ""}$normalizedPath"
        return ArchiveUri.uriFactory(resolvedStr)
    }
    override fun stat(ref: ResourceRef): ProviderResult<ResourceEntry> = try {
        val uri = Uri.parse(ref.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val path = ArchiveUri.getEntryPath(uri)
        if (path.isEmpty()) {
            ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(getSourceName(source),
                "vnd.android.document/directory", null, null, true, canWrite = isWritable(uri)), uri))
        } else {
            val entry = getArchiveTree(source, ArchiveUri.isDeletedView(uri), path.substringBeforeLast('/', "")).getEntry(path) ?: throw IOException("Archive entry not found")
            ProviderResult.Success(ResourceEntry(ref, ResourceMetadata(entry.name, entry.mimeType,
                entry.sizeBytes, entry.modifiedAtMillis, entry.isDirectory, canRead = entry.readError == null, canWrite = isWritable(uri),
                isSymbolicLink = entry.isSymbolicLink, linkTarget = entry.linkTarget,
                permissionsMode = entry.permissionsMode,
                permissionsFormatted = entry.permissionsMode?.let { com.infinifolder.storage.PermissionHelper.formatPosix(it, entry.isDirectory) },
                owner = entry.owner, group = entry.group, readError = entry.readError, recoveryNote = entry.recoveryNote, partialRecoveryAvailable = entry.partialRecoveryAvailable), uri))
        }
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot query archive entry", e) }

    override fun capabilities(ref: ResourceRef): ProviderResult<ResourceCapabilities> = when (val result = stat(ref)) {
        is ProviderResult.Success -> {
            val uri = Uri.parse(ref.documentId)
            val source = ArchiveUri.getSourceUri(uri)
            val path = ArchiveUri.getEntryPath(uri)
            val meta = result.value.metadata
            val writable = meta.canWrite
            ProviderResult.Success(ResourceCapabilities.from(meta, features, overrides = mapOf(
                ResourceCapability.ACCEPT_MOVE to Support.UNSUPPORTED,
                ResourceCapability.ACCEPT_INTERNAL_MOVE to if (meta.isDirectory && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.BATCH_WRITE to if (meta.isDirectory && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.CREATE to if (meta.isDirectory && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.DELETE to if (path.isNotEmpty() && writable) Support.SUPPORTED else Support.UNSUPPORTED,
                ResourceCapability.MOVE to if (path.isNotEmpty() && writable && !meta.isSymbolicLink) Support.SUPPORTED else Support.UNSUPPORTED)),
                notice = if (writable) null else writableFailures[uri.toString()])
        }
        is ProviderResult.Failure -> result
        is ProviderResult.Unsupported -> result
    }

    fun invalidateReadCaches() {
        synchronized(treeCache) { treeCache.evictAll() }
    }

    /** Source conflict checks must inspect stored bytes, including compressed images. */
    fun <T> withFreshReads(block: () -> T): T = reader.withFreshReads {
        ReadDeviceScope.withScope(enabled = false) {
            invalidateReadCaches()
            block()
        }
    }

    private val seekDepth = ThreadLocal.withInitial { 0 }

    override fun openSeekable(ref: ResourceRef, writable: Boolean): ProviderResult<BlockDevice> {
        if (writable) return ProviderResult.Unsupported(ResourceCapability.SEEK_WRITE)
        val depth = seekDepth.get() ?: 0
        if (depth >= SeekableSourceResolver.MAX_NESTING_DEPTH) return ProviderResult.Failure("Container nesting limit exceeded")
        seekDepth.set(depth + 1)
        var parentDevice: BlockDevice? = null
        return try {
            val uri = Uri.parse(ref.documentId)
            val source = ArchiveUri.getSourceUri(uri)
            val path = ArchiveUri.getEntryPath(uri)
            parentDevice = resolveSource(source).third
            val slice = if (ArchiveUri.isDeletedView(uri)) null else parentDevice?.let { reader.getEntryBlockDevice(getSourceName(source), path, it) }
            if (slice != null) {
                val owner = parentDevice!!
                parentDevice = null
                ProviderResult.Success(object : BlockDevice by slice {
                    override val isReadOnly = true
                    override val capabilities = setOf(DeviceCapability.READ, DeviceCapability.SEEK)
                    override fun writeAt(offset: Long, buffer: ByteArray, srcOffset: Int, length: Int) { throw IOException("Nested containers are read-only") }
                    override fun truncate(newSize: Long) { throw IOException("Nested containers are read-only") }
                    override fun close() { try { slice.close() } finally { owner.close() } }
                })
            } else {
                parentDevice?.close()
                parentDevice = null
                val snapshot = extractToCache(uri)
                val device = FileBlockDevice(java.io.RandomAccessFile(snapshot, "r"), true, ownsFile = true)
                ProviderResult.Success(object : BlockDevice by device {
                    override fun close() { try { device.close() } finally { snapshot.delete() } }
                })
            }
        } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot open nested container", e) }
        finally { parentDevice?.close(); seekDepth.set(depth) }
    }

    var sourceProvider: StorageProvider? = null
    var streamResolver: ((Uri) -> InputStream?)? = null
    var metadataResolver: ((Uri) -> Pair<Long?, Long?>?)? = null
    var deviceResolver: ((Uri, Boolean) -> BlockDevice?)? = null

    private val diskCache by lazy { com.infinifolder.storage.DiskCache.get(context) }
    // Custom paths are isolated transaction scratch/test providers, not reusable shared read caches.
    val reader = ArchiveReader(File(customCacheDir ?: context.cacheDir, "archive_temp"), if (customCacheDir == null) diskCache else null)
    private val treeCache = LruCache<String, ArchiveTree>(64)
    private val treeLoads = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<ArchiveTree>>()

    private fun sha256(str: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(str.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Returns internal storage/infinifolder/cache, creating it if it does not exist. */
    fun getCacheRoot(): File {
        if (customCacheDir != null) {
            if (!customCacheDir.exists()) customCacheDir.mkdirs()
            return customCacheDir
        }
        return try {
            val externalStorage = Environment.getExternalStorageDirectory()
            if (externalStorage != null) {
                val primaryDir = File(externalStorage, "infinifolder/cache")
                if (!primaryDir.exists()) {
                    primaryDir.mkdirs()
                }
                if (primaryDir.exists() && (primaryDir.canWrite() || primaryDir.isDirectory)) {
                    return primaryDir
                }
            }
            fallbackCacheRoot()
        } catch (_: Exception) {
            fallbackCacheRoot()
        }
    }

    private fun fallbackCacheRoot(): File {
        val extCache = context.getExternalCacheDir()?.resolve("infinifolder/cache")
        if (extCache != null) {
            if (!extCache.exists()) extCache.mkdirs()
            if (extCache.exists()) return extCache
        }
        val internalCache = File(context.cacheDir, "infinifolder/cache")
        if (!internalCache.exists()) internalCache.mkdirs()
        return internalCache
    }

    fun resolveSource(sourceUri: Uri, writable: Boolean = false): Triple<() -> InputStream, File?, BlockDevice?> {
        if (writable && ArchiveUri.isArchiveUri(sourceUri)) {
            throw IOException("Nested containers are read-only until changes can be committed to every enclosing container")
        }
        val dev = deviceResolver?.invoke(sourceUri, writable)
        val privateSnapshot = trustedBatchSources.contains(sourceUri.toString())
        fun openFileDevice(file: File): BlockDevice? = dev ?: if (!file.exists()) null
            else if (privateSnapshot) PrivateBatchBlockDevice(file, writable) { privateBatchFailure = it }
            else FileBlockDevice(java.io.RandomAccessFile(file, if (writable) "rw" else "r"),
                isReadOnly = !writable, ownsFile = true)
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
            if (file != null) {
                val fileDev = openFileDevice(file)
                return Triple({ file.inputStream() }, file, fileDev)
            }
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            val fileDev = openFileDevice(file)
            return Triple({ file.inputStream() }, file, fileDev)
        }
        if (dev != null) {
            return Triple({ dev.asInputStream() }, null, dev)
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            val cachedFile = extractToCache(sourceUri)
            val fileDev = if (cachedFile.exists()) FileBlockDevice(java.io.RandomAccessFile(cachedFile, if (writable) "rw" else "r"), isReadOnly = !writable, ownsFile = true) else null
            return Triple({ cachedFile.inputStream() }, cachedFile, fileDev)
        }
        val customResolver = streamResolver
        if (customResolver != null && (SftpUri.isSftpUri(sourceUri) || sourceUri.scheme != "content")) {
            val openStream: () -> InputStream = {
                customResolver(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Triple(openStream, null, null)
        }
        if (sourceUri.scheme == "content") {
            val openStream: () -> InputStream = {
                context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Triple(openStream, null, null)
        }
        if (customResolver != null) {
            val openStream: () -> InputStream = {
                customResolver(sourceUri)
                    ?: throw IOException("Cannot open input stream for: $sourceUri")
            }
            return Triple(openStream, null, null)
        }
        throw IOException("Cannot resolve stream for URI scheme: ${sourceUri.scheme ?: "unknown"}")
    }

    fun getSourceName(sourceUri: Uri): String {
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val name = runCatching { lp.fileFor(sourceUri).name }.getOrNull()
            if (name != null) return name
        }
        if (sourceUri.scheme == "file") {
            return File(sourceUri.path ?: "").name
        }
        if (ArchiveUri.isArchiveUri(sourceUri)) {
            return ArchiveUri.getEntryPath(sourceUri).substringAfterLast('/')
        }
        if (SftpUri.isSftpUri(sourceUri)) {
            val remotePath = SftpUri.getRemotePath(sourceUri)
            val name = remotePath.substringAfterLast('/')
            if (name.isNotEmpty()) return name
        }
        if (sourceUri.scheme == "content") {
            try {
                context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            } catch (_: Exception) {}
        }
        return sourceUri.lastPathSegment?.substringAfterLast('/') ?: "archive.zip"
    }

    private fun getSourceMetadata(sourceUri: Uri): Pair<Long?, Long?> {
        val lp = localProvider
        if (lp != null && lp.owns(sourceUri)) {
            val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
            if (file != null) return Pair(file.length(), file.lastModified())
        }
        if (sourceUri.scheme == "file") {
            val file = File(sourceUri.path ?: "")
            return Pair(file.length(), file.lastModified())
        }
        val meta = metadataResolver?.invoke(sourceUri)
        if (meta != null) return meta
        if (sourceUri.scheme == "content") {
            try {
                context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        return Pair(cursor.getLong(0), null)
                    }
                }
            } catch (_: Exception) {}
        }
        return Pair(null, null)
    }

    private fun archiveVolumes(source: Uri): ArchiveVolumeSet? {
        val name = getSourceName(source)
        if (!NativeArchiveReader.isSupported(name)) return null
        val storage = sourceProvider ?: localProvider
        val ref = storage?.refForUri(source)
        val parent = ref?.let { (storage.parent(it) as? ProviderResult.Success)?.value }
        val siblings = if (parent != null && storage != null) com.infinifolder.storage.StorageSafety.complete(storage.list(parent)) else emptyList()
        val family = NativeArchiveReader.family(name)
        // CAB header links can name any sibling, including names without .cab.
        // Offer identities; the native callback opens only explicitly requested names.
        val matching = siblings.filter { !it.metadata.isDirectory &&
            (family.second == "cab" || NativeArchiveReader.family(it.metadata.name) == family) }
        val sources = matching.associate { it.metadata.name to it.uri }.toMutableMap()
        sources.putIfAbsent(name, source)
        val stable = matching.isNotEmpty() && matching.all { (it.metadata.modifiedAtMillis ?: 0) > 0 }
        val revision = (if (stable) "" else java.util.UUID.randomUUID().toString()) + matching.sortedBy { it.metadata.name }.joinToString(";") {
            "${it.metadata.name}:${it.metadata.sizeBytes}:${it.metadata.modifiedAtMillis}"
        }
        return ArchiveVolumeSet(sources.keys.toList(), revision, primaryName = if (family.second == "cab") name else null) { member ->
            sources[member]?.let { uri ->
                val (input, file, device) = resolveSource(uri)
                if (device != null) device
                else if (file != null) FileBlockDevice(java.io.RandomAccessFile(file, "r"), true, ownsFile = true)
                else {
                    val lease = diskCache.stage(member, getSourceMetadata(uri).first, input = input)
                    try {
                        val backing = FileBlockDevice(java.io.RandomAccessFile(lease.file, "r"), true, ownsFile = true)
                        object : BlockDevice by backing {
                            override fun close() { try { backing.close() } finally { lease.close() } }
                        }
                    } catch (error: Throwable) { lease.close(); throw error }
                }
            }
        }
    }

    fun supportsFatRecovery(sourceUri: Uri): Boolean {
        val (input, file, dev) = resolveSource(sourceUri)
        return try { reader.supportsFatRecovery(getSourceName(sourceUri), input, file, dev) }
            finally { dev?.close() }
    }

    fun getArchiveTree(sourceUri: Uri, deleted: Boolean = false, directoryPath: String = ""): ArchiveTree {
        val (size, modified) = getSourceMetadata(sourceUri)
        val volumes = archiveVolumes(sourceUri)
        val sourceName = getSourceName(sourceUri)
        // Content detection also recognizes extensionless/ambiguous images. These
        // need directory-scoped requests/cache keys, or the reader recurses through
        // every volume before showing the root. Ordinary archives ignore the path.
        val directoryScoped = ArchiveFormats.isDiscImage(sourceName) ||
            com.infinifolder.storage.FileMagicDetector.isAmbiguousOrUnknown(sourceName, "application/x-known")
        val imageDirectory = if (!deleted && directoryScoped)
            directoryPath.replace('\\', '/').trim('/').takeIf { it.split('/').none { part -> part == "." || part == ".." } }
                ?: throw IOException("Invalid image directory path") else null
        val cacheKey = "${deleted}:${sourceUri}:${size ?: 0}:${modified ?: 0}:${volumes?.revision ?: ""}:${imageDirectory ?: "<catalog>"}"
        synchronized(treeCache) {
            val cached = treeCache.get(cacheKey)
            if (!deleted && cached != null && modified != null && modified > 0) return cached
        }

        val loading = java.util.concurrent.CompletableFuture<ArchiveTree>()
        val existing = treeLoads.putIfAbsent(cacheKey, loading)
        if (existing != null) {
            com.infinifolder.storage.DiagnosticLog.record("archive.tree.wait", "source=${getSourceName(sourceUri)} sharedScan=true")
            try {
                while (true) {
                    RecoveryCancellation.check()
                    try { return existing.get(250, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: java.util.concurrent.TimeoutException) { /* Check cancellation again. */ }
                }
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Archive scan wait interrupted").also { it.initCause(e) }
            }
        }
        val started = android.os.SystemClock.uptimeMillis()
        try {
        // A preceding scan may have completed between the initial cache check
        // and registering this load. Recheck before reopening a remote image.
        synchronized(treeCache) {
            val cached = treeCache.get(cacheKey)
            if (!deleted && cached != null && modified != null && modified > 0) {
                loading.complete(cached)
                return cached
            }
        }
        val (openStream, localFile, dev) = resolveSource(sourceUri)
        val archiveName = getSourceName(sourceUri)
        com.infinifolder.storage.DiagnosticLog.record("archive.tree.begin", "source=$archiveName seekable=${dev != null} deleted=$deleted directory=$imageDirectory key=${sha256(cacheKey).take(12)} size=$size modified=$modified")

        val tree = try { if (deleted) reader.buildDeletedFatTree(sourceUri, archiveName, openStream, localFile, dev)
            else reader.buildTree(sourceUri, archiveName, openStream, localFile, dev, volumes, directoryPath = imageDirectory) }
            finally { dev?.close() }

        synchronized(treeCache) {
            treeCache.put(cacheKey, tree)
        }
        loading.complete(tree)
        com.infinifolder.storage.DiagnosticLog.record("archive.tree.complete", "source=$archiveName elapsedMs=${android.os.SystemClock.uptimeMillis() - started}")
        return tree
        } catch (e: Throwable) {
            loading.completeExceptionally(e)
            com.infinifolder.storage.DiagnosticLog.record("archive.tree.failed", "source=${getSourceName(sourceUri)} elapsedMs=${android.os.SystemClock.uptimeMillis() - started}", e)
            throw e
        } finally {
            treeLoads.remove(cacheKey, loading)
        }
    }

    /**
     * Resolves the relative path of an archive entry structured as if from the virtual root.
     * E.g.: "Internal shared storage/Download/test.zip/subfolder/file.txt"
     */
    fun resolveVirtualPath(archiveUri: Uri): String {
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)

        val sourceVirtualPath = when {
            localProvider != null && localProvider!!.owns(sourceUri) -> {
                val lp = localProvider!!
                val file = runCatching { lp.fileFor(sourceUri) }.getOrNull()
                if (file != null) {
                    val parts = listOf("Internal shared storage") + lp.store.components(file)
                    parts.joinToString("/")
                } else getSourceName(sourceUri)
            }
            sourceUri.scheme == "file" -> {
                val file = File(sourceUri.path ?: "")
                val extStorage = Environment.getExternalStorageDirectory()
                if (extStorage != null && file.absolutePath.startsWith(extStorage.absolutePath)) {
                    val rel = file.absolutePath.removePrefix(extStorage.absolutePath).trimStart('/')
                    "Internal shared storage/$rel"
                } else {
                    file.name
                }
            }
            ArchiveUri.isArchiveUri(sourceUri) -> {
                resolveVirtualPath(sourceUri)
            }
            SftpUri.isSftpUri(sourceUri) -> {
                val remotePath = SftpUri.getRemotePath(sourceUri).trimStart('/')
                "sftp/${SftpUri.getConnectionId(sourceUri)}/$remotePath"
            }
            sourceUri.scheme == "content" -> {
                val auth = sourceUri.authority.orEmpty()
                if (auth == "com.android.externalstorage.documents") {
                    val docId = try {
                        if (sourceUri.pathSegments.contains("document")) DocumentsContract.getDocumentId(sourceUri)
                        else DocumentsContract.getTreeDocumentId(sourceUri)
                    } catch (_: Exception) { "" }
                    if (docId.startsWith("primary:")) {
                        val rel = docId.removePrefix("primary:").trimStart('/')
                        "Internal shared storage/$rel"
                    } else {
                        getSourceName(sourceUri)
                    }
                } else {
                    getSourceName(sourceUri)
                }
            }
            else -> getSourceName(sourceUri)
        }

        val tree = try { getArchiveTree(sourceUri, ArchiveUri.isDeletedView(archiveUri), entryPath.substringBeforeLast('/', "")) } catch (_: Exception) { null }
        val matchedEntry = tree?.getEntry(entryPath)
        val canonicalEntryPath = matchedEntry?.path ?: entryPath
        val cleanEntry = canonicalEntryPath.replace('\\', '/').trim().removePrefix("/").removeSuffix("/")
        return if (cleanEntry.isEmpty()) sourceVirtualPath else "$sourceVirtualPath/$cleanEntry"
    }

    fun extractToCache(archiveUri: Uri, virtualPathOverride: String? = null): File {
        require(ArchiveUri.isArchiveUri(archiveUri)) { "Expected archive URI: $archiveUri" }
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        val archiveName = getSourceName(sourceUri)

        val virtualPath = virtualPathOverride ?: resolveVirtualPath(archiveUri)
        val sanitizedRelativePath = virtualPath.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                segment.replace(':', '_').replace('\u0000', '_')
            }

        val tree = getArchiveTree(sourceUri, ArchiveUri.isDeletedView(archiveUri), entryPath.substringBeforeLast('/', ""))
        val entry = tree.getEntry(entryPath)
        // File-returning callers may hand this to another app or retain it as a nested mount.
        // Keep its lease for the process lifetime rather than guessing when that consumer finishes.
        val lease = diskCache.stage(entry?.name ?: sanitizedRelativePath.substringAfterLast('/'), entry?.sizeBytes) {
            openStream(archiveUri)
        }
        retainedCacheLeases.add(lease)
        return lease.file
    }
    private val retainedCacheLeases = java.util.concurrent.ConcurrentLinkedQueue<java.io.Closeable>()

    fun openStream(archiveUri: Uri): InputStream {
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        val archiveName = getSourceName(sourceUri)
        // Batch refs already identify the requested entry. FAT's path reader
        // resolves names and aliases itself; rebuilding every directory here
        // makes verifying N small writes repeatedly scan the growing image.
        val canonicalPath = if (trustedBatchSources.contains(sourceUri.toString())) entryPath
            else (try { getArchiveTree(sourceUri, ArchiveUri.isDeletedView(archiveUri), entryPath.substringBeforeLast('/', "")) } catch (_: Exception) { null })?.getEntry(entryPath)?.path ?: entryPath
        // Resolve after canonicalization so refreshing a live image's directory
        // cannot leave the actual extraction bound to its older cached device.
        val (openStream, localFile, dev) = resolveSource(sourceUri)
        val stream = try { if (ArchiveUri.isDeletedView(archiveUri))
            reader.openDeletedFatEntry(archiveName, canonicalPath, openStream, localFile, dev)
            else reader.openEntryStream(archiveName, canonicalPath, openStream, localFile, dev, archiveVolumes(sourceUri)) }
            catch (e: Exception) { dev?.close(); throw e }
        return object : java.io.FilterInputStream(stream) {
            override fun close() { try { super.close() } finally { dev?.close() } }
        }
    }

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> = try {
        val uri = ArchiveUri.uriFactory(parent.documentId)
        val sourceUri = ArchiveUri.getSourceUri(uri)
        val parentPath = ArchiveUri.getEntryPath(uri)
        val tree = getArchiveTree(sourceUri, ArchiveUri.isDeletedView(uri), parentPath)
        val entries = tree.list(parentPath)

        val archiveWritable = try { isWritable(uri) } catch (_: Throwable) { false }
        val resourceEntries = entries.map { entry ->
            val entryUri = ArchiveUri.build(sourceUri, entry.path, deleted = ArchiveUri.isDeletedView(uri))
            ResourceEntry(
                ref = ResourceRef(id, entryUri.toString()),
                metadata = ResourceMetadata(
                    name = entry.name,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    modifiedAtMillis = entry.modifiedAtMillis,
                    isDirectory = entry.isDirectory,
                    canRead = entry.readError == null,
                    canWrite = archiveWritable,
                    permissionsMode = entry.permissionsMode,
                    permissionsFormatted = entry.permissionsMode?.let { com.infinifolder.storage.PermissionHelper.formatPosix(it, entry.isDirectory) },
                    owner = entry.owner, group = entry.group,
                    isSymbolicLink = entry.isSymbolicLink,
                    linkTarget = entry.linkTarget,
                    readError = entry.readError,
                    recoveryNote = entry.recoveryNote,
                    partialRecoveryAvailable = entry.partialRecoveryAvailable
                ),
                uri = entryUri
            )
        }
        val warnings = tree.warnings + entries.filter { it.readError != null }.map { "${it.name}: ${it.readError}" }
        ProviderResult.Success(resourceEntries, notice = warnings.distinct().take(20).joinToString("\n").takeIf { it.isNotEmpty() })
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to list archive contents", e)
    }

    override fun openPartialRecovery(ref: ResourceRef): ProviderResult<RecoveryExport> = try {
        val uri = Uri.parse(ref.documentId)
        if (!ArchiveUri.isDeletedView(uri)) throw IOException("Partial recovery requires a deleted-file candidate")
        val source = ArchiveUri.getSourceUri(uri)
        val (input, file, dev) = resolveSource(source)
        val result = try { reader.openPartialDeletedFatEntry(getSourceName(source), ArchiveUri.getEntryPath(uri), input, file, dev) }
            finally { dev?.close() }
        ProviderResult.Success(result)
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot recover partial file", e) }

    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> = try {
        val uri = Uri.parse(ref.documentId)
        ProviderResult.Success(openStream(uri))
    } catch (e: Throwable) {
        ProviderResult.Failure(e.message ?: "Failed to read archive entry", e)
    }

    fun checkMounted(archiveUri: Uri): MountCheckResult? {
        if (!ArchiveUri.isArchiveUri(archiveUri)) return null
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        // A batch snapshot lives in app-private cache and cannot be mounted by
        // Android while this batch is operating. Probing /proc/mounts for every
        // per-entry mutation adds noticeable latency to large copies.
        if (trustedBatchSources.contains(sourceUri.toString())) return null
        // Only a local source can be mounted by this Android device. Do not open remote handles for UI checks.
        if (sourceUri.scheme != "file" && localProvider?.owns(sourceUri) != true) return null
        val (_, localFile, dev) = try { resolveSource(sourceUri) } catch (_: Exception) { return null }
        return try { localFile?.let { MountedPartitionDetector.checkMounted(it) } } finally { dev?.close() }
    }

    private val writableFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val writableHints = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Only private, app-created batch snapshots may skip repeated full-image probes. */
    private val trustedBatchSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val loggedBatchSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var privateBatchFailure: Throwable? = null

    internal fun requireHealthyPrivateBatch() {
        privateBatchFailure?.let { throw IOException("Cannot publish a failed image mutation; original retained", it) }
    }

    internal fun canRelocatePrivateEntries(entries: List<ResourceRef>, destination: ResourceRef): Boolean {
        val target = Uri.parse(destination.documentId)
        val source = ArchiveUri.getSourceUri(target)
        require(trustedBatchSources.contains(source.toString())) { "Relocation preflight requires a private image" }
        val paths = (entries + destination).map { ref ->
            val uri = Uri.parse(ref.documentId)
            require(ArchiveUri.getSourceUri(uri) == source && !ArchiveUri.isDeletedView(uri))
            ArchiveUri.getEntryPath(uri)
        }
        val (_, file, dev) = resolveSource(source)
        return try { reader.sameRelocationFilesystem(getSourceName(source), file, dev, paths) }
            finally { dev?.close() }
    }

    internal fun trustPrivateBatchSource(sourceUri: Uri) {
        require(sourceUri.scheme == "file" && localProvider?.owns(sourceUri) == true) {
            "Only a private local batch snapshot can be trusted"
        }
        val file = localProvider.fileFor(sourceUri)
        require(file.isFile && file.canWrite()) { "Batch snapshot is not writable" }
        val batchRoot = File(context.cacheDir, "container_batches").canonicalFile.toPath()
        val sourceDirectory = customCacheDir?.parentFile?.resolve("source")?.canonicalFile
        require(file.canonicalFile.parentFile == sourceDirectory && file.canonicalFile.toPath().startsWith(batchRoot)) {
            "Only a snapshot inside this private batch workspace can be trusted"
        }
        trustedBatchSources += sourceUri.toString()
        com.infinifolder.storage.DiagnosticLog.record("container.batch.private-image",
            "source=${file.name} mountChecks=publication verification=direct-path sync=publication")
    }

    /** Presentation hint only: mutations always probe their actual writable handle again. */
    fun cachedWritable(archiveUri: Uri): Boolean = writableHints[archiveUri.toString()] ?: false

    fun isWritable(archiveUri: Uri): Boolean {
        if (ArchiveUri.isDeletedView(archiveUri)) {
            writableFailures[archiveUri.toString()] = "Deleted-file recovery views are read-only"
            writableHints[archiveUri.toString()] = false
            return false
        }
        val sourceUri = runCatching { ArchiveUri.getSourceUri(archiveUri) }.getOrNull()
        val trustedSnapshot = sourceUri != null && trustedBatchSources.contains(sourceUri.toString())
        val failure = if (trustedSnapshot) {
            val file = runCatching { localProvider?.fileFor(sourceUri!!) }.getOrNull()
            if (file?.isFile == true && file.canWrite()) null else "Private batch image is no longer writable"
        } else try { probeWritableFailure(archiveUri) }
            catch (e: Exception) { e.message ?: "Cannot inspect destination write access" }
        if (writableHints.size > 512) { writableHints.clear(); writableFailures.clear() }
        val key = archiveUri.toString()
        if (failure == null) writableFailures.remove(key) else writableFailures[key] = failure
        writableHints[key] = failure == null
        if (!trustedSnapshot || failure != null || loggedBatchSources.add(sourceUri.toString())) {
            com.infinifolder.storage.DiagnosticLog.record("archive.write-capability",
                "source=${runCatching { getSourceName(sourceUri ?: ArchiveUri.getSourceUri(archiveUri)) }.getOrDefault("unknown")} " +
                    "writable=${failure == null} batchCache=$trustedSnapshot reason=${failure.orEmpty()}")
        }
        return failure == null
    }

    private fun isZipSource(source: Uri): Boolean = ArchiveFormats.containerExtension(getSourceName(source)) == "zip"

    private fun needsRebuild(source: Uri): Boolean = !trustedBatchSources.contains(source.toString()) && getSourceName(source).let {
        ContainerCompression.isTar(it) || ArchiveFormats.isDiscImage(it)
    }

    /** Single UI mutations use the same private commit path as bulk copy/delete. */
    private fun <T> rebuiltMutation(destination: ResourceRef, action: (StorageProvider) -> ProviderResult<T>): ProviderResult<T> {
        val opened = beginWriteBatch(destination) { false }
        if (opened !is ProviderResult.Success) return when (opened) {
            is ProviderResult.Failure -> opened
            else -> ProviderResult.Failure("Cannot prepare container changes")
        }
        return opened.value.use { batch ->
            action(batch.provider).also { if (it is ProviderResult.Success) batch.commit() }
        }
    }

    private fun rewriteZip(source: Uri, path: String, directory: Boolean = false, content: File? = null,
        createNew: Boolean = false, delete: Boolean = false) {
        if (ArchiveUri.isArchiveUri(source)) throw IOException("Nested containers are read-only until outer-layer commits exist")
        val storage = sourceProvider ?: throw IOException("Container storage is unavailable")
        val ref = storage.refForUri(source) ?: throw IOException("Container provider is unavailable")
        val work = File(context.cacheDir, "zip_updates/${java.util.UUID.randomUUID()}")
        if (!work.mkdirs()) throw IOException("Cannot allocate ZIP staging directory")
        val original = File(work, "original.zip")
        val updated = File(work, "updated.zip")
        try {
            val opened = storage.openRead(ref)
            if (opened !is ProviderResult.Success) throw IOException((opened as? ProviderResult.Failure)?.message ?: "Cannot read ZIP", (opened as? ProviderResult.Failure)?.cause)
            opened.value.use { input -> original.outputStream().use { output ->
                ContainerSpace.copy(input, output, work)
                output.fd.sync()
            } }
            val revision = original.inputStream().use(com.infinifolder.storage.ContentRevision::read)
            if (ContainerCompression.isTar(getSourceName(source))) {
                val additions = if (delete) emptyList() else listOf(ZipContainerEditor.Addition(path, directory, content))
                TarContainerEditor.rebuild(original, updated, getSourceName(source), additions,
                    if (delete || !createNew) setOf(path) else emptySet(), { false }, original.length())
            } else ZipContainerEditor.rewrite(original, updated, path, directory, content, createNew, delete)
            when (val saved = com.infinifolder.storage.ContentReplacement.replaceFromFile(storage, ref, updated,
                File(context.filesDir, "save_recovery"), revision)) {
                is ProviderResult.Success -> Unit
                is ProviderResult.Failure -> throw IOException(saved.message, saved.cause)
                is ProviderResult.Unsupported -> throw IOException("ZIP backing storage cannot publish replacements")
            }
        } finally { treeCache.evictAll(); writableHints.clear(); work.deleteRecursively() }
    }

    private fun zipCreated(parent: ResourceRef, name: String, directory: Boolean, mime: String): ResourceEntry {
        com.infinifolder.storage.StorageSafety.requireNewName(this, parent, name)
        val uri = Uri.parse(parent.documentId)
        val source = ArchiveUri.getSourceUri(uri)
        val base = ArchiveUri.getEntryPath(uri)
        val path = if (base.isEmpty()) name else "$base/$name"
        rewriteZip(source, path, directory = directory, createNew = true)
        val entryUri = ArchiveUri.build(source, path)
        return ResourceEntry(ResourceRef(id, entryUri.toString()), ResourceMetadata(name,
            if (directory) "vnd.android.document/directory" else mime, if (directory) null else 0L,
            System.currentTimeMillis(), directory, canWrite = true), entryUri)
    }

    private fun probeWritableFailure(archiveUri: Uri): String? {
        if (!ArchiveUri.isArchiveUri(archiveUri)) return "Not a mounted container"
        val sourceUri = ArchiveUri.getSourceUri(archiveUri)
        val entryPath = ArchiveUri.getEntryPath(archiveUri)
        if (ArchiveUri.isArchiveUri(sourceUri)) return "Nested container destinations are read-only"
        val name = getSourceName(sourceUri)
        if (ArchiveFormats.containerExtension(name) in setOf("tar.z", "taz"))
            return "Unix-compress TAR archives support browsing and extraction; writing .Z compression is not implemented"
        val tar = ContainerCompression.isTar(name)
        val compressedImage = ContainerCompression.isCompressedImage(name)
        if (isZipSource(sourceUri) || tar || compressedImage) {
            if (getSourceName(sourceUri).endsWith(".apk", ignoreCase = true)) return "APK packages are read-only"
            val storage = sourceProvider ?: return "No backing storage provider is available"
            val ref = storage.refForUri(sourceUri) ?: return "No provider owns the backing archive"
            val failure = when (val result = storage.stat(ref)) {
                is ProviderResult.Success -> if (result.value.metadata.canWrite) null else "The backing container file is read-only"
                is ProviderResult.Failure -> result.message
                is ProviderResult.Unsupported -> "The backing provider cannot inspect container write access"
            }
            if (failure != null || !compressedImage) return failure
            val (input, file, device) = resolveSource(sourceUri)
            return try { reader.compressedWritableFailureReason(name, entryPath, input, file, device) }
                finally { device?.close() }
        }
        // A provider advertising staged/native seek writes can be checked using read access.
        // Unknown providers still have to supply an actual writable handle. Mutations always
        // open a fresh writable device regardless of this presentation hint.
        val storage = sourceProvider
        val sourceRef = storage?.refForUri(sourceUri)
        val capabilities = sourceRef?.let { storage.capabilities(it) }
        if (capabilities is ProviderResult.Failure) return capabilities.message
        val support = (capabilities as? ProviderResult.Success)?.value?.get(ResourceCapability.SEEK_WRITE)
        if (support == Support.UNSUPPORTED)
            return "This storage connection cannot safely save changes inside an image. Copy the image to writable local storage and edit that copy."
        val probeOnly = support == Support.SUPPORTED
        val (_, localFile, dev) = try { resolveSource(sourceUri, writable = !probeOnly) }
            catch (e: Exception) { return "Cannot open image for random access: ${e.message ?: "provider refused access"}" }
        return try {
            reader.writableFailureReason(getSourceName(sourceUri), localFile, dev, entryPath, probeOnly)
        } finally { dev?.close() }
    }

    override fun openWrite(ref: ResourceRef): ProviderResult<java.io.OutputStream> = try {
        val uri = Uri.parse(ref.documentId)
        if (ArchiveUri.isDeletedView(uri)) throw IOException("Deleted-file recovery views are read-only")
        val source = ArchiveUri.getSourceUri(uri)
        if (ArchiveFormats.isDiscImage(getSourceName(source)) && !trustedBatchSources.contains(source.toString())) {
            val parent = ResourceRef(id, ArchiveUri.build(source, ArchiveUri.getEntryPath(uri).substringBeforeLast('/', "")).toString())
            val batch = when (val opened = beginWriteBatch(parent) { false }) {
                is ProviderResult.Success -> opened.value
                is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
                else -> throw IOException("Cannot prepare compressed image changes")
            }
            try {
                val output = when (val opened = batch.provider.openWrite(ref)) {
                    is ProviderResult.Success -> opened.value
                    is ProviderResult.Failure -> throw IOException(opened.message, opened.cause)
                    else -> throw IOException("Cannot open image entry for editing")
                }
                ProviderResult.Success(object : java.io.OutputStream(), com.infinifolder.storage.PendingWrite {
                    private var aborted = false
                    private var closed = false
                    override fun abort() { aborted = true; (output as? com.infinifolder.storage.PendingWrite)?.abort() }
                    override fun write(value: Int) { try { output.write(value) } catch (e: Exception) { abort(); throw e } }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        try { output.write(bytes, offset, length) } catch (e: Exception) { abort(); throw e }
                    }
                    override fun flush() { try { output.flush() } catch (e: Exception) { abort(); throw e } }
                    override fun close() {
                        if (closed) return
                        closed = true
                        try { output.close(); if (!aborted) batch.commit() } finally { batch.close() }
                    }
                })
            } catch (e: Exception) { batch.close(); throw e }
        } else {
        val mountCheck = checkMounted(uri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot write to '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(uri)) {
            ProviderResult.Unsupported(ResourceCapability.CREATE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val entryPath = ArchiveUri.getEntryPath(uri)
            val stageDir = File(context.cacheDir, "archive_writes").apply { mkdirs() }
            val content = File.createTempFile("entry-", ".tmp", stageDir)
            val output = content.outputStream()
            val pending = object : java.io.OutputStream(), com.infinifolder.storage.PendingWrite {
                private var closed = false
                private var aborted = false
                override fun abort() { aborted = true }
                override fun write(value: Int) { try { ContainerSpace.require(stageDir, 1); output.write(value) } catch (e: Exception) { aborted = true; throw e } }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    try { ContainerSpace.require(stageDir, length.toLong()); output.write(bytes, offset, length) } catch (e: Exception) { aborted = true; throw e }
                }
                override fun flush() { try { output.flush() } catch (e: Exception) { aborted = true; throw e } }
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        // This payload is immediately copied into an unpublished
                        // snapshot. Its durability boundary is the batch's final
                        // image sync, not one storage sync for every small file.
                        output.use { if (!aborted && !trustedBatchSources.contains(sourceUri.toString())) it.fd.sync() }
                        if (!aborted) {
                            if (isZipSource(sourceUri) || ContainerCompression.isTar(getSourceName(sourceUri))) rewriteZip(sourceUri, entryPath, content = content)
                            else synchronized(this@ArchiveStorageProvider) {
                                val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
                                try { reader.writeEntryFromFile(getSourceName(sourceUri), localFile, entryPath, content, dev) }
                                finally { dev?.close() }
                            }
                        }
                    } finally { treeCache.evictAll(); content.delete() }
                }
            }
            ProviderResult.Success(pending)
        }
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to write archive entry", e)
    }

    override fun replaceContent(ref: ResourceRef, data: ByteArray): ProviderResult<Unit> =
        when (val opened = openWrite(ref)) {
            is ProviderResult.Success -> try {
                opened.value.use {
                    it.write(data)
                    it.flush()
                }
                ProviderResult.Success(Unit)
            } catch (e: Exception) {
                ProviderResult.Failure(e.message ?: "Failed to replace archive entry", e)
            }
            is ProviderResult.Failure -> opened
            is ProviderResult.Unsupported -> opened
        }

    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> { return try {
        if (ArchiveUri.isDeletedView(Uri.parse(parent.documentId))) throw IOException("Deleted-file recovery views are read-only")
        val source = ArchiveUri.getSourceUri(Uri.parse(parent.documentId))
        if (needsRebuild(source)) return rebuiltMutation(parent) { it.createDirectory(parent, name) }
        if (isZipSource(source)) {
            if (!isWritable(Uri.parse(parent.documentId))) return ProviderResult.Unsupported(ResourceCapability.CREATE)
            return ProviderResult.Success(zipCreated(parent, name, true, "vnd.android.document/directory"))
        }
        // FatFileSystem enforces create-new semantics, including case-folded
        // names and short-name aliases, while writing the directory entry.
        com.infinifolder.storage.StorageSafety.validateName(name)
        val parentUri = Uri.parse(parent.documentId)
        val mountCheck = checkMounted(parentUri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot create directory in '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(parentUri)) {
            ProviderResult.Unsupported(ResourceCapability.CREATE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val parentPath = ArchiveUri.getEntryPath(parentUri)
            val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
            val archiveName = getSourceName(sourceUri)
            val newEntryPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            try { synchronized(this) { reader.createDirectory(archiveName, localFile, newEntryPath, dev) } } finally { treeCache.evictAll(); dev?.close() }
            treeCache.evictAll()
            val newUri = ArchiveUri.build(sourceUri, newEntryPath)
            ProviderResult.Success(
                ResourceEntry(
                    ref = ResourceRef(id, newUri.toString()),
                    metadata = ResourceMetadata(
                        name = name,
                        mimeType = "",
                        sizeBytes = null,
                        modifiedAtMillis = System.currentTimeMillis(),
                        isDirectory = true
                    ),
                    uri = newUri
                )
            )
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create directory in archive", e)
    }
    }

    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> { return try {
        if (ArchiveUri.isDeletedView(Uri.parse(parent.documentId))) throw IOException("Deleted-file recovery views are read-only")
        val source = ArchiveUri.getSourceUri(Uri.parse(parent.documentId))
        if (needsRebuild(source)) return rebuiltMutation(parent) { it.createFile(parent, name, mimeType) }
        if (isZipSource(source)) {
            if (!isWritable(Uri.parse(parent.documentId))) return ProviderResult.Unsupported(ResourceCapability.CREATE)
            return ProviderResult.Success(zipCreated(parent, name, false, mimeType))
        }
        // FatFileSystem enforces create-new semantics, including case-folded
        // names and short-name aliases, during the actual directory entry write.
        com.infinifolder.storage.StorageSafety.validateName(name)
        val parentUri = Uri.parse(parent.documentId)
        val mountCheck = checkMounted(parentUri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot create file in '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(parentUri)) {
            ProviderResult.Unsupported(ResourceCapability.CREATE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(parentUri)
            val parentPath = ArchiveUri.getEntryPath(parentUri)
            val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
            val archiveName = getSourceName(sourceUri)
            val newEntryPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            try { synchronized(this) { reader.createFile(archiveName, localFile, newEntryPath, ByteArray(0), dev) } } finally { treeCache.evictAll(); dev?.close() }
            treeCache.evictAll()
            val newUri = ArchiveUri.build(sourceUri, newEntryPath)
            ProviderResult.Success(
                ResourceEntry(
                    ref = ResourceRef(id, newUri.toString()),
                    metadata = ResourceMetadata(
                        name = name,
                        mimeType = mimeType.ifEmpty { "application/octet-stream" },
                        sizeBytes = 0L,
                        modifiedAtMillis = System.currentTimeMillis(),
                        isDirectory = false
                    ),
                    uri = newUri
                )
            )
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to create file in archive", e)
    }
    }

    /** This hook deliberately cannot mutate a public backing image directly. */
    @Synchronized
    internal fun relocatePrivateEntry(ref: ResourceRef, destination: ResourceRef, name: String): ProviderResult<ResourceEntry> = try {
        com.infinifolder.storage.StorageSafety.validateName(name)
        val sourceUri = Uri.parse(ref.documentId)
        val destinationUri = Uri.parse(destination.documentId)
        val source = ArchiveUri.getSourceUri(sourceUri)
        if (ref.providerId != id || destination.providerId != id ||
            !trustedBatchSources.contains(source.toString()) || ArchiveUri.getSourceUri(destinationUri) != source ||
            ArchiveUri.isDeletedView(sourceUri) || ArchiveUri.isDeletedView(destinationUri))
            throw IOException("Relocation requires the same private writable container snapshot")
        val parent = ArchiveUri.getEntryPath(destinationUri)
        val path = if (parent.isEmpty()) name else "$parent/$name"
        val (_, localFile, device) = resolveSource(source, writable = true)
        try {
            reader.relocateEntry(getSourceName(source), localFile, ArchiveUri.getEntryPath(sourceUri), path, device)
        } finally { treeCache.evictAll(); device?.close() }
        stat(ResourceRef(id, ArchiveUri.build(source, path).toString()))
    } catch (error: Exception) { ProviderResult.Failure(error.message ?: "Cannot relocate container entry", error) }

    @Synchronized
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> =
        ProviderResult.Unsupported(ResourceCapability.RENAME)

    override fun delete(ref: ResourceRef): ProviderResult<Unit> { return try {
        val uri = Uri.parse(ref.documentId)
        if (ArchiveUri.isDeletedView(uri)) throw IOException("Deleted-file recovery views are read-only")
        val source = ArchiveUri.getSourceUri(uri)
        if (needsRebuild(source)) {
            val path = ArchiveUri.getEntryPath(uri)
            if (path.isEmpty()) throw IOException("Cannot delete a container root")
            val parent = ResourceRef(id, ArchiveUri.build(source, path.substringBeforeLast('/', "")).toString())
            return rebuiltMutation(parent) { it.delete(ref) }
        }
        if (isZipSource(source)) {
            if (!isWritable(uri)) return ProviderResult.Unsupported(ResourceCapability.DELETE)
            val path = ArchiveUri.getEntryPath(uri)
            if (path.isEmpty()) throw IOException("Cannot delete a container root")
            rewriteZip(source, path, delete = true)
            return ProviderResult.Success(Unit)
        }
        val mountCheck = checkMounted(uri)
        if (mountCheck?.isMounted == true) {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val archiveName = getSourceName(sourceUri)
            ProviderResult.Failure(
                "Cannot delete from '$archiveName': partition is currently mounted (${mountCheck.reason})",
                MountedPartitionException(archiveName, mountCheck.reason)
            )
        } else if (!isWritable(uri)) {
            ProviderResult.Unsupported(ResourceCapability.DELETE)
        } else {
            val sourceUri = ArchiveUri.getSourceUri(uri)
            val entryPath = ArchiveUri.getEntryPath(uri)
            val (_, localFile, dev) = resolveSource(sourceUri, writable = true)
            val archiveName = getSourceName(sourceUri)
            try { synchronized(this) { reader.deleteEntry(archiveName, localFile, entryPath, dev) } } finally { treeCache.evictAll(); dev?.close() }
            treeCache.evictAll()
            ProviderResult.Success(Unit)
        }
    } catch (e: Exception) {
        ProviderResult.Failure(e.message ?: "Failed to delete archive entry", e)
    }
    }

    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.COPY)

    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        ProviderResult.Unsupported(ResourceCapability.MOVE)

    companion object {
    }
}
