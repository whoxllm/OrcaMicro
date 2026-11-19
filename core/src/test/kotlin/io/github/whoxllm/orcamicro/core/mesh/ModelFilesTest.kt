package io.github.whoxllm.orcamicro.core.mesh

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ModelFilesTest {
    private val geometry="""<vertices><vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="1"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles>"""
    private fun archive(entries:Map<String,String>,test:(java.io.File)->Unit) {
        val file=Files.createTempFile("orca-geometry-", ".zip").toFile()
        try { ZipOutputStream(file.outputStream()).use { zip->entries.forEach { (name,text)->zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() } };test(file) }
        finally {file.delete()}
    }
    @Test fun `3MF root relationship supports other filenames and optional Orca settings`() {
        val xml="""<model><resources><object id="1"><mesh>$geometry</mesh></object></resources><build><item objectid="1"/></build></model>"""
        val rel="""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/custom.model" Id="r1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>"""
        archive(mapOf("_rels/.rels" to rel,"3D/custom.model" to xml,"Metadata/project_settings.config" to """{"layer_height":"0.16","filament_type":["PLA"]}""")){file->
            assertEquals(1,ModelFiles.entries(file).size);assertEquals(1,ModelFiles.read(file).triangleCount);assertNotNull(ModelFiles.projectSettings(file))
        }
    }
    @Test fun `3MF root relationship cannot load external or traversing paths`() {
        for(path in listOf("../../tmp/model","https://example.com/model")) {
            val rel="""<Relationships><Relationship Target="$path" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>"""
            archive(mapOf("_rels/.rels" to rel)){assertFails{ModelFiles.read(it)}}
        }
    }
    @Test fun `3MF components transforms and units produce actual mesh geometry`() {
        val xml="""<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" unit="inch"><resources><object id="1"><mesh>$geometry</mesh></object><object id="2"><components><component objectid="1" transform="1 0 0 0 1 0 0 0 1 2 0 0"/></components></object></resources><build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 0 3 0"/></build></model>"""
        archive(mapOf("3D/3dmodel.model" to xml)) { file->
            val mesh=ModelFiles.read(file);assertEquals(1,mesh.triangleCount)
            assertEquals(50.8f,mesh.bounds().min.x,.001f);assertEquals(76.2f,mesh.bounds().min.y,.001f);assertEquals(25.4f,mesh.bounds().max.z,.001f)
        }
    }
    @Test fun `production extension component paths load MakerWorld style submodels`() {
        val main="""<model xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06"><resources><object id="1"><components><component objectid="9" p:path="/3D/Objects/object_1.model"/></components></object></resources><build><item objectid="1"/></build></model>"""
        val child="""<model><resources><object id="9"><mesh>$geometry</mesh></object></resources></model>"""
        archive(mapOf("3D/3dmodel.model" to main,"3D/Objects/object_1.model" to child)) { assertEquals(1,ModelFiles.read(it).triangleCount) }
    }
    @Test fun `3MF rejects XML entities component cycles and invalid triangle indices`() {
        val cycle="""<model><resources><object id="1"><components><component objectid="1"/></components></object></resources><build><item objectid="1"/></build></model>"""
        val entity="""<!DOCTYPE model [<!ENTITY x SYSTEM "file:///etc/passwd">]><model><resources>&x;</resources></model>"""
        val bad="""<model><resources><object id="1"><mesh>${geometry.replace("v1=\"0\"","v1=\"999\"")}</mesh></object></resources><build><item objectid="1"/></build></model>"""
        for(xml in listOf(cycle,entity,bad))archive(mapOf("3D/3dmodel.model" to xml)) { assertFails { ModelFiles.read(it) } }
    }
    @Test fun `ZIP lists STL choices and imports only the selected detail without extracting paths`() {
        val stl="""solid triangle
facet normal 0 0 1
outer loop
vertex 0 0 0
vertex 1 0 0
vertex 0 1 0
endloop
endfacet
endsolid
"""
        archive(mapOf("a.stl" to stl,"parts/b.stl" to stl,"../../not-extracted.txt" to "ignored")) { file->
            assertEquals(listOf("a.stl","parts/b.stl"),ModelFiles.entries(file))
            assertFails { ModelFiles.read(file) };assertEquals(1,ModelFiles.read(file,"parts/b.stl").triangleCount)
        }
    }
}
