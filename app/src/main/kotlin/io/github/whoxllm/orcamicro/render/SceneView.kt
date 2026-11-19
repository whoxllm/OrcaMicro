package io.github.whoxllm.orcamicro.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.GestureDetector
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Hosts a [SceneRenderer] in Compose. One finger orbits, two fingers pan, pinch zooms.
 * [update] runs on every recomposition to push new data to the renderer.
 */
@Composable
fun <R : SceneRenderer> SceneView(
    renderer: R,
    modifier: Modifier = Modifier,
    onModelDrag: ((Float, Float) -> Unit)? = null,
    onModelDragEnd: (() -> Unit)? = null,
    update: (R) -> Unit = {},
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
    // SurfaceView must acquire a fresh backing surface after the profile panel changes height.
    key(maxWidth, maxHeight) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> SceneSurface(context, renderer).also { holder[0] = it } },
        update = { view ->
            renderer.requestFrame = { view.requestRender() }
            view.onModelDragEnd = onModelDragEnd
            view.onModelDrag = onModelDrag
            update(renderer)
            view.requestRender()
        },
    )
    }
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class SceneSurface(context: Context, private val renderer: SceneRenderer) : GLSurfaceView(context) {
    var onModelDrag: ((Float, Float) -> Unit)? = null
    var onModelDragEnd: (() -> Unit)? = null
    private var dragging = false
    private var anchor: FloatArray? = null
    private var lastDragAt = 0L
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean = onModelDrag != null
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            // onDoubleTap receives the FIRST tap's coordinates; anchor the drag to
            // the actual second DOWN to avoid a jump when the taps differ slightly.
            if(e.actionMasked == MotionEvent.ACTION_DOWN) {
                dragging = onModelDrag != null && (renderer as? PlateRenderer)?.hitModel(e.x, e.y, width, height) == true
                anchor = if (dragging) renderer.camera.bedPoint(e.x, e.y, width, height) else null
                lastDragAt = 0L
                if (dragging) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            return dragging
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pointers = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    }).apply {
        // Android's single-finger double-tap-and-drag zoom conflicts with model
        // placement. Keep zoom on the two-finger pinch gesture instead.
        isQuickScaleEnabled = false
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointers = 1
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if(dragging) onModelDragEnd?.invoke()
                dragging = false; anchor = null
                pointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) event.pointerCount else event.pointerCount - 1
                focus(event, excluding = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
                if (pointers == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && pointers == 1) {
                    if (event.eventTime - lastDragAt >= 32) {
                        val point = renderer.camera.bedPoint(event.x, event.y, width, height)
                        val from = anchor
                        if (point != null && from != null) {
                            onModelDrag?.invoke(point[0]-from[0], point[1]-from[1])
                            anchor = point; lastDragAt = event.eventTime
                        }
                    }
                } else if (pointers == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.camera.rotate(dx * ROTATE_DEGREES_PER_PX, dy * ROTATE_DEGREES_PER_PX)
                    lastX = event.x
                    lastY = event.y
                } else if (pointers >= 2) {
                    val previousX = lastFocusX
                    val previousY = lastFocusY
                    focus(event, excluding = -1)
                    renderer.camera.pan((lastFocusX - previousX) / height, (lastFocusY - previousY) / height)
                }
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if(dragging) {
                    if(event.actionMasked == MotionEvent.ACTION_UP) {
                        val point = renderer.camera.bedPoint(event.x,event.y,width,height)
                        val from = anchor
                        if(point != null && from != null) onModelDrag?.invoke(point[0]-from[0],point[1]-from[1])
                    }
                    onModelDragEnd?.invoke()
                }
                pointers = 0; dragging = false; anchor = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun focus(event: MotionEvent, excluding: Int) {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excluding) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count > 0) {
            lastFocusX = sumX / count
            lastFocusY = sumY / count
        }
    }

    private companion object {
        const val ROTATE_DEGREES_PER_PX = 0.35f
    }
}
