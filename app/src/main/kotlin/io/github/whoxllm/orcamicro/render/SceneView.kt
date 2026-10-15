package io.github.whoxllm.orcamicro.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
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
    AndroidView(
        modifier = modifier,
        factory = { context -> SceneSurface(context, renderer).also { holder[0] = it } },
        update = { view ->
            update(renderer)
            view.requestRender()
        },
    )
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class SceneSurface(context: Context, private val renderer: SceneRenderer) : GLSurfaceView(context) {
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
    })

    init {
        setEGLContextClientVersion(2)
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
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                pointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) event.pointerCount else event.pointerCount - 1
                focus(event, excluding = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
                if (pointers == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointers == 1 && !scaleDetector.isInProgress) {
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointers = 0
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
