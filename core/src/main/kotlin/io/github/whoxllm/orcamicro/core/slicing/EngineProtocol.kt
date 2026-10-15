package io.github.whoxllm.orcamicro.core.slicing

import io.github.whoxllm.orcamicro.core.settings.BedType
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.core.settings.PresetEditor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The request the native engine reads; field names match native/bridge/engine.hpp. */
@Serializable
data class EngineRequest(
    @SerialName("resources_dir") val resourcesDir: String,
    @SerialName("work_dir") val workDir: String,
    @SerialName("model_path") val modelPath: String,
    @SerialName("machine_path") val machinePath: String,
    @SerialName("process_path") val processPath: String,
    @SerialName("filament_path") val filamentPath: String,
    @SerialName("output_path") val outputPath: String,
    @SerialName("center_x") val centerX: Double,
    @SerialName("center_y") val centerY: Double,
    @SerialName("log_level") val logLevel: Int = 2,
) {
    fun toJson(): String = EngineJson.encodeToString(serializer(), this)
}

@Serializable
data class EngineResponse(
    val ok: Boolean,
    @SerialName("gcode_path") val gcodePath: String? = null,
    val warnings: List<String> = emptyList(),
    val stage: String? = null,
    val error: String? = null,
) {
    companion object {
        fun parse(json: String): EngineResponse = EngineJson.decodeFromString(serializer(), json)
    }
}

@Serializable
data class EngineProgress(val percent: Int = 0, val text: String = "") {
    companion object {
        fun parse(json: String): EngineProgress = EngineJson.decodeFromString(serializer(), json)
    }
}

internal val EngineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The three standalone presets for one slice, with the user's edits applied. */
class SliceInputs(
    val machine: JsonObject,
    val process: JsonObject,
    val filament: JsonObject,
    val bedType: BedType,
) {
    companion object {
        /** Applies [edits] to flattened presets and pins the bed type that picks the bed temperatures. */
        fun build(machine: JsonObject, process: JsonObject, filament: JsonObject, edits: PresetEdits): SliceInputs {
            val bedType = PresetEditor.bedType(machine, edits)
            val editedProcess = PresetEditor.apply(process, edits.process, bedType)
            return SliceInputs(
                machine = PresetEditor.apply(machine, edits.machine, bedType),
                process = JsonObject(editedProcess + ("curr_bed_type" to JsonPrimitive(bedType.orcaName))),
                filament = PresetEditor.apply(filament, edits.filament, bedType),
                bedType = bedType,
            )
        }
    }
}
