package com.infinifolder.archive

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Safe in-place modifier for STORED (uncompressed) entries in a ZIP archive.
 * When the replacement data matches the existing entry size, it safely:
 * 1. Writes the new payload bytes directly at the entry's data offset
 * 2. Recalculates the CRC-32 checksum
 * 3. Updates the CRC-32 in the local file header
 * 4. Updates the CRC-32 in the Central Directory header
 * 5. Flushes the BlockDevice
 *
 * If the size changes or the entry is compressed, in-place modification is rejected.
 */
object ZipInPlaceModifier {

    fun canModifyInPlace(device: BlockDevice, entryPath: String): Boolean {
        if (device.isReadOnly) return false
        return try {
            ZipFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { zf ->
                val clean = entryPath.trim().removePrefix("/").removePrefix("./")
                val entry = zf.getEntry(clean)
                    ?: zf.getEntry("/$clean")
                    ?: zf.entries.asSequence().firstOrNull { it.name.trim().removePrefix("/") == clean }
                entry != null && entry.method == ZipArchiveEntry.STORED && entry.size >= 0
            }
        } catch (_: Exception) {
            false
        }
    }

    fun modifyStoredEntry(device: BlockDevice, entryPath: String, newData: ByteArray) {
        if (device.isReadOnly) throw IOException("BlockDevice is read-only")

        val clean = entryPath.trim().removePrefix("/").removePrefix("./")
        var localHeaderOffset: Long = -1L
        var expectedSize: Long = -1L

        ZipFile.builder().setSeekableByteChannel(device.asSeekableByteChannel()).get().use { zf ->
            val entry = zf.getEntry(clean)
                ?: zf.getEntry("/$clean")
                ?: zf.entries.asSequence().firstOrNull { it.name.trim().removePrefix("/") == clean }
                ?: throw NoSuchElementException("Entry not found in ZIP: $entryPath")

            if (entry.method != ZipArchiveEntry.STORED) {
                throw IOException("In-place write only supported for uncompressed (STORED) ZIP entries")
            }
            localHeaderOffset = entry.localHeaderOffset
            expectedSize = entry.size
        }

        if (newData.size.toLong() != expectedSize) {
            throw IOException("In-place write requires exact size match (expected $expectedSize bytes, got ${newData.size})")
        }

        // Verify local file header (PK\x03\x04)
        val lfh = device.readBytes(localHeaderOffset, 30)
        if (lfh.size < 30 || lfh[0] != 0x50.toByte() || lfh[1] != 0x4B.toByte() || lfh[2] != 0x03.toByte() || lfh[3] != 0x04.toByte()) {
            throw IOException("Corrupt or invalid local file header at offset $localHeaderOffset")
        }

        val nameLen = (lfh[26].toInt() and 0xFF) or ((lfh[27].toInt() and 0xFF) shl 8)
        val extraLen = (lfh[28].toInt() and 0xFF) or ((lfh[29].toInt() and 0xFF) shl 8)
        val dataOffset = localHeaderOffset + 30L + nameLen + extraLen

        // 1. Calculate new CRC-32
        val crc = CRC32()
        crc.update(newData)
        val newCrcValue = crc.value

        val crcBytes = ByteArray(4)
        crcBytes[0] = (newCrcValue and 0xFF).toByte()
        crcBytes[1] = ((newCrcValue ushr 8) and 0xFF).toByte()
        crcBytes[2] = ((newCrcValue ushr 16) and 0xFF).toByte()
        crcBytes[3] = ((newCrcValue ushr 24) and 0xFF).toByte()

        // 2. Write payload
        device.writeAt(dataOffset, newData, 0, newData.size)

        // 3. Update CRC-32 in local header (offset 14)
        device.writeAt(localHeaderOffset + 14L, crcBytes, 0, 4)

        // 4. Update CRC-32 in Central Directory
        updateCentralDirectoryCrc(device, localHeaderOffset, clean, crcBytes)

        // 5. Flush
        device.flush()
    }

    private fun updateCentralDirectoryCrc(
        device: BlockDevice,
        targetLocalHeaderOffset: Long,
        targetName: String,
        crcBytes: ByteArray
    ) {
        val totalSize = device.size
        if (totalSize < 22) return

        // Search backward from end of file for EOCD (PK\x05\x06)
        val searchLen = 65536.coerceAtMost(totalSize.toInt())
        val searchStart = totalSize - searchLen
        val footerBytes = device.readBytes(searchStart, searchLen)

        var eocdOffset = -1L
        for (i in footerBytes.size - 22 downTo 0) {
            if (footerBytes[i] == 0x50.toByte() && footerBytes[i + 1] == 0x4B.toByte() &&
                footerBytes[i + 2] == 0x05.toByte() && footerBytes[i + 3] == 0x06.toByte()
            ) {
                eocdOffset = searchStart + i
                break
            }
        }
        if (eocdOffset < 0) return

        val eocd = device.readBytes(eocdOffset, 22)
        val cdSize = ((eocd[12].toLong() and 0xFF)) or
            ((eocd[13].toLong() and 0xFF) shl 8) or
            ((eocd[14].toLong() and 0xFF) shl 16) or
            ((eocd[15].toLong() and 0xFF) shl 24)
        val cdOffset = ((eocd[16].toLong() and 0xFF)) or
            ((eocd[17].toLong() and 0xFF) shl 8) or
            ((eocd[18].toLong() and 0xFF) shl 16) or
            ((eocd[19].toLong() and 0xFF) shl 24)

        // Scan Central Directory entries (PK\x01\x02)
        var pos = cdOffset
        val end = cdOffset + cdSize
        while (pos + 46 <= end && pos + 46 <= totalSize) {
            val cdh = device.readBytes(pos, 46)
            if (cdh.size < 46 || cdh[0] != 0x50.toByte() || cdh[1] != 0x4B.toByte() ||
                cdh[2] != 0x01.toByte() || cdh[3] != 0x02.toByte()
            ) {
                break
            }

            val nLen = (cdh[28].toInt() and 0xFF) or ((cdh[29].toInt() and 0xFF) shl 8)
            val eLen = (cdh[30].toInt() and 0xFF) or ((cdh[31].toInt() and 0xFF) shl 8)
            val cLen = (cdh[32].toInt() and 0xFF) or ((cdh[33].toInt() and 0xFF) shl 8)

            val localOffsetInCd = ((cdh[42].toLong() and 0xFF)) or
                ((cdh[43].toLong() and 0xFF) shl 8) or
                ((cdh[44].toLong() and 0xFF) shl 16) or
                ((cdh[45].toLong() and 0xFF) shl 24)

            val nameBytes = device.readBytes(pos + 46L, nLen)
            val nameStr = String(nameBytes, StandardCharsets.UTF_8).trim().removePrefix("/")

            if (localOffsetInCd == targetLocalHeaderOffset || nameStr == targetName) {
                // Update CRC at offset 16 in Central Directory header
                device.writeAt(pos + 16L, crcBytes, 0, 4)
                break
            }

            pos += 46L + nLen + eLen + cLen
        }
    }
}
