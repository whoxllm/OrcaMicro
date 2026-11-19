package io.github.whoxllm.orcamicro

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.render.PlateRenderer
import io.github.whoxllm.orcamicro.render.SceneSurface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercises Android's real gesture detectors, including the double-tap quick-scale threshold. */
@RunWith(AndroidJUnit4::class)
class SceneGestureTest {
    @Test fun doubleTapDragLeavesCameraFixedWhilePinchAndOrbitStillWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val renderer = PlateRenderer().apply {
                camera.frame(110f, 110f, 0f, 132f)
                setBed(BedShape.rectangle(0f, 0f, 220f, 220f), 250f)
                setModel(Mesh(floatArrayOf(100f,100f,0f, 120f,100f,0f, 110f,120f,20f)), true)
            }
            lateinit var surface: SceneSurface
            var movedX = 0f
            var movedY = 0f
            var dragEnds = 0
            scenario.onActivity { activity ->
                surface = SceneSurface(activity, renderer).apply {
                    onModelDrag = { dx, dy ->
                        movedX += dx; movedY += dy
                        renderer.offsetX = movedX; renderer.offsetY = movedY
                    }
                    onModelDragEnd = { dragEnds++ }
                }
                activity.setContentView(surface)
            }
            instrumentation.waitForIdleSync()
            assertTrue("Scene must be laid out", surface.width > 0 && surface.height > 0)
            instrumentation.runOnMainSync {
                val x = surface.width / 2f
                val y = surface.height / 2f
                var time = SystemClock.uptimeMillis()
                fun snapshot() = FloatArray(16).also { renderer.camera.viewProjection(surface.width, surface.height, it) }
                fun event(down: Long, at: Long, action: Int, points: List<Pair<Float, Float>>) {
                    val properties = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                    val coordinates = Array(points.size) { i -> MotionEvent.PointerCoords().apply {
                        this.x = points[i].first; this.y = points[i].second; pressure = 1f; size = 1f
                    } }
                    MotionEvent.obtain(down, at, action, points.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                        surface.dispatchTouchEvent(it); it.recycle()
                    }
                }
                fun one(down: Long, at: Long, action: Int, px: Float, py: Float) = event(down, at, action, listOf(px to py))
                fun doubleTapDrag(dy: Float) {
                    movedX = 0f; movedY = 0f; renderer.offsetX = 0f; renderer.offsetY = 0f
                    val before = snapshot()
                    val ends = dragEnds
                    one(time, time, MotionEvent.ACTION_DOWN, x, y)
                    one(time, time + 40, MotionEvent.ACTION_UP, x, y)
                    one(time + 120, time + 120, MotionEvent.ACTION_DOWN, x, y)
                    // 200px exceeds Android's anchored scale slop; the old short drag test did not.
                    for (i in 1..4) one(time + 120, time + 120 + i * 60, MotionEvent.ACTION_MOVE, x, y + dy * i / 4f)
                    one(time + 120, time + 420, MotionEvent.ACTION_UP, x, y + dy)
                    assertTrue("Model must move with the second tap held", abs(movedX) + abs(movedY) > 1f)
                    assertEquals("One completed drag", ends + 1, dragEnds)
                    assertArrayEquals("Double-tap model drag must not zoom, pan or orbit the camera", before, snapshot(), 0f)
                    time += 1000
                }
                doubleTapDrag(200f)
                doubleTapDrag(-200f)

                // A real two-finger pinch still changes the zoom, with a stationary centroid.
                val beforePinch = snapshot()
                val nearBefore = renderer.camera.clipPlanes()[0]
                val modelBefore = movedX to movedY
                // The minimum span is specified in physical mm and varies with
                // the device DPI. Stay above it, along the phone's longer axis.
                val config = ViewConfiguration.get(surface.context)
                val step = config.scaledTouchSlop * 2f
                val initialSpan = config.scaledMinimumScalingSpan / 2f + step
                fun pair(span: Float) = listOf(x to (y - span), x to (y + span))
                one(time, time, MotionEvent.ACTION_DOWN, x, y - initialSpan)
                event(time, time + 40, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pair(initialSpan))
                for (i in 1..4) {
                    event(time, time + 40 + i * 60, MotionEvent.ACTION_MOVE, pair(initialSpan + i * step))
                }
                event(time, time + 340, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pair(initialSpan + 4 * step))
                one(time, time + 380, MotionEvent.ACTION_UP, x, y - initialSpan - 4 * step)
                assertFalse("Pinch must still change the camera", beforePinch.contentEquals(snapshot()))
                assertTrue("Spreading fingers must zoom in", renderer.camera.clipPlanes()[0] < nearBefore)
                assertEquals("Pinch must not move the model", modelBefore, movedX to movedY)

                // A normal one-finger swipe remains a camera orbit.
                time += 1000
                val beforeOrbit = snapshot()
                one(time, time, MotionEvent.ACTION_DOWN, 20f, 20f)
                one(time, time + 80, MotionEvent.ACTION_MOVE, 120f, 70f)
                one(time, time + 120, MotionEvent.ACTION_UP, 120f, 70f)
                assertFalse("Single-finger orbit must still work", beforeOrbit.contentEquals(snapshot()))
                assertEquals("Orbit must not move the model", modelBefore, movedX to movedY)
            }
        }
    }
}
