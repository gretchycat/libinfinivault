package com.infinifolder.archive

import com.infinifolder.model.*
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** Explicit salvage: never read allocated clusters and never invent an original fragmented chain. */
internal object FatPartialRecovery {
    fun extract(source: BlockDevice, output: OutputStream, path: String, size: Long, start: Long,
        fs: FatFileSystem, allocationBlock: (Long) -> String?, clusterLimit: Long,
        verifySnapshot: (BlockDevice) -> Unit, note: String): RecoveryReport {
        verifySnapshot(source)
        val ranges = mutableListOf<RecoveryRange>()
        val outputHash = MessageDigest.getInstance("SHA-256")
        val copiedHash = MessageDigest.getInstance("SHA-256")
        fun emit(offset: Long, bytes: ByteArray, grade: RecoveryGrade, disposition: RecoveryDisposition, reason: String) {
            RecoveryCancellation.check()
            if (bytes.isEmpty()) return
            val previous = ranges.lastOrNull()
            if (previous != null && previous.offset + previous.length == offset && previous.grade == grade &&
                previous.disposition == disposition && previous.reason == reason)
                ranges[ranges.lastIndex] = previous.copy(length = previous.length + bytes.size)
            else {
                if (ranges.size >= 20000) throw IOException("Partial recovery exceeds the report range limit")
                ranges.add(RecoveryRange(offset, bytes.size.toLong(), grade, disposition, reason))
            }
            outputHash.update(bytes)
            if (disposition == RecoveryDisposition.COPIED_UNVERIFIED) copiedHash.update(bytes)
            output.write(bytes)
        }
        var position = 0L
        while (position < size) {
            RecoveryCancellation.check()
            val clusterIndex = position / fs.clusterSize
            val cluster = start + clusterIndex
            val within = (position % fs.clusterSize).toInt()
            val n = minOf((fs.clusterSize - within).toLong(), size - position).toInt()
            if (cluster !in 2 until clusterLimit) {
                emit(position, ByteArray(n), RecoveryGrade.UNAVAILABLE, RecoveryDisposition.ZERO_FILLED, "outside_validated_volume")
                position += n; continue
            }
            val allocationReason = allocationBlock(cluster)
            if (allocationReason != null) {
                emit(position, ByteArray(n), if (allocationReason == "marked_bad_cluster") RecoveryGrade.UNAVAILABLE else RecoveryGrade.POSSIBLY_BAD,
                    RecoveryDisposition.ZERO_FILLED, allocationReason + "; original contents are not recoverable from this allocation evidence")
                position += n; continue
            }
            val grade = if (clusterIndex == 0L) RecoveryGrade.POSSIBLY_GOOD else RecoveryGrade.POSSIBLY_BAD
            val reason = if (clusterIndex == 0L) "free_first_cluster; original contents unverified" else "free_cluster; contiguous_position_assumed"
            val address = fs.dataStartOffset + (cluster - 2) * fs.clusterSize + within
            val bytes = ByteArray(n)
            var read = 0
            try {
                while (read < n) {
                    RecoveryCancellation.check()
                    val got = source.readAt(address + read, bytes, read, n - read)
                    if (got <= 0) break
                    if (got > n - read) throw IOException("Invalid recovery read length")
                    read += got
                }
            } catch (e: IOException) {
                RecoveryCancellation.check()
                // Keep the readable prefix; retry the remainder at sector boundaries below.
            }
            if (read > 0) emit(position, bytes.copyOf(read), grade, RecoveryDisposition.COPIED_UNVERIFIED, reason)
            var rest = read
            while (rest < n) {
                RecoveryCancellation.check()
                val take = minOf(fs.bytesPerSector - (within + rest) % fs.bytesPerSector, n - rest)
                val sector = ByteArray(take)
                var recovered = 0
                try {
                    while (recovered < take) {
                        val got = source.readAt(address + rest + recovered, sector, recovered, take - recovered)
                        if (got <= 0) break
                        if (got > take - recovered) throw IOException("Invalid recovery sector read length")
                        recovered += got; RecoveryCancellation.check()
                    }
                } catch (e: IOException) {
                    RecoveryCancellation.check()
                }
                if (recovered > 0) emit(position + rest, sector.copyOf(recovered), grade, RecoveryDisposition.COPIED_UNVERIFIED, reason)
                if (recovered < take) emit(position + rest + recovered, ByteArray(take - recovered), RecoveryGrade.UNAVAILABLE,
                    RecoveryDisposition.ZERO_FILLED, "storage_read_failed_at_sector_retry_unit; untested bytes in that sector may also be zero-filled")
                rest += take
            }
            position += n
        }
        // Reread only copied ranges. A change or new read failure aborts rather than publishing an inconsistent file.
        val reread = MessageDigest.getInstance("SHA-256")
        for (range in ranges.filter { it.disposition == RecoveryDisposition.COPIED_UNVERIFIED }) {
            var at = range.offset
            while (at < range.offset + range.length) {
                RecoveryCancellation.check()
                val n = minOf(65536L, range.offset + range.length - at).toInt()
                val address = fs.dataStartOffset + (start - 2) * fs.clusterSize + at
                val bytes = source.readBytes(address, n)
                if (bytes.size != n) throw IOException("Partial recovery source became unreadable during verification")
                reread.update(bytes); at += n
            }
        }
        if (!reread.digest().contentEquals(copiedHash.digest())) throw IOException("Partial recovery source data changed; refresh and retry")
        verifySnapshot(source)
        return RecoveryReport(path, size, fs.fatType.name, ranges,
            outputHash.digest().joinToString("") { "%02x".format(it) }, note, ioRetryUnitBytes = fs.bytesPerSector)
    }
}
