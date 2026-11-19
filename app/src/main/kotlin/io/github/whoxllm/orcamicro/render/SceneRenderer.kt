package io.github.whoxllm.orcamicro.render

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.opengl.GLES20
import android.opengl.GLES30
import io.github.whoxllm.orcamicro.core.settings.RenderOptions
import io.github.whoxllm.orcamicro.core.settings.PlateTexture
import android.opengl.GLSurfaceView
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.settings.BedShape
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shared base for the 3D views: owns the camera, the line shader and the bed drawing. GL
 * resources are rebuilt in [onSurfaceCreated], since Android may drop the GL context whenever
 * the view goes off screen.
 */
abstract class SceneRenderer : GLSurfaceView.Renderer {
    val camera = OrbitCamera()
    @Volatile var requestFrame: (() -> Unit)? = null
    protected val mvp = FloatArray(16)
    protected lateinit var lineProgram: GlProgram
    protected var lineWidth = 1f
    private var width = 1
    private var height = 1

    @Volatile private var bed: Bed? = null
    private var bedLines: GlBuffer? = null
    private var bedOutline: GlBuffer? = null
    @Volatile private var bedDirty = true
    private var bedFill: GlBuffer? = null
    private lateinit var bedProgram: GlProgram
    private lateinit var postProgram: GlProgram
    private var postQuad: GlBuffer? = null
    private var frameBuffer = 0
    private var imageTexture = 0
    private var depthTexture = 0
    private var targetWidth = 0
    private var targetHeight = 0
    @Volatile var renderStatus = tr("OpenGL ES 3 · рендер по запросу")
        private set
    @Volatile protected var options = RenderOptions()
    fun setRenderOptions(next: RenderOptions) {
        if (options.volume != next.volume) bedDirty = true
        options = next
        camera.freeOrbit = next.freeCamera
    }

    var clearColor: FloatArray = rgba(0x454545)

    private data class Bed(val shape: BedShape, val height: Float)

    fun setBed(shape: BedShape?, printableHeight: Float) {
        val next = shape?.let { Bed(it, printableHeight) }
        if (next != bed) {
            bed = next
            bedDirty = true
        }
    }

    final override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lineProgram = GlProgram(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
        lineWidth = maxLineWidth()
        bedProgram = GlProgram(Shaders.BED_VERTEX, Shaders.BED_FRAGMENT)
        postProgram = GlProgram(Shaders.POST_VERTEX, Shaders.POST_FRAGMENT)
        postQuad = GlBuffer(floatBytes(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)), 4)
        frameBuffer = 0; imageTexture = 0; depthTexture = 0; targetWidth = 0; targetHeight = 0
        bedFill = null
        bedLines = null
        bedOutline = null
        bedDirty = true
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        onCreateResources()
    }

    final override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    final override fun onDrawFrame(gl: GL10?) {
        val opts = options
        val scaled = opts.ssao || opts.resolution < 0.99f
        val w = max(1, (width * opts.resolution.coerceIn(0.5f,1f)).toInt())
        val h = max(1, (height * opts.resolution.coerceIn(0.5f,1f)).toInt())
        val offscreen = scaled && ensureTarget(w,h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if(offscreen) frameBuffer else 0)
        GLES20.glViewport(0,0,if(offscreen) w else width,if(offscreen) h else height)
        val bg = rgba(opts.background)
        GLES20.glClearColor(bg[0],bg[1],bg[2],1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        camera.viewProjection(width, height, mvp)
        drawBed()
        drawScene()
        if(offscreen) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
            GLES20.glViewport(0,0,width,height)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            postProgram.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,imageTexture)
            GLES20.glUniform1i(postProgram.uniform("uImage"),0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,depthTexture)
            GLES20.glUniform1i(postProgram.uniform("uDepth"),1)
            GLES20.glUniform2f(postProgram.uniform("uPixel"),1f/w,1f/h)
            GLES20.glUniform2fv(postProgram.uniform("uClip"),1,camera.clipPlanes(),0)
            GLES20.glUniform1f(postProgram.uniform("uAo"),if(opts.ssao) 1f else 0f)
            val a=postProgram.attribute("aPosition")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,postQuad!!.id)
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,8,0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GLES20.glDisableVertexAttribArray(a)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
    }

    private fun ensureTarget(w: Int,h: Int): Boolean {
        if(targetWidth==w && targetHeight==h && frameBuffer!=0) return true
        if(frameBuffer!=0) GLES20.glDeleteFramebuffers(1,intArrayOf(frameBuffer),0)
        GLES20.glDeleteTextures(2,intArrayOf(imageTexture,depthTexture),0)
        val ids=IntArray(2); GLES20.glGenTextures(2,ids,0); imageTexture=ids[0]; depthTexture=ids[1]
        for(id in ids) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            if(id==imageTexture) GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
            else GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES30.GL_DEPTH_COMPONENT24,w,h,0,GLES20.GL_DEPTH_COMPONENT,GLES20.GL_UNSIGNED_INT,null)
        }
        val f=IntArray(1); GLES20.glGenFramebuffers(1,f,0); frameBuffer=f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,frameBuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,imageTexture,0)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_DEPTH_ATTACHMENT,GLES20.GL_TEXTURE_2D,depthTexture,0)
        val ok=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)==GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0)
        targetWidth=w; targetHeight=h
        if(!ok) {
            GLES20.glDeleteFramebuffers(1,intArrayOf(frameBuffer),0); frameBuffer=0
            renderStatus=tr("Эффекты недоступны: драйвер не создал буфер глубины")
        } else renderStatus=tr("OpenGL ES 3 · {0}×{1} · буфер глубины готов" ,w,h)
        return ok
    }

    /** Create GL objects; the previous context and everything in it is gone. */
    protected abstract fun onCreateResources()

    protected abstract fun drawScene()

    private fun drawBed() {
        val current = bed ?: return
        if (bedDirty) {
            bedLines?.delete()
            bedOutline?.delete()
            bedFill?.delete()
            val (grid, outline) = bedGeometry(current.shape, if(options.volume) current.height else 0f)
            bedLines = GlBuffer(floatBytes(grid), grid.size / 3)
            bedOutline = GlBuffer(floatBytes(outline), outline.size / 3)
            val vertices = current.shape.points.flatMap { listOf(it.first,it.second,-0.06f) }.toFloatArray()
            bedFill = GlBuffer(floatBytes(vertices), vertices.size/3)
            bedDirty = false
        }
        if(options.texture != PlateTexture.NONE) {
            bedProgram.use()
            GLES20.glUniformMatrix4fv(bedProgram.uniform("uMvp"),1,false,mvp,0)
            GLES20.glUniform1f(bedProgram.uniform("uTexture"),options.texture.ordinal.toFloat())
            val a=bedProgram.attribute("aPosition")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,bedFill!!.id)
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a,3,GLES20.GL_FLOAT,false,12,0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN,0,bedFill!!.count)
            GLES20.glDisableVertexAttribArray(a)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0)
        }
        GLES20.glDepthMask(false)
        if(options.grid) drawLines(bedLines!!, rgba(0x686868), 1f)
        GLES20.glDepthMask(true)
        drawLines(bedOutline!!, rgba(0xB2B3B5), lineWidth)
    }

    /** Draws position-only lines in one colour. */
    protected fun drawLines(buffer: GlBuffer, color: FloatArray, width: Float) {
        lineProgram.use()
        GLES20.glLineWidth(width)
        GLES20.glUniformMatrix4fv(lineProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4fv(lineProgram.uniform("uColor"), 1, color, 0)
        GLES20.glUniform1f(lineProgram.uniform("uShadow"), 0f)
        GLES20.glUniform1f(lineProgram.uniform("uUseVertexColor"), 0f)
        GLES20.glUniform1f(lineProgram.uniform("uBrightness"), 1f)
        val position = lineProgram.attribute("aPosition")
        val colorAttribute = lineProgram.attribute("aColor")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer.id)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glDisableVertexAttribArray(colorAttribute)
        GLES20.glVertexAttrib4f(colorAttribute, 1f, 1f, 1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, buffer.count)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        /** Grid lines every 10 mm (20 mm on big beds), and the bed outline plus the build volume edges. */
        fun bedGeometry(shape: BedShape, height: Float): Pair<FloatArray, FloatArray> {
            val step = if (max(shape.width, shape.depth) > 400f) 20f else 10f
            val grid = ArrayList<Float>()
            var x = ceil(shape.minX / step) * step
            while (x <= shape.maxX) {
                grid += listOf(x, shape.minY, 0f, x, shape.maxY, 0f)
                x += step
            }
            var y = ceil(shape.minY / step) * step
            while (y <= shape.maxY) {
                grid += listOf(shape.minX, y, 0f, shape.maxX, y, 0f)
                y += step
            }
            val outline = ArrayList<Float>()
            val points = shape.points
            for (i in points.indices) {
                val (ax, ay) = points[i]
                val (bx, by) = points[(i + 1) % points.size]
                outline += listOf(ax, ay, 0f, bx, by, 0f)
                outline += listOf(ax, ay, height, bx, by, height)
                outline += listOf(ax, ay, 0f, ax, ay, height)
            }
            return grid.toFloatArray() to outline.toFloatArray()
        }

        /** Positions and flat normals, interleaved, for drawing a mesh. */
        fun meshVertices(mesh: Mesh): java.nio.ByteBuffer {
            val p = mesh.positions
            val buffer = directBuffer(mesh.triangleCount * 3 * 6 * 4)
            val out = buffer.asFloatBuffer()
            var i = 0
            while (i < p.size) {
                val ux = p[i + 3] - p[i]; val uy = p[i + 4] - p[i + 1]; val uz = p[i + 5] - p[i + 2]
                val vx = p[i + 6] - p[i]; val vy = p[i + 7] - p[i + 1]; val vz = p[i + 8] - p[i + 2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                if (length > 0f) { nx /= length; ny /= length; nz /= length } else { nz = 1f }
                for (k in 0 until 3) {
                    out.put(p[i + k * 3]); out.put(p[i + k * 3 + 1]); out.put(p[i + k * 3 + 2])
                    out.put(nx); out.put(ny); out.put(nz)
                }
                i += 9
            }
            return buffer
        }
    }
}
