package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.profiles.*

@Composable
fun PresetsScreen(state: UiState, viewModel: MainViewModel) {
    val catalog = state.catalog ?: return
    val selection = state.selection ?: return
    var type by rememberSaveable { mutableStateOf(state.presetPicker ?: PresetType.MACHINE) }
    var vendor by rememberSaveable { mutableStateOf<String?>(null) }
    var browsePrinters by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var save by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> viewModel.importPreset(uri, type) } }
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)
    BackHandler { if (vendor != null) vendor = null else if (browsePrinters) browsePrinters = false else viewModel.back() }
    val active = when(type) { PresetType.MACHINE -> state.edits.importedMachine; PresetType.PROCESS -> state.edits.importedProcess; PresetType.FILAMENT -> state.edits.importedFilament }
    fun matches(text: String) = query.trim().split(' ').all { text.contains(it, true) }
    Scaffold(topBar = {
        DesktopHeader(state, viewModel) {
            IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("На стол")) }
            IconButton(onClick = { import.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { OrcaIcon(OrcaAction.Import, tr("Импорт профилей")) }
            PresetSaveAction(state, viewModel, type)
        }
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(PresetType.entries.indexOf(type)) {
                PresetType.entries.forEach { t -> Tab(selected = type == t, onClick = { type = t; vendor = null; browsePrinters = false; query = "" }, text = { Text(t.title()) }) }
            }
            if(state.presetDirty(type)) Text(tr("* Не сохранено в профиль"), Modifier.padding(horizontal=16.dp, vertical=6.dp), color=MaterialTheme.colorScheme.primary)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), placeholder = { Text(tr("Поиск профиля")) }, singleLine = true)
            LazyColumn(Modifier.weight(1f)) {
                if (!browsePrinters || type != PresetType.MACHINE) {
                    item { SectionLabel(tr("Мои профили")) }
                    val own = state.userPresets.filter { it.type == type && matches(it.name) }
                    if (own.isEmpty()) item { Text(tr("Импортируйте JSON или набор из конфигурации OrcaSlicer"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
                    items(own, key = { "user:${it.id}" }) { p ->
                        PresetItem(p.name + if(active?.string("name")==p.name)state.presetSuffix(type)else "", tr("Пользовательский"), active?.string("name") == p.name, p.preset.first("default_filament_colour") ?: p.preset.first("filament_colour")) {
                            viewModel.selectUserPreset(p); viewModel.back()
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton(onClick = { import.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { OrcaIcon(OrcaAction.Add, null); Text(tr("Импортировать"), Modifier.padding(start = 6.dp)) }
                            TextButton(onClick = { name = viewModel.presetFor(type)?.string("name").orEmpty() + tr(" — копия"); save = true }, modifier = Modifier.weight(1f)) { OrcaIcon(OrcaAction.Save, null); Text(tr("Сохранить как"), Modifier.padding(start = 6.dp)) }
                        }
                    }
                }
                item { SectionLabel(tr("Штатные профили OrcaSlicer")) }
                when (type) {
                    PresetType.MACHINE -> {
                        if (!browsePrinters) {
                            item { PresetItem(tr("+ Добавить принтер"), tr("Выберите марку и модель"), false) { browsePrinters = true; query = "" } }
                            item { PresetItem(selection.printer, displayVendor(selection.vendor), active == null) { viewModel.selectPrinter(selection.vendor, selection.printer); viewModel.back() } }
                        } else if (vendor == null) {
                            items(catalog.printerVendors.filter { matches(displayVendor(it.name)) }, key = { "vendor:${it.name}" }) { v ->
                                PresetItem(displayVendor(v.name), tr("Моделей: {0}" ,v.printers.size), false) { vendor = v.name; query = "" }
                            }
                        } else {
                            item { TextButton(onClick = { vendor = null }) { Text(tr("‹ Все производители")) } }
                            items(catalog.vendor(vendor!!)?.printers.orEmpty().filter { matches(it.name) }, key = { "printer:${it.name}" }) { p ->
                                PresetItem(p.name, tr("Сопло {0} мм" ,p.nozzle), active == null && vendor == selection.vendor && p.name == selection.printer) {
                                    viewModel.selectPrinter(vendor!!, p.name); viewModel.back()
                                }
                            }
                        }
                    }
                    PresetType.PROCESS -> items(catalog.printer(selection.vendor, selection.printer)?.let { catalog.processesFor(selection.vendor, it) }.orEmpty().filter(::matches), key = { "process:${it}" }) { p ->
                        PresetItem(p, tr("Для {0}" ,selection.printer), active == null && p == selection.process) { viewModel.selectProcess(p); viewModel.back() }
                    }
                    PresetType.FILAMENT -> items(catalog.printer(selection.vendor, selection.printer)?.let { catalog.filamentsFor(selection.vendor, it) }.orEmpty().filter { matches(it.name) }, key = { "filament:${it.vendor}:${it.name}" }) { p ->
                        PresetItem(p.name, displayVendor(p.vendor), active == null && p == selection.filament) { viewModel.selectFilament(p); viewModel.back() }
                    }
                }
                item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
            }
        }
    }
    if (save) AlertDialog(onDismissRequest = { save = false }, title = { Text(tr("Сохранить в Мои профили")) }, text = {
        OutlinedTextField(name, { name = it }, label = { Text(tr("Имя профиля")) }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { viewModel.saveUserPreset(type, name); save = false }, enabled = name.isNotBlank()) { Text(tr("Сохранить")) } }, dismissButton = { TextButton(onClick = { save = false }) { Text(tr("Отмена")) } })
}

fun PresetType.title() = when(this) { PresetType.MACHINE -> tr("Принтер"); PresetType.PROCESS -> tr("Процесс"); PresetType.FILAMENT -> tr("Пластик") }
private fun displayVendor(vendor: String) = when(vendor) { Catalog.LIBRARY_VENDOR -> tr("Универсальные"); "Custom" -> tr("Свой принтер / Klipper"); else -> vendor }
@Composable private fun SectionLabel(text: String) { Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge) } }
@Composable private fun PresetItem(name: String, detail: String, selected: Boolean, color: String? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(name) }, supportingContent = { Text(detail) }, modifier = Modifier.clickable(onClick = onClick),
        leadingContent = color?.let { { Box(Modifier.size(24.dp).clip(CircleShape).background(parseColor(it))) } },
        trailingContent = if (selected) { { Icon(Icons.Default.Check, tr("Выбран"), tint = MaterialTheme.colorScheme.primary) } } else null)
    HorizontalDivider()
}
fun parseColor(hex: String?): Color = Color(0xFF000000 or (hex?.removePrefix("#")?.take(6)?.toLongOrNull(16) ?: 0xE6B83C))
fun UiState.modelRgb(): Int = (inputs?.filament?.first("default_filament_colour") ?: inputs?.filament?.first("filament_colour"))?.removePrefix("#")?.take(6)?.toIntOrNull(16) ?: 0xE6B83C
