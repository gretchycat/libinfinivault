package com.infinifolder.archive

import java.io.File
import java.io.RandomAccessFile

/** A handle to an unpublished, app-private snapshot; its owner syncs it before publication. */
internal class PrivateBatchBlockDevice(file: File, writable: Boolean,
    private val onFailure: (Throwable) -> Unit = {}) : BlockDevice by FileBlockDevice(
    RandomAccessFile(file, if (writable) "rw" else "r"),
    isReadOnly = !writable,
    ownsFile = true,
    flushOnRequest = false
) {
    private val source = file.canonicalFile

    fun belongsTo(file: File): Boolean = source == file.canonicalFile
    fun markFailed(error: Throwable) { onFailure(error) }
}
