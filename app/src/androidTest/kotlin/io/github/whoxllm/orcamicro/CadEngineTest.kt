package io.github.whoxllm.orcamicro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.cad.CadClient
import io.github.whoxllm.orcamicro.cad.CadDisplayMesh
import io.github.whoxllm.orcamicro.cad.CadSketchEntity
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.mesh.StlReader
import io.github.whoxllm.orcamicro.core.profiles.Catalog
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import io.github.whoxllm.orcamicro.data.ProfileRepository
import io.github.whoxllm.orcamicro.engine.EngineClient
import io.github.whoxllm.orcamicro.engine.EngineEvent
import io.github.whoxllm.orcamicro.engine.EngineResources
import io.github.whoxllm.orcamicro.engine.SliceJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end test of OrcaSlicer's parametric CAD kernel in the :cad process:
 * sketch -> extrude -> binary mesh -> recipe round-trip -> fillet + topo_generation guard ->
 * STEP export/import -> STL export -> slice in :engine.
 */
@RunWith(AndroidJUnit4::class)
class CadEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cadExtrudeRecipeRoundTripStepAndSlice() = runBlocking {
        val workDir = File(context.cacheDir, "cad-test-${System.currentTimeMillis()}").apply { mkdirs() }
        val recipeFile = File(workDir, "design.recipe")
        val stlFile = File(workDir, "design.stl")
        val stepFile = File(workDir, "design.step")
        val stepStlFile = File(workDir, "from-step.stl")
        val cad = CadClient(context)
        try {
            // 1. Open fresh session, add 20x10 rectangle sketch, extrude 5 mm.
            val opened = withTimeout(60_000) { cad.open(workDir, recipeFile) }
            assertTrue("open failed: ${opened.error}", opened.ok)
            assertEquals(0, opened.features.size)

            val sketched = withTimeout(60_000) {
                cad.addSketch(
                    plane = "XY",
                    name = "Base",
                    entities = CadSketchEntity.rectangle(-10.0, -5.0, 10.0, 5.0),
                )
            }
            assertTrue("addSketch failed: ${sketched.error}", sketched.ok)
            val sketchIdx = sketched.featureIndex
            assertTrue(sketchIdx >= 0)

            val extruded = withTimeout(60_000) {
                cad.addExtrude(sketchRef = sketchIdx, distance = 5.0, mode = "new", name = "Block")
            }
            assertTrue("addExtrude failed: ${extruded.error}", extruded.ok)
            assertEquals(1, extruded.bodyCount)
            assertEquals(1000.0, extruded.volumeMm3, 2.0)
            val bounds = requireNotNull(extruded.bounds)
            assertEquals(20f, bounds.sizeX, 0.2f)
            assertEquals(10f, bounds.sizeY, 0.2f)
            assertEquals(5f, bounds.sizeZ, 0.2f)

            // Binary display mesh + B-rep edges are written to disk.
            val meshFile = File(requireNotNull(extruded.meshPath))
            val displayMesh = requireNotNull(CadDisplayMesh.read(meshFile)) { "display.cadmesh unreadable" }
            assertTrue("expected triangles", displayMesh.triangleCount >= 12)
            assertTrue("expected B-rep edge segments", displayMesh.edgeSegmentCount >= 12)

            // 2. Save recipe and reopen -> identical geometry and volume.
            val saved = withTimeout(60_000) { cad.save(recipeFile) }
            assertTrue("save failed: ${saved.error}", saved.ok)
            assertTrue("recipe file missing", recipeFile.isFile && recipeFile.length() > 0)

            val reopened = withTimeout(60_000) { cad.open(workDir, recipeFile) }
            assertTrue("reopen failed: ${reopened.error}", reopened.ok)
            assertEquals(2, reopened.features.size)
            assertEquals(1000.0, reopened.volumeMm3, 2.0)

            // 3. Stale topo_generation is rejected; an impossible fillet rolls back without polluting redo;
            //    a valid fillet succeeds and undo restores the 1000 mm3 block.
            val stale = withTimeout(60_000) {
                cad.addFillet(radius = 1.0, edgeIds = listOf(0), topoGeneration = reopened.topoGeneration + 99)
            }
            assertFalse("stale topo_generation must be rejected", stale.ok)

            val impossible = withTimeout(60_000) {
                cad.addFillet(radius = 999.0, faceGroup = "top", name = "Too Big")
            }
            assertFalse("impossible fillet must fail", impossible.ok)
            val afterFailRedo = withTimeout(60_000) { cad.redo() }
            assertTrue(afterFailRedo.ok)
            assertFalse("failed mutation must not leave anything on the redo stack", afterFailRedo.canRedo)
            assertEquals(1000.0, afterFailRedo.volumeMm3, 2.0)

            val filleted = withTimeout(60_000) {
                cad.addFillet(radius = 1.0, faceGroup = "top", name = "Top Fillet")
            }
            assertTrue("addFillet failed: ${filleted.error}", filleted.ok)
            assertTrue("fillet should slightly reduce volume", filleted.volumeMm3 < 1000.0 && filleted.volumeMm3 > 900.0)

            // Undo restores 1000 mm3 block.
            val undone = withTimeout(60_000) { cad.undo() }
            assertTrue("undo failed: ${undone.error}", undone.ok)
            assertEquals(1000.0, undone.volumeMm3, 2.0)

            // 4. STEP export and STEP -> STL conversion round-trip.
            val exportedStep = withTimeout(60_000) { cad.exportStep(stepFile) }
            assertTrue("exportStep failed: ${exportedStep.error}", exportedStep.ok)
            assertTrue("STEP file missing", stepFile.isFile && stepFile.length() > 100)

            val converted = withTimeout(60_000) { cad.stepToStl(stepFile, stepStlFile) }
            assertTrue("stepToStl failed: ${converted.error}", converted.ok)
            val stepMesh = StlReader.read(stepStlFile.readBytes())
            assertTrue("converted STEP STL has triangles", stepMesh.triangleCount >= 12)

            // 5. Export STL and slice through :engine service.
            val exportedStl = withTimeout(60_000) { cad.exportStl(stlFile) }
            assertTrue("exportStl failed: ${exportedStl.error}", exportedStl.ok)
            val mesh = StlReader.read(stlFile.readBytes())
            assertTrue(mesh.triangleCount >= 12)

            ActivityScenario.launch(MainActivity::class.java).use {
                val profiles = ProfileRepository(context.assets)
                val catalog = profiles.catalog
                val printer = catalog.printer("Custom", "MyMarlin 0.4 nozzle")!!
                val process = catalog.defaultProcessFor("Custom", printer)!!
                val filament = catalog.filamentsFor("Custom", printer).first { it.vendor == Catalog.LIBRARY_VENDOR && it.name == "Generic PLA @System" }
                val inputs = SliceInputs.build(
                    profiles.resolve("Custom", printer.name),
                    profiles.resolve("Custom", process),
                    profiles.resolve(filament.vendor, filament.name),
                    PresetEdits(),
                )
                val bed = BedShape.of(inputs.machine)!!
                val placement = Placement(centerX = bed.centerX, centerY = bed.centerY)
                val job = SliceJob.create(context, mesh, placement, inputs)
                val resources = EngineResources.ensure(context)
                val events = withTimeout(5 * 60_000L) {
                    EngineClient(context).slice(job.request(resources, placement).toJson()).toList()
                }
                val last = events.lastOrNull()
                assertTrue("engine gave no result: $events", last is EngineEvent.Finished)
                val resp = (last as EngineEvent.Finished).response
                assertTrue("slicing CAD STL failed: ${resp.error}", resp.ok)
                assertTrue("no G-code written", job.gcode.isFile && job.gcode.length() > 1000)
            }
        } finally {
            cad.close()
            workDir.deleteRecursively()
        }
    }
}
