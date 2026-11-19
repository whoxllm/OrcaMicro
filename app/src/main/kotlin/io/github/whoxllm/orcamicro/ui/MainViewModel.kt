package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.coroutines.async
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
import io.github.whoxllm.orcamicro.core.profiles.PresetExchange
import io.github.whoxllm.orcamicro.core.profiles.first
import io.github.whoxllm.orcamicro.core.profiles.string
import io.github.whoxllm.orcamicro.core.network.PrinterConnection
import io.github.whoxllm.orcamicro.core.network.MoonrakerClient
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.core.slicing.EngineResponse
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import io.github.whoxllm.orcamicro.data.PresetSelection
import io.github.whoxllm.orcamicro.data.ProfileRepository
import io.github.whoxllm.orcamicro.data.SavedState
import io.github.whoxllm.orcamicro.data.CatalogCredentialStore
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.engine.EngineClient
import io.github.whoxllm.orcamicro.engine.EngineEvent
import io.github.whoxllm.orcamicro.engine.EngineResources
import io.github.whoxllm.orcamicro.engine.SliceJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import io.github.whoxllm.orcamicro.core.profiles.UserPreset
import io.github.whoxllm.orcamicro.core.profiles.UserPresets
import io.github.whoxllm.orcamicro.core.settings.RenderOptions
import io.github.whoxllm.orcamicro.core.settings.Russian
import java.io.File
import io.github.whoxllm.orcamicro.core.network.*
import io.github.whoxllm.orcamicro.core.mesh.ModelFiles
import io.github.whoxllm.orcamicro.core.mesh.StlWriter
import kotlinx.coroutines.CancellationException
import java.util.UUID
import io.github.whoxllm.orcamicro.data.ProjectStore
import io.github.whoxllm.orcamicro.data.ProjectDocument
import io.github.whoxllm.orcamicro.render.ModelThumbnail
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/** Raw (unedited) presets for the current selection. */
private class ResolvedPresets(val selection: PresetSelection, val machine: JsonObject, val process: JsonObject, val filament: JsonObject)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val profiles = ProfileRepository(application.assets)
    private val store = SettingsStore(application)
    private val projects = ProjectStore(application)
    private val engine = EngineClient(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var presets: ResolvedPresets? = null
    private var selectionJob: Job? = null
    private var selectionBackup: UiState? = null
    private var placementJob: Job? = null
    private var sliceJob: Job? = null
    private data class SaveRequest(val saved:SavedState?=null,val project:ProjectDocument?=null,val mesh:Mesh?=null,val barrier:CompletableDeferred<Unit>?=null)
    private val saves = Channel<SaveRequest>(Channel.UNLIMITED)
    private val pendingProjectSaves=AtomicInteger()
    private var saveError:Exception?=null
    private var telemetryClient: PrinterClient? = null
    private var telemetryConnection: PrinterConnection? = null
    private var historyAt = 0L
    private var uploadedConnection: PrinterConnection? = null
    private var pendingSetup = false
    private var catalogJob: Job? = null
    private var printSetupReturn = false
    private val credentialStore = CatalogCredentialStore(application)
    private var pendingExternalLink:String?=null
    private var pendingExternalModel:Uri?=null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (request in saves) {
                try {
                    request.saved?.let(store::save)
                    if(request.project!=null && request.mesh!=null) {
                        val recent=projects.save(request.project,request.mesh)
                        _state.update { it.copy(recentProjects=recent) }
                    }
                    if(request.saved!=null)saveError=null
                } catch(e:Exception) { saveError=e;showMessage(tr("Не удалось сохранить проект или настройки: {0}",e.message)) }
                finally {
                    if(request.project!=null) { val remaining=pendingProjectSaves.decrementAndGet();_state.update { it.copy(projectSaving=remaining>0) } }
                    request.barrier?.let { if(saveError==null)it.complete(Unit)else it.completeExceptionally(requireNotNull(saveError)) }
                }
            }
        }
        viewModelScope.launch { load() }
    }

    // ---- loading -------------------------------------------------------------------------

    private suspend fun load() {
        try {
            val saved = withContext(Dispatchers.IO) { store.load() }
            val recent=withContext(Dispatchers.IO) { projects.list() }
            io.github.whoxllm.orcamicro.core.i18n.AppText.parameterLanguage=saved.parameterLanguage
            val cacheDays=saved.modelCacheDays.takeIf { it in ModelCache.durations } ?: 7
            withContext(Dispatchers.IO) { ModelCache.clean(File(app.filesDir,"library"),cacheDays) }
            val apiCredentials = withContext(Dispatchers.IO) { runCatching { credentialStore.load() }.getOrDefault(CatalogCredentials()) }
            val catalog = withContext(Dispatchers.IO) { profiles.catalog }
            val selection = saved.selection?.takeIf { isValid(catalog, it) } ?: defaultSelection(catalog)
            val resolved = withContext(Dispatchers.IO) { resolve(selection) }
            presets = resolved
            val firstRun = saved.selection == null
            val printers = saved.printers.ifEmpty { if(saved.connection.apiUrl.isBlank()) emptyList() else listOf(SavedPrinter("legacy", saved.edits.importedMachine?.string("name") ?: selection.printer, saved.connection, saved.edits.importedMachine?.string("name"))) }
            val active = printers.firstOrNull { it.id == saved.activePrinterId } ?: printers.firstOrNull { it.connection == saved.connection } ?: printers.firstOrNull()
            _state.update {
                it.copy(
                    recentProjects=recent,
                    activeProjectId=saved.activeProjectId?.takeIf { id->recent.any { p->p.document.id==id } },
                    catalogCredentials = apiCredentials,
                    activeModelCard = saved.libraryModels.firstOrNull { it.id==saved.activeModelId },
                    catalog = catalog,
                    selection = selection,
                    edits = if (firstRun) PresetEdits() else saved.edits,
                    favorites = saved.favorites,
                    connection = active?.connection ?: saved.connection,
                    printers = printers,
                    activePrinterId = active?.id,
                    libraryModels = saved.libraryModels.map { model->if(model.localFile?.let { name->File(app.filesDir,"library/${name}").isFile }==true)model else model.copy(localFile=null) },
                    modelCacheDays = cacheDays,
                    modelCacheBytes = File(app.filesDir,"library").listFiles().orEmpty().sumOf { it.length() },
                    userPresets = UserPresets.merge(saved.userPresets, listOfNotNull(
                        saved.edits.importedMachine?.let { UserPreset(PresetType.MACHINE, it) },
                        saved.edits.importedProcess?.let { UserPreset(PresetType.PROCESS, it) },
                        saved.edits.importedFilament?.let { UserPreset(PresetType.FILAMENT, it) })),
                    renderOptions = saved.renderOptions,
                    parameterLanguage = saved.parameterLanguage,
                    temperatureWindowMinutes=saved.temperatureWindowMinutes.takeIf{it in listOf(1,5,10)} ?: 10,
                    telemetryIntervalSeconds=saved.telemetryIntervalSeconds.takeIf{it in listOf(1,5,10,30)} ?: 5,
                    screen = Screen.HOME,
                )
            }
            refreshInputs()
            restoreModel(saved)
            _state.update { it.copy(ready=true) }
            if(_state.value.model!=null)persist()
            pendingExternalModel?.let { pendingExternalModel=null;openModel(it) }
            pendingExternalLink?.let { pendingExternalLink=null;openModelLink(it) }
        } catch (e: Exception) {
            _state.update { it.copy(ready = true, loadError = tr("Не удалось загрузить профили принтера: {0}" ,e.message)) }
        }
    }

    private suspend fun restoreModel(saved: SavedState) {
        val name = saved.modelName ?: return
        val mesh = withContext(Dispatchers.IO) {
            runCatching { StlReader.read(store.modelFile.readBytes()) }.getOrNull()
        } ?: return
        setModel(name, mesh, saved.placement ?: centeredPlacement())
        _state.update { it.copy(activeProjectId=it.activeProjectId ?: UUID.randomUUID().toString()) }
    }

    private fun isValid(catalog: Catalog, selection: PresetSelection): Boolean {
        val printer = catalog.printer(selection.vendor, selection.printer) ?: return false
        return selection.process in catalog.processesFor(selection.vendor, printer) &&
            selection.filament in catalog.filamentsFor(selection.vendor, printer)
    }

    private fun defaultSelection(catalog: Catalog): PresetSelection {
        val vendor = catalog.vendor("Custom")?.takeIf { it.printers.isNotEmpty() } ?: catalog.printerVendors.first()
        val printer = vendor.printers.firstOrNull { it.name == "MyKlipper 0.4 nozzle" } ?: vendor.printers.first()
        return selectionFor(catalog, vendor.name, printer.name)
    }

    private fun selectionFor(catalog: Catalog, vendor: String, printerName: String): PresetSelection {
        val printer = requireNotNull(catalog.printer(vendor, printerName))
        val filaments = catalog.filamentsFor(vendor, printer)
        val process = requireNotNull(catalog.defaultProcessFor(vendor, printer)) { "${printerName} has no process presets" }
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
        if (resolved.selection != _state.value.selection) return
        val edits = _state.value.edits
        val baseline = SliceInputs.build(resolved.machine, resolved.process, resolved.filament, edits.copy(machine=emptyMap(), process=emptyMap(), filament=emptyMap(), rawMachine=emptyMap(), rawProcess=emptyMap(), rawFilament=emptyMap()))
        val inputs = SliceInputs.build(resolved.machine, resolved.process, resolved.filament, edits)
        val bed = BedShape.of(inputs.machine) ?: BedShape.rectangle(0f, 0f, 220f, 220f)
        val height = inputs.machine.first("printable_height")?.toFloatOrNull() ?: 250f
        _state.update { state ->
            val changed=state.inputs?.let { it.machine!=inputs.machine || it.process!=inputs.process || it.filament!=inputs.filament }==true
            state.copy(
                inputs = inputs,
                changedPresetKeys = PresetType.entries.associateWith { type ->
                    val current = when(type) { PresetType.MACHINE -> inputs.machine; PresetType.PROCESS -> inputs.process; PresetType.FILAMENT -> inputs.filament }
                    val original = when(type) { PresetType.MACHINE -> baseline.machine; PresetType.PROCESS -> baseline.process; PresetType.FILAMENT -> baseline.filament }
                    (current.keys + original.keys).filterTo(mutableSetOf()) { !io.github.whoxllm.orcamicro.core.settings.PresetChanges.equivalent(current[it], original[it]) }.also {
                        if(type==PresetType.MACHINE && inputs.bedType != baseline.bedType && io.github.whoxllm.orcamicro.core.settings.PseudoKeys.BED_TYPE in edits.machine) it.add(io.github.whoxllm.orcamicro.core.settings.PseudoKeys.BED_TYPE)
                    }
                },
                lastResult = if(changed) null else state.lastResult,
                externalGcode = if(changed) null else state.externalGcode,
                uploadedFile = if(changed) null else state.uploadedFile,
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
        applySelection(selection.copy(process = name), _state.value.edits.copy(process = emptyMap(), rawProcess = emptyMap(), importedProcess = null))
    }

    fun selectFilament(ref: PresetRef) {
        val selection = _state.value.selection ?: return
        applySelection(selection.copy(filament = ref), _state.value.edits.copy(filament = emptyMap(), rawFilament = emptyMap(), importedFilament = null))
    }

    private fun applySelection(selection: PresetSelection, edits: PresetEdits) {
        selectionJob?.cancel()
        if(_state.value.inputs!=null) selectionBackup=_state.value
        val previous=selectionBackup ?: _state.value
        _state.update { it.copy(selection=selection, edits=edits, inputs=null, changedPresetKeys=emptyMap(), lastResult=null, externalGcode=null, uploadedFile=null) }
        selectionJob = viewModelScope.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { resolve(selection) }
                presets = resolved
                selectionBackup=null
                refreshInputs()
                persist()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(selection=previous.selection, edits=previous.edits, inputs=previous.inputs, changedPresetKeys=previous.changedPresetKeys) }
                showMessage(tr("Не удалось загрузить профиль: {0}" ,e.message))
            }
        }
    }

    fun setEdit(type: PresetType, key: String, value: String?) {
        _state.update { state ->
            fun Map<String, String>.with() = if (value == null) this - key else this + (key to value)
            val edits = state.edits
            state.copy(
                edits = when (type) {
                    PresetType.MACHINE -> edits.copy(machine = edits.machine.with(), rawMachine = edits.rawMachine - key)
                    PresetType.PROCESS -> edits.copy(process = edits.process.with(), rawProcess = edits.rawProcess - key)
                    PresetType.FILAMENT -> edits.copy(filament = edits.filament.with(), rawFilament = edits.rawFilament - key)
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
                    PresetType.MACHINE -> edits.copy(machine = emptyMap(), rawMachine = emptyMap())
                    PresetType.PROCESS -> edits.copy(process = emptyMap(), rawProcess = emptyMap())
                    PresetType.FILAMENT -> edits.copy(filament = emptyMap(), rawFilament = emptyMap())
                },
            )
        }
        refreshInputs()
        persist()
    }

    /** The unedited preset value, to show what a reset would go back to. */
    fun presetFor(type: PresetType): JsonObject? = when (type) {
        PresetType.MACHINE -> _state.value.edits.importedMachine ?: presets?.machine
        PresetType.PROCESS -> _state.value.edits.importedProcess ?: presets?.process
        PresetType.FILAMENT -> _state.value.edits.importedFilament ?: presets?.filament
    }

    fun setRawEdit(type: PresetType, key: String, value: JsonElement?) {
        _state.update { state ->
            val e = state.edits
            fun Map<String, JsonElement>.edited() = if (value == null) this - key else this + (key to value)
            state.copy(edits = when (type) {
                PresetType.MACHINE -> e.copy(rawMachine = e.rawMachine.edited(), machine = e.machine - key)
                PresetType.PROCESS -> e.copy(rawProcess = e.rawProcess.edited(), process = e.process - key)
                PresetType.FILAMENT -> e.copy(rawFilament = e.rawFilament.edited(), filament = e.filament - key)
            })
        }
        refreshInputs()
        persist()
    }

    fun toggleFavorite(type: PresetType, key: String) {
        val id = "${type.name}/${key}"
        _state.update { it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id) }
        persist()
    }

    fun selectUserPreset(preset: UserPreset) {
        _state.update { state -> state.copy(edits = activate(state.edits, preset), lastResult = null, externalGcode=null,uploadedFile=null) }
        refreshInputs()
        persist()
        if (preset.type == PresetType.MACHINE && _state.value.connection.apiUrl.isBlank()) {
            preset.preset.string("print_host")?.takeIf { it.isNotBlank() }?.let { host ->
                saveConnection(PrinterConnection(host, preset.preset.string("print_host_webui").orEmpty().ifBlank { host }))
            }
        }
    }

    private fun activate(e: PresetEdits, p: UserPreset): PresetEdits = when (p.type) {
        PresetType.MACHINE -> e.copy(machine = emptyMap(), rawMachine = emptyMap(), importedMachine = p.preset)
        PresetType.PROCESS -> e.copy(process = emptyMap(), rawProcess = emptyMap(), importedProcess = p.preset)
        PresetType.FILAMENT -> e.copy(filament = emptyMap(), rawFilament = emptyMap(), importedFilament = p.preset)
    }

    fun saveUserPreset(type: PresetType, name: String) {
        val inputs = _state.value.inputs ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        val base = when(type) { PresetType.MACHINE -> JsonObject(inputs.machine + ("default_bed_type" to JsonPrimitive(inputs.bedType.orcaName))); PresetType.PROCESS -> inputs.process; PresetType.FILAMENT -> inputs.filament }
        val p = UserPreset(type, JsonObject(base + mapOf("name" to JsonPrimitive(clean), "from" to JsonPrimitive("User"))))
        _state.update { it.copy(userPresets = UserPresets.merge(it.userPresets, listOf(p))) }
        selectUserPreset(p)
        showMessage(tr("Профиль «{0}» сохранён в Мои профили" ,clean))
    }

    fun deleteUserPreset(p: UserPreset) {
        _state.update { it.copy(userPresets = it.userPresets.filterNot { entry -> entry.id == p.id }) }
        // An active preset remains a standalone base until another preset is selected.
        persist()
    }

    fun importPreset(uri: Uri, type: PresetType) {
        viewModelScope.launch {
            try {
                val before = _state.value
                val imported = withContext(Dispatchers.IO) {
                    val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(16 * 1024 * 1024 + 1)
                        var size = 0
                        while (size < buffer.size) { val n = reader.read(buffer, size, buffer.size - size); if (n < 0) break; size += n }
                        require(size < buffer.size) { tr("Набор превышает 16 МБ") }
                        String(buffer, 0, size)
                    } ?: error(tr("Файл недоступен"))
                    UserPresets.import(text, type) { kind, parent ->
                        before.userPresets.firstOrNull { it.type == kind && it.name == parent }?.preset
                            ?: profiles.importPreset(JsonObject(mapOf("name" to JsonPrimitive("resolve"), "inherits" to JsonPrimitive(parent))).toString(), kind, before.selection?.vendor.orEmpty(), listOfNotNull(presetFor(kind)))
                    }
                }
                _state.update { s ->
                    var e = s.edits
                    PresetType.entries.forEach { kind -> imported.firstOrNull { it.type == kind }?.let { e = activate(e, it) } }
                    s.copy(userPresets = UserPresets.merge(s.userPresets, imported), edits = e, lastResult = null, externalGcode=null)
                }
                refreshInputs()
                persist()
                imported.firstOrNull { it.type == PresetType.MACHINE }?.let { selectUserPreset(it) }
                showMessage(tr("Импортировано профилей: {0}. Они доступны в «Мои профили»" ,imported.size))
            } catch (e: Exception) { showMessage(tr("Не удалось импортировать: {0}" ,e.message)) }
        }
    }

    fun setModelColor(hex: String) {
        val value = hex.removePrefix("#").uppercase()
        if (!value.matches(Regex("[0-9A-F]{6}"))) { return }
        setRawEdit(PresetType.FILAMENT, "default_filament_colour", JsonArray(listOf(JsonPrimitive("#${value}"))))
    }

    fun setRenderOptions(options: RenderOptions) {
        _state.update { it.copy(renderOptions = options) }; persist()
    }

    fun moveModelBy(dx: Float, dy: Float) {
        val m = _state.value.model ?: return
        updatePlacement(m.placement.copy(centerX = m.placement.centerX + dx, centerY = m.placement.centerY + dy), false)
    }

    fun exportPreset(uri: Uri, type: PresetType) {
        val inputs = _state.value.inputs ?: return
        val preset = when (type) {
            PresetType.MACHINE -> inputs.machine
            PresetType.PROCESS -> inputs.process
            PresetType.FILAMENT -> inputs.filament
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(PresetExchange.export(preset, type)) }
                        ?: error(tr("Не удалось открыть место сохранения"))
                }
                showMessage(tr("JSON-профиль сохранён"))
            } catch (e: Exception) {
                showMessage(tr("Ошибка сохранения: {0}" ,e.message))
            }
        }
    }

    // ---- model ---------------------------------------------------------------------------

    fun openModel(uri: Uri) {
        if(!_state.value.ready) { pendingExternalModel=uri;return }
        importModel(uri)
    }

    fun attachCatalogFile(uri:Uri) {
        val s=_state.value
        val card=s.catalogFileModel ?: return
        dismissCatalogFiles()
        importModel(uri,card,s.catalogFileSetup)
    }

    private fun importModel(uri: Uri, attributed:LibraryModel?=null, setup:Boolean=false) {
        if(_state.value.libraryBusy)return showMessage(tr("Дождитесь открытия модели"))
        _state.update { it.copy(libraryBusy=true,libraryPercent=null,libraryProgress=tr("Импорт модели…")) }
        viewModelScope.launch {
            val local="${UUID.randomUUID()}.bin"
            val file=File(app.filesDir,"library/${local}").also { it.parentFile?.mkdirs() }
            try {
                val name=withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input->file.outputStream().use { out->
                        val buffer=ByteArray(64*1024);var total=0
                        while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=ModelFiles.MAX_FILE_BYTES){tr("Файл больше 96 МБ")};out.write(buffer,0,n)}
                    } } ?: error(tr("Файл недоступен"))
                    _state.update { it.copy(libraryPercent=null,libraryProgress=tr("Проверка файла модели…")) }
                    ModelFiles.entries(file)
                    runCatching { app.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    displayName(uri)
                }
                val old=_state.value.libraryModels.firstOrNull { if(attributed!=null)it.id==attributed.id else it.url==uri.toString() }
                val card=attributed?.copy(filename=name,localFile=local) ?: old?.copy(filename=name,localFile=local) ?: LibraryModel(source=null,url=uri.toString(),title=name,filename=name,localFile=local)
                old?.localFile?.takeIf { it.matches(Regex("[a-zA-Z0-9.-]+")) }?.let { File(app.filesDir,"library/${it}").delete() }
                _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,libraryModels=(listOf(card)+it.libraryModels.filterNot { m->m.id==card.id }).take(100),modelCacheBytes=File(app.filesDir,"library").listFiles().orEmpty().sumOf { f->f.length() }) }
                persist();openLibraryModel(card.id,setup)
            } catch(e:Exception) { file.delete();_state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };showMessage(tr("Не удалось открыть модель: {0}" ,e.message)) }
            catch(e:OutOfMemoryError) { file.delete();_state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };showMessage(tr("Модель слишком большая для телефона")) }
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

    fun finishModelMove() { persist() }

    fun setPlacement(placement: Placement) = updatePlacement(placement, true)

    private fun updatePlacement(placement: Placement, save: Boolean) {
        val current = _state.value.model ?: return
        _state.update { it.copy(lastResult=null,externalGcode=null,uploadedFile=null) }
        val previous = current.placement
        if (placementJob?.isActive != true && placement.rotationX == previous.rotationX && placement.rotationY == previous.rotationY && placement.rotationZ == previous.rotationZ && placement.scalePercent == previous.scalePercent) {
            val moved = current.copy(placement = placement, offsetX = current.offsetX+placement.centerX-previous.centerX, offsetY = current.offsetY+placement.centerY-previous.centerY)
            _state.update { with -> with.copy(model = withFit(moved, with.bed, with.printableHeight)) }
            if(save) persist()
            return
        }
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
        _state.update { it.copy(model = null, activeProjectId=null, activeModelCard=null, lastResult = null, externalGcode=null,uploadedFile=null, slice = SliceState.Idle) }
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
        val b = model.placedBounds
        val margin = 0.01f
        val fits = b.min.x + model.offsetX >= bed.minX - margin && b.max.x + model.offsetX <= bed.maxX + margin &&
            b.min.y + model.offsetY >= bed.minY - margin && b.max.y + model.offsetY <= bed.maxY + margin &&
            b.max.z <= height + margin
        return model.copy(fits = fits)
    }

    // ---- slicing -------------------------------------------------------------------------

    fun slice() = sliceInternal(false)

    fun quickSlice() = sliceInternal(true)

    private fun sliceInternal(quickPrint:Boolean) {
        val state = _state.value
        val model = state.model ?: return showMessage(tr("Сначала откройте STL-модель"))
        val inputs = state.inputs ?: return
        if (!model.fits) return showMessage(tr("Модель выходит за границы области печати. Переместите её или уменьшите масштаб."))
        if (state.slice is SliceState.Running) return
        sliceJob = viewModelScope.launch {
            _state.update { it.copy(slice = SliceState.Running(0, tr("Подготовка")),uploadedFile=null) }
            val job = withContext(Dispatchers.IO) { SliceJob.create(app, model.mesh, model.placement, inputs) }
            val resources = withContext(Dispatchers.IO) { EngineResources.ensure(app) }
            var response: EngineResponse? = null
            var crashed = false
            engine.slice(job.request(resources, model.placement).toJson()).collect { event ->
                when (event) {
                    is EngineEvent.Progress -> _state.update {
                        it.copy(slice = SliceState.Running(event.progress.percent, Russian.text(event.progress.text)))
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
                crashed -> fail(tr("Движок нарезки неожиданно остановился."), job.logTail())
                result == null -> fail(tr("Движок нарезки не ответил."), job.logTail())
                !result.ok -> fail(explain(result), job.logTail())
                _state.value.model !== state.model || _state.value.inputs !== state.inputs -> _state.update { it.copy(slice=SliceState.Idle,lastResult=null,externalGcode=null,uploadedFile=null,screen=Screen.PLATE,message=tr("Модель или параметры изменились. Повторите нарезку.")) }
                else -> showPreview(job, result, model, state.modelRgb(), quickPrint)
            }
        }
    }

    private suspend fun showPreview(job: SliceJob, result: EngineResponse, model:ModelState, color:Int, quickPrint:Boolean) {
        _state.update { it.copy(slice = SliceState.Running(100, tr("Подготовка предпросмотра"))) }
        val gcode = result.gcodePath?.let { File(it) }?.takeIf { it.isFile } ?: job.gcode
        try {
            withContext(Dispatchers.IO) { ModelThumbnail.write(gcode,model.mesh,model.placement,color) }
            val toolpaths = withContext(Dispatchers.Default) { gcode.bufferedReader().use { GcodeParser.parse(it) } }
            val done = SliceState.Done(gcode, toolpaths, result.warnings.map(Russian::text), model.name)
            _state.update { it.copy(slice = SliceState.Idle, lastResult = done, screen = if(quickPrint)Screen.PRINT_SETUP else Screen.PREVIEW) }
        } catch (e: OutOfMemoryError) {
            fail(tr("G-code создан, но слишком велик для предпросмотра на этом устройстве."), null)
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { fail(tr("Не удалось подготовить G-code: {0}",e.message),job.logTail()) }
    }

    private fun explain(result: EngineResponse): String {
        val stage = when (result.stage) {
            "config" -> tr("Параметры печати отклонены")
            "model" -> tr("Не удалось прочитать модель")
            "validate" -> tr("OrcaSlicer не может напечатать эту модель")
            "export" -> tr("Ошибка записи G-code")
            else -> tr("Ошибка нарезки")
        }
        return "${stage}: ${Russian.text(result.error ?: tr("неизвестная ошибка"))}"
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
        val source=_state.value.externalGcode?.file ?: _state.value.lastResult?.gcode ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        ?: throw IllegalStateException(tr("Не удалось открыть место сохранения"))
                }
                showMessage(tr("G-code сохранён"))
            } catch (e: Exception) {
                showMessage(tr("Ошибка сохранения: {0}" ,e.message))
            }
        }
    }

    // ---- online models and print preparation ---------------------------------------------

    fun searchModels(source:ModelSource=_state.value.catalogSource,query:String=_state.value.catalogQuery,tags:List<String> = _state.value.catalogTags,more:Boolean=false) {
        val before=_state.value
        if(more&&(before.catalogLoading||before.catalogNext==null))return
        val offset=if(more)before.catalogNext ?: 0 else 0
        catalogJob?.cancel()
        _state.update { it.copy(catalogSource=source,catalogQuery=query,catalogTags=tags,catalogLoading=true,catalogLoaded=true,catalogError=null,
            catalogModels=if(more)it.catalogModels else emptyList(),catalogNext=if(more)it.catalogNext else null,catalogTotal=if(more)it.catalogTotal else 0) }
        val credentials=_state.value.catalogCredentials
        catalogJob=viewModelScope.launch {
            try {
                val page=withContext(Dispatchers.IO) { ModelCatalogClient(credentials).search(source,query,tags,offset) }
                _state.update { it.copy(catalogLoading=false,catalogModels=(if(more)it.catalogModels+page.models else page.models).distinctBy { m->m.id },catalogNext=page.nextOffset,catalogTotal=page.total) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { _state.update { it.copy(catalogLoading=false,catalogError=e.message ?: tr("Каталог недоступен")) } }
        }
    }
    fun saveCatalogCredentials(credentials:CatalogCredentials) {
        try { credentials.validate() }catch(e:Exception) { return showMessage(e.message ?: tr("Проверьте ключи")) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { credentialStore.save(credentials) }
                _state.update { it.copy(catalogCredentials=credentials) };searchModels();showMessage(tr("Настройки API сохранены на устройстве"))
            }catch(e:Exception) { showMessage(tr("Не удалось сохранить ключи: {0}" ,e.message)) }
        }
    }
    private fun keepCatalogCard(model:LibraryModel):LibraryModel {
        val old=_state.value.libraryModels.firstOrNull { it.id==model.id || it.url==model.url }
        val saved=model.copy(id=old?.id ?: model.id,localFile=old?.localFile,filename=old?.filename,tags=(model.tags+old?.tags.orEmpty()).distinct().take(20))
        _state.update { it.copy(libraryModels=(listOf(saved)+it.libraryModels.filterNot { m->m.id==saved.id }).take(100),
            catalogModels=it.catalogModels.map { m->if(m.id==model.id||m.url==model.url)m.copy(author=saved.author,license=saved.license,paid=saved.paid,priceLabel=saved.priceLabel)else m }) };persist()
        return saved
    }
    fun prepareCatalogModel(model:LibraryModel,setup:Boolean) {
        if(_state.value.libraryBusy)return
        val cached=_state.value.libraryModels.firstOrNull { (it.id==model.id || it.url==model.url)&&it.localFile!=null }
        if(cached!=null) { openLibraryModel(cached.id,setup);return }
        _state.update { it.copy(libraryBusy=true,libraryPercent=null,libraryProgress=tr("Получение файлов {0}…" ,model.source?.title.orEmpty()),catalogFileModel=null,catalogFiles=emptyList(),catalogNotice=null) }
        val client=ModelCatalogClient(_state.value.catalogCredentials)
        viewModelScope.launch {
            try {
                val detail=withContext(Dispatchers.IO) { client.details(model.url) }
                val card=keepCatalogCard(detail.model)
                _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,catalogFileModel=card,catalogFiles=detail.files,catalogFileSetup=setup,catalogNotice=detail.notice) }
                if(detail.files.size==1)chooseCatalogFile(detail.files.single())
            }catch(e:CancellationException) { _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };throw e }
            catch(e:Exception) { _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,catalogFileModel=model,catalogFileSetup=setup,catalogNotice=e.message ?: tr("Не удалось получить файлы")) } }
        }
    }
    fun dismissCatalogFiles() = _state.update { it.copy(catalogFiles=emptyList(),catalogFileModel=null,catalogNotice=null) }
    fun chooseCatalogFile(file:CatalogFile) {
        val s=_state.value;val card=s.catalogFileModel ?: return
        if(s.libraryBusy)return
        _state.update { it.copy(libraryBusy=true,libraryPercent=null,libraryProgress=tr("Получение ссылки на файл…"),catalogNotice=null) }
        val client=ModelCatalogClient(s.catalogCredentials)
        viewModelScope.launch {
            try {
                val url=withContext(Dispatchers.IO) { client.downloadUrl(card,file) }
                _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };dismissCatalogFiles()
                downloadModel(url,card.url,card.title,file.name,card.imageUrl,tags=card.tags,setup=s.catalogFileSetup,catalogCard=card)
            }catch(e:CancellationException) { _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };throw e }
            catch(e:Exception) { _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,catalogNotice=e.message ?: tr("Не удалось получить файл")) } }
        }
    }
    fun editQuickPrintPlacement() = _state.update { it.copy(screen=Screen.PLATE,quickPrintPlacement=true) }
    fun finishQuickPrintPlacement() = _state.update { it.copy(screen=Screen.PRINT_SETUP,quickPrintPlacement=false) }
    fun addLibraryCard(url:String,title:String,image:String?=null,tags:List<String> = emptyList()):LibraryModel? {
        val valid=runCatching { java.net.URI(url).let { it.scheme=="https"&&!it.host.isNullOrBlank()&&it.userInfo==null } }.getOrDefault(false)
        if(!valid)return null
        val source=ModelSource.of(url)
        val old=_state.value.libraryModels.firstOrNull { it.url==url }
        val card=old?.copy(title=title.ifBlank { old.title }.take(180), imageUrl=image ?: old.imageUrl,tags=(old.tags+tags).distinct().take(20))
            ?: LibraryModel(source=source,url=url,title=title.ifBlank { source?.title ?: tr("Модель по ссылке") }.take(180),imageUrl=image,tags=tags.take(20))
        _state.update { it.copy(libraryModels=(listOf(card)+it.libraryModels.filterNot { m->m.id==card.id }).take(100)) };persist()
        return card
    }
    fun deleteLibraryCard(id:String) {
        val card=_state.value.libraryModels.firstOrNull { it.id==id } ?: return
        _state.update { it.copy(libraryModels=it.libraryModels.filterNot { m->m.id==id }) };persist()
        card.localFile?.let { name-> File(app.filesDir,"library/${name}").takeIf { name.matches(Regex("[a-zA-Z0-9.-]+")) }?.delete() }
    }
    fun downloadModel(url:String,page:String,title:String,filename:String,image:String?=null,userAgent:String="",tags:List<String> = emptyList(),setup:Boolean=true,catalogCard:LibraryModel?=null) {
        if(_state.value.libraryBusy) return
        val card=(catalogCard?.let(::keepCatalogCard) ?: addLibraryCard(page,title,image,tags)) ?: return showMessage(tr("Проверьте ссылку на модель"))
        _state.update { it.copy(libraryBusy=true,libraryPercent=null,libraryProgress=tr("Скачивание модели…")) }
        viewModelScope.launch {
            val local="${UUID.randomUUID()}.bin"
            val file=File(app.filesDir,"library/${local}").also { it.parentFile?.mkdirs() }
            try {
                withContext(Dispatchers.IO) {
                    var last=-1L
                    ModelDownload.fetch(url,file,headers={ target -> buildMap {
                        putAll(ModelCatalogClient(_state.value.catalogCredentials).headers(target))
                        if(userAgent.isNotBlank()) put("User-Agent",userAgent)
                        put("Referer",page)
                    } },progress={ received,total ->
                        val value=if(total>0)received*100/total else received/(256*1024)
                        if(value!=last) { last=value;_state.update { it.copy(libraryPercent=if(total>0)value.toInt().coerceIn(0,100)else null,libraryProgress=if(total>0)tr("Скачивание: {0}%" ,value) else tr("Получено {0} МБ" ,received/(1024*1024))) } }
                    })
                    _state.update { it.copy(libraryPercent=null,libraryProgress=tr("Проверка файла модели…")) }
                    ModelFiles.entries(file)
                }
                card.localFile?.let { old->if(old.matches(Regex("[a-zA-Z0-9.-]+")))File(app.filesDir,"library/${old}").delete() }
                val saved=card.copy(localFile=local,filename=filename.substringAfterLast('/').substringAfterLast('\\').take(180))
                _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,libraryModels=it.libraryModels.map { m->if(m.id==card.id)saved else m },modelCacheBytes=File(app.filesDir,"library").listFiles().orEmpty().sumOf { f->f.length() }) };persist()
                openLibraryModel(card.id,setup=setup)
            } catch(e:Exception) { file.delete();_state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };showMessage(tr("Не удалось скачать модель: {0}" ,e.message)) }
            catch(e:OutOfMemoryError) { file.delete();_state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };showMessage(tr("Модель слишком большая для телефона")) }
        }
    }
    fun openLibraryModel(id:String,setup:Boolean=false,entry:String?=null) {
        if(_state.value.libraryBusy) return
        val card=_state.value.libraryModels.firstOrNull { it.id==id } ?: return
        val name=card.localFile ?: return if(card.source!=null)prepareCatalogModel(card,setup)else openModelLink(card.url)
        if(!name.matches(Regex("[a-zA-Z0-9.-]+")))return
        val file=File(app.filesDir,"library/${name}")
        _state.update { it.copy(libraryBusy=true,libraryPercent=null,libraryProgress=tr("Открытие модели…"),archiveChoices=emptyList(),pendingLibraryId=null) }
        viewModelScope.launch {
            try {
                val entries=withContext(Dispatchers.IO) { ModelFiles.entries(file) }
                if(entries.size>1 && entry==null) {
                    pendingSetup=setup
                    _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,archiveChoices=entries,pendingLibraryId=id) };return@launch
                }
                val mesh=withContext(Dispatchers.IO) { ModelFiles.read(file,entry).also { mesh->file.setLastModified(System.currentTimeMillis());store.modelFile.outputStream().use { StlWriter.write(mesh,it) } } }
                val project=withContext(Dispatchers.IO){runCatching{ModelFiles.projectSettings(file,entry)}.getOrNull()}
                setModel(entry?.substringAfterLast('/') ?: card.filename ?: card.title,mesh,centeredPlacement())
                _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null,lastResult=null,externalGcode=null,uploadedFile=null,printDialog=false,activeProjectId=UUID.randomUUID().toString(),activeModelCard=card,projectSettings=project,quickPrintPlacement=false,screen=if(setup)Screen.PRINT_SETUP else Screen.PLATE) };persist()
            } catch(e:Exception) { _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };showMessage(tr("Не удалось открыть модель: {0}" ,e.message)) }
            catch(e:OutOfMemoryError) { _state.update { it.copy(libraryBusy=false,libraryPercent=null,libraryProgress=null) };showMessage(tr("Модель слишком большая для телефона")) }
        }
    }
    fun dismissProjectSettings(){_state.update{it.copy(projectSettings=null)}}
    fun applyProjectSettings(machine:Boolean,process:Boolean,filament:Boolean) {
        val s=_state.value;val source=s.projectSettings ?: return
        val options=io.github.whoxllm.orcamicro.core.settings.OrcaOptions.all
        fun fields(type:PresetType)=source.filterKeys{key->options.any{it.key==key&&it.type==type&&!it.isGcode}}.mapValues{(key,value)->if(value is JsonArray&&(type==PresetType.FILAMENT||key=="nozzle_diameter"))JsonArray(value.take(1))else value}
        val edits=s.edits.copy(rawMachine=s.edits.rawMachine+(if(machine)fields(PresetType.MACHINE)else emptyMap()),rawProcess=s.edits.rawProcess+(if(process)fields(PresetType.PROCESS)else emptyMap()),rawFilament=s.edits.rawFilament+(if(filament)fields(PresetType.FILAMENT)else emptyMap()))
        _state.update{it.copy(edits=edits,projectSettings=null,lastResult=null,externalGcode=null,uploadedFile=null)}
        refreshInputs();persist();showMessage(tr("Параметры проекта 3MF применены"))
    }
    fun chooseArchiveEntry(entry:String) { val id=_state.value.pendingLibraryId ?: return;openLibraryModel(id,pendingSetup,entry) }
    fun dismissArchive() = _state.update { it.copy(archiveChoices=emptyList(),pendingLibraryId=null) }
    fun showPrintDialog(show:Boolean) = _state.update { it.copy(printDialog=show) }
    fun openModelLink(text:String) {
        if(!_state.value.ready) { pendingExternalLink=text;return }
        if((text.startsWith("content://")||text.startsWith("file://"))&&_state.value.libraryModels.any { it.url==text }) { openModel(Uri.parse(text));return }
        try {
            when(val link=ModelLinks.parse(text)) {
                is ModelLink.Page -> {
                    navigate(Screen.LIBRARY)
                    val source=ModelSource.of(link.url)!!
                    val id=ModelCatalogClient.modelId(source,link.url)
                    prepareCatalogModel(LibraryModel(id="${source.name.lowercase()}:${id}",source=source,url=link.url,title=source.title,remoteId=id),true)
                }
                is ModelLink.File -> { navigate(Screen.LIBRARY);downloadModel(link.url,link.source ?: link.url,link.name,link.name) }
                null -> { navigate(Screen.LIBRARY);showMessage(tr("Введите запрос или HTTPS-ссылку на модель")) }
            }
        } catch(e:Exception) { showMessage(e.message ?: tr("Проверьте ссылку")) }
    }
    fun setModelTags(id:String,text:String) { _state.update { it.copy(libraryModels=it.libraryModels.map { m->if(m.id==id)m.copy(tags=ModelTags.parse(text))else m }) };persist() }
    fun setCacheDays(days:Int) {
        if(days !in ModelCache.durations)return
        _state.update { it.copy(modelCacheDays=days) };persist();cleanModelCache()
    }
    fun cleanModelCache(all:Boolean=false) {
        if(_state.value.libraryBusy)return
        viewModelScope.launch {
            val days=_state.value.modelCacheDays
            val bytes=withContext(Dispatchers.IO) { ModelCache.clean(File(app.filesDir,"library"),days,all=all) }
            _state.update { it.copy(libraryModels=it.libraryModels.map { m->if(m.localFile?.let { f->File(app.filesDir,"library/${f}").isFile }==true)m else m.copy(localFile=null) },modelCacheBytes=File(app.filesDir,"library").listFiles().orEmpty().sumOf { f->f.length() }) };persist()
            if(all)showMessage(tr("Кэш очищен: {0} МБ. Карточки моделей сохранены." ,bytes/(1024*1024)))
        }
    }
    fun startUploadedPrint() {
        val s=_state.value;val file=s.uploadedFile ?: return
        if(uploadedConnection!=s.connection) return showMessage(tr("Загрузите G-code на выбранный принтер"))
        showPrintDialog(false)
        printerCommand { it.startPrint(file) }
    }

    // ---- navigation and messages ---------------------------------------------------------

    private suspend fun flushSaves() {
        val barrier=CompletableDeferred<Unit>()
        saves.send(SaveRequest(barrier=barrier));barrier.await()
    }

    private suspend fun saveCurrentProject() {
        selectionJob?.join();placementJob?.join()
        persist();flushSaves()
    }

    private fun projectActionAllowed():Boolean {
        val s=_state.value
        if(s.slice is SliceState.Running || s.uploadPercent!=null || s.libraryBusy) {
            showMessage(tr("Дождитесь завершения текущей операции"));return false
        }
        return true
    }

    fun newProject() {
        if(!projectActionAllowed())return
        persist()
        _state.update { it.copy(libraryBusy=true,libraryProgress=tr("Сохранение проекта…")) }
        viewModelScope.launch {
            try {
                saveCurrentProject()
                placementJob?.cancel()
                withContext(Dispatchers.IO) { store.modelFile.delete() }
                _state.update { it.copy(model=null,activeProjectId=null,activeModelCard=null,lastResult=null,externalGcode=null,uploadedFile=null,projectSettings=null,quickPrintPlacement=false,printDialog=false,slice=SliceState.Idle,screen=Screen.PLATE) }
                persist()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { showMessage(tr("Не удалось сохранить проект: {0}",e.message)) }
            finally { _state.update { it.copy(libraryBusy=false,libraryProgress=null) } }
        }
    }

    fun openRecentProject(id:String) {
        if(!projectActionAllowed())return
        persist()
        _state.update { it.copy(libraryBusy=true,libraryProgress=tr("Открытие проекта…")) }
        viewModelScope.launch {
            try {
                saveCurrentProject()
                val opened=withContext(Dispatchers.IO) { projects.open(id) }
                val doc=opened.document
                val selection=doc.selection.takeIf { isValid(requireNotNull(_state.value.catalog),it) }
                    ?: error(tr("Профили этого проекта недоступны"))
                val resolved=withContext(Dispatchers.IO) { resolve(selection) }
                withContext(Dispatchers.IO) { store.modelFile.outputStream().buffered().use { StlWriter.write(opened.mesh,it) } }
                presets=resolved;selectionBackup=null
                _state.update { it.copy(selection=selection,edits=doc.edits,inputs=null,activeProjectId=id,activeModelCard=it.libraryModels.firstOrNull { m->m.id==doc.activeModelId },lastResult=null,externalGcode=null,uploadedFile=null,projectSettings=null,quickPrintPlacement=false,printDialog=false) }
                refreshInputs()
                setModel(doc.name,opened.mesh,doc.placement)
                _state.update { it.copy(screen=Screen.PLATE) }
                persist()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { showMessage(tr("Не удалось открыть проект: {0}",e.message)) }
            catch(e:OutOfMemoryError) { showMessage(tr("Модель слишком большая для телефона")) }
            finally { _state.update { it.copy(libraryBusy=false,libraryProgress=null) } }
        }
    }

    fun deleteRecentProject(id:String) {
        if(!projectActionAllowed())return
        _state.update { it.copy(libraryBusy=true,libraryProgress=tr("Удаление проекта…")) }
        viewModelScope.launch {
            try {
                saveCurrentProject()
                val recent=withContext(Dispatchers.IO) { projects.delete(id) }
                if(_state.value.activeProjectId==id) {
                    withContext(Dispatchers.IO) { store.modelFile.delete() }
                    _state.update { it.copy(model=null,activeProjectId=null,activeModelCard=null,lastResult=null,externalGcode=null,uploadedFile=null,projectSettings=null,slice=SliceState.Idle) }
                }
                _state.update { it.copy(recentProjects=recent) }
                persist()
            } catch(e:Exception) { showMessage(tr("Не удалось удалить проект: {0}",e.message)) }
            finally { _state.update { it.copy(libraryBusy=false,libraryProgress=null) } }
        }
    }

    fun openSettings(type: PresetType = PresetType.PROCESS) {
        if(_state.value.screen==Screen.PRINT_SETUP)printSetupReturn=true
        _state.update { it.copy(screen = Screen.SETTINGS, settingsType = type) }
    }

    fun openPreset(type: PresetType) {
        if(_state.value.screen==Screen.PRINT_SETUP)printSetupReturn=true
        _state.update { it.copy(screen = Screen.PRESETS, presetPicker = type) }
    }

    fun saveConnection(connection: PrinterConnection): Boolean = try {
        val normalized = connection.normalized()
        require(!normalized.apiKey.contains('\n') && !normalized.apiKey.contains('\r')) { tr("Недопустимый API-ключ") }
        val s=_state.value
        val existing=s.printers.firstOrNull { it.id==s.activePrinterId }
        val printer=existing?.copy(connection=normalized) ?: SavedPrinter(name=s.inputs?.machine?.string("name") ?: tr("Принтер"),connection=normalized,machineProfile=s.edits.importedMachine?.string("name"))
        _state.update { it.copy(connection = normalized, printerStatus = null, printers=it.printers.filterNot { p->p.id==printer.id }+printer, activePrinterId=printer.id,
            telemetry=null,telemetryAt=null,temperatureHistory=emptyList(),deviceError=null,uploadedFile=null,lastResult=if(it.connection==normalized)it.lastResult else null) }
        telemetryClient=null;historyAt=0
        persist()
        true
    } catch (e: Exception) { showMessage(e.message ?: tr("Проверьте адрес")); false }

    fun savePrinter(name:String,connection:PrinterConnection,id:String?=null,machineProfile:String?=null):Boolean = try {
        require(name.isNotBlank()) { tr("Укажите имя принтера") }
        val normalized=connection.normalized()
        require(!normalized.apiKey.contains('\n')&&!normalized.apiKey.contains('\r'))
        val printer=SavedPrinter(id ?: UUID.randomUUID().toString(),name.trim().take(100),normalized,machineProfile)
        _state.update { it.copy(printers=it.printers.filterNot { p->p.id==printer.id }+printer) }
        selectSavedPrinter(printer.id);true
    } catch(e:Exception) { showMessage(e.message ?: tr("Проверьте подключение"));false }
    fun selectSavedPrinter(id:String) {
        val p=_state.value.printers.firstOrNull { it.id==id } ?: return
        if(_state.value.deviceBusy || _state.value.uploadPercent!=null) return showMessage(tr("Дождитесь завершения команды"))
        telemetryClient=null;historyAt=0
        _state.update { it.copy(connection=p.connection,activePrinterId=id,telemetry=null,telemetryAt=null,deviceError=null,temperatureHistory=emptyList(),printerStatus=null,connecting=false,uploadedFile=null,lastResult=if(it.activePrinterId==id)it.lastResult else null) }
        p.machineProfile?.let { name->_state.value.userPresets.firstOrNull { it.type==PresetType.MACHINE&&it.name==name }?.let(::selectUserPreset) }
        persist()
    }
    fun deletePrinter(id:String) {
        if(_state.value.deviceBusy||_state.value.uploadPercent!=null) { return }
        _state.update { it.copy(printers=it.printers.filterNot { p->p.id==id }) }
        if(_state.value.activePrinterId==id) {
            val next=_state.value.printers.firstOrNull()
            if(next!=null) {
                selectSavedPrinter(next.id)
            } else {
                _state.update { it.copy(connection=PrinterConnection(),activePrinterId=null,telemetry=null,temperatureHistory=emptyList(),telemetryAt=null,uploadedFile=null,lastResult=null,externalGcode=null) }
            }
        }
        persist()
    }
    suspend fun refreshTelemetry(forceHistory:Boolean=false) {
        val connection=_state.value.connection
        if(connection.apiUrl.isBlank())return
        if(telemetryConnection!=connection || telemetryClient==null) { telemetryConnection=connection;telemetryClient=printerClient(connection);historyAt=0 }
        val client=telemetryClient ?: return
        try {
            val snapshot=withContext(Dispatchers.IO) { client.snapshot() }
            _state.update { if(it.connection==connection)it.copy(telemetry=snapshot,telemetryAt=System.currentTimeMillis(),deviceError=null)else it }
            if(forceHistory || System.currentTimeMillis()-historyAt>=_state.value.telemetryIntervalSeconds*1000L) {
                try {
                    val history=withContext(Dispatchers.IO) { client.temperatureHistory() }
                    historyAt=System.currentTimeMillis()
                    _state.update { if(it.connection==connection)it.copy(temperatureHistory=history,temperatureHistoryError=null)else it }
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { historyAt=System.currentTimeMillis();_state.update { if(it.connection==connection)it.copy(temperatureHistoryError=tr("История температур недоступна: {0}" ,e.message))else it } }
            }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { _state.update { if(it.connection==connection)it.copy(deviceError=e.message ?: tr("Нет связи с Klipper"),telemetry=null)else it } }
    }
    fun setTemperatureWindow(minutes:Int){require(minutes in listOf(1,5,10));_state.update{it.copy(temperatureWindowMinutes=minutes)};persist()}
    fun setTelemetryInterval(seconds:Int){require(seconds in listOf(1,5,10,30));_state.update{it.copy(telemetryIntervalSeconds=seconds)};historyAt=0;persist()}
    private var statisticsJob:Job?=null
    fun loadPrinterStatistics(printerId:String?) {
        statisticsJob?.cancel()
        val printers=_state.value.printers.filter{printerId==null||it.id==printerId}.distinctBy{it.connection.backend to it.connection.apiUrl}
        _state.update{it.copy(statisticsScopeId=printerId,statisticsLoading=true,printerStatistics=emptyList())}
        statisticsJob=viewModelScope.launch {
            val semaphore=kotlinx.coroutines.sync.Semaphore(3)
            val results=kotlinx.coroutines.coroutineScope {printers.map { printer->async {
                semaphore.acquire()
                try {
                    val stats=kotlinx.coroutines.withTimeout(60000){kotlinx.coroutines.runInterruptible(Dispatchers.IO){printerClient(printer.connection).statistics()}}
                    PrinterStatisticsResult(printer,stats,null)
                }catch(e:kotlinx.coroutines.TimeoutCancellationException){PrinterStatisticsResult(printer,null,tr("Превышено время ожидания"))}catch(e:CancellationException){throw e}catch(e:Exception){PrinterStatisticsResult(printer,null,e.message ?: tr("Нет связи с принтером"))}
                finally{semaphore.release()}
            }}.map{it.await()} }
            _state.update{it.copy(statisticsLoading=false,printerStatistics=results,statisticsAt=System.currentTimeMillis())}
        }
    }
    fun readPrinterConfiguration() {
        val s=_state.value;if(s.connection.apiUrl.isBlank()||s.configurationLoading)return
        _state.update{it.copy(configurationLoading=true)}
        viewModelScope.launch {
            try {
                val config=withContext(Dispatchers.IO){printerClient(s.connection).readConfiguration()}
                if(_state.value.connection==s.connection&&_state.value.activePrinterId==s.activePrinterId)_state.update{it.copy(configurationPreview=PrinterConfigurationPreview(s.connection,s.activePrinterId,config))}
            }catch(e:CancellationException){throw e}catch(e:Exception){showMessage(e.message ?: tr("Не удалось считать конфигурацию"))}
            finally{_state.update{it.copy(configurationLoading=false)}}
        }
    }
    fun dismissPrinterConfiguration(){_state.update{it.copy(configurationPreview=null)}}
    fun savePrinterConfiguration(name:String) {
        val s=_state.value;val preview=s.configurationPreview ?: return
        if(s.connection!=preview.connection||s.activePrinterId!=preview.printerId){dismissPrinterConfiguration();return showMessage(tr("Принтер изменился. Считайте конфигурацию заново."))}
        if(name.isBlank()||s.userPresets.any{it.type==PresetType.MACHINE&&it.name==name.trim()})return showMessage(tr("Укажите новое имя профиля"))
        val base=s.inputs?.machine ?: return
        val profile=UserPreset(PresetType.MACHINE,JsonObject(base+preview.config.values+mapOf("name" to JsonPrimitive(name.trim()),"type" to JsonPrimitive("machine"),"from" to JsonPrimitive("User"))))
        _state.update{it.copy(userPresets=UserPresets.merge(it.userPresets,listOf(profile)),configurationPreview=null,printers=it.printers.map{p->if(p.id==preview.printerId)p.copy(machineProfile=profile.name)else p})}
        selectUserPreset(profile);persist();showMessage(tr("Параметры сохранены в новый профиль принтера"))
    }

    fun printerCommand(command:(PrinterClient)->Unit) {
        val connection=_state.value.connection
        if(connection.apiUrl.isBlank())return showMessage(tr("Настройте подключение Moonraker"))
        if(_state.value.deviceBusy)return
        _state.update { it.copy(deviceBusy=true,deviceError=null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { command(printerClient(connection)) };showMessage(tr("Команда выполнена")) }
            catch(e:Exception) { val message=e.message ?: tr("Команда отклонена");_state.update { it.copy(deviceError=message) };showMessage(message) }
            finally { _state.update { it.copy(deviceBusy=false) } }
        }
    }
    fun requestEmergencyStop() {
        val s=_state.value
        if(s.connection.apiUrl.isBlank())return
        val name=s.printers.firstOrNull { it.id==s.activePrinterId }?.name ?: s.connection.apiUrl
        _state.update { it.copy(stopChallenge=StopChallenge(s.connection,s.activePrinterId,name)) }
    }
    fun dismissEmergencyStop() = _state.update { it.copy(stopChallenge=null) }
    fun confirmEmergencyStop(text:String):Boolean {
        val s=_state.value;val challenge=s.stopChallenge ?: return false
        if(challenge.connection!=s.connection || challenge.printerId!=s.activePrinterId) {
            dismissEmergencyStop();showMessage(tr("Принтер изменился. Подтвердите STOP заново."));return false
        }
        if(!EmergencyStopConfirmation.matches(text))return false
        dismissEmergencyStop()
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { printerClient(challenge.connection).emergencyStop() };showMessage(tr("Аварийная остановка отправлена")) }
            catch(e:Exception) { showMessage(tr("Не удалось отправить STOP: {0}" ,e.message)) }
        }
        return true
    }

    fun dismissUploadError() = _state.update { it.copy(uploadError = null) }

    fun testPrinter() {
        if (_state.value.connecting) return
        telemetryClient=null;historyAt=0
        val connection = _state.value.connection
        _state.update { it.copy(connecting = true, printerStatus = null) }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { printerClient(connection).info() }.getOrElse { tr("Нет подключения: {0}" ,it.message) }
            }
            _state.update { if(it.connection==connection) it.copy(connecting = false, printerStatus = status) else it }
        }
    }

    fun uploadLastGcode() {
        _state.value.externalGcode?.let{upload(it.file,it.name);return}
        val result = _state.value.lastResult ?: return
        upload(result.gcode, result.modelName.substringBeforeLast('.') + ".gcode")
    }

    fun uploadPickedGcode(uri: Uri) {
        if (_state.value.uploadPercent != null) return
        val name = displayName(uri)
        if(name.endsWith(".3mf",true)||name.endsWith(".stl",true)||name.endsWith(".zip",true)){importModel(uri,setup=true);return}
        if (!name.endsWith(".gcode", true) && !name.endsWith(".gco", true) && !name.endsWith(".gc", true)) {
            showMessage(tr("Выберите файл .gcode, .gco или .gc")); return
        }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    File(app.cacheDir, "upload-${UUID.randomUUID()}.gcode").also { target ->
                        app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out->val b=ByteArray(65536);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;require(total<=ModelFiles.MAX_FILE_BYTES){tr("Файл больше 96 МБ")};out.write(b,0,n)} } }
                            ?: error(tr("Не удалось открыть файл"))
                    }
                }
                _state.update{it.copy(externalGcode=ExternalGcode(file,name),uploadedFile=null,printDialog=true)}
            } catch (e: Exception) { showMessage(tr("Не удалось прочитать G-code: {0}" ,e.message)) }
        }
    }

    fun uploadAndPrintGcode() {
        val s=_state.value
        if(s.uploadedFile!=null){startUploadedPrint();return}
        val file=s.externalGcode?.file ?: s.lastResult?.gcode ?: return
        val name=s.externalGcode?.name ?: (s.lastResult!!.modelName.substringBeforeLast('.')+".gcode")
        upload(file,name,startAfter=true)
    }
    private fun upload(file: File, name: String,startAfter:Boolean=false) {
        val before=_state.value
        val connection = before.connection
        if (connection.apiUrl.isBlank()) {
            _state.update { it.copy(screen = Screen.DEVICE, message = tr("Укажите адрес Moonraker для загрузки")) }; return
        }
        if (_state.value.uploadPercent != null) return
        _state.update { it.copy(uploadPercent = 0, uploadError = null, uploadedFile = null) }
        viewModelScope.launch {
            try {
                val uploaded = withContext(Dispatchers.IO) {
                    printerClient(connection).upload(file, name) { percent -> _state.update { it.copy(uploadPercent = percent.coerceIn(0,100)) } }
                }
                uploadedConnection=connection
                if(startAfter) {
                    val current=_state.value
                    require(current.connection==connection&&current.activePrinterId==before.activePrinterId&&current.lastResult===before.lastResult&&current.externalGcode==before.externalGcode){tr("Модель или параметры изменились. Повторите нарезку.")}
                    withContext(Dispatchers.IO){printerClient(connection).startPrint(uploaded)}
                    showPrintDialog(false)
                }
                _state.update { it.copy(uploadPercent = null, uploadedFile = if(it.connection==connection && it.activePrinterId==before.activePrinterId && it.model===before.model && it.inputs===before.inputs)uploaded else null, message = tr("Загружено: {0}" ,uploaded)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(uploadError = tr("Ошибка загрузки: {0}" ,e.message)) }
            } finally {
                _state.update { it.copy(uploadPercent=null) }
            }
        }
    }

    fun setParameterLanguage(language: io.github.whoxllm.orcamicro.core.i18n.ParameterLanguage) {
        io.github.whoxllm.orcamicro.core.i18n.AppText.parameterLanguage=language
        _state.update { it.copy(parameterLanguage=language) }
        persist()
    }

    fun openAppSettings() = _state.update { it.copy(screen=Screen.ABOUT, appSettingsOpen=true) }
    fun closeAppSettings() = _state.update { it.copy(appSettingsOpen=false) }

    fun navigate(screen: Screen) {
        if(screen==Screen.HOME)persist()
        printSetupReturn=false
        _state.update { it.copy(screen = screen, appSettingsOpen=false, presetPicker = null,quickPrintPlacement=false) }
    }

    fun back() {
        if(_state.value.appSettingsOpen) { closeAppSettings();return }
        if(_state.value.screen==Screen.PLATE)persist()
        _state.update { it.copy(screen = when {
            it.quickPrintPlacement||printSetupReturn -> Screen.PRINT_SETUP
            it.screen in setOf(Screen.PLATE,Screen.LIBRARY,Screen.DEVICE,Screen.ABOUT) -> Screen.HOME
            else -> Screen.PLATE
        },quickPrintPlacement=false) }
        printSetupReturn=false
    }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun persist() {
        val state = _state.value
        val saved = SavedState(state.selection, state.edits, state.model?.name, state.model?.placement, state.favorites, state.connection, state.userPresets, state.renderOptions,
            state.printers,state.activePrinterId,state.libraryModels,state.modelCacheDays,state.activeModelCard?.id,state.temperatureWindowMinutes,state.telemetryIntervalSeconds,state.parameterLanguage,state.activeProjectId)
        val model=state.model
        val project=if(model!=null && state.selection!=null && state.activeProjectId!=null && state.inputs!=null)
            ProjectDocument(state.activeProjectId,model.name,state.selection,state.edits,model.placement,state.modelRgb(),state.inputs.machine.string("name") ?: state.selection.printer,state.inputs.filament.string("name") ?: state.selection.filament.name,model.mesh.triangleCount,state.activeModelCard?.id)
        else null
        if(project!=null) { pendingProjectSaves.incrementAndGet();_state.update { it.copy(projectSaving=true) } }
        saves.trySend(SaveRequest(saved,project,model?.mesh))
    }
}
