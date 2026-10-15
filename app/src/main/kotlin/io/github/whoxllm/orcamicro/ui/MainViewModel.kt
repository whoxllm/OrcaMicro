package io.github.whoxllm.orcamicro.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.whoxllm.orcamicro.core.gcode.GcodeParser
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.mesh.StlFormatException
import io.github.whoxllm.orcamicro.core.mesh.StlReader
import io.github.whoxllm.orcamicro.core.profiles.Catalog
import io.github.whoxllm.orcamicro.core.profiles.PresetRef
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.profiles.first
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.core.slicing.EngineResponse
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import io.github.whoxllm.orcamicro.data.PresetSelection
import io.github.whoxllm.orcamicro.data.ProfileRepository
import io.github.whoxllm.orcamicro.data.SavedState
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.engine.EngineClient
import io.github.whoxllm.orcamicro.engine.EngineEvent
import io.github.whoxllm.orcamicro.engine.EngineResources
import io.github.whoxllm.orcamicro.engine.SliceJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Raw (unedited) presets for the current selection. */
private class ResolvedPresets(val selection: PresetSelection, val machine: JsonObject, val process: JsonObject, val filament: JsonObject)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val profiles = ProfileRepository(application.assets)
    private val store = SettingsStore(application)
    private val engine = EngineClient(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var presets: ResolvedPresets? = null
    private var placementJob: Job? = null
    private var sliceJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    // ---- loading -------------------------------------------------------------------------

    private suspend fun load() {
        try {
            val saved = withContext(Dispatchers.IO) { store.load() }
            val catalog = withContext(Dispatchers.IO) { profiles.catalog }
            val selection = saved.selection?.takeIf { isValid(catalog, it) } ?: defaultSelection(catalog)
            val resolved = withContext(Dispatchers.IO) { resolve(selection) }
            presets = resolved
            val firstRun = saved.selection == null
            _state.update {
                it.copy(
                    ready = true,
                    catalog = catalog,
                    selection = selection,
                    edits = if (firstRun) PresetEdits() else saved.edits,
                    screen = if (firstRun) Screen.PRESETS else Screen.PLATE,
                )
            }
            refreshInputs()
            restoreModel(saved)
        } catch (e: Exception) {
            _state.update { it.copy(ready = true, loadError = "Could not load the printer profiles: ${e.message}") }
        }
    }

    private suspend fun restoreModel(saved: SavedState) {
        val name = saved.modelName ?: return
        val mesh = withContext(Dispatchers.IO) {
            runCatching { StlReader.read(store.modelFile.readBytes()) }.getOrNull()
        } ?: return
        setModel(name, mesh, saved.placement ?: centeredPlacement())
    }

    private fun isValid(catalog: Catalog, selection: PresetSelection): Boolean {
        val printer = catalog.printer(selection.vendor, selection.printer) ?: return false
        return selection.process in catalog.processesFor(selection.vendor, printer) &&
            selection.filament in catalog.filamentsFor(selection.vendor, printer)
    }

    private fun defaultSelection(catalog: Catalog): PresetSelection {
        val vendor = catalog.vendor("Custom")?.takeIf { it.printers.isNotEmpty() } ?: catalog.printerVendors.first()
        val printer = vendor.printers.firstOrNull { it.name == "MyMarlin 0.4 nozzle" } ?: vendor.printers.first()
        return selectionFor(catalog, vendor.name, printer.name)
    }

    private fun selectionFor(catalog: Catalog, vendor: String, printerName: String): PresetSelection {
        val printer = requireNotNull(catalog.printer(vendor, printerName))
        val filaments = catalog.filamentsFor(vendor, printer)
        val process = requireNotNull(catalog.defaultProcessFor(vendor, printer)) { "$printerName has no process presets" }
        val filament = printer.defaultFilament?.takeIf { it in filaments }
            ?: filaments.firstOrNull { it.name == "Generic PLA @System" }
            ?: filaments.first()
        return PresetSelection(vendor, printerName, process, filament)
    }

    private fun resolve(selection: PresetSelection) = ResolvedPresets(
        selection = selection,
        machine = profiles.resolve(selection.vendor, selection.printer),
        process = profiles.resolve(selection.vendor, selection.process),
        filament = profiles.resolve(selection.filament.vendor, selection.filament.name),
    )

    /** Recomputes the edited presets and everything derived from the printer (bed, fit). */
    private fun refreshInputs() {
        val resolved = presets ?: return
        val edits = _state.value.edits
        val inputs = SliceInputs.build(resolved.machine, resolved.process, resolved.filament, edits)
        val bed = BedShape.of(inputs.machine) ?: BedShape.rectangle(0f, 0f, 220f, 220f)
        val height = inputs.machine.first("printable_height")?.toFloatOrNull() ?: 250f
        _state.update { state ->
            state.copy(
                inputs = inputs,
                bed = bed,
                printableHeight = height,
                model = state.model?.let { withFit(it, bed, height) },
            )
        }
    }

    // ---- presets and settings ------------------------------------------------------------

    fun selectPrinter(vendor: String, printer: String) {
        val catalog = _state.value.catalog ?: return
        applySelection(selectionFor(catalog, vendor, printer), PresetEdits())
    }

    fun selectProcess(name: String) {
        val selection = _state.value.selection ?: return
        applySelection(selection.copy(process = name), _state.value.edits.copy(process = emptyMap()))
    }

    fun selectFilament(ref: PresetRef) {
        val selection = _state.value.selection ?: return
        applySelection(selection.copy(filament = ref), _state.value.edits.copy(filament = emptyMap()))
    }

    private fun applySelection(selection: PresetSelection, edits: PresetEdits) {
        viewModelScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { resolve(selection) }
                presets = resolved
                _state.update { it.copy(selection = selection, edits = edits) }
                refreshInputs()
                persist()
            } catch (e: Exception) {
                showMessage("Could not load that preset: ${e.message}")
            }
        }
    }

    fun setEdit(type: PresetType, key: String, value: String?) {
        _state.update { state ->
            fun Map<String, String>.with() = if (value == null) this - key else this + (key to value)
            val edits = state.edits
            state.copy(
                edits = when (type) {
                    PresetType.MACHINE -> edits.copy(machine = edits.machine.with())
                    PresetType.PROCESS -> edits.copy(process = edits.process.with())
                    PresetType.FILAMENT -> edits.copy(filament = edits.filament.with())
                },
            )
        }
        refreshInputs()
        persist()
    }

    fun resetEdits(type: PresetType) {
        _state.update { state ->
            val edits = state.edits
            state.copy(
                edits = when (type) {
                    PresetType.MACHINE -> edits.copy(machine = emptyMap())
                    PresetType.PROCESS -> edits.copy(process = emptyMap())
                    PresetType.FILAMENT -> edits.copy(filament = emptyMap())
                },
            )
        }
        refreshInputs()
        persist()
    }

    /** The unedited preset value, to show what a reset would go back to. */
    fun presetFor(type: PresetType): JsonObject? = when (type) {
        PresetType.MACHINE -> presets?.machine
        PresetType.PROCESS -> presets?.process
        PresetType.FILAMENT -> presets?.filament
    }

    // ---- model ---------------------------------------------------------------------------

    fun openModel(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, mesh) = withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw StlFormatException("Could not open the file")
                    val mesh = StlReader.read(bytes)
                    store.modelFile.writeBytes(bytes)
                    displayName(uri) to mesh
                }
                setModel(name, mesh, centeredPlacement())
                _state.update { it.copy(lastResult = null) }
                persist()
            } catch (e: StlFormatException) {
                showMessage("That file is not a usable STL: ${e.message}")
            } catch (e: OutOfMemoryError) {
                showMessage("That model is too large to load on this device")
            } catch (e: Exception) {
                showMessage("Could not open the model: ${e.message}")
            }
        }
    }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"
    }

    private fun centeredPlacement(): Placement {
        val bed = _state.value.bed
        return Placement(centerX = bed?.centerX ?: 110f, centerY = bed?.centerY ?: 110f)
    }

    private suspend fun setModel(name: String, mesh: Mesh, placement: Placement) {
        val state = _state.value
        val model = withContext(Dispatchers.Default) {
            placedModel(name, mesh, placement, state.bed, state.printableHeight)
        }
        _state.update { it.copy(model = model) }
    }

    fun setPlacement(placement: Placement) {
        val current = _state.value.model ?: return
        _state.update { it.copy(model = current.copy(placement = placement)) }
        placementJob?.cancel()
        placementJob = viewModelScope.launch {
            val state = _state.value
            val model = withContext(Dispatchers.Default) {
                placedModel(current.name, current.mesh, placement, state.bed, state.printableHeight)
            }
            _state.update { it.copy(model = model) }
            persist()
        }
    }

    fun centerModel() {
        val model = _state.value.model ?: return
        val bed = _state.value.bed ?: return
        setPlacement(model.placement.copy(centerX = bed.centerX, centerY = bed.centerY))
    }

    fun removeModel() {
        _state.update { it.copy(model = null, lastResult = null, slice = SliceState.Idle) }
        viewModelScope.launch(Dispatchers.IO) { store.modelFile.delete() }
        persist()
    }

    private fun placedModel(name: String, mesh: Mesh, placement: Placement, bed: BedShape?, height: Float): ModelState {
        val placed = placement.place(mesh)
        val size = placed.bounds().size
        return withFit(ModelState(name, mesh, placement, placed, size, fits = true), bed, height)
    }

    private fun withFit(model: ModelState, bed: BedShape?, height: Float): ModelState {
        if (bed == null) return model
        val b = model.placed.bounds()
        val margin = 0.01f
        val fits = b.min.x >= bed.minX - margin && b.max.x <= bed.maxX + margin &&
            b.min.y >= bed.minY - margin && b.max.y <= bed.maxY + margin &&
            b.max.z <= height + margin
        return model.copy(fits = fits)
    }

    // ---- slicing -------------------------------------------------------------------------

    fun slice() {
        val state = _state.value
        val model = state.model ?: return showMessage("Open an STL file first")
        val inputs = state.inputs ?: return
        if (!model.fits) return showMessage("The model does not fit this printer's build volume. Move or scale it first.")
        if (state.slice is SliceState.Running) return
        sliceJob = viewModelScope.launch {
            _state.update { it.copy(slice = SliceState.Running(0, "Preparing")) }
            val job = withContext(Dispatchers.IO) { SliceJob.create(app, model.mesh, model.placement, inputs) }
            val resources = withContext(Dispatchers.IO) { EngineResources.ensure(app) }
            var response: EngineResponse? = null
            var crashed = false
            engine.slice(job.request(resources, model.placement).toJson()).collect { event ->
                when (event) {
                    is EngineEvent.Progress -> _state.update {
                        it.copy(slice = SliceState.Running(event.progress.percent, event.progress.text))
                    }
                    is EngineEvent.Finished -> {
                        response = event.response
                    }
                    EngineEvent.Crashed -> {
                        crashed = true
                    }
                }
            }
            val result = response
            when {
                crashed -> fail("The slicing engine stopped unexpectedly.", job.logTail())
                result == null -> fail("The slicing engine did not answer.", job.logTail())
                !result.ok -> fail(explain(result), job.logTail())
                else -> showPreview(job, result, model.name)
            }
        }
    }

    private suspend fun showPreview(job: SliceJob, result: EngineResponse, modelName: String) {
        _state.update { it.copy(slice = SliceState.Running(100, "Preparing preview")) }
        val gcode = result.gcodePath?.let { File(it) }?.takeIf { it.isFile } ?: job.gcode
        try {
            val toolpaths = withContext(Dispatchers.Default) { gcode.bufferedReader().use { GcodeParser.parse(it) } }
            val done = SliceState.Done(gcode, toolpaths, result.warnings, modelName)
            _state.update { it.copy(slice = SliceState.Idle, lastResult = done, screen = Screen.PREVIEW) }
        } catch (e: OutOfMemoryError) {
            fail("The G-code was written but is too large to preview on this device. You can still export it.", null)
        }
    }

    private fun explain(result: EngineResponse): String {
        val stage = when (result.stage) {
            "config" -> "The print settings were rejected"
            "model" -> "The model could not be read"
            "validate" -> "OrcaSlicer cannot print this"
            "export" -> "Writing the G-code failed"
            else -> "Slicing failed"
        }
        return "$stage: ${result.error ?: "unknown error"}"
    }

    private fun fail(message: String, details: String?) {
        _state.update { it.copy(slice = SliceState.Failed(message, details)) }
    }

    fun cancelSlice() {
        sliceJob?.cancel()
        sliceJob = null
        _state.update { it.copy(slice = SliceState.Idle) }
    }

    fun dismissSliceError() {
        _state.update { it.copy(slice = SliceState.Idle) }
    }

    fun exportGcode(uri: Uri) {
        val result = _state.value.lastResult ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out -> result.gcode.inputStream().use { it.copyTo(out) } }
                        ?: throw IllegalStateException("Could not open the destination")
                }
                showMessage("G-code saved")
            } catch (e: Exception) {
                showMessage("Saving failed: ${e.message}")
            }
        }
    }

    // ---- navigation and messages ---------------------------------------------------------

    fun navigate(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun back() = _state.update { it.copy(screen = Screen.PLATE) }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun persist() {
        val state = _state.value
        val saved = SavedState(state.selection, state.edits, state.model?.name, state.model?.placement)
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.save(saved) } }
    }
}
