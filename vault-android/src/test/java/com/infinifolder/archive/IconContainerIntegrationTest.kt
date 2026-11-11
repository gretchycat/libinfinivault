package com.infinifolder.archive

import com.infinifolder.storage.FileMagicDetector
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises actual bytes and the production container reader, not a scripted provider. */
class IconContainerIntegrationTest {
    @get:Rule val temp=TemporaryFolder()
    private fun zip(name:String):File=temp.newFile(name).also {file ->
        ZipOutputStream(file.outputStream()).use {out ->
            out.putNextEntry(ZipEntry("inside.txt"));out.write("archive payload".toByteArray());out.closeEntry()
        }
    }
    @Test fun realZipAndZipDerivedFilesActuallyListAndOpenTheirPayload() {
        for(name in listOf("normal.zip","extensionless","known.txt","book.docx","book.epub")) {
            val file=zip(name)
            assertEquals("application/zip",FileMagicDetector.detectFromStream {file.inputStream()}?.mimeType)
            val reader=ArchiveReader(temp.newFolder())
            val tree=reader.buildTree(null,file.name,{file.inputStream()},file)
            assertEquals(name,listOf("inside.txt"),tree.list("").map {it.name})
            assertEquals("archive payload",reader.openEntryStream(file.name,"inside.txt",{file.inputStream()},file).use {it.reader().readText()})
        }
    }
    @Test fun malformedZipDoesNotProduceBrowsableSuccess() {
        val file=temp.newFile("faux.zip").apply {writeText("PKxx not a ZIP central directory")}
        val reader=ArchiveReader(temp.newFolder())
        try {reader.buildTree(null,file.name,{file.inputStream()},file);fail("Faux archive must fail actual listing")}
        catch(_:Exception) {}
    }
    @Test fun extensionlessPngIsRecognizedFromActualImageBytes() {
        val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH2kAAAAASUVORK5CYII=")
        val file=temp.newFile("image-without-extension").apply {writeBytes(png)}
        assertEquals("image/png",FileMagicDetector.detectFromStream {file.inputStream()}?.mimeType)
    }
}
