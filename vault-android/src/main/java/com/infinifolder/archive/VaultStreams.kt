package com.infinifolder.archive

import com.infinifolder.storage.StreamBlockDevice
import io.github.gretchycat.infinistream.*

/** Lazy entry-source bridge; legacy parsers keep their native random-access/expansion behavior. */
object VaultStreams {
    val archiveDescriptor = ProcessorDescriptor("vault.archive",
        listOf(PortDescriptor("container", setOf(DataKind.BYTES))),
        listOf(PortDescriptor("entries", setOf(DataKind.DIRECTORY), multiple = true)))

    fun entry(reader: ArchiveReader, container: SourceResource, path: String,
        entry: ArchiveEntry? = null): SourceResource = object : SourceResource {
        override val metadata = StreamMetadata(
            ContentType(mimeType = entry?.mimeType), entry?.name ?: path.substringAfterLast('/'), entry?.sizeBytes,
            capabilities = setOf(Capability.READ),
            provenance = container.metadata.provenance.copy(transformations = container.metadata.provenance.transformations + "archive-entry:$path"))
        override fun open(): ByteSource {
            val device = if (Capability.POSITIONAL_READ in container.metadata.capabilities && container.metadata.length != null) {
                val handle = container.open()
                try { StreamBlockDevice(handle) } catch (error: Throwable) {
                    try { handle.close() } catch (close: Throwable) { error.addSuppressed(close) }; throw error
                }
            } else null
            try {
                val input = reader.openEntryStream(container.metadata.name ?: "container", path,
                    { container.open().asInputStream() }, device = device)
                val owned = object : java.io.FilterInputStream(input) {
                    override fun close() { try { super.close() } finally { device?.close() } }
                }
                return InputStreamSource(owned, metadata)
            } catch (error: Throwable) { try { device?.close() } catch (close: Throwable) { error.addSuppressed(close) }; throw error }
        }
    }
}
