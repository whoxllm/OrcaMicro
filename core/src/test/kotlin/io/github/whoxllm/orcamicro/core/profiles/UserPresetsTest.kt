package io.github.whoxllm.orcamicro.core.profiles

import kotlinx.serialization.json.*
import io.github.whoxllm.orcamicro.core.settings.OrcaOptions
import kotlin.test.*

class UserPresetsTest {
    @Test fun `bundle resolves parents after children and keeps all materials`() {
        val p = UserPresets.import("""{"profiles":[
            {"type":"filament","name":"Red","inherits":"PETG","default_filament_colour":["#FF0000"]},
            {"type":"filament","name":"Black","inherits":"PETG","default_filament_colour":["#111111"]},
            {"type":"filament","name":"PETG","nozzle_temperature":["240","245"]},
            {"type":"machine","name":"Printer","nozzle_diameter":["0.4"]}
        ]}""", PresetType.PROCESS) { _,_ -> error("Unexpected external parent") }
        assertEquals(4,p.size)
        assertEquals(listOf("240","245"), p.first { it.name=="Red" }.preset["nozzle_temperature"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(p.first().preset["inherits"])
        val updated = p.first().copy(preset = JsonObject(p.first().preset + ("default_filament_colour" to JsonArray(listOf(JsonPrimitive("#00FF00"))))))
        val merged=UserPresets.merge(p,listOf(updated))
        assertEquals(4,merged.size)
        assertEquals(updated,merged.first())
    }
    @Test fun `cycle unknown type and unresolved parent fail before library can change`() {
        assertFailsWith<IllegalArgumentException> { UserPresets.import("""{"profiles":[{"type":"filament","name":"A","inherits":"B"},{"type":"filament","name":"B","inherits":"A"}]}""",PresetType.FILAMENT) { _,_ -> error("Missing") } }
        assertFailsWith<IllegalStateException> { UserPresets.import("""{"type":"wrong","name":"A"}""",PresetType.PROCESS) { _,_ -> error("Missing") } }
        assertFailsWith<IllegalStateException> { UserPresets.import("""{"type":"machine","name":"A","inherits":"Missing"}""",PresetType.MACHINE) { _,_ -> error("Missing") } }
    }
    @Test fun `translated values preserve engine enum keys and raw array types`() {
        val seam=OrcaOptions.all.first { it.key=="seam_position" }
        assertEquals("Случайный",OrcaOptions.display(seam,JsonPrimitive("random")))
        assertEquals(JsonPrimitive("random"),OrcaOptions.parseForm(seam,JsonPrimitive("random"),null))
        val limit=OrcaOptions.all.first { it.key=="machine_max_speed_x" }
        assertTrue(limit.labelRu.startsWith("Максимальная скорость"))
        assertTrue(limit.isList)
        assertEquals(JsonArray(listOf(JsonPrimitive("250"),JsonPrimitive("300"))),OrcaOptions.parseForm(limit,JsonArray(listOf(JsonPrimitive("250"),JsonPrimitive("300"))),null))
    }
}
