package io.github.whoxllm.orcamicro.core.settings

import kotlinx.serialization.json.*
import kotlin.test.*

class PresetChangesTest {
    @Test fun `numeric formatting is ignored without hiding structural or text changes`() {
        fun json(s:String)=Json.parseToJsonElement(s)
        assertTrue(PresetChanges.equivalent(json("[\"0.200\"]"),json("[0.2]")))
        assertFalse(PresetChanges.equivalent(json("[\"0.2\",\"0.3\"]"),json("[\"0.2\"]")))
        assertFalse(PresetChanges.equivalent(json("\"0.2\""),json("[\"0.2\"]")))
        assertFalse(PresetChanges.equivalent(JsonPrimitive("G1 X1"),JsonPrimitive("G1 X2")))
        assertFalse(PresetChanges.equivalent(JsonNull,null))
        assertTrue(PresetChanges.equivalent(json("{\"speed\":\"30.0\"}"),json("{\"speed\":30}")))
        assertFalse(PresetChanges.equivalent(json("{\"speed\":30}"),json("{\"speed\":31}")))
    }
}
