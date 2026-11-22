package io.github.whoxllm.orcamicro.core.plugins

import kotlin.test.*

class PluginHeaderTest {
    @Test fun `reads the four OrcaSlicer 2_5_1 script headers`() {
        val examples = listOf(
            Triple("gcode_stamp", "G-code Stamp", "0.01"),
            Triple("twistify", "Twistify", "0.03"),
            Triple("fuzzy_slices", "Fuzzy Slices", "0.01"),
            Triple("inset", "Inset Every Slice", "0.02"),
        )
        for ((id, name, version) in examples) {
            val script = javaClass.getResource("/plugins/$id.py")!!.readText()
            val header = assertNotNull(PluginHeader.parse(script))
            assertEquals(name, header.name)
            assertEquals(version, header.version)
            assertEquals(if (id == "twistify") "SoftFever" else "OrcaSlicer", header.author)
            assertEquals("slicing-pipeline", header.type)
            assertEquals(">=3.12", header.requiresPython)
            assertTrue(header.description.isNotBlank())
        }
        assertNull(PluginHeader.parse("# [tool.orcaslicer.plugin]\nprint('hello')"))
        assertNull(PluginHeader.parse("# /// script\n# name = 'plain script'\n# ///"))
    }
}
