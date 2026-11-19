package io.github.whoxllm.orcamicro.core.profiles

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A named, flattened user preset. Its identity does not change when re-imported. */
@Serializable
data class UserPreset(val type: PresetType, val preset: JsonObject) {
    val name: String get() = preset.string("name").orEmpty()
    val id: String get() = "${type.orcaName}:${name}"
}

object UserPresets {
    fun merge(existing: List<UserPreset>, incoming: List<UserPreset>): List<UserPreset> =
        (existing + incoming).associateBy { it.id }.values.toList()

    /** Accepts one native JSON preset or a bundle of native presets, in any parent order. */
    fun import(text: String, fallbackType: PresetType, resolve: (PresetType, String) -> JsonObject): List<UserPreset> {
        require(text.length <= 16 * 1024 * 1024) { tr("Набор профилей превышает 16 МБ") }
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error(tr("Нужен JSON OrcaSlicer"))
        val entries = (root["profiles"] as? JsonArray)?.map {
            it as? JsonObject ?: error(tr("Неверный профиль в наборе"))
        } ?: listOf(root)
        require(entries.isNotEmpty() && entries.size <= 2000) { tr("Набор должен содержать от 1 до 2000 профилей") }
        fun type(p: JsonObject) = p.string("type")?.let { value ->
            PresetType.entries.firstOrNull { it.orcaName == value } ?: error(tr("Неизвестный тип профиля: {0}" ,value))
        } ?: fallbackType
        val byId = entries.associateBy { "${type(it).orcaName}:${it.string("name") ?: error(tr("У профиля нет имени"))}" }
        val done = linkedMapOf<String, UserPreset>()
        val visiting = mutableSetOf<String>()
        fun flatten(p: JsonObject): UserPreset {
            val t = type(p)
            val id = "${t.orcaName}:${p.string("name")}"
            done[id]?.let { return it }
            require(visiting.add(id)) { tr("Циклическое наследование: {0}" ,p.string("name")) }
            val flat = PresetExchange.import(p.toString(), t) { parent ->
                byId["${t.orcaName}:${parent}"]?.let { flatten(it).preset } ?: resolve(t, parent)
            }
            visiting.remove(id)
            return UserPreset(t, flat).also { done[id] = it }
        }
        return entries.map { flatten(it) }.distinctBy { it.id }
    }
}
