package io.github.whoxllm.orcamicro.core.settings

import io.github.whoxllm.orcamicro.core.profiles.strings
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetEditorTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val machine = json(
        """{"type":"machine","name":"M","printable_area":["-5x-5","215x-5","215x215","-5x215"],
            "nozzle_diameter":["0.4"],"printable_height":"250","default_bed_type":"Textured PEI Plate"}""",
    )
    private val filament = json(
        """{"type":"filament","name":"F","nozzle_temperature":["210"],"textured_plate_temp":["55"],
            "textured_plate_temp_initial_layer":["60"],"hot_plate_temp":["60"]}""",
    )
    private val process = json("""{"type":"process","name":"P","layer_height":"0.2"}""")

    @Test
    fun `bed size is read from and written to printable_area keeping the origin`() {
        val width = PrintSettings.machine.first { it.key == PseudoKeys.BED_WIDTH }
        assertEquals("220", PresetEditor.read(machine, width, BedType.HIGH_TEMP))
        val edited = PresetEditor.apply(machine, mapOf(PseudoKeys.BED_WIDTH to "300"), BedType.HIGH_TEMP)
        assertEquals(listOf("-5x-5", "295x-5", "295x215", "-5x215"), edited.strings("printable_area"))
    }

    @Test
    fun `per-extruder options stay lists and scalars stay strings`() {
        val edited = PresetEditor.apply(machine, mapOf("nozzle_diameter" to "0.6", "printable_height" to "300"), BedType.HIGH_TEMP)
        assertEquals(listOf("0.6"), edited.strings("nozzle_diameter"))
        assertEquals(JsonPrimitive("300"), edited["printable_height"])
    }

    @Test
    fun `bed temperature edits the set for the chosen bed type`() {
        val edits = PresetEdits(filament = mapOf(PseudoKeys.BED_TEMPERATURE to "65"))
        val inputs = SliceInputs.build(machine, process, filament, edits)
        assertEquals(BedType.TEXTURED_PEI, inputs.bedType)
        assertEquals(listOf("65"), inputs.filament.strings("textured_plate_temp"))
        assertEquals(listOf("60"), inputs.filament.strings("hot_plate_temp"))
        assertEquals(JsonPrimitive("Textured PEI Plate"), inputs.process["curr_bed_type"])
    }

    @Test
    fun `bed type edit overrides the printer default`() {
        val edits = PresetEdits(machine = mapOf(PseudoKeys.BED_TYPE to "High Temp Plate"))
        val inputs = SliceInputs.build(machine, process, filament, edits)
        assertEquals(BedType.HIGH_TEMP, inputs.bedType)
        val bedTemp = PrintSettings.filament.first { it.key == PseudoKeys.BED_TEMPERATURE }
        assertEquals("60", PresetEditor.read(inputs.filament, bedTemp, inputs.bedType))
    }

    @Test
    fun `bed types parse from names and Orca enum numbers`() {
        assertEquals(BedType.TEXTURED_PEI, BedType.fromOrca("4"))
        assertEquals(BedType.COOL, BedType.fromOrca("Cool Plate"))
        assertEquals(null, BedType.fromOrca(""))
    }

    @Test
    fun `numbers are formatted like Orca presets`() {
        assertEquals("220", formatNumber(220.0))
        assertEquals("0.2", formatNumber(0.2))
        assertEquals("-5", formatNumber(-5.0))
        assertEquals("0.125", formatNumber(0.125))
    }
}
