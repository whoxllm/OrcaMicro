package io.github.whoxllm.orcamicro.core.gcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GcodeParserTest {
    private fun parse(text: String) = GcodeParser.parse(text.trimIndent().reader().buffered())

    @Test
    fun `layers, features and travels are split the way Orca marks them`() {
        val toolpaths = parse(
            """
            ; HEADER_BLOCK_START
            G90
            M83
            G1 X5 Y5 E2 ; purge line before the first layer
            ;LAYER_CHANGE
            ;Z:0.2
            ;HEIGHT:0.2
            G1 Z0.2 F600
            G0 X10 Y10
            ;TYPE:Outer wall
            G1 X20 Y10 E0.5
            G1 X20 Y20 E0.5
            ;TYPE:Sparse infill
            G1 X10 Y20 E0.5
            G1 E-0.8 ; retract, no XY move
            ;LAYER_CHANGE
            ;Z:0.4
            ;HEIGHT:0.2
            G1 Z0.4
            G1 X10 Y10 E0.5
            """,
        )
        assertEquals(2, toolpaths.layers.size)
        val first = toolpaths.layers[0]
        assertEquals(0.2f, first.z)
        assertEquals(0.2f, first.height)
        // The purge line from before the first layer marker is shown with layer 1.
        assertEquals(4, first.extrusionCount)
        assertEquals(1, first.travelCount)
        assertEquals(
            listOf(Feature.CUSTOM, Feature.OUTER_WALL, Feature.OUTER_WALL, Feature.SPARSE_INFILL),
            first.features.map { Feature.entries[it.toInt()] },
        )
        // Second outer wall segment: (20,10,0.2) -> (20,20,0.2)
        assertEquals(listOf(20f, 10f, 0.2f, 20f, 20f, 0.2f), first.extrusions.slice(12 until 18))
        assertEquals(0.4f, toolpaths.layers[1].z)
        assertEquals(Feature.SPARSE_INFILL, Feature.entries[toolpaths.layers[1].features[0].toInt()])
        assertEquals(setOf(Feature.OUTER_WALL, Feature.SPARSE_INFILL), toolpaths.features - Feature.CUSTOM)
    }

    @Test
    fun `absolute extrusion and G92 resets are tracked`() {
        val toolpaths = parse(
            """
            M82
            ;LAYER_CHANGE
            ;Z:0.3
            G92 E0
            G1 X10 Y0 E1
            G1 X20 Y0 E1 ; no new filament: a travel
            G1 X30 Y0 E2
            G92 E0
            G1 X40 Y0 E0.5
            """,
        )
        val layer = toolpaths.layers.single()
        assertEquals(3, layer.extrusionCount)
        assertEquals(1, layer.travelCount)
    }

    @Test
    fun `arcs become short segments that end exactly on the target`() {
        val toolpaths = parse(
            """
            M83
            ;LAYER_CHANGE
            ;Z:0.2
            G1 X10 Y0
            ;TYPE:Inner wall
            G3 X0 Y10 I-10 J0 E1
            """,
        )
        val layer = toolpaths.layers.single()
        assertTrue(layer.extrusionCount > 10, "a quarter circle of radius 10 should be split up")
        val last = layer.extrusions.size - 6
        assertEquals(0f, layer.extrusions[last + 3])
        assertEquals(10f, layer.extrusions[last + 4])
    }

    @Test
    fun `statistics come from Orca's comments`() {
        val toolpaths = parse(
            """
            ;LAYER_CHANGE
            ;Z:0.2
            G1 X1 Y1 E1
            ; filament used [mm] = 1234.50
            ; filament used [g] = 3.70
            ; total filament used [g] = 3.71
            ; estimated printing time (normal mode) = 1h 2m 3s
            """,
        )
        assertEquals(1234.5, toolpaths.stats.filamentMillimetres)
        assertEquals(3.71, toolpaths.stats.filamentGrams)
        assertEquals(3723L, toolpaths.stats.printSeconds)
    }

    @Test
    fun `Bambu-style tags are understood too`() {
        val toolpaths = parse(
            """
            ; CHANGE_LAYER
            ; Z_HEIGHT: 0.28
            ; LAYER_HEIGHT: 0.28
            ; FEATURE: Support
            G1 X1 Y1 E1
            """,
        )
        val layer = toolpaths.layers.single()
        assertEquals(0.28f, layer.z)
        assertEquals(Feature.SUPPORT, Feature.entries[layer.features[0].toInt()])
    }

    @Test
    fun `durations parse with any subset of units`() {
        assertEquals(90061L, GcodeParser.parseDuration("1d 1h 1m 1s"))
        assertEquals(45L, GcodeParser.parseDuration(" 45s"))
        assertNull(GcodeParser.parseDuration("soon"))
    }
}
