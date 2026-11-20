package io.github.whoxllm.orcamicro.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.whoxllm.orcamicro.cad.CadDisplayMesh
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Renders a parametric CAD solid with flat-shaded faces, B-rep edges, and face/edge highlights.
 */
class CadRenderer : SceneRenderer() {
    private lateinit var meshProgram: GlProgram

    @Volatile var displayMesh: CadDisplayMesh? = null
    @Volatile var selectedFace: Pair<Int, Int>? = null
    @Volatile var selectedEdges: Set<Pair<Int, Int>> = emptySet()

    private var uploadedMesh: CadDisplayMesh? = null
    private var uploadedFace: Pair<Int, Int>? = null
    private var uploadedEdges: Set<Pair<Int, Int>> = emptySet()

    private var normalMeshBuf: GlBuffer? = null
    private var selectedFaceBuf: GlBuffer? = null
    private var normalEdgeBuf: GlBuffer? = null
    private var selectedEdgeBuf: GlBuffer? = null

    override fun onCreateResources() {
        meshProgram = GlProgram(Shaders.MESH_VERTEX, Shaders.MESH_FRAGMENT)
        uploadedMesh = null
        normalMeshBuf = null
        selectedFaceBuf = null
        normalEdgeBuf = null
        selectedEdgeBuf = null
    }

    private fun rebuildBuffers(mesh: CadDisplayMesh) {
        normalMeshBuf?.delete(); selectedFaceBuf?.delete()
        normalEdgeBuf?.delete(); selectedEdgeBuf?.delete()

        val selFace = selectedFace
        val selEdges = selectedEdges

        var normalTriCount = 0
        var selTriCount = 0
        for (i in 0 until mesh.triangleCount) {
            if (selFace != null && mesh.triBodyIds[i] == selFace.first && mesh.triFaceIds[i] == selFace.second) selTriCount++
            else normalTriCount++
        }

        val normalVerts = if (normalTriCount > 0) directBuffer(normalTriCount * 3 * 6 * 4) else null
        val selVerts = if (selTriCount > 0) directBuffer(selTriCount * 3 * 6 * 4) else null
        val nOut = normalVerts?.asFloatBuffer()
        val sOut = selVerts?.asFloatBuffer()

        val p = mesh.positions
        for (i in 0 until mesh.triangleCount) {
            val base = i * 9
            val ux = p[base + 3] - p[base]; val uy = p[base + 4] - p[base + 1]; val uz = p[base + 5] - p[base + 2]
            val vx = p[base + 6] - p[base]; val vy = p[base + 7] - p[base + 1]; val vz = p[base + 8] - p[base + 2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) { nx /= len; ny /= len; nz /= len } else { nz = 1f }
            val target = if (selFace != null && mesh.triBodyIds[i] == selFace.first && mesh.triFaceIds[i] == selFace.second) sOut else nOut
            if (target != null) {
                for (k in 0 until 3) {
                    target.put(p[base + k * 3]); target.put(p[base + k * 3 + 1]); target.put(p[base + k * 3 + 2])
                    target.put(nx); target.put(ny); target.put(nz)
                }
            }
        }
        normalMeshBuf = normalVerts?.let { GlBuffer(it, normalTriCount * 3) }
        selectedFaceBuf = selVerts?.let { GlBuffer(it, selTriCount * 3) }

        val nEdges = ArrayList<Float>()
        val sEdges = ArrayList<Float>()
        val ep = mesh.edgePositions
        for (i in 0 until mesh.edgeSegmentCount) {
            val key = mesh.edgeBodyIds[i] to mesh.edgeIds[i]
            val dst = if (key in selEdges) sEdges else nEdges
            val base = i * 6
            for (k in 0 until 6) dst.add(ep[base + k])
        }
        normalEdgeBuf = if (nEdges.isNotEmpty()) GlBuffer(floatBytes(nEdges.toFloatArray()), nEdges.size / 3) else null
        selectedEdgeBuf = if (sEdges.isNotEmpty()) GlBuffer(floatBytes(sEdges.toFloatArray()), sEdges.size / 3) else null

        uploadedMesh = mesh
        uploadedFace = selFace
        uploadedEdges = selEdges
    }

    override fun drawScene() {
        val current = displayMesh ?: return
        if (current !== uploadedMesh || selectedFace != uploadedFace || selectedEdges != uploadedEdges) {
            rebuildBuffers(current)
        }
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(1.0f, 1.0f)
        normalMeshBuf?.let { drawMeshBuffer(it, BODY_COLOR) }
        selectedFaceBuf?.let { drawMeshBuffer(it, SELECTED_FACE_COLOR) }
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)

        normalEdgeBuf?.let { drawLines(it, EDGE_COLOR, 1.5f) }
        selectedEdgeBuf?.let { drawLines(it, SELECTED_EDGE_COLOR, 4.0f) }
    }

    private fun drawMeshBuffer(buffer: GlBuffer, color: FloatArray) {
        meshProgram.use()
        GLES20.glUniform2f(meshProgram.uniform("uOffset"), 0f, 0f)
        GLES20.glUniformMatrix4fv(meshProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4fv(meshProgram.uniform("uColor"), 1, color, 0)
        GLES20.glUniform1f(meshProgram.uniform("uBrightness"), options.brightness)
        GLES20.glUniform1f(meshProgram.uniform("uShadow"), 0f)
        val position = meshProgram.attribute("aPosition")
        val normal = meshProgram.attribute("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer.id)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buffer.count)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        val BODY_COLOR = rgba(0x88A6B8)
        val SELECTED_FACE_COLOR = rgba(0x26A69A)
        val EDGE_COLOR = rgba(0x22262A)
        val SELECTED_EDGE_COLOR = rgba(0xFFB300)
    }
}

@Composable
fun CadSceneView(
    renderer: CadRenderer,
    modifier: Modifier = Modifier,
    onTap: (Float, Float, Int, Int) -> Unit,
    update: (CadRenderer) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { arrayOfNulls<GLSurfaceView>(1) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BoxWithConstraints(modifier) {
        key(maxWidth, maxHeight) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> CadSurface(context, renderer).also { holder[0] = it } },
                update = { view ->
                    renderer.requestFrame = { view.requestRender() }
                    view.onTap = onTap
                    update(renderer)
                    view.requestRender()
                },
            )
        }
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class CadSurface(context: Context, private val renderer: CadRenderer) : GLSurfaceView(context) {
    var onTap: ((Float, Float, Int, Int) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pointers = 0
    private var moved = false
    private var wasMulti = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointers = 1
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false; wasMulti = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                wasMulti = true
                pointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) event.pointerCount else event.pointerCount - 1
                focus(event, excluding = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
                if (pointers == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining); lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointers == 1 && !moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    moved = true
                    lastX = event.x; lastY = event.y
                }
                if (pointers == 1 && !scaleDetector.isInProgress && (moved || wasMulti)) {
                    renderer.camera.rotate((event.x - lastX) * 0.35f, (event.y - lastY) * 0.35f)
                    lastX = event.x; lastY = event.y
                } else if (pointers >= 2) {
                    val px = lastFocusX; val py = lastFocusY
                    focus(event, excluding = -1)
                    renderer.camera.pan((lastFocusX - px) / height, (lastFocusY - py) / height)
                }
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !moved && !wasMulti) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onTap?.invoke(event.x, event.y, width, height)
                    requestRender()
                }
                pointers = 0; moved = false; wasMulti = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun focus(event: MotionEvent, excluding: Int) {
        var sx = 0f; var sy = 0f; var c = 0
        for (i in 0 until event.pointerCount) {
            if (i == excluding) continue
            sx += event.getX(i); sy += event.getY(i); c++
        }
        if (c > 0) { lastFocusX = sx / c; lastFocusY = sy / c }
    }
}
