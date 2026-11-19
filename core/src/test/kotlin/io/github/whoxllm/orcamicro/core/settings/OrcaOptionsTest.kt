package io.github.whoxllm.orcamicro.core.settings

import io.github.whoxllm.orcamicro.core.profiles.PresetExchange
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import kotlinx.serialization.json.*
import kotlin.test.*

class OrcaOptionsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `catalog covers each pinned preset option list without duplicate keys per type`() {
        assertEquals(638, OrcaOptions.all.size)
        assertEquals(638, OrcaOptions.all.map { it.type to it.key }.toSet().size)
        assertEquals("coFloat", OrcaOptions.all.first { it.key == "layer_height" }.valueType)
        assertTrue(OrcaOptions.all.first { it.key == "nozzle_temperature" }.isList)
        assertTrue(OrcaOptions.all.first { it.key == "layer_height" }.matches("высота слоя"))
        assertTrue(OrcaOptions.all.any { it.key == "filament_retraction_length" && it.isList })
    }

    @Test fun `editing full arrays preserves different extruder values and explicit nil`() {
        val option = OrcaOptions.all.first { it.key == "nozzle_temperature" }
        val value = OrcaOptions.parseValue(option, """["210","225","nil"]""")
        val preset = obj("""{"type":"filament","nozzle_temperature":["200","200"]}""")
        val edited = PresetEditor.apply(preset, emptyMap(), BedType.HIGH_TEMP, mapOf(option.key to value))
        assertEquals(value, edited[option.key])
        assertFailsWith<IllegalArgumentException> { OrcaOptions.parseValue(option, "210,225") }
    }

    @Test fun `imports replace base instead of inheriting leftovers from previous selection`() {
        val imported = PresetExchange.import("""{"type":"machine","name":"My printer","inherits":"Parent","printable_height":"250"}""", PresetType.MACHINE) {
            assertEquals("Parent", it)
            obj("""{"type":"machine","machine_start_gcode":"G28","nozzle_diameter":["0.4"]}""")
        }
        val result = SliceInputs.build(
            obj("""{"machine_end_gcode":"WRONG_OLD_PRINTER"}"""), obj("{}"), obj("{}"),
            PresetEdits(importedMachine = imported),
        )
        assertNull(result.machine["machine_end_gcode"])
        assertEquals(JsonPrimitive("G28"), result.machine["machine_start_gcode"])
        assertNull(result.machine["inherits"])
    }

    @Test fun `JSON import export round trip keeps unknown keys arrays and multiline gcode`() {
        val original = obj("""{"type":"machine","name":"Custom","nozzle_diameter":["0.4","0.6"],"machine_start_gcode":"G28\nG92 E0","future_option":["a","b"]}""")
        val exported = PresetExchange.export(original, PresetType.MACHINE)
        val imported = PresetExchange.import(exported, PresetType.MACHINE) { error("Unexpected parent") }
        for ((key, value) in original) assertEquals(value, imported[key], key)
        val custom = OrcaOptions.forType(PresetType.MACHINE, imported).first { it.key == "future_option" }
        assertTrue(custom.isList)
    }

    @Test fun `wrong profile types unresolved parents and nonfinite numbers cannot silently import`() {
        assertFailsWith<IllegalArgumentException> { PresetExchange.import("""{"type":"filament"}""", PresetType.MACHINE) { obj("{}") } }
        assertFailsWith<IllegalStateException> { PresetExchange.import("""{"inherits":"Missing"}""", PresetType.PROCESS) { error("Missing") } }
        val layer = OrcaOptions.all.first { it.key == "layer_height" }
        assertFailsWith<IllegalArgumentException> { OrcaOptions.parseValue(layer, "NaN") }
        assertEquals(JsonPrimitive("0.2"), OrcaOptions.parseValue(layer, "0,2"))
    }

    @Test fun `raw overrides survive saved edits serialization and reach all three engine presets`() {
        val edits = PresetEdits(
            rawMachine = mapOf("machine_start_gcode" to JsonPrimitive("G28\nG92 E0")),
            rawProcess = mapOf("seam_position" to JsonPrimitive("random")),
            rawFilament = mapOf("nozzle_temperature" to JsonArray(listOf(JsonPrimitive("220"), JsonPrimitive("230")))),
        )
        val restored = Json.decodeFromString(PresetEdits.serializer(), Json.encodeToString(PresetEdits.serializer(), edits))
        val result = SliceInputs.build(obj("{}"), obj("{}"), obj("{}"), restored)
        assertEquals(edits.rawMachine["machine_start_gcode"], result.machine["machine_start_gcode"])
        assertEquals(edits.rawProcess["seam_position"], result.process["seam_position"])
        assertEquals(edits.rawFilament["nozzle_temperature"], result.filament["nozzle_temperature"])
    }

    @Test fun `advanced bed type uses matching temperatures and explicit process choice wins`() {
        val machine = obj("""{"default_bed_type":"High Temp Plate"}""")
        val changed = PresetEdits(rawMachine = mapOf("default_bed_type" to JsonPrimitive("Textured PEI Plate")))
        assertEquals(BedType.TEXTURED_PEI, SliceInputs.build(machine, obj("{}"), obj("{}"), changed).bedType)
        val explicit = changed.copy(rawProcess = mapOf("curr_bed_type" to JsonPrimitive("Cool Plate")))
        assertEquals(BedType.COOL, SliceInputs.build(machine, obj("{}"), obj("{}"), explicit).bedType)
    }

    @Test fun `one value is displayed without JSON syntax but remains an array when edited`() {
        val option = OrcaOptions.all.first { it.key == "nozzle_diameter" }
        val original = Json.parseToJsonElement("""["0.8"]""")
        assertEquals("0.8", OrcaOptions.display(original))
        val draft = JsonArray(listOf(JsonPrimitive("0,6")))
        assertEquals(Json.parseToJsonElement("""["0.6"]"""), OrcaOptions.parseForm(option, draft, original))
    }

    @Test fun `independent fields preserve other extruders nested arrays and multiline strings`() {
        val option = OrcaOptions.all.first { it.key == "nozzle_temperature" }
        val original = Json.parseToJsonElement("""["210","225","nil"]""")
        assertEquals("210 · 225 · По умолчанию", OrcaOptions.display(original))
        val draft = JsonArray(listOf(JsonPrimitive("215"), original.jsonArray[1], original.jsonArray[2]))
        assertEquals(Json.parseToJsonElement("""["215","225","nil"]"""), OrcaOptions.parseForm(option, draft, original))
        val gcode = OrcaOption("machine_start_gcode", PresetType.MACHINE, "G-code", valueType = "coStrings")
        val lines = JsonArray(listOf(JsonPrimitive("G28\n; \"quoted\"")))
        assertEquals("G28\n; \"quoted\"", OrcaOptions.display(lines))
        assertEquals(lines, OrcaOptions.parseForm(gcode, lines, lines))
        val points = OrcaOption("points", PresetType.MACHINE, "Points", valueType = "coPointsGroups")
        val nested = Json.parseToJsonElement("""[["0x0","220x0"],["0x0","100x0"]]""")
        assertEquals(nested, OrcaOptions.parseForm(points, nested, nested))
        assertEquals("Нет значений", OrcaOptions.display(JsonArray(emptyList())))
    }
}
