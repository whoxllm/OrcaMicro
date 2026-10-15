package io.github.whoxllm.orcamicro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.gcode.Feature
import io.github.whoxllm.orcamicro.core.gcode.GcodeParser
import io.github.whoxllm.orcamicro.core.gcode.Toolpaths
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.profiles.Catalog
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.core.slicing.EngineResponse
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import io.github.whoxllm.orcamicro.data.ProfileRepository
import io.github.whoxllm.orcamicro.engine.EngineClient
import io.github.whoxllm.orcamicro.engine.EngineEvent
import io.github.whoxllm.orcamicro.engine.EngineResources
import io.github.whoxllm.orcamicro.engine.NativeEngine
import io.github.whoxllm.orcamicro.engine.SliceJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real native engine with OrcaSlicer's bundled profiles, in the app's own process.
 * CI runs it on an emulator; it is the end-to-end check that the APK actually slices.
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val profiles = ProfileRepository(context.assets)

    @Test
    fun engineLoads() {
        assertTrue("engine failed to load: ${NativeEngine.unavailableReason}", NativeEngine.isAvailable)
        assertTrue(NativeEngine.version(), NativeEngine.version().startsWith("OrcaSlicer"))
    }

    @Test
    fun slicesACubeForAGenericMarlinPrinter() {
        val sliced = slice("Custom", "MyMarlin 0.4 nozzle", box(20f, 20f, 20f), PresetEdits())
        val paths = sliced.toolpaths
        assertTrue("expected about 100 layers, got ${paths.layers.size}", paths.layers.size in 80..120)
        assertTrue(Feature.OUTER_WALL in paths.features)
        assertTrue(Feature.SPARSE_INFILL in paths.features)
        assertNotNull("print time estimate", paths.stats.printSeconds)
        assertTrue("filament used", (paths.stats.filamentMillimetres ?: 0.0) > 100.0)

        // The cube is centred where the app placed it, on the bed.
        val walls = wallPoints(paths)
        val midX = (walls.minOf { it.first } + walls.maxOf { it.first }) / 2
        val midY = (walls.minOf { it.second } + walls.maxOf { it.second }) / 2
        assertEquals(sliced.bed.centerX, midX, 1.5f)
        assertEquals(sliced.bed.centerY, midY, 1.5f)
        assertEquals(20f, walls.maxOf { it.first } - walls.minOf { it.first }, 1.5f)
    }

    @Test
    fun generatesNormalSupportsUnderAnOverhang() {
        val edits = PresetEdits(process = mapOf("enable_support" to "1", "support_type" to "normal(auto)"))
        val sliced = slice("Creality", "Creality Ender-3 V3 SE 0.4 nozzle", tShape(), edits)
        assertTrue("no support toolpaths: ${sliced.toolpaths.features}", Feature.SUPPORT in sliced.toolpaths.features)
    }

    @Test
    fun generatesTreeSupportsUnderAnOverhang() {
        val edits = PresetEdits(process = mapOf("enable_support" to "1", "support_type" to "tree(auto)"))
        val sliced = slice("Creality", "Creality Ender-3 V3 SE 0.4 nozzle", tShape(), edits)
        assertTrue("no support toolpaths: ${sliced.toolpaths.features}", Feature.SUPPORT in sliced.toolpaths.features)
    }

    @Test
    fun slicesThroughTheEngineService() {
        // The app's own path: EngineClient binds EngineService in the :engine process. The app is
        // in the foreground, as when the user taps Slice, so it may start a foreground service.
        ActivityScenario.launch(MainActivity::class.java).use {
            val prepared = prepare("Custom", "MyMarlin 0.4 nozzle", box(20f, 20f, 20f), PresetEdits())
            val events = runBlocking { withTimeout(SERVICE_TIMEOUT_MS) { EngineClient(context).slice(prepared.requestJson).toList() } }
            val last = events.lastOrNull()
            assertTrue("engine process gave no result: $events", last is EngineEvent.Finished)
            val response = (last as EngineEvent.Finished).response
            assertTrue("slice failed (${response.stage}): ${response.error}\n${prepared.job.logTail()}", response.ok)
            assertTrue("no G-code written", prepared.job.gcode.isFile && prepared.job.gcode.length() > 1000)
        }
    }

    private class Sliced(val toolpaths: Toolpaths, val bed: BedShape)

    private class Prepared(val job: SliceJob, val requestJson: String, val bed: BedShape)

    private fun slice(vendor: String, printerName: String, mesh: Mesh, edits: PresetEdits): Sliced {
        val prepared = prepare(vendor, printerName, mesh, edits)
        val job = prepared.job
        val response = EngineResponse.parse(NativeEngine.slice(prepared.requestJson))
        assertTrue("slice failed (${response.stage}): ${response.error}\n${job.logTail()}", response.ok)
        val gcode = job.gcode
        assertTrue("no G-code written", gcode.isFile && gcode.length() > 1000)
        val toolpaths = gcode.bufferedReader().use { GcodeParser.parse(it) }
        return Sliced(toolpaths, prepared.bed)
    }

    private fun prepare(vendor: String, printerName: String, mesh: Mesh, edits: PresetEdits): Prepared {
        val catalog = profiles.catalog
        val printer = requireNotNull(catalog.printer(vendor, printerName)) { "$printerName missing from the catalog" }
        val process = requireNotNull(catalog.defaultProcessFor(vendor, printer))
        val filament = catalog.filamentsFor(vendor, printer).first { it.vendor == Catalog.LIBRARY_VENDOR && it.name == "Generic PLA @System" }
        val inputs = SliceInputs.build(
            profiles.resolve(vendor, printerName),
            profiles.resolve(vendor, process),
            profiles.resolve(filament.vendor, filament.name),
            edits,
        )
        val bed = BedShape.of(inputs.machine)!!
        val placement = Placement(centerX = bed.centerX, centerY = bed.centerY)
        val job = SliceJob.create(context, mesh, placement, inputs)
        val resources = EngineResources.ensure(context)
        return Prepared(job, job.request(resources, placement).toJson(), bed)
    }

    private fun wallPoints(paths: Toolpaths): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>()
        for (layer in paths.layers.drop(2)) {
            for (s in 0 until layer.extrusionCount) {
                if (Feature.entries[layer.features[s].toInt()] != Feature.OUTER_WALL) continue
                val o = s * 6
                points += layer.extrusions[o] to layer.extrusions[o + 1]
            }
        }
        assertTrue("no outer walls", points.isNotEmpty())
        return points
    }

    private companion object {
        const val SERVICE_TIMEOUT_MS = 5 * 60 * 1000L
    }

    /** A 30 mm wide cap on a narrow stem: unprintable without support. */
    private fun tShape(): Mesh = Mesh(box(8f, 8f, 15f, x = 11f, y = 11f).positions + box(30f, 30f, 4f, z = 15f).positions)

    private fun box(w: Float, d: Float, h: Float, x: Float = 0f, y: Float = 0f, z: Float = 0f): Mesh {
        val v = Array(8) { i ->
            floatArrayOf(x + if (i and 1 != 0) w else 0f, y + if (i and 2 != 0) d else 0f, z + if (i and 4 != 0) h else 0f)
        }
        val quads = listOf(
            intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 6, 7, 3), intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5),
        )
        val out = ArrayList<Float>()
        for (q in quads) {
            for (t in listOf(intArrayOf(q[0], q[1], q[2]), intArrayOf(q[0], q[2], q[3]))) {
                for (i in t) out.addAll(v[i].toList())
            }
        }
        return Mesh(out.toFloatArray())
    }
}
