package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.i18n.ptr
import io.github.whoxllm.orcamicro.core.i18n.AppText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.profiles.string
import io.github.whoxllm.orcamicro.core.settings.OrcaOption
import io.github.whoxllm.orcamicro.core.settings.OrcaOptions
import io.github.whoxllm.orcamicro.core.settings.PrintSettings
import kotlinx.serialization.json.*

private val settingTabs get() = listOf(PresetType.PROCESS to tr("Печать"), PresetType.FILAMENT to tr("Пластик"), PresetType.MACHINE to tr("Принтер"))

@Composable
fun SettingsScreen(state: UiState, viewModel: MainViewModel) {
    val inputs = state.inputs ?: return
    var tab by rememberSaveable { mutableIntStateOf(settingTabs.indexOfFirst { it.first == state.settingsType }.coerceAtLeast(0)) }
    var query by rememberSaveable { mutableStateOf("") }
    var allOptions by rememberSaveable { mutableStateOf(true) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var changedOnly by rememberSaveable { mutableStateOf(false) }
    val parameterEnglish=AppText.parametersEnglish
    var category by rememberSaveable(tab,parameterEnglish) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<OrcaOption?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var transferType by rememberSaveable { mutableStateOf(PresetType.PROCESS) }
    val type = settingTabs[tab].first
    val preset = when (type) {
        PresetType.MACHINE -> inputs.machine
        PresetType.PROCESS -> inputs.process
        PresetType.FILAMENT -> inputs.filament
    }
    val base = viewModel.presetFor(type)
    val keysChanged = state.changedPresetKeys[type].orEmpty()
    val options = remember(type, preset.keys,parameterEnglish) { OrcaOptions.forType(type, preset) }
    val quickKeys = remember(type) { PrintSettings.forType(type).map { it.key }.toSet() }
    val categories = remember(options,parameterEnglish) { options.map { it.displayCategory }.distinct().sortedBy { listOf(ptr("Качество"), ptr("Прочность"), ptr("Скорость"), ptr("Поддержки"), ptr("Мультиматериалы"), ptr("Прочее")).indexOf(it).let { n -> if (n < 0) 99 else n } } }
    val shown = remember(options, query, allOptions, favoritesOnly, changedOnly, category, state.favorites, keysChanged,parameterEnglish) {
        options.filter { option ->
            (allOptions || query.isNotBlank() || favoritesOnly || changedOnly || option.key in quickKeys) &&
                option.matches(query) && (category == null || category == option.displayCategory) &&
                (!favoritesOnly || "${type.name}/${option.key}" in state.favorites) &&
                (!changedOnly || option.key in keysChanged)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importPreset(it, transferType) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportPreset(it, transferType) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(tr("Параметры") + state.presetSuffix(type)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = { IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("На стол")) } },
                actions = { PresetSaveAction(state, viewModel, type); IconButton(onClick = { confirmReset = true }) { OrcaIcon(OrcaAction.Refresh, tr("Сбросить вкладку")) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { transferType = type; import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, modifier = Modifier.weight(1f)) { Text(tr("Импорт"), maxLines = 1) }
                    Button(onClick = { transferType = type; export.launch("OrcaSlicer-${type.orcaName}.json") }, modifier = Modifier.weight(1f)) { Text(tr("Экспорт"), maxLines = 1) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                settingTabs.forEachIndexed { index, (_, title) -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
            }
            Text((preset.string("name").orEmpty()) + state.presetSuffix(type), Modifier.padding(horizontal=16.dp, vertical=6.dp), style=MaterialTheme.typography.labelMedium)
            if(state.presetDirty(type)) Text(tr("* Не сохранено в профиль"), Modifier.padding(horizontal=16.dp), color=MaterialTheme.colorScheme.primary, style=MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = query, onValueChange = { query = it; category = null },
                placeholder = { Text(tr("Найти параметр"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, tr("Очистить поиск")) } } } else null,
                singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = !allOptions, onClick = { allOptions = false; category = null }, label = { Text(tr("Основные")) }) }
                item { FilterChip(selected = allOptions, onClick = { allOptions = true }, label = { Text(tr("Все · {0}" ,options.size)) }) }
                item { FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text(tr("Избранное")) }) }
                item { FilterChip(selected = changedOnly, onClick = { changedOnly = !changedOnly }, label = { Text(tr("Изменены · {0}" ,keysChanged.size)) }) }
            }
            if (allOptions) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text(tr("Все разделы")) }) }
                    items(categories) { name -> FilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) }) }
                }
            }
            Text(
                tr("{0} параметров",shown.size),
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Параметры не найдены. Проверьте поиск и фильтры."), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { "${type.name}/${it.key}/${parameterEnglish}" }) { option ->
                        val value = preset[option.key]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editing = option }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(option.displayLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val summary = if (value == null) ptr("По умолчанию") else OrcaOptions.display(option, value).ifBlank { ptr("Пусто") }
                            Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (option.key in keysChanged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 112.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if ("${type.name}/${option.key}" in state.favorites) Icon(Icons.Default.Star, tr("Избранное"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
    editing?.let { option ->
        OptionEditor(
            option = option, value = preset[option.key], baseValue = base?.get(option.key), changed = option.key in keysChanged,
            onSave = { viewModel.setRawEdit(type, option.key, it); editing = null },
            onReset = { viewModel.setRawEdit(type, option.key, null); editing = null },
            onDismiss = { editing = null },
            favorite = "${type.name}/${option.key}" in state.favorites, onFavorite = { viewModel.toggleFavorite(type, option.key) },
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text(tr("Сбросить «{0}»?" ,settingTabs[tab].second)) },
        text = { Text(tr("Правки этой вкладки будут сброшены до значений выбранного профиля. Сам профиль сохранится.")) },
        confirmButton = { TextButton(onClick = { viewModel.resetEdits(type); confirmReset = false }) { Text(tr("Сбросить")) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Отмена")) } },
    )
}

@Composable
private fun OptionEditor(option: OrcaOption, value: JsonElement?, baseValue: JsonElement?, changed: Boolean, onSave: (JsonElement) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit, favorite: Boolean, onFavorite: () -> Unit) {
    var draft by remember(option.key) { mutableStateOf(OrcaOptions.formValue(option, value)) }
    var error by remember(option.key) { mutableStateOf<String?>(null) }
    var showHelp by remember(option.key) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.displayLabel + if(draft != OrcaOptions.formValue(option, value)) " *" else "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.Add, if (favorite) tr("Убрать из избранного") else tr("В избранное")) }
            }
            Text(option.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            if (option.displayDescription.isNotEmpty()) {
                TextButton(onClick = { showHelp = !showHelp }, contentPadding = PaddingValues(0.dp)) { Text(if (showHelp) tr("Скрыть описание") else tr("Описание параметра")) }
                if (showHelp) Text(option.displayDescription, style = MaterialTheme.typography.bodySmall)
            }
            if (value == null) Text(tr("По умолчанию в движке. Сохраните значение, чтобы изменить его."), style = MaterialTheme.typography.bodySmall)
            ValueFields(option, draft, value, tr("Значение")) { draft = it; error = null }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (baseValue != null) Text(tr("В исходном профиле: {0}" ,OrcaOptions.display(option, baseValue).take(160)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onReset, enabled = changed, modifier = Modifier.weight(1f)) { Text(tr("Вернуть")) }
                Button(onClick = {
                    try { onSave(OrcaOptions.parseForm(option, draft, value)) }
                    catch (e: Exception) { error = e.message ?: tr("Проверьте значение") }
                }, modifier = Modifier.weight(1f)) { OrcaIcon(OrcaAction.Save, null); Spacer(Modifier.width(6.dp)); Text(tr("Применить")) }
            }
        }
    }
}

@Composable
private fun ValueFields(option: OrcaOption, value: JsonElement, original: JsonElement?, label: String, onChange: (JsonElement) -> Unit) {
    when (value) {
        is JsonArray -> {
            if (value.isEmpty()) Text(tr("Значения не заданы"), style = MaterialTheme.typography.bodySmall)
            value.forEachIndexed { index, item ->
                key(index) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ValueFields(OrcaOptions.scalarOption(option), item, (original as? JsonArray)?.getOrNull(index), if (value.size == 1) label else "${label} ${index + 1}") { changed ->
                                onChange(JsonArray(value.toMutableList().also { it[index] = changed }))
                            }
                        }
                        if (value.size > 1) IconButton(onClick = { onChange(JsonArray(value.filterIndexed { i, _ -> i != index })) }) { OrcaIcon(OrcaAction.Remove, tr("Удалить значение {0}" ,index + 1)) }
                    }
                }
            }
            TextButton(onClick = { onChange(JsonArray(value + JsonPrimitive(""))) }) { Text(tr("Добавить значение")) }
        }
        is JsonObject -> value.forEach { (name, item) ->
            ValueFields(OrcaOptions.scalarOption(option), item, (original as? JsonObject)?.get(name), name) { onChange(JsonObject(value + (name to it))) }
        }
        is JsonPrimitive -> {
            val inherited = value == JsonNull || value.contentOrNull == "nil"
            val text = if (inherited) "" else value.content
            if (option.valueType == "coBool") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (inherited) tr("{0} · по умолчанию" ,label) else label, Modifier.weight(1f))
                    Switch(checked = text == "1" || text.equals("true", true), onCheckedChange = { onChange(JsonPrimitive(if (it) "1" else "0")) })
                }
            } else {
                if (option.choices.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(option.choices) { choice -> FilterChip(selected = text == choice, onClick = { onChange(JsonPrimitive(choice)) }, label = { Text(option.choiceLabel(choice)) }) }
                }
                if(option.valueType != "coEnum" || option.choices.isEmpty()) OutlinedTextField(
                    value = text, onValueChange = { onChange(JsonPrimitive(it)) },
                    label = { Text(label) }, placeholder = if (inherited) { { Text(ptr("По умолчанию")) } } else null,
                    suffix = if (option.unit.isNotBlank()) { { Text(option.unit) } } else null,
                    modifier = Modifier.fillMaxWidth(), minLines = if (option.isGcode) 4 else 1, maxLines = if (option.isGcode) 10 else 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (option.isGcode) FontFamily.Monospace else MaterialTheme.typography.bodyMedium.fontFamily),
                    keyboardOptions = KeyboardOptions(keyboardType = if (option.isNumber) KeyboardType.Decimal else KeyboardType.Text),
                )
            }
        }
    }
}
