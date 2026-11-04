package com.infinifolder.archive

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeContainerCapabilitiesTest {
    @Test fun discoveredReaderListsAndReadsRealNestedZipWithoutAnApplication() {
        val directory=java.nio.file.Files.createTempDirectory("capability-vault").toFile()
        try {
            val inner=java.io.ByteArrayOutputStream().also { out ->ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("value.txt"));zip.write("nested payload".toByteArray());zip.closeEntry()
            } }.toByteArray()
            val outer=File(directory,"outer.zip")
            ZipOutputStream(outer.outputStream()).use { zip ->zip.putNextEntry(ZipEntry("inside.zip"));zip.write(inner);zip.closeEntry() }
            val reader=ArchiveReader(directory)
            val nested=VaultStreams.entry(reader,FileSourceResource(outer),"inside.zip")
            val found=VaultCapabilities.registry.probe(CapabilityResource(nested.metadata,source=nested,containerDepth=1),Operations.LIST,
                ProbeBudget(maxNanos=2_000_000_000))
            val handler=found.single {it.descriptor.id=="vault.builtin"}.handler.service(ContainerCapabilityHandler::class.java)!!
            val request=ContainerReadRequest(reader,"inside.zip",{nested.open().asInputStream()})
            assertEquals("value.txt",handler.list(request).list("").single().name)
            handler.openEntry(request,"value.txt").use {assertEquals("nested payload",it.readBytes().decodeToString())}
            assertFalse(found.any {Operations.WRITE in it.operations})
        }finally{directory.deleteRecursively()}
    }
    @Test fun newRegisteredContainerUsesRealListingAndEntryDispatch() {
        val directory=java.nio.file.Files.createTempDirectory("capability-extension").toFile()
        try {
            // A simple actual length-prefixed single-entry container, not a scripted reader result.
            val bytes=java.io.ByteArrayOutputStream().also { out ->java.io.DataOutputStream(out).use {
                it.writeBytes("ONE0");it.writeUTF("message.txt");it.writeInt(7);it.write("content".toByteArray())
            } }.toByteArray()
            val handler=object:ContainerCapabilityHandler {
                override val descriptor=HandlerDescriptor("vault.test.one","vault","Single entry",
                    listOf(FormatSupport(setOf("application/x-test-one"),setOf("one"),magic=listOf(MagicSignature("4f4e4530")))),
                    setOf(Operations.LIST,Operations.EXTRACT),attributes=mapOf("container" to "true","identityMagic" to "true"))
                private fun parse(request:ContainerReadRequest):Pair<String,ByteArray> = request.openStream().use { raw ->
                    val input=java.io.DataInputStream(raw)
                    require(input.readInt()==0x4f4e4530)
                    val name=input.readUTF();require(!name.contains('/')&&!name.contains('\\'))
                    val length=input.readInt();require(length in 0..1024)
                    name to ByteArray(length).also {input.readFully(it)}
                }
                override fun list(request:ContainerReadRequest):ArchiveTree {
                    val (name,data)=parse(request)
                    val entry=ArchiveEntry(name,name,false,data.size.toLong(),null,"text/plain")
                    return ArchiveTree(null,mapOf("" to listOf(entry)),mapOf(name to entry))
                }
                override fun openEntry(request:ContainerReadRequest,path:String):java.io.InputStream {
                    val (name,data)=parse(request);require(name==path);return data.inputStream()
                }
            }
            VaultCapabilities.registry.register(handler).use {
                val reader=ArchiveReader(directory)
                assertEquals("application/x-test-one",com.infinifolder.mime.MimeRegistry.guess("new.one"))
                val detected=com.infinifolder.storage.FileMagicDetector.detectFromBytes(bytes)!!
                assertEquals("application/x-test-one",detected.mimeType);assertTrue(detected.isArchiveOrDiscImage)
                assertTrue(ArchiveFormats.isArchive("new.one"))
                assertEquals("message.txt",reader.buildTree(null,"new.one",{bytes.inputStream()}).list("").single().name)
                reader.openEntryStream("new.one","message.txt",{bytes.inputStream()}).use {assertEquals("content",it.readBytes().decodeToString())}
                assertThrows(Exception::class.java){reader.buildTree(null,"broken.one",{byteArrayOf(0,1).inputStream()})}
            }
            assertFalse(ArchiveFormats.isArchive("new.one"))
        }finally{directory.deleteRecursively()}
    }
}
