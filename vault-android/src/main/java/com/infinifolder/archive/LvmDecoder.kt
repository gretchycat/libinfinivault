package com.infinifolder.archive

import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Decodes LVM2 (Logical Volume Manager) Physical Volumes (PV), Volume Groups (VG),
 * and Logical Volumes (LV). Supports linear allocations on a single PV.
 * Other layouts are rejected rather than mapped to incorrect source bytes.
 */
object LvmDecoder {

    private fun trace(stage: String, detail: String) =
        com.infinifolder.storage.DiagnosticLog.record("disc.lvm.$stage", detail)

    private fun empty(reason: String): List<LogicalVolume> {
        trace("empty", "reason=$reason")
        return emptyList()
    }


    data class LogicalVolume(
        val name: String,
        val uuid: String,
        val sizeBytes: Long,
        val device: BlockDevice
    )

    fun isLvm(dev: BlockDevice, baseOffset: Long = 0L): Boolean {
        val fileLen = dev.size
        if (fileLen < baseOffset + 1024L) return false
        for (sec in 0..3) {
            val off = baseOffset + sec * 512L
            if (off + 32 > fileLen) break
            val b = dev.readBytes(off, 32)
            if (b.size >= 32 &&
                String(b, 0, 8, StandardCharsets.US_ASCII) == "LABELONE" &&
                String(b, 24, 8, StandardCharsets.US_ASCII) == "LVM2 001"
            ) {
                return true
            }
        }
        return false
    }

    fun decode(dev: BlockDevice, baseOffset: Long = 0L): List<LogicalVolume> {
        val fileLen = dev.size
        trace("begin", "deviceBytes=$fileLen baseOffset=$baseOffset")
        if (fileLen < baseOffset + 1024L) return empty("device-too-small")

        var labelSector = -1
        var labelBytes: ByteArray? = null
        for (sec in 0..3) {
            val off = baseOffset + sec * 512L
            if (off + 32 > fileLen) break
            val b = dev.readBytes(off, 32)
            if (b.size >= 32 &&
                String(b, 0, 8, StandardCharsets.US_ASCII) == "LABELONE" &&
                String(b, 24, 8, StandardCharsets.US_ASCII) == "LVM2 001"
            ) {
                labelSector = sec
                labelBytes = b
                break
            }
        }
        if (labelSector < 0 || labelBytes == null) return empty("label-not-found")
        trace("label", "sector=$labelSector pvHeaderRelativeOffset=${u32le(labelBytes, 20)}")

        val offsetToPvHeader = u32le(labelBytes, 20)
        val pvHeaderOffset = baseOffset + labelSector * 512L + offsetToPvHeader
        if (pvHeaderOffset + 56L > fileLen) return empty("pv-header-outside-device offset=$pvHeaderOffset")
        val pvHeader = dev.readBytes(pvHeaderOffset, 64)
        if (pvHeader.size < 56) return empty("short-pv-header bytes=${pvHeader.size}")
        // A bounded extra metadata read is diagnostic only. Do not dump UUIDs,
        // configuration text or file contents, and do not change decode behavior.
        runCatching {
            val sample = dev.readBytes(pvHeaderOffset, minOf(256L, fileLen - pvHeaderOffset).toInt())
            val fields = (40 until (sample.size - 15) step 16).take(12).joinToString(";") { pos ->
                "$pos:${i64le(sample, pos)},${i64le(sample, pos + 8)}"
            }
            trace("pv-header", "offset=$pvHeaderOffset declaredDeviceBytes=${i64le(pvHeader, 32)} locationPairs=$fields")
        }.onFailure { trace("diagnostic-read-failed", "stage=pv-header error=${it.javaClass.simpleName}") }

        // The PV header has two offset-terminated lists: data areas followed
        // by metadata areas. Offset 40 is the first DATA area, not metadata.
        val labelEnd = baseOffset + (labelSector + 1) * 512L
        if (pvHeaderOffset < baseOffset || pvHeaderOffset > labelEnd - 40L) {
            throw IOException("LVM PV header lies outside its label sector")
        }
        val areaHeader = dev.readBytes(pvHeaderOffset, (labelEnd - pvHeaderOffset).toInt())
        var areaPos = 40
        fun readAreas(): List<Pair<Long, Long>> {
            val result = mutableListOf<Pair<Long, Long>>()
            while (areaPos + 16 <= areaHeader.size) {
                val offset = i64le(areaHeader, areaPos)
                val size = i64le(areaHeader, areaPos + 8)
                areaPos += 16
                if (offset == 0L) return result
                if (offset < 0L || size < 0L || offset > fileLen - baseOffset || size > fileLen - baseOffset - offset) {
                    throw IOException("LVM area lies outside the physical volume")
                }
                result += offset to size
            }
            throw IOException("Unterminated LVM physical-volume area list")
        }
        val dataAreas = readAreas()
        val metadataAreas = readAreas()
        trace("areas", "data=${dataAreas.joinToString(";")} metadata=${metadataAreas.joinToString(";")}")
        val metadata = metadataAreas.firstOrNull() ?: return empty("no-metadata-area-on-this-pv")
        val actualMdaOffset = baseOffset + metadata.first
        trace("metadata-location", "selectedOffset=$actualMdaOffset areaBytes=${metadata.second} source=metadata-area-list")
        if (actualMdaOffset + 512L > fileLen) return empty("metadata-header-outside-device offset=$actualMdaOffset")

        val mdaHeader = dev.readBytes(actualMdaOffset, 512)
        if (mdaHeader.size < 512) return empty("short-metadata-header bytes=${mdaHeader.size}")
        val magic = " LVM2 x[5A%r0N*>".toByteArray(StandardCharsets.US_ASCII)
        if (!(4 until 20).all { mdaHeader[it] == magic[it - 4] } || u32le(mdaHeader, 20) != 1L) {
            throw IOException("Invalid LVM metadata header signature or version")
        }
        val areaSize = i64le(mdaHeader, 32)
        if (i64le(mdaHeader, 24) != metadata.first || areaSize < 512L || areaSize > metadata.second) {
            throw IOException("LVM metadata header bounds disagree with its PV area")
        }
        trace("metadata-header", "offset=$actualMdaOffset version=${u32le(mdaHeader, 20)} start=${i64le(mdaHeader, 24)} bytes=${i64le(mdaHeader, 32)}")

        var bestLocOffset = 0L
        var bestLocSize = 0L
        for (i in 0 until 19) {
            val rPos = 40 + i * 24
            if (rPos + 24 > mdaHeader.size) break
            val locOffset = i64le(mdaHeader, rPos)
            val locSize = i64le(mdaHeader, rPos + 8)
            val flags = u32le(mdaHeader, rPos + 20)
            trace("metadata-record", "index=$i offset=$locOffset bytes=$locSize flags=$flags")
            if (locOffset == 0L) break
            if (flags and 1L != 0L) continue
            if (locOffset < 512L || locOffset >= areaSize || locSize <= 0L || locSize > areaSize - 512L || locSize > 4L * 1024 * 1024) {
                throw IOException("Invalid or oversized LVM metadata text location")
            }
            if (bestLocSize == 0L) {
                bestLocOffset = locOffset
                bestLocSize = locSize
                break // First committed record; larger records may be uncommitted.
            }
        }

        val text = if (bestLocOffset > 0L && bestLocSize > 0L) {
            val textOffset = actualMdaOffset + bestLocOffset
            val firstSize = minOf(bestLocSize, areaSize - bestLocOffset).toInt()
            val first = dev.readBytes(textOffset, firstSize)
            if (first.size != firstSize) throw IOException("Short LVM metadata text")
            val secondSize = (bestLocSize - firstSize).toInt()
            val second = if (secondSize > 0) dev.readBytes(actualMdaOffset + 512L, secondSize) else ByteArray(0)
            if (second.size != secondSize) throw IOException("Short wrapped LVM metadata text")
            trace("metadata-text", "mode=record offset=$textOffset requestedBytes=$bestLocSize readBytes=${first.size + second.size} wrapped=${secondSize > 0}")
            String(first + second, StandardCharsets.UTF_8).trimEnd('\u0000')
        } else {
            return empty("no-committed-metadata-text")
        }

        return parseLvmMetadataText(text, dev, baseOffset, String(pvHeader, 0, 32, StandardCharsets.US_ASCII)).also { trace("result", "volumes=${it.size} volumeSizes=${it.take(16).joinToString(",") { lv -> lv.sizeBytes.toString() }}") }
    }

    private fun parseLvmMetadataText(text: String, dev: BlockDevice, baseOffset: Long, pvId: String): List<LogicalVolume> {
        val pvMatch = Regex("""physical_volumes\s*\{""").find(text)
            ?: throw IOException("LVM physical volume metadata is missing")
        val pvText = extractBraceBlock(text, pvMatch.range.last + 1)
        val pvIds = Regex("""\bid\s*=\s*"([^"]+)"""").findAll(pvText).toList()
        if (pvIds.size != 1 || pvIds.single().groupValues[1].replace("-", "") != pvId) {
            throw IOException("LVM multi-PV or mismatched physical-volume metadata is unsupported")
        }
        val pvName = Regex("""([a-zA-Z0-9_\-]+)\s*\{""").find(pvText)?.groupValues?.get(1)
            ?: throw IOException("Missing LVM physical volume name")
        val extentSizeSectors = Regex("""extent_size\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 8192L
        val extentSizeBytes = Math.multiplyExact(extentSizeSectors, 512L)

        val peStartSectors = Regex("""pe_start\s*=\s*(\d+)""").find(pvText)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw IOException("Missing LVM physical extent origin")
        val peStartBytes = Math.multiplyExact(peStartSectors, 512L)
        trace("allocation", "extentBytes=$extentSizeBytes peStartBytes=$peStartBytes")

        val lvBlockMatch = Regex("""logical_volumes\s*\{""").find(text) ?: return empty("logical-volume-block-missing")
        val lvBlockStart = lvBlockMatch.range.last + 1
        val lvBlockText = extractBraceBlock(text, lvBlockStart)

        val lvRegex = Regex("""([a-zA-Z0-9_\-\.]+)\s*\{""")
        var match = lvRegex.find(lvBlockText)
        val logicalVolumes = mutableListOf<LogicalVolume>()

        while (match != null) {
            val lvName = match.groupValues[1]
            val bodyStart = match.range.last + 1
            val lvBody = extractBraceBlock(lvBlockText, bodyStart)

            val segments = parseLvSegments(lvBody, baseOffset, peStartBytes, extentSizeBytes, dev, pvName)
            if (segments.isNotEmpty()) {
                val totalSize = segments.sumOf { it.length }
                val lvDev: BlockDevice = if (segments.size == 1) {
                    val seg = segments.first()
                    PartitionSliceBlockDevice(seg.device, seg.physicalOffset, seg.length, seg.device.isReadOnly)
                } else {
                    CompositeBlockDevice(segments, dev.isReadOnly)
                }
                val lvUuid = Regex("""id\s*=\s*"([^"]+)"""").find(lvBody)?.groupValues?.get(1) ?: ""
                logicalVolumes += LogicalVolume(lvName, lvUuid, totalSize, lvDev)
            }

            val nextSearchStart = bodyStart + lvBody.length + 1
            match = if (nextSearchStart < lvBlockText.length) lvRegex.find(lvBlockText, nextSearchStart) else null
        }

        return logicalVolumes
    }

    private fun parseLvSegments(
        lvBody: String,
        baseOffset: Long,
        peStartBytes: Long,
        extentSizeBytes: Long,
        dev: BlockDevice,
        pvName: String
    ): List<CompositeBlockDevice.SegmentMapping> {
        val segRegex = Regex("""segment\d+\s*\{""")
        var match = segRegex.find(lvBody)
        val segments = mutableListOf<CompositeBlockDevice.SegmentMapping>()
        var curLogicalOffset = 0L

        while (match != null) {
            val segBody = extractBraceBlock(lvBody, match.range.last + 1)
            val extentCount = Regex("""extent_count\s*=\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val type = Regex("""type\s*=\s*"([^"]+)"""").find(segBody)?.groupValues?.get(1)
            val stripeCount = Regex("""stripe_count\s*=\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toIntOrNull()
            val stripe = Regex("""stripes\s*=\s*\[\s*"([^"]+)"\s*,\s*(\d+)\s*]""").find(segBody)
            if (type != "striped" || stripeCount != 1 || stripe == null || stripe.groupValues[1] != pvName) {
                throw IOException("Unsupported LVM segment: only linear allocation on this PV is supported")
            }
            val startPe = Regex("""stripes\s*=\s*\[\s*"[^"]+"\s*,\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("""stripes\s*=\s*\[[^,]+,\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L

            if (segments.size < 16) trace("segment", "index=${segments.size} extentCount=$extentCount startPe=$startPe logicalOffset=$curLogicalOffset")
            val startExtent = Regex("""start_extent\s*=\s*(\d+)""").find(segBody)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw IOException("Missing LVM logical extent offset")
            if (extentSizeBytes <= 0L || extentCount <= 0L || Math.multiplyExact(startExtent, extentSizeBytes) != curLogicalOffset) {
                throw IOException("Invalid or noncontiguous LVM logical segment layout")
            }
            if (extentCount > 0L) {
                val segLength = Math.multiplyExact(extentCount, extentSizeBytes)
                val segPhysOffset = Math.addExact(Math.addExact(baseOffset, peStartBytes), Math.multiplyExact(startPe, extentSizeBytes))
                if (segPhysOffset < 0L || segPhysOffset > dev.size || segLength > dev.size - segPhysOffset) {
                    throw IOException("LVM logical segment lies outside physical volume")
                }
                segments += CompositeBlockDevice.SegmentMapping(
                    logicalOffset = curLogicalOffset,
                    physicalOffset = segPhysOffset,
                    length = segLength,
                    device = dev
                )
                curLogicalOffset = Math.addExact(curLogicalOffset, segLength)
            }

            val nextStart = match.range.last + 1 + segBody.length + 1
            match = if (nextStart < lvBody.length) segRegex.find(lvBody, nextStart) else null
        }
        return segments
    }

    private fun extractBraceBlock(text: String, startIdx: Int): String {
        var depth = 1
        var pos = startIdx
        var inQuote = false
        while (pos < text.length && depth > 0) {
            val c = text[pos]
            if (c == '"' && (pos == 0 || text[pos - 1] != '\\')) {
                inQuote = !inQuote
            } else if (!inQuote) {
                if (c == '{') depth++
                else if (c == '}') depth--
            }
            pos++
        }
        return if (pos > startIdx) text.substring(startIdx, (pos - 1).coerceAtMost(text.length)) else ""
    }

    private fun u8(buf: ByteArray, offset: Int): Int =
        buf[offset].toInt() and 0xFF

    private fun u32le(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFFL) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 24)

    private fun i64le(buf: ByteArray, offset: Int): Long =
        u32le(buf, offset) or (u32le(buf, offset + 4) shl 32)
}
