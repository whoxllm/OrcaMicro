package io.github.whoxllm.orcamicro.core.settings

import kotlinx.serialization.json.*

/** Literal upstream messages; raw diagnostic details remain available for troubleshooting. */
object Russian {
    private val messages by lazy {
        javaClass.getResourceAsStream("/orca-messages-ru.json")?.bufferedReader()?.use { Json.parseToJsonElement(it.readText()).jsonObject } ?: JsonObject(emptyMap())
    }
    fun text(source: String): String = if(io.github.whoxllm.orcamicro.core.i18n.AppText.english)source else messages[source]?.jsonPrimitive?.content ?: messages[source.trim()]?.jsonPrimitive?.content ?: when(source.trim().removeSuffix("...")) {
        "Preparing", "Processing triangulated mesh" -> "Подготовка модели"
        "Slicing mesh" -> "Нарезка модели"
        "Generating perimeters" -> "Построение стенок"
        "Preparing infill" -> "Подготовка заполнения"
        "Generating infill" -> "Построение заполнения"
        "Generating support material" -> "Построение поддержек"
        "Exporting G-code", "Exporting G-code to file" -> "Запись G-code"
        else -> source
    }
}
