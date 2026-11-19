package io.github.whoxllm.orcamicro

import android.opengl.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.settings.*
import io.github.whoxllm.orcamicro.render.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderTest {
    @Test fun cameraUnprojectsBedAndHitTestFollowsTranslatedModel() {
        val r=PlateRenderer()
        r.camera.frame(110f,110f,0f,132f)
        val p=r.camera.bedPoint(160f,120f,320,240)!!
        assertEquals(110f,p[0],0.02f); assertEquals(110f,p[1],0.02f)
        r.setModel(Mesh(floatArrayOf(100f,100f,0f,120f,100f,0f,110f,115f,30f)),true)
        assertTrue(r.hitModel(160f,120f,320,240))
        r.offsetX=80f
        assertFalse(r.hitModel(160f,120f,320,240))
        r.camera.view(-90f,89f)
        val center=r.camera.bedPoint(160f,120f,320,240)!!
        val right=r.camera.bedPoint(180f,120f,320,240)!!
        assertTrue("Right drag must increase X",right[0] > center[0])
        assertEquals(center[1],right[1],0.1f)
    }

    @Test fun renderEffectsCompileDrawAndResizeRealDepthFramebuffer() {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<EGLConfig>(1)
        val count=IntArray(1)
        val attributes=intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_DEPTH_SIZE,24,EGL14.EGL_NONE)
        assertTrue(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0))
        assertTrue(count[0] > 0)
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,320,EGL14.EGL_HEIGHT,240,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,context))
        try {
            val r=PlateRenderer()
            r.setBed(BedShape.rectangle(0f,0f,220f,220f),250f)
            r.camera.frame(110f,110f,0f,132f)
            r.setModel(Mesh(floatArrayOf(100f,100f,0f,120f,100f,0f,110f,115f,40f)),true)
            r.onSurfaceCreated(null,null); r.onSurfaceChanged(null,320,240)
            fun draw(o: RenderOptions): Int {
                r.setRenderOptions(o); r.onDrawFrame(null)
                assertEquals("GL failure for $o",GLES20.GL_NO_ERROR,GLES20.glGetError())
                val pixels=directBuffer(320*240*4)
                GLES20.glReadPixels(0,0,320,240,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
                val bytes=ByteArray(pixels.capacity()); pixels.get(bytes)
                return bytes.contentHashCode()
            }
            val plain=draw(RenderOptions())
            assertNotEquals(plain,draw(RenderOptions(texture=PlateTexture.PEI)))
            assertNotEquals(plain,draw(RenderOptions(texture=PlateTexture.CARBON)))
            assertNotEquals(plain,draw(RenderOptions(shadows=true)))
            assertNotEquals(plain,draw(RenderOptions(ssao=true)))
            assertTrue(r.renderStatus,listOf("буфер глубины готов","depth buffer ready").any{r.renderStatus.contains(it)})
            val scaled=draw(RenderOptions(resolution=0.75f))
            assertNotEquals("SSAO must change shading with an identical framebuffer",scaled,draw(RenderOptions(ssao=true,resolution=0.75f)))
            draw(RenderOptions(ssao=true,resolution=0.5f))
            assertTrue(r.renderStatus,r.renderStatus.contains("160×120"))
            r.onSurfaceChanged(null,240,180)
            draw(RenderOptions(ssao=true,resolution=0.75f))
            assertTrue(r.renderStatus,r.renderStatus.contains("180×135"))
        } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
}
