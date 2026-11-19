package io.github.whoxllm.orcamicro.core.settings

import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.i18n.ptr
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** The preset option lists, labels and help text from the pinned OrcaSlicer sources. */
@Serializable
data class OrcaOption(
    val key: String,
    val type: PresetType,
    val label: String,
    val labelRu: String = label,
    val category: String = "Other",
    val categoryRu: String = category,
    val description: String = "",
    val descriptionRu: String = description,
    val valueType: String = "coString",
    val unit: String = "",
    val choices: List<String> = emptyList(),
    val choiceLabelsRu: Map<String, String> = emptyMap(),
) {
    val displayLabel:String get()=if(io.github.whoxllm.orcamicro.core.i18n.AppText.parametersEnglish)label else labelRu
    val displayDescription:String get()=if(io.github.whoxllm.orcamicro.core.i18n.AppText.parametersEnglish)description else descriptionRu
    val displayCategory:String get()=if(io.github.whoxllm.orcamicro.core.i18n.AppText.parametersEnglish)category else categoryRu
    fun choiceLabel(value:String):String=if(io.github.whoxllm.orcamicro.core.i18n.AppText.parametersEnglish)value.replace('_',' ').replaceFirstChar{it.uppercase()}else choiceLabelsRu[value] ?: value

    val isList: Boolean get() = valueType in setOf("coFloats", "coInts", "coStrings", "coPercents", "coBools", "coEnums", "coFloatsOrPercents", "coPoints", "coPointsGroups")
    val isGcode: Boolean get() = "gcode" in key
    val isNumber: Boolean get() = valueType in setOf("coFloat", "coInt", "coPercent", "coFloatOrPercent")

    fun matches(query: String): Boolean = query.trim().split(Regex("\\s+")).all { word ->
        listOf(key, label, labelRu, category, categoryRu).any { it.contains(word, ignoreCase = true) }
    }
}

object OrcaOptions {
    val all: List<OrcaOption> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/orca-options.json")) { "Orca option catalog missing" }
        stream.bufferedReader().use { Json.decodeFromString(ListSerializer(OrcaOption.serializer()), it.readText()) }
    }

    fun forType(type: PresetType, preset: JsonObject): List<OrcaOption> {
        val known = all.filter { it.type == type }
        val knownKeys = known.map { it.key }.toSet()
        return known + preset.keys.filter { it !in knownKeys && it !in metadata }.map { key ->
            OrcaOption(key, type, key, category = "From profile", categoryRu = "Из профиля", valueType = if (preset[key] is JsonArray) "coStrings" else "coString")
        }
    }

    val metadata = setOf("name", "from", "type", "version", "inherits", "instantiation", "setting_id", "base_id", "is_custom_defined", "filament_id")

    /** Scalars stay Orca strings. Lists use complete JSON so extruder values stay distinct. */
    fun parseValue(option: OrcaOption, text: String): JsonElement {
        if (option.isList) {
            val array = Json.parseToJsonElement(text) as? JsonArray ?: error(tr("Нужен JSON-массив, например [\"210\", \"215\"]"))
            require(array.all { it is JsonPrimitive || (option.valueType == "coPointsGroups" && it is JsonArray) }) { tr("Недопустимая структура массива") }
            return array
        }
        if (option.isNumber) {
            val number = text.trim().replace(',', '.').removeSuffix("%").toDoubleOrNull()
            require(number != null && number.isFinite()) { tr("Введите конечное число") }
            require(option.valueType != "coInt" || number == Math.rint(number)) { tr("Введите целое число") }
            return JsonPrimitive(text.trim().replace(',', '.'))
        }
        if (option.valueType == "coBool") {
            require(text.lowercase() in setOf("0", "1", "true", "false")) { tr("Выберите включено или выключено") }
            return JsonPrimitive(if (text == "1" || text.equals("true", true)) "1" else "0")
        }
        return JsonPrimitive(text)
    }

    /** Human-readable values; JSON syntax belongs to the saved profile, not the interface. */
    fun display(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonPrimitive -> if (value == JsonNull || value.content == "nil") ptr("По умолчанию") else value.content
        is JsonArray -> value.joinToString(" · ") { display(it) }.ifEmpty { ptr("Нет значений") }
        is JsonObject -> value.entries.joinToString("; ") { "${it.key}: ${display(it.value)}" }
    }

    fun display(option: OrcaOption, value: JsonElement?): String = when(value) {
        is JsonArray -> value.joinToString(" · ") { display(option, it) }
        is JsonPrimitive -> if (scalarOption(option).valueType == "coBool") when(value.content) { "1", "true" -> ptr("Включено"); "0", "false" -> ptr("Выключено"); else -> display(value) } else option.choiceLabel(display(value))
        else -> display(value)
    }

    fun scalarOption(option: OrcaOption): OrcaOption = option.copy(valueType = when (option.valueType) {
        "coFloats" -> "coFloat"
        "coInts" -> "coInt"
        "coPercents" -> "coPercent"
        "coFloatsOrPercents" -> "coFloatOrPercent"
        "coBools" -> "coBool"
        "coEnums" -> "coEnum"
        "coPoints", "coPointsGroups" -> "coPoint"
        "coStrings" -> "coString"
        else -> option.valueType
    })

    fun formValue(option: OrcaOption, value: JsonElement?): JsonElement =
        if (option.isList && value !is JsonArray) JsonArray(listOf(value ?: JsonPrimitive("")))
        else value ?: JsonPrimitive("")

    /** Convert independent form fields back to the exact original container structure. */
    fun parseForm(option: OrcaOption, draft: JsonElement, original: JsonElement?): JsonElement {
        if (draft == original) return draft
        return when (draft) {
            is JsonArray -> JsonArray(draft.mapIndexed { i, item -> parseForm(scalarOption(option), item, (original as? JsonArray)?.getOrNull(i)) })
            is JsonObject -> JsonObject(draft.mapValues { (key, item) -> parseForm(scalarOption(option), item, (original as? JsonObject)?.get(key)) })
            is JsonPrimitive -> {
                // Preserve an inherited nullable value; normal numeric input is still validated.
                if (draft == JsonNull || (draft.content == "nil" && (original as? JsonPrimitive)?.content == "nil")) draft
                else parseValue(scalarOption(option), draft.content)
            }
        }
    }
}
