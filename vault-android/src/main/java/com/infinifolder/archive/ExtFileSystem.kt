package com.infinifolder.archive

import androidx.annotation.Keep
import com.infinifolder.storage.DiagnosticLog
import com.infinifolder.storage.FileSystemInfo
import java.io.File
import java.io.IOException
import com.infinifolder.model.TransferMetadata

/** Native ext2/3/4 mutations over the same bounded block-device contract as FAT.
 * Writes are permitted only on a private image batch, never the public original.
 */
internal class ExtFileSystem(private val device: BlockDevice, private val tempDir: File,
                             private val privateSnapshot: Boolean) : WritableImageFileSystem {
    init { ExtNative.requireAvailable() }

    fun probe() { call(0, "") }

    private fun call(action: Int, path: String, target: String? = null, content: File? = null): LongArray {
        RecoveryCancellation.check()
        val mutating = action != 0 && action != 6
        if (mutating && (!privateSnapshot || device.isReadOnly))
            throw IOException("ext2/3/4 edits require a private writable image batch; original retained")
        val started = android.os.SystemClock.uptimeMillis()
        val result = ExtNative.execute(ExtNativeDevice(device, mutating), device.size, action,
            path.toByteArray(Charsets.UTF_8), target?.toByteArray(Charsets.UTF_8),
            content?.absolutePath?.toByteArray(Charsets.UTF_8))
        DiagnosticLog.record(if (action == 0) "image.ext.probe" else "image.ext.mutation",
            "action=$action path=$path target=${target.orEmpty()} journal=${result[3] == 1L} " +
                "recovered=${result[4] == 1L} inode=${result[5]} payloadCopied=${action != 5} " +
                "elapsedMs=${android.os.SystemClock.uptimeMillis() - started}")
        return result
    }

    override fun createFile(relativePath: String, data: ByteArray) {
        call(1, relativePath)
        if (data.isNotEmpty()) writeEntry(relativePath, data)
    }
    override fun createDirectory(relativePath: String) { call(2, relativePath) }
    override fun writeEntry(relativePath: String, data: ByteArray) {
        tempDir.mkdirs()
        val content = File.createTempFile("ext-entry-", ".tmp", tempDir)
        try { content.writeBytes(data); writeEntryFromFile(relativePath, content) }
        finally { content.delete() }
    }
    override fun writeEntryFromFile(relativePath: String, file: File) { call(3, relativePath, content = file) }
    override fun deleteEntry(relativePath: String) { call(4, relativePath) }
    override fun relocateEntry(relativePath: String, targetPath: String) { call(5, relativePath, targetPath) }
    override fun readTransferMetadata(relativePath: String): TransferMetadata {
        tempDir.mkdirs()
        val file = File.createTempFile("ext-metadata-", ".tmp", tempDir)
        return try {
            call(6, relativePath, content = file)
            val raw = TransferMetadata.readFrom(file)
            raw.copy(xattrs = raw.xattrs?.mapValues { (name, value) -> if (ExtAclCodec.isAcl(name)) ExtAclCodec.export(value) else value })
                .also { it.xattrs?.get("system.posix_acl_access")?.let { acl -> ExtAclCodec.validate(acl, it.mode) } }
        } finally { file.delete() }
    }
    override fun applyTransferMetadata(relativePath: String, metadata: TransferMetadata) {
        tempDir.mkdirs()
        val file = File.createTempFile("ext-metadata-", ".tmp", tempDir)
        try {
            metadata.copy(xattrs = metadata.xattrs?.mapValues { (name, value) ->
                if (ExtAclCodec.isAcl(name)) ExtAclCodec.import(value, metadata.mode, name.endsWith("_access")) else value
            }).writeTo(file)
            call(7, relativePath, content = file)
            val actual = readTransferMetadata(relativePath)
            if (!metadata.matches(actual)) {
                DiagnosticLog.record("image.ext.metadata-mismatch", "expected=$metadata actual=$actual")
                throw IOException("Ext metadata verification failed; original retained")
            }
        } finally { file.delete() }
    }

    companion object {
        /** Superblock-only accounting avoids a native mount just to show free space. */
        fun info(device: BlockDevice): FileSystemInfo {
            val sb = device.readBytes(1024, 1024)
            if (sb.size != 1024) throw IOException("Truncated ext superblock")
            fun u32(offset: Int): Long = (0..3).fold(0L) { n, i -> n or ((sb[offset+i].toLong() and 255) shl (i*8)) }
            val incompat = u32(96)
            val blockShift = u32(24)
            if (blockShift > 6) throw IOException("Unsupported ext block size")
            val blockSize = 1024L shl blockShift.toInt()
            val free = u32(12) or (if (incompat and 0x80L != 0L) u32(344) shl 32 else 0L)
            val type = if (incompat and (0x40L or 0x80L or 0x200L) != 0L || u32(100) and 0x400L != 0L) "ext4"
                else if (u32(92) and 4 != 0L) "ext3" else "ext2"
            val freeBytes = if (free >= 0 && free <= device.size / blockSize) free * blockSize else null
            return FileSystemInfo(type, null, "Ext superblock; mutation features checked separately", caseSensitive = true,
                availableBytes = freeBytes, allocationUnitBytes = blockSize)
        }
    }
}

/** Stable JNI method names; cancellation is checked at every I/O boundary. */
@Keep
internal class ExtNativeDevice(private val device: BlockDevice, private val writable: Boolean) {
    fun readAt(offset: Long, buffer: ByteArray, start: Int, length: Int): Int {
        RecoveryCancellation.check()
        return device.readAt(offset, buffer, start, length)
    }
    fun writeAt(offset: Long, buffer: ByteArray, start: Int, length: Int) {
        RecoveryCancellation.check()
        if (!writable || device.isReadOnly) throw IOException("Read-only ext device")
        requireDeviceRange(offset, buffer, start, length)
        if (offset > device.size || length.toLong() > device.size - offset) throw IOException("Ext write exceeds volume bounds")
        device.writeAt(offset, buffer, start, length)
    }
    fun flush() { if (writable) { RecoveryCancellation.check(); device.flush() } }
}

@Keep
internal object ExtNative {
    private val loadError = runCatching { System.loadLibrary("infiniext") }.exceptionOrNull()
    fun requireAvailable() {
        if (loadError != null) throw IOException("ext2/3/4 writing is unavailable for this build/device architecture", loadError)
    }
    external fun execute(device: ExtNativeDevice, size: Long, action: Int, path: ByteArray,
                         target: ByteArray?, content: ByteArray?): LongArray
}
