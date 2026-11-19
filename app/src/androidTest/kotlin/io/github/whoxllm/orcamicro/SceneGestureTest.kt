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

/** Exercises tap-to-select, drag-to-move, orbit, and pinch on Android's real MotionEvents. */
@RunWith(AndroidJUnit4::class)
class SceneGestureTest {
    @Test fun tapToSelectDragMovesModelWhileOffModelDragAndPinchControlCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val mesh = Mesh(floatArrayOf(100f,100f,0f, 120f,100f,0f, 110f,120f,20f))
            val renderer = PlateRenderer().apply {
                camera.frame(110f, 110f, 0f, 132f)
                setBed(BedShape.rectangle(0f, 0f, 220f, 220f), 250f)
                setModel(mesh, true)
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
                fun tap(px: Float, py: Float) {
                    one(time, time, MotionEvent.ACTION_DOWN, px, py)
                    one(time, time + 40, MotionEvent.ACTION_UP, px, py)
                    time += 300
                }
                fun drag(sx: Float, sy: Float, dx: Float, dy: Float) {
                    one(time, time, MotionEvent.ACTION_DOWN, sx, sy)
                    for (i in 1..4) one(time, time + i * 60, MotionEvent.ACTION_MOVE, sx + dx * i / 4f, sy + dy * i / 4f)
                    one(time, time + 300, MotionEvent.ACTION_UP, sx + dx, sy + dy)
                    time += 500
                }

                // 1. Tap the model to select it.
                assertFalse("Model starts unselected", renderer.modelSelected)
                tap(x, y)
                assertTrue("Tapping the model must select it", renderer.modelSelected)

                // 2. Drag starting on the selected model moves it without moving the camera.
                fun selectedDrag(dy: Float) {
                    movedX = 0f; movedY = 0f; renderer.offsetX = 0f; renderer.offsetY = 0f
                    val before = snapshot()
                    val ends = dragEnds
                    drag(x, y, 0f, dy)
                    assertTrue("Selected model must move when dragged", abs(movedX) + abs(movedY) > 1f)
                    assertEquals("One completed drag", ends + 1, dragEnds)
                    assertArrayEquals("Model drag must not zoom, pan or orbit the camera", before, snapshot(), 0f)
                }
                selectedDrag(200f)
                selectedDrag(-200f)

                // 3. Drag starting off the model rotates the camera even while selected.
                val beforeOrbit = snapshot()
                val modelBeforeOrbit = movedX to movedY
                drag(20f, 20f, 100f, 50f)
                assertFalse("Dragging off the model must orbit the camera", beforeOrbit.contentEquals(snapshot()))
                assertEquals("Orbit must not move the model", modelBeforeOrbit, movedX to movedY)

                // 4. Two-finger pinch zooms without moving the model even while selected.
                val beforePinch = snapshot()
                val nearBefore = renderer.camera.clipPlanes()[0]
                val modelBeforePinch = movedX to movedY
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
                time += 500
                assertFalse("Pinch must still change the camera", beforePinch.contentEquals(snapshot()))
                assertTrue("Spreading fingers must zoom in", renderer.camera.clipPlanes()[0] < nearBefore)
                assertEquals("Pinch must not move the model", modelBeforePinch, movedX to movedY)

                // 5. Tapping empty space deselects; tapping model toggles; replacing model clears selection.
                tap(20f, 20f)
                assertFalse("Tapping empty space must deselect", renderer.modelSelected)

                renderer.camera.frame(110f, 110f, 0f, 132f)
                movedX = 0f; movedY = 0f; renderer.offsetX = 0f; renderer.offsetY = 0f
                tap(x, y)
                assertTrue("Tapping model selects it again", renderer.modelSelected)
                tap(x, y)
                assertFalse("Tapping selected model deselects it", renderer.modelSelected)

                tap(x, y)
                assertTrue(renderer.modelSelected)
                renderer.setModel(null, true)
                assertFalse("Removing model clears selection", renderer.modelSelected)
            }
        }
    }
}
