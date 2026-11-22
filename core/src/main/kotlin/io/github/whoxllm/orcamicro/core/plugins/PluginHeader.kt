package io.github.whoxllm.orcamicro.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Display metadata available before Python inspects the plugin. */
data class PluginHeader(
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val type: String,
    val requiresPython: String,
) {
    companion object {
        private val scalar = Regex("""([\w-]+)\s*=\s*("(?:\\.|[^"\\])*"|'[^']*')\s*(?:#.*)?""")

        fun parse(script: String): PluginHeader? {
            val lines = script.removePrefix("\uFEFF").lineSequence().dropWhile { it.trimEnd() != "# /// script" }
                .drop(1)
            val fields = mutableMapOf<String, String>()
            var section = ""
            var found = false
            var closed = false
            for (line in lines) {
                if (line.trimEnd() == "# ///") {
                    closed = true
                    break
                }
                if (line != "#" && !line.startsWith("# ")) return null
                val value = line.removePrefix("#").trim()
                if (value.startsWith("[")) {
                    section = value.substringBefore('#').trim()
                    if (section == "[tool.orcaslicer.plugin]") found = true
                    continue
                }
                val match = scalar.matchEntire(value) ?: continue
                val (key, literal) = match.destructured
                if (section == "[tool.orcaslicer.plugin]" || section.isEmpty() && key == "requires-python") {
                    fields[key] = if (literal.startsWith("'")) literal.drop(1).dropLast(1)
                    else runCatching { Json.parseToJsonElement(literal).jsonPrimitive.content }.getOrDefault(literal.drop(1).dropLast(1))
                }
            }
            if (!found || !closed) return null
            return PluginHeader(fields["name"].orEmpty(), fields["description"].orEmpty(), fields["author"].orEmpty(),
                fields["version"].orEmpty(), fields["type"].orEmpty(), fields["requires-python"].orEmpty())
        }
    }
}
