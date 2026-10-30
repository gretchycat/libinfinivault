package com.infinifolder.archive

import java.io.File

/** Filesystem-level mutations. Publication belongs to the enclosing image batch. */
interface WritableImageFileSystem {
    fun readTransferMetadata(relativePath: String): com.infinifolder.model.TransferMetadata? = null
    fun applyTransferMetadata(relativePath: String, metadata: com.infinifolder.model.TransferMetadata) {
        throw java.io.IOException("This filesystem cannot preserve the requested metadata")
    }
    fun createFile(relativePath: String, data: ByteArray)
    fun createDirectory(relativePath: String)
    fun writeEntry(relativePath: String, data: ByteArray)
    fun writeEntryFromFile(relativePath: String, file: File)
    fun deleteEntry(relativePath: String)
    fun relocateEntry(relativePath: String, targetPath: String)
}
