package com.infinifolder.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

/** Compression is a publication layer, never a writable random-access device. */
internal enum class ContainerCompression {
    GZIP, BZIP2, XZ, LZMA;

    fun input(raw: InputStream): InputStream = when (this) {
        GZIP -> GzipCompressorInputStream(raw, true)
        BZIP2 -> BZip2CompressorInputStream(raw, true)
        XZ -> XZCompressorInputStream(raw, true)
        LZMA -> LZMACompressorInputStream(raw)
    }

    fun output(raw: OutputStream, size: Long): OutputStream = when (this) {
        GZIP -> GzipCompressorOutputStream(raw)
        BZIP2 -> BZip2CompressorOutputStream(raw)
        XZ -> XZCompressorOutputStream(raw)
        LZMA -> LZMAOutputStream(raw, LZMA2Options(), size)
    }

    companion object {
        fun forName(name: String): ContainerCompression? {
            val ext = ArchiveFormats.containerExtension(name).lowercase(Locale.ROOT)
            return when {
                ext.endsWith(".gz") || ext == "tgz" -> GZIP
                ext.endsWith(".bz2") || ext.endsWith(".bz") || ext in setOf("tbz", "tbz2") -> BZIP2
                ext.endsWith(".xz") || ext == "txz" -> XZ
                ext.endsWith(".lzma") || ext == "tlz" -> LZMA
                else -> null
            }
        }
        fun isTar(name: String): Boolean = ArchiveFormats.containerExtension(name).let {
            it in setOf("tar", "cbt", "tgz", "tbz", "tbz2", "txz", "tlz") ||
                it in setOf("tar.gz", "tar.bz2", "tar.bz", "tar.xz", "tar.lzma")
        }
        fun isCompressedImage(name: String): Boolean = ArchiveFormats.isDiscImage(name) && forName(name) != null
    }
}

/** Leave room for a recovery copy while staging. Unknown remote capacity is never invented. */
internal object ContainerSpace {
    private const val MARGIN = 1024L * 1024L
    fun require(directory: File, bytes: Long, reserved: Long = 0) {
        if (bytes < 0 || reserved < 0 || bytes > Long.MAX_VALUE - reserved - MARGIN)
            throw IOException("Container working-space requirement is too large")
        val available = directory.usableSpace
        if (available < bytes + reserved + MARGIN)
            throw IOException("Not enough temporary space for container changes. About " +
                "${com.infinifolder.storage.StorageMessages.approximateSize(bytes + reserved + MARGIN)} needed; " +
                "${com.infinifolder.storage.StorageMessages.approximateSize(available)} available. Original retained.")
    }
    fun copy(input: InputStream, output: OutputStream, directory: File, reserved: Long = 0,
        cancelled: () -> Boolean = { false }) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            if (cancelled()) throw InterruptedException("Container operation cancelled")
            val n = input.read(buffer)
            if (n < 0) break
            require(directory, n.toLong(), reserved)
            output.write(buffer, 0, n)
        }
    }
    fun guarded(output: OutputStream, directory: File, reserved: Long, cancelled: () -> Boolean) =
        object : java.io.FilterOutputStream(output) {
            override fun write(value: Int) {
                if (cancelled()) throw InterruptedException("Container operation cancelled")
                require(directory, 1, reserved)
                out.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (cancelled()) throw InterruptedException("Container operation cancelled")
                require(directory, length.toLong(), reserved)
                out.write(bytes, offset, length)
            }
        }
}
