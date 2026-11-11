package com.infinifolder.archive

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class MountedPartitionDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        MountedPartitionDetector.mockMountEntries = null
        MountedPartitionDetector.mockIsLocked = null
        MountedPartitionDetector.mockLoopDevice = null
    }

    @After
    fun tearDown() {
        MountedPartitionDetector.mockMountEntries = null
        MountedPartitionDetector.mockIsLocked = null
        MountedPartitionDetector.mockLoopDevice = null
    }

    @Test
    fun testUnmountedFile() {
        val testFile = tempFolder.newFile("unmounted.img")
        testFile.writeBytes(ByteArray(1024))

        val result = MountedPartitionDetector.checkMounted(testFile)
        assertFalse(result.isMounted)
        assertEquals("", result.reason)
    }

    @Test
    fun testDirectMountDetection() {
        val testFile = tempFolder.newFile("disk.img")
        testFile.writeBytes(ByteArray(1024))

        MountedPartitionDetector.mockMountEntries = listOf(
            MountedPartitionDetector.MountEntry(
                spec = testFile.canonicalPath,
                mountPoint = "/mnt/virtual_disk",
                fstype = "vfat",
                options = "rw,relatime"
            )
        )

        val result = MountedPartitionDetector.checkMounted(testFile)
        assertTrue(result.isMounted)
        assertTrue(result.reason.contains("/mnt/virtual_disk"))
        assertTrue(result.reason.contains("vfat"))
        assertEquals("/mnt/virtual_disk", result.mountPoint)
    }

    @Test
    fun testBlockDevicePartitionMountDetection() {
        // Suppose the user opens /dev/block/sdb, and /dev/block/sdb1 is mounted in /proc/mounts
        val fakeDisk = File("/dev/block/sdb")

        MountedPartitionDetector.mockMountEntries = listOf(
            MountedPartitionDetector.MountEntry(
                spec = "/dev/block/sdb1",
                mountPoint = "/media/usb_drive",
                fstype = "ext4",
                options = "rw,relatime"
            )
        )

        val result = MountedPartitionDetector.checkMounted(fakeDisk)
        assertTrue(result.isMounted)
        assertTrue(result.reason.contains("/dev/block/sdb1"))
        assertTrue(result.reason.contains("/media/usb_drive"))
    }

    @Test
    fun testLoopDeviceMountedDetection() {
        val testFile = tempFolder.newFile("image.iso")
        testFile.writeBytes(ByteArray(1024))

        MountedPartitionDetector.mockLoopDevice = { path ->
            if (path == testFile.canonicalPath) "/dev/block/loop7" else null
        }
        MountedPartitionDetector.mockMountEntries = listOf(
            MountedPartitionDetector.MountEntry(
                spec = "/dev/block/loop7",
                mountPoint = "/mnt/cdrom",
                fstype = "iso9660",
                options = "ro,relatime"
            )
        )

        val result = MountedPartitionDetector.checkMounted(testFile)
        assertTrue(result.isMounted)
        assertTrue(result.reason.contains("loop7"))
        assertTrue(result.reason.contains("/mnt/cdrom"))
        assertEquals("/mnt/cdrom", result.mountPoint)
    }

    @Test
    fun testHypervisorVmExclusiveLockDetection() {
        val testFile = tempFolder.newFile("vm_disk.vdi")
        testFile.writeBytes(ByteArray(1024))

        // Mock hypervisor holding lock
        MountedPartitionDetector.mockIsLocked = { f ->
            f.canonicalPath == testFile.canonicalPath
        }

        val result = MountedPartitionDetector.checkMounted(testFile)
        assertTrue(result.isMounted)
        assertTrue(result.reason.contains("locked", ignoreCase = true))
    }

    @Test
    fun testMountedPartitionDisablesDiscImageWriter() {
        val testFile = tempFolder.newFile("mounted_fat.img")
        testFile.writeBytes(ByteArray(1024 * 1024))

        val reader = DiscImageReader(tempFolder.newFolder("disc_temp"))

        // Mock mount entry
        MountedPartitionDetector.mockMountEntries = listOf(
            MountedPartitionDetector.MountEntry(
                spec = testFile.canonicalPath,
                mountPoint = "/mnt/live_fat",
                fstype = "vfat",
                options = "rw"
            )
        )

        // isWritable must return false when mounted
        assertFalse(reader.isWritable("mounted_fat.img", testFile))

        // Attempting to write must throw MountedPartitionException
        try {
            reader.createFile("mounted_fat.img", testFile, "test.txt", "Hello".toByteArray(StandardCharsets.UTF_8))
            fail("Expected MountedPartitionException when writing to mounted disc")
        } catch (e: MountedPartitionException) {
            assertTrue(e.reason.contains("/mnt/live_fat"))
        }
    }
}
