package io.github.whoxllm.orcamicro.core.settings

import io.github.whoxllm.orcamicro.core.profiles.first
import io.github.whoxllm.orcamicro.core.profiles.string
import io.github.whoxllm.orcamicro.core.profiles.strings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.max

/** The user's changes on top of the selected presets, as Orca option strings keyed by option (or pseudo key). */
@Serializable
data class PresetEdits(
    val machine: Map<String, String> = emptyMap(),
    val process: Map<String, String> = emptyMap(),
    val filament: Map<String, String> = emptyMap(),
)

/** The printable area of a printer, from Orca's `printable_area` ("0x0", "220x0", ...). */
data class BedShape(val points: List<Pair<Float, Float>>) {
    val minX: Float get() = points.minOf { it.first }
    val maxX: Float get() = points.maxOf { it.first }
    val minY: Float get() = points.minOf { it.second }
    val maxY: Float get() = points.maxOf { it.second }
    val width: Float get() = maxX - minX
    val depth: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f

    fun toOrca(): List<String> = points.map { "${formatNumber(it.first.toDouble())}x${formatNumber(it.second.toDouble())}" }

    companion object {
        /** Accepts Orca's list form (["0x0", "220x0", ...]) and its comma-separated string form ("0x0,220x0,..."). */
        fun parse(values: List<String>): BedShape? {
            val points = values.flatMap { it.split(',') }.mapNotNull { value ->
                val parts = value.split('x', 'X')
                if (parts.size != 2) return@mapNotNull null
                val x = parts[0].trim().toFloatOrNull() ?: return@mapNotNull null
                val y = parts[1].trim().toFloatOrNull() ?: return@mapNotNull null
                x to y
            }
            return if (points.size >= 3) BedShape(points) else null
        }

        fun of(machine: JsonObject): BedShape? = parse(machine.strings("printable_area"))

        fun rectangle(originX: Float, originY: Float, width: Float, depth: Float) = BedShape(
            listOf(originX to originY, originX + width to originY, originX + width to originY + depth, originX to originY + depth),
        )
    }
}

/** Reads and writes the [PrintSettings] on flattened Orca presets. */
object PresetEditor {
    /** The bed type a printer preset starts with: its `default_bed_type`, else a smooth high-temperature plate. */
    fun defaultBedType(machine: JsonObject): BedType = BedType.fromOrca(machine.string("default_bed_type")) ?: BedType.HIGH_TEMP

    fun bedType(machine: JsonObject, edits: PresetEdits): BedType =
        BedType.fromOrca(edits.machine[PseudoKeys.BED_TYPE]) ?: defaultBedType(machine)

    /** Current value of [spec] in [preset] (first entry for per-extruder options), as Orca writes it. */
    fun read(preset: JsonObject, spec: SettingSpec, bedType: BedType): String? = when (spec.key) {
        PseudoKeys.BED_WIDTH -> BedShape.of(preset)?.width?.let { formatNumber(it.toDouble()) }
        PseudoKeys.BED_DEPTH -> BedShape.of(preset)?.depth?.let { formatNumber(it.toDouble()) }
        PseudoKeys.BED_TYPE -> defaultBedType(preset).orcaName
        PseudoKeys.BED_TEMPERATURE -> preset.first(bedType.temperatureKey)
        PseudoKeys.BED_TEMPERATURE_FIRST_LAYER -> preset.first(bedType.firstLayerTemperatureKey)
        // Orca also accepts per-extruder values as one comma-separated string.
        else -> if (spec.perExtruder) preset.first(spec.key)?.substringBefore(',')?.trim() else preset.string(spec.key)
    }

    /** [preset] with [edits] applied. Pseudo keys are expanded into the Orca options they stand for. */
    fun apply(preset: JsonObject, edits: Map<String, String>, bedType: BedType): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(preset)
        for ((key, value) in edits) {
            when (key) {
                PseudoKeys.BED_WIDTH, PseudoKeys.BED_DEPTH, PseudoKeys.BED_TYPE -> Unit
                PseudoKeys.BED_TEMPERATURE -> setList(out, bedType.temperatureKey, value)
                PseudoKeys.BED_TEMPERATURE_FIRST_LAYER -> setList(out, bedType.firstLayerTemperatureKey, value)
                else -> {
                    val spec = PrintSettings.all.firstOrNull { it.key == key }
                    if (spec?.perExtruder == true || out[key] is JsonArray) setList(out, key, value) else out[key] = JsonPrimitive(value)
                }
            }
        }
        val width = edits[PseudoKeys.BED_WIDTH]?.toFloatOrNull()
        val depth = edits[PseudoKeys.BED_DEPTH]?.toFloatOrNull()
        if (width != null || depth != null) {
            val current = BedShape.of(preset)
            val shape = BedShape.rectangle(
                current?.minX ?: 0f, current?.minY ?: 0f,
                width ?: current?.width ?: 220f, depth ?: current?.depth ?: 220f,
            )
            out["printable_area"] = JsonArray(shape.toOrca().map { JsonPrimitive(it) })
        }
        return JsonObject(out)
    }

    private fun setList(out: MutableMap<String, JsonElement>, key: String, value: String) {
        val size = max(1, when (val current = out[key]) {
            is JsonArray -> current.size
            is JsonPrimitive -> current.contentOrNull?.split(',')?.size ?: 1
            else -> 1
        })
        out[key] = JsonArray(List(size) { JsonPrimitive(value) })
    }
}

/** Formats a number the way Orca presets do: "220", "0.2", never "220.0". */
fun formatNumber(value: Double): String {
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
    return "%.4f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
}
