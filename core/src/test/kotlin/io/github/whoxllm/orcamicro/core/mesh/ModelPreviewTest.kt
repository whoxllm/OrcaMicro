package io.github.whoxllm.orcamicro.core.mesh

import kotlin.test.*

class ModelPreviewTest {
    @Test fun `preview is framed with transparent background and shaded model colour`() {
        val image=ModelPreview.render(TestMeshes.box(10f,20f,30f),Placement(),0x30C060,160)
        assertEquals(0,image.first());assertEquals(0,image.last())
        val model=image.filter { it!=0 }
        assertTrue(model.size in 3000..20000)
        assertTrue(model.distinct().size>=3,"each visible face is shaded")
        assertTrue(model.all { (it ushr 24)==255 && ((it shr 8) and 255)>((it shr 16) and 255) })
    }

    @Test fun `thumbnail respects rotation and ignores bed offsets`() {
        val mesh=TestMeshes.box(8f,14f,40f)
        val normal=ModelPreview.render(mesh,Placement(),0xE6B83C,128)
        assertContentEquals(normal,ModelPreview.render(mesh,Placement(centerX=400f,centerY=-250f),0xE6B83C,128))
        val rotated=ModelPreview.render(mesh,Placement(rotationX=90f),0xE6B83C,128)
        assertTrue(normal.indices.count { normal[it]!=rotated[it] }>2000)
    }

    @Test fun `depth buffer hides back faces independently of triangle order`() {
        val mesh=TestMeshes.box(12f,20f,30f)
        val backwards=Mesh(mesh.positions.asList().chunked(9).reversed().flatten().toFloatArray())
        val a=ModelPreview.render(mesh,Placement(),0xCCCCCC,128)
        val b=ModelPreview.render(backwards,Placement(),0xCCCCCC,128)
        assertTrue(a.indices.count { a[it]!=b[it] }<a.size/100)
        assertFailsWith<IllegalArgumentException> { ModelPreview.render(Mesh(floatArrayOf()),Placement(),0,128) }
    }
}
