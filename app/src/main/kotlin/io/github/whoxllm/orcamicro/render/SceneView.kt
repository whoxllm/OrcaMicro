package io.github.whoxllm.orcamicro.render

import android.annotation.SuppressLint
import android.content.Context
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
import kotlin.math.hypot

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
        factory = { context ->
            (renderer as? PlateRenderer)?.modelSelected = false
            SceneSurface(context, renderer).also { holder[0] = it }
        },
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var dragging = false
    private var didDragMove = false
    private var movedBeyondSlop = false
    private var wasMultiTouch = false
    private var anchor: FloatArray? = null
    private var lastDragAt = 0L
    private var downX = 0f
    private var downY = 0f
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
        // Keep zoom on the two-finger pinch gesture only.
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
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pointers = 1
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                movedBeyondSlop = false
                wasMultiTouch = false
                didDragMove = false
                val plate = renderer as? PlateRenderer
                dragging = onModelDrag != null && plate != null && plate.modelSelected &&
                    plate.hitModel(event.x, event.y, width, height)
                anchor = if (dragging) renderer.camera.bedPoint(event.x, event.y, width, height) else null
                lastDragAt = 0L
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                wasMultiTouch = true
                if (dragging && didDragMove) onModelDragEnd?.invoke()
                dragging = false
                didDragMove = false
                anchor = null
                pointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) event.pointerCount else event.pointerCount - 1
                focus(event, excluding = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
                if (pointers == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointers == 1 && !movedBeyondSlop && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    movedBeyondSlop = true
                    lastX = event.x
                    lastY = event.y
                }
                if (dragging && pointers == 1) {
                    if (movedBeyondSlop && event.eventTime - lastDragAt >= 32) {
                        val point = renderer.camera.bedPoint(event.x, event.y, width, height)
                        val from = anchor
                        if (point != null && from != null) {
                            onModelDrag?.invoke(point[0] - from[0], point[1] - from[1])
                            anchor = point
                            lastDragAt = event.eventTime
                            didDragMove = true
                        }
                    }
                } else if (pointers == 1 && !scaleDetector.isInProgress && (movedBeyondSlop || wasMultiTouch)) {
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
                if (dragging && didDragMove) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val point = renderer.camera.bedPoint(event.x, event.y, width, height)
                        val from = anchor
                        if (point != null && from != null) onModelDrag?.invoke(point[0] - from[0], point[1] - from[1])
                    }
                    onModelDragEnd?.invoke()
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !movedBeyondSlop && !wasMultiTouch && onModelDrag != null) {
                    val plate = renderer as? PlateRenderer
                    if (plate != null) {
                        val hit = plate.hitModel(event.x, event.y, width, height)
                        plate.modelSelected = if (hit) !plate.modelSelected else false
                        if (plate.modelSelected) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        requestRender()
                    }
                }
                pointers = 0
                dragging = false
                didDragMove = false
                movedBeyondSlop = false
                wasMultiTouch = false
                anchor = null
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
