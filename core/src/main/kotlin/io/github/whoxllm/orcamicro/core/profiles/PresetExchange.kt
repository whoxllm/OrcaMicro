package io.github.whoxllm.orcamicro.core.profiles

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.json.*

/** Import one Orca preset, resolving its parent before it replaces the selected base. */
object PresetExchange {
    fun import(text: String, expectedType: PresetType, resolveParent: (String) -> JsonObject): JsonObject {
        require(text.length <= 4 * 1024 * 1024) { tr("Профиль слишком большой (максимум 4 МБ)") }
        val input = Json.parseToJsonElement(text) as? JsonObject ?: error(tr("Нужен JSON-профиль OrcaSlicer"))
        val type = input.string("type")
        require(type == null || type == expectedType.orcaName) { tr("Это профиль {0}; откройте соответствующую вкладку" ,type) }
        val parent = input.string("inherits").orEmpty()
        val base = if (parent.isBlank()) emptyMap() else resolveParent(parent).also {
            require(it.string("type") == null || it.string("type") == expectedType.orcaName) { tr("Тип родительского профиля не совпадает") }
        }
        val result = (base + input).toMutableMap()
        PresetResolver.BOOKKEEPING_KEYS.forEach { result.remove(it) }
        result["type"] = JsonPrimitive(expectedType.orcaName)
        result["name"] = JsonPrimitive(input.string("name") ?: "Imported ${expectedType.orcaName}")
        result["from"] = JsonPrimitive("User")
        return JsonObject(result)
    }

    fun export(preset: JsonObject, type: PresetType): String = Json { prettyPrint = true }.encodeToString(
        JsonObject.serializer(), JsonObject(preset - PresetResolver.BOOKKEEPING_KEYS.toSet() + mapOf(
            "type" to JsonPrimitive(type.orcaName), "from" to JsonPrimitive("User"),
        )),
    )
}
