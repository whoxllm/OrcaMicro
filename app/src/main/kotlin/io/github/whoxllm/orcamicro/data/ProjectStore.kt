package io.github.whoxllm.orcamicro.data

import android.content.Context
import android.graphics.Bitmap
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.mesh.StlReader
import io.github.whoxllm.orcamicro.core.mesh.StlWriter
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.render.ModelThumbnail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ProjectDocument(
    val id:String,
    val name:String,
    val selection:PresetSelection,
    val edits:PresetEdits,
    val placement:Placement,
    val color:Int,
    val printerName:String,
    val filamentName:String,
    val triangleCount:Int,
    val activeModelId:String?=null,
    val modifiedAt:Long=0,
)

data class RecentProject(val document:ProjectDocument, val thumbnail:File)
data class OpenProject(val document:ProjectDocument, val mesh:Mesh)

/** Durable snapshots, separate from expiring model downloads. All methods run on the save IO worker. */
class ProjectStore(context:Context) {
    private val root=File(context.filesDir,"projects")
    private val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    private fun dir(id:String):File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid project ID" }
        return File(root,id)
    }
    @Synchronized fun list():List<RecentProject> = root.listFiles().orEmpty().mapNotNull { directory ->
        runCatching {
            val metadata=File(dir(directory.name),"project.json")
            require(metadata.length() in 1..4*1024*1024)
            val doc=json.decodeFromString(ProjectDocument.serializer(),metadata.readText())
            require(doc.id==directory.name && File(directory,"model.stl").isFile)
            RecentProject(doc,File(directory,"preview.png"))
        }.getOrNull()
    }.sortedByDescending { it.document.modifiedAt }

    @Synchronized fun save(document:ProjectDocument, mesh:Mesh):List<RecentProject> {
        val directory=dir(document.id).apply { check(mkdirs() || isDirectory) }
        val previous=list().firstOrNull { it.document.id==document.id }?.document
        val changed=previous==null || previous.copy(modifiedAt=0)!=document.copy(modifiedAt=0)
        if(!changed)return list()
        val model=File(directory,"model.stl")
        if(!model.isFile) {
            val tmp=File(directory,"model.stl.tmp")
            try {
                tmp.outputStream().buffered().use { StlWriter.write(mesh,it) }
                check(tmp.renameTo(model))
            } finally { tmp.delete() }
        }
        // Position on the plate does not change the framed image; rotation and filament colour do.
        if(previous==null || previous.color!=document.color || previous.placement.copy(centerX=0f,centerY=0f)!=document.placement.copy(centerX=0f,centerY=0f) || !File(directory,"preview.png").isFile) {
            val image=ModelThumbnail.render(mesh,document.placement,document.color)
            val tmp=File(directory,"preview.png.tmp")
            try {
                tmp.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
                check(tmp.renameTo(File(directory,"preview.png")))
            } finally { image.recycle();tmp.delete() }
        }
        val metadata=File(directory,"project.json")
        val tmp=File(directory,"project.json.tmp")
        try {
            tmp.writeText(json.encodeToString(ProjectDocument.serializer(),document.copy(modifiedAt=System.currentTimeMillis())))
            check(tmp.renameTo(metadata))
        } finally { tmp.delete() }
        return list()
    }

    @Synchronized fun open(id:String):OpenProject {
        val doc=list().firstOrNull { it.document.id==id }?.document ?: error("Project not found")
        val mesh=File(dir(id),"model.stl").inputStream().use { StlReader.read(it.readBytes()) }
        return OpenProject(doc,mesh)
    }
    @Synchronized fun delete(id:String):List<RecentProject> {
        check(!dir(id).exists() || dir(id).deleteRecursively()) { "Could not remove project" }
        return list()
    }
}
