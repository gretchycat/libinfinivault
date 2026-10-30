package com.infinifolder.archive

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MountCheckResult(
    val isMounted: Boolean,
    val reason: String = "",
    val mountPoint: String = ""
)

class MountedPartitionException(
    val targetName: String,
    val reason: String
) : java.io.IOException("Cannot write to '$targetName': partition or disk image is currently mounted or in use ($reason)")

object MountedPartitionDetector {

    // Testing / Mock hooks
    var mockMountEntries: List<MountEntry>? = null
    var mockIsLocked: ((File) -> Boolean)? = null
    var mockLoopDevice: ((String) -> String?)? = null

    data class MountEntry(
        val spec: String,
        val mountPoint: String,
        val fstype: String,
        val options: String
    )

    fun checkMounted(file: File?): MountCheckResult {
        if (file == null) return MountCheckResult(false)

        val canonicalFile = try { file.canonicalFile } catch (_: Exception) { file.absoluteFile }
        val canonicalPath = canonicalFile.canonicalPath
        val absPath = file.absolutePath

        // 1. Check proc mount tables (/proc/mounts, /proc/self/mounts, /proc/self/mountinfo)
        val mountEntries = mockMountEntries ?: readProcMounts()
        val mountHit = findMountMatch(canonicalPath, absPath, mountEntries)
        if (mountHit != null) {
            return MountCheckResult(
                isMounted = true,
                reason = mountHit.second,
                mountPoint = mountHit.first.mountPoint
            )
        }

        // 2. Check if file is associated with any active loop device
        val loopDev = mockLoopDevice?.invoke(canonicalPath) ?: findLoopDevice(canonicalPath)
        if (loopDev != null) {
            val loopMount = mountEntries.firstOrNull {
                it.spec == loopDev || it.spec.startsWith("$loopDev" + "p")
            }
            if (loopMount != null) {
                return MountCheckResult(
                    isMounted = true,
                    reason = "Attached to loop device $loopDev and mounted at ${loopMount.mountPoint} (${loopMount.fstype})",
                    mountPoint = loopMount.mountPoint
                )
            } else {
                return MountCheckResult(
                    isMounted = true,
                    reason = "Attached to active kernel loop device $loopDev",
                    mountPoint = ""
                )
            }
        }

        // 3. Check VM / Hypervisor / Process file locks (tryLock)
        val isLocked = mockIsLocked?.invoke(canonicalFile) ?: isFileLocked(canonicalFile)
        if (isLocked) {
            return MountCheckResult(
                isMounted = true,
                reason = "File is locked exclusively by an active virtual machine or process"
            )
        }

        return MountCheckResult(false)
    }

    private fun findMountMatch(
        canonicalPath: String,
        absPath: String,
        mountEntries: List<MountEntry>
    ): Pair<MountEntry, String>? {
        for (m in mountEntries) {
            // Direct device match
            if (m.spec == canonicalPath || m.spec == absPath) {
                return m to "Mounted at ${m.mountPoint} (${m.fstype})"
            }

            // Direct mount point match
            if (m.mountPoint == canonicalPath || m.mountPoint == absPath) {
                return m to "Directory is an active mount point for ${m.spec} (${m.fstype})"
            }

            // Block device partition match (e.g. file is /dev/block/sda and m.spec is /dev/block/sda1)
            if (canonicalPath.startsWith("/dev/") || absPath.startsWith("/dev/")) {
                val devPath = if (canonicalPath.startsWith("/dev/")) canonicalPath else absPath
                if (m.spec.startsWith(devPath)) {
                    val suffix = m.spec.removePrefix(devPath)
                    if (suffix.isNotEmpty() && (suffix.startsWith("p") || suffix[0].isDigit())) {
                        return m to "Partition ${m.spec} is mounted at ${m.mountPoint} (${m.fstype})"
                    }
                }
            }
        }
        return null
    }

    private fun readProcMounts(): List<MountEntry> {
        val mountsFile = File("/proc/mounts").takeIf { it.canRead() }
            ?: File("/proc/self/mounts").takeIf { it.canRead() }
            ?: File("/proc/self/mountinfo").takeIf { it.canRead() }
            ?: return emptyList()

        return try {
            mountsFile.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        MountEntry(
                            spec = parts[0],
                            mountPoint = parts[1],
                            fstype = parts[2],
                            options = parts[3]
                        )
                    } else null
                }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findLoopDevice(canonicalPath: String): String? {
        return try {
            val process = ProcessBuilder("losetup", "-j", canonicalPath)
                .redirectErrorStream(true)
                .start()
            val finished = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                process.waitFor(1, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                true
            }
            if (finished && process.exitValue() == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.isNotEmpty()) {
                    output.substringBefore(':').trim().takeIf { it.isNotEmpty() }
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun isFileLocked(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                val lock = channel.tryLock()
                if (lock == null) {
                    true
                } else {
                    lock.release()
                    false
                }
            }
        } catch (e: Exception) {
            if (e is OverlappingFileLockException) return true
            val msg = e.message?.lowercase(Locale.ROOT) ?: ""
            msg.contains("busy") || msg.contains("resource temporarily unavailable") || msg.contains("lock")
        }
    }
}
