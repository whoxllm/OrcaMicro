package io.github.whoxllm.orcamicro.render

import android.opengl.GLES20
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
    protected val mvp = FloatArray(16)
    protected lateinit var lineProgram: GlProgram
    protected var lineWidth = 1f
    private var width = 1
    private var height = 1

    @Volatile private var bed: Bed? = null
    private var bedLines: GlBuffer? = null
    private var bedOutline: GlBuffer? = null
    private var bedDirty = true

    var clearColor: FloatArray = rgba(0x1E2228)

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
        GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        camera.viewProjection(width, height, mvp)
        drawBed()
        drawScene()
    }

    /** Create GL objects; the previous context and everything in it is gone. */
    protected abstract fun onCreateResources()

    protected abstract fun drawScene()

    private fun drawBed() {
        val current = bed ?: return
        if (bedDirty) {
            bedLines?.delete()
            bedOutline?.delete()
            val (grid, outline) = bedGeometry(current.shape, current.height)
            bedLines = GlBuffer(floatBytes(grid), grid.size / 3)
            bedOutline = GlBuffer(floatBytes(outline), outline.size / 3)
            bedDirty = false
        }
        GLES20.glDepthMask(false)
        drawLines(bedLines!!, rgba(0x3A414C), 1f)
        GLES20.glDepthMask(true)
        drawLines(bedOutline!!, rgba(0x8A94A6), lineWidth)
    }

    /** Draws position-only lines in one colour. */
    protected fun drawLines(buffer: GlBuffer, color: FloatArray, width: Float) {
        lineProgram.use()
        GLES20.glLineWidth(width)
        GLES20.glUniformMatrix4fv(lineProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4fv(lineProgram.uniform("uColor"), 1, color, 0)
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
