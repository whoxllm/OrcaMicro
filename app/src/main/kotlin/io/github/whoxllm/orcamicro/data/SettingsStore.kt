package io.github.whoxllm.orcamicro.data

import android.content.Context
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.profiles.PresetRef
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Which presets are selected. */
@Serializable
data class PresetSelection(
    val vendor: String,
    val printer: String,
    val process: String,
    val filament: PresetRef,
)

/** Everything OrcaMicro remembers between launches. */
@Serializable
data class SavedState(
    val selection: PresetSelection? = null,
    val edits: PresetEdits = PresetEdits(),
    val modelName: String? = null,
    val placement: Placement? = null,
)

/** Persists [SavedState] as JSON in app storage, and keeps a copy of the current model. */
class SettingsStore(context: Context) {
    private val stateFile = File(context.filesDir, "state.json")
    val modelFile = File(context.filesDir, "model.stl")

    fun load(): SavedState = try {
        if (stateFile.isFile) json.decodeFromString(SavedState.serializer(), stateFile.readText()) else SavedState()
    } catch (e: Exception) {
        SavedState()
    }

    fun save(state: SavedState) {
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(json.encodeToString(SavedState.serializer(), state))
        tmp.renameTo(stateFile)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
