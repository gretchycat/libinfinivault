package com.infinifolder.archive

import io.github.gretchycat.infinistream.CancellationToken
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile

data class ZipExtractionReport(val files: Int, val bytes: Long, val skippedUnsafeEntries: Int)

/** Existing leased-directory ZIP expansion. The host supplies capacity-checked output streams. */
object VaultZipExtraction {
    @Throws(IOException::class)
    fun extract(source: File, directory: File, output: (File) -> OutputStream,
        cancellation: CancellationToken = CancellationToken.NONE): ZipExtractionReport {
        cancellation.check()
        val root = directory.canonicalFile.toPath()
        var files = 0; var bytes = 0L; var skipped = 0
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                cancellation.check()
                val entry = entries.nextElement()
                val name = entry.name
                val target = File(directory, name)
                if (name.startsWith('/') || name.contains('\\') || name.contains('\u0000') ||
                    !target.canonicalFile.toPath().startsWith(root) || target.canonicalFile.toPath() == root) {
                    skipped++; continue
                }
                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create ZIP resource directory")
                } else {
                    target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create ZIP resource directory") }
                    zip.getInputStream(entry).use { input -> output(target).use { destination ->
                        while (true) {
                            cancellation.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("ZIP input made no progress")
                            destination.write(buffer, 0, count)
                            bytes = Math.addExact(bytes, count.toLong())
                        }
                    } }
                    files++
                }
            }
        }
        cancellation.check()
        return ZipExtractionReport(files, bytes, skipped)
    }
}
