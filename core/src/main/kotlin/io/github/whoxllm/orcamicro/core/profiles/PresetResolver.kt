package io.github.whoxllm.orcamicro.core.profiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Reads files below OrcaSlicer's resources/profiles folder, e.g. "Creality/machine/x.json". */
fun interface ProfileSource {
    fun read(path: String): String?
}

class DirectoryProfileSource(private val root: File) : ProfileSource {
    override fun read(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()
}

class PresetNotFoundException(message: String) : Exception(message)

/** The three kinds of preset OrcaSlicer combines into one print configuration. */
enum class PresetType(val orcaName: String) {
    MACHINE("machine"), PROCESS("process"), FILAMENT("filament");

    companion object {
        fun of(orcaName: String?): PresetType? = entries.firstOrNull { it.orcaName == orcaName }
    }
}

/**
 * Turns OrcaSlicer's system presets into standalone ones: follows `inherits` up to the root,
 * merges parent settings under child settings, and marks the result as a user preset so the
 * engine loads it as-is.
 */
class PresetResolver(
    private val source: ProfileSource,
    private val catalog: Catalog,
) {
    private val cache = object : LinkedHashMap<String, JsonObject>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>) = size > 256
    }

    /** A flattened copy of [name] from [vendor], ready to hand to the engine. */
    fun resolve(vendor: String, name: String): JsonObject {
        val chain = ArrayList<JsonObject>()
        var current: Pair<String, JsonObject>? = vendor to load(vendor, name)
        val seen = HashSet<String>()
        while (current != null) {
            val (currentVendor, preset) = current
            chain.add(preset)
            val parent = preset.string("inherits").orEmpty()
            if (parent.isEmpty()) break
            if (!seen.add("$currentVendor/$parent")) throw IllegalStateException("Preset inheritance loop at $parent")
            current = findParent(currentVendor, parent)
                ?: throw PresetNotFoundException("'$name' inherits '$parent', which no vendor provides")
        }
        val merged = LinkedHashMap<String, JsonElement>()
        for (preset in chain.asReversed()) merged.putAll(preset)
        for (key in BOOKKEEPING_KEYS) merged.remove(key)
        merged["name"] = JsonPrimitive(name)
        merged["from"] = JsonPrimitive("User")
        return JsonObject(merged)
    }

    private fun findParent(childVendor: String, parent: String): Pair<String, JsonObject>? {
        val candidates = listOf(childVendor, Catalog.LIBRARY_VENDOR) + catalog.vendors.map { it.name }
        for (vendor in candidates.distinct()) {
            if (catalog.vendor(vendor)?.presets?.containsKey(parent) == true) return vendor to load(vendor, parent)
        }
        return null
    }

    private fun load(vendor: String, name: String): JsonObject {
        val key = "$vendor/$name"
        cache[key]?.let { return it }
        val path = catalog.vendor(vendor)?.presets?.get(name)
            ?: throw PresetNotFoundException("No preset '$name' from $vendor")
        val text = source.read("$vendor/$path") ?: throw PresetNotFoundException("Missing preset file $vendor/$path")
        val parsed = json.parseToJsonElement(text) as? JsonObject
            ?: throw PresetNotFoundException("Preset file $vendor/$path is not a JSON object")
        cache[key] = parsed
        return parsed
    }

    companion object {
        /** Keys that describe a system preset rather than configure the print. */
        val BOOKKEEPING_KEYS = listOf("inherits", "instantiation", "setting_id", "base_id", "is_custom_defined")

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A JSON value as a string, or null; Orca stores scalars as strings. */
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** A JSON value as a list of strings; Orca stores per-extruder values as string arrays. */
fun JsonObject.strings(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    else -> emptyList()
}

/** The first entry of a per-extruder value, or a plain scalar. */
fun JsonObject.first(key: String): String? = strings(key).firstOrNull()
