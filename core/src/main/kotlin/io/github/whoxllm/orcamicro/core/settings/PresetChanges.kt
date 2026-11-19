package io.github.whoxllm.orcamicro.core.settings

import kotlinx.serialization.json.*

/** Orca stores numbers both as strings and JSON numbers; formatting alone is not an edit. */
object PresetChanges {
    fun equivalent(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == b -> true
        a is JsonPrimitive && b is JsonPrimitive && a != JsonNull && b != JsonNull -> {
            val number = a.content.toBigDecimalOrNull()
            val other = b.content.toBigDecimalOrNull()
            number != null && other != null && number.compareTo(other) == 0
        }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { equivalent(a[it], b[it]) }
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { equivalent(a[it], b[it]) }
        else -> false
    }
}
