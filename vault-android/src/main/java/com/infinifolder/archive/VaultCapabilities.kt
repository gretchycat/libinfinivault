package com.infinifolder.archive

import android.net.Uri
import com.infinifolder.storage.FileMagicDetector
import io.github.gretchycat.infinistream.*
import java.io.File
import java.io.InputStream

data class ContainerReadRequest(val reader:ArchiveReader,val name:String,val openStream:()->InputStream,
    val localFile:File?=null,val device:BlockDevice?=null,val volumes:ArchiveVolumeSet?=null,
    val sourceUri:Uri?=null,val directoryPath:String?=null)
interface ContainerCapabilityHandler : CapabilityHandler {
    override fun describe()=descriptor.copy(attributes=descriptor.attributes+("container" to "true"))
    fun list(request:ContainerReadRequest):ArchiveTree
    fun openEntry(request:ContainerReadRequest,path:String):InputStream
}
object VaultCapabilities {
    val registry get()=RuntimeCapabilities.library("vault")
    internal fun extension(request:ContainerReadRequest):ContainerCapabilityHandler? {
        val handlers=registry.query(Operations.LIST).filter { it.descriptor.id!="vault.builtin" }
        if(handlers.isEmpty())return null
        val metadata=StreamMetadata(name=request.name,capabilities=setOf(Capability.READ),length=request.localFile?.length())
        val source=object:SourceResource {
            override val metadata=metadata
            override fun open():ByteSource=InputStreamSource(request.openStream(),metadata)
        }
        val context=CapabilityProbeContext(CapabilityResource(metadata,source=source),ProbeBudget(maxNanos=2_000_000_000))
        return handlers.firstNotNullOfOrNull { entry ->
            val handler=entry.handler.service(ContainerCapabilityHandler::class.java)
            try {handler?.takeIf { it.probe(context).confidence!=MatchConfidence.NONE }}
            catch(cancel:java.io.InterruptedIOException){throw cancel}catch(_:Exception){null}catch(_:LinkageError){null}
        }
    }
}
class VaultCapabilityProvider : CapabilityProvider {
    override val library="vault"
    override fun handlers():List<CapabilityHandler> = listOf(BuiltinContainerHandler())
}
private class BuiltinContainerHandler : ContainerCapabilityHandler {
    override val descriptor=HandlerDescriptor("vault.builtin","vault","Vault containers",
        listOf(FormatSupport(extensions=ArchiveFormats.knownExtensions,
            magic=listOf(MagicSignature("504b0304",description="ZIP local header"),MagicSignature("504b0506",description="Empty ZIP"),
                MagicSignature("377abcaf271c",description="7z"),MagicSignature("526172211a07",description="RAR")))),
        setOf(Operations.LIST,Operations.EXTRACT,Operations.READ),metadata=setOf("entries","size","modified","warnings","readError"),
        limitations=listOf("Format recognition is provisional until the parser opens", "Native dependencies and filesystem/codec subsets apply", "Mutation support must be queried on the mounted provider/resource; no universal archive write claim"),
        attributes=mapOf("container" to "true"))
    override fun availability()=HandlerAvailability(AvailabilityState.CONDITIONAL,"Individual native/filesystem features and input are validated by the actual reader")
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        val candidate=super.probe(context)
        if(context.resource.source==null)return candidate
        val magic=FileMagicDetector.detectFromBytes(context.prefix())
        return if(magic?.isArchiveOrDiscImage==true)candidate.copy(confidence=MatchConfidence.SIGNATURE,
            metadata=mapOf("mimeType" to magic.mimeType))else candidate
    }
    override fun list(request:ContainerReadRequest)=request.reader.buildBuiltinTree(request.sourceUri,request.name,request.openStream,
        request.localFile,request.device,request.volumes,request.directoryPath)
    override fun openEntry(request:ContainerReadRequest,path:String)=request.reader.openBuiltinEntryStream(request.name,path,
        request.openStream,request.localFile,request.device,request.volumes)
}
