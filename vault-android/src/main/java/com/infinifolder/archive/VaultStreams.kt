package com.infinifolder.archive

import com.infinifolder.storage.StreamBlockDevice
import com.infinifolder.storage.StreamFormatProbe
import com.infinifolder.storage.StreamIdentification
import io.github.gretchycat.infinistream.*

/** Lazy entry-source bridge; legacy parsers keep their native random-access/expansion behavior. */
object VaultStreams {
    val archiveDescriptor = ProcessorDescriptor("vault.archive",
        listOf(PortDescriptor("container", setOf(DataKind.BYTES), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("entries", setOf(DataKind.DIRECTORY), multiple = true)))

    private fun readerName(original: String, identification: StreamIdentification): String {
        val extension = when (identification.legacyResult?.mimeType) {
            "application/zip" -> "zip"
            "application/x-7z-compressed" -> "7z"
            "application/x-tar" -> "tar"
            "application/x-cpio" -> "cpio"
            "application/x-archive" -> "ar"
            "application/vnd.rar", "application/x-rar-compressed" -> "rar"
            "application/x-arj" -> "arj"
            "application/vnd.ms-cab-compressed", "application/x-cabinet" -> "cab"
            "application/x-iso9660-image" -> "iso"
            "application/x-raw-disk-image" -> "img"
            "application/x-virtualbox-vdi" -> "vdi"
            "application/x-qemu-disk" -> "qcow2"
            "application/x-vmdk" -> "vmdk"
            "application/x-vhd" -> "vhd"
            "application/x-vhdx" -> "vhdx"
            else -> return original
        }
        // A decoder dispatch hint only; the original name/URI remain in provenance.
        return "stream.$extension"
    }
    fun entry(reader: ArchiveReader, container: SourceResource, path: String,
        entry: ArchiveEntry? = null): SourceResource = object : SourceResource {
        override val metadata = StreamMetadata(
            ContentType(mimeType = entry?.mimeType), entry?.name ?: path.substringAfterLast('/'), entry?.sizeBytes,
            capabilities = setOf(Capability.READ), warnings = listOfNotNull(entry?.readError, entry?.recoveryNote),
            provenance = container.metadata.provenance.copy(transformations = container.metadata.provenance.transformations + "archive-entry:$path"))
        override fun open(): ByteSource {
            val handle = container.open()
            var owner: java.io.Closeable = handle
            try {
                // Negotiate from the demonstrated handle, not optimistic resource-wide hints.
                val positional = handle as? PositionalReadable
                val device: StreamBlockDevice?
                val input: () -> java.io.InputStream
                val identified: StreamIdentification
                if (positional != null && handle.metadata.length != null) {
                    device = StreamBlockDevice(handle)
                    owner = device
                    identified = StreamFormatProbe.inspect(ProbeReader(positional, handle.metadata,
                        ProbeBudget(maxNanos = 2_000_000_000)))
                    input = { container.open().asInputStream() }
                } else {
                    device = null
                    val replay = ReplayableSource(handle, 2048)
                    owner = replay
                    identified = StreamFormatProbe.inspectPrefix(replay.metadata, replay.peek(2048))
                    var first = true
                    input = { if (first) { first = false; replay.asInputStream() } else container.open().asInputStream() }
                }
                val stream = reader.openEntryStream(readerName(container.metadata.name ?: "container", identified), path,
                    input, device = device)
                val owned = object : java.io.FilterInputStream(stream) {
                    override fun close() { try { super.close() } finally { owner.close() } }
                }
                return InputStreamSource(owned, metadata.copy(warnings = metadata.warnings + identified.metadata.warnings))
            } catch (error: Throwable) { try { owner.close() } catch (close: Throwable) { error.addSuppressed(close) }; throw error }
        }
    }
}
