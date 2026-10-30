package com.infinifolder.archive

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Conservative recovery policy for GPT. Never repairs or falls back to a damaged table. */
internal object FatRecoveryPartitions {
    private fun fail(reason: String): Nothing = throw IOException("FAT recovery: $reason; partition scan refused")
    private fun u32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun i64(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun read(dev: BlockDevice, offset: Long, length: Int): ByteArray {
        RecoveryCancellation.check()
        if (offset < 0 || offset > dev.size || length > dev.size - offset) fail("Partition metadata is outside the disk")
        return dev.readBytes(offset, length).also { if (it.size != length) fail("Incomplete partition metadata") }
    }
    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    fun validate(dev: BlockDevice) {
        if (dev.size < 1024) return
        val first = read(dev, 0, 512)
        val header = read(dev, 512, 512)
        val protective = (0 until 4).any { first[446 + it * 16 + 4] == 0xee.toByte() }
        if (String(header, 0, 8, Charsets.US_ASCII) != "EFI PART") {
            if (protective) fail("Protective MBR has no readable primary GPT header")
            return
        }
        if (!protective || first[510] != 0x55.toByte() || first[511] != 0xaa.toByte()) fail("GPT protective MBR is missing")
        fun validateHeader(h: ByteArray, location: Long): ByteArray {
            val size = u32(h, 12)
            if (String(h, 0, 8, Charsets.US_ASCII) != "EFI PART" || u32(h, 8) != 0x10000L || size !in 92L..512L ||
                u32(h, 20) != 0L || i64(h, 24) != location) fail("Invalid GPT header")
            val checked = h.copyOf(size.toInt()).apply { fill(0, 16, 20) }
            if (crc(checked) != u32(h, 16)) fail("GPT header checksum mismatch")
            val count = u32(h, 80); val width = u32(h, 84)
            // Match the shared reader's supported enumeration bounds rather than silently truncating.
            if (count !in 1L..128L || width !in listOf(128L, 256L, 512L)) fail("Unsupported GPT entry-array dimensions")
            val length = (count * width).toInt()
            val lba = i64(h, 72)
            if (lba < 2 || lba > (dev.size - length) / 512) fail("GPT entry array is outside the disk")
            val entries = read(dev, lba * 512, length)
            if (crc(entries) != u32(h, 88)) fail("GPT entry-array checksum mismatch")
            return entries
        }
        val entries = validateHeader(header, 1)
        val backupLba = i64(header, 32)
        if (backupLba != dev.size / 512 - 1 || backupLba < 2) fail("GPT backup header is not at the disk end")
        val backup = read(dev, backupLba * 512, 512)
        val backupEntries = validateHeader(backup, backupLba)
        if (i64(backup, 32) != 1L || !header.copyOfRange(40, 72).contentEquals(backup.copyOfRange(40, 72)) ||
            !header.copyOfRange(80, 92).contentEquals(backup.copyOfRange(80, 92)) || !entries.contentEquals(backupEntries))
            fail("Primary and backup GPT disagree")
        val firstUsable = i64(header, 40); val lastUsable = i64(header, 48)
        if (firstUsable < 2 || lastUsable < firstUsable || lastUsable >= backupLba ||
            i64(header, 72) + (entries.size + 511) / 512 > firstUsable ||
            i64(backup, 72) <= lastUsable || i64(backup, 72) + (entries.size + 511) / 512 > backupLba)
            fail("GPT metadata overlaps the usable partition area")
        val width = u32(header, 84).toInt()
        for (p in entries.indices step width) {
            if ((p until p + 16).all { entries[it] == 0.toByte() }) continue
            val start = i64(entries, p + 32); val end = i64(entries, p + 40)
            if (start < firstUsable || end < start || end > lastUsable) fail("GPT partition is outside the usable disk area")
        }
    }
}
