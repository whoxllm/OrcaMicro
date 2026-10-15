package io.github.whoxllm.orcamicro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.settings.PresetEditor
import io.github.whoxllm.orcamicro.core.settings.PrintSettings
import io.github.whoxllm.orcamicro.core.settings.PseudoKeys
import io.github.whoxllm.orcamicro.core.settings.SettingKind
import io.github.whoxllm.orcamicro.core.settings.SettingSpec
import io.github.whoxllm.orcamicro.core.settings.formatNumber

private val tabs = listOf(PresetType.MACHINE to "Printer", PresetType.PROCESS to "Print", PresetType.FILAMENT to "Filament")

@Composable
fun SettingsScreen(state: UiState, viewModel: MainViewModel) {
    val inputs = state.inputs ?: return
    var tab by rememberSaveable { mutableStateOf(1) }
    val type = tabs[tab].first
    val preset = when (type) {
        PresetType.MACHINE -> inputs.machine
        PresetType.PROCESS -> inputs.process
        PresetType.FILAMENT -> inputs.filament
    }
    val edits = when (type) {
        PresetType.MACHINE -> state.edits.machine
        PresetType.PROCESS -> state.edits.process
        PresetType.FILAMENT -> state.edits.filament
    }
    val sections = PrintSettings.forType(type).groupBy { it.section }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (edits.isNotEmpty()) {
                        TextButton(onClick = { viewModel.resetEdits(type) }) { Text("Reset ${tabs[tab].second.lowercase()}") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, (_, label) ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            val presetName = when (type) {
                PresetType.MACHINE -> state.selection?.printer
                PresetType.PROCESS -> state.selection?.process
                PresetType.FILAMENT -> state.selection?.filament?.name
            }
            Text(
                "Based on: $presetName" + if (edits.isEmpty()) "" else "  ·  ${edits.size} changed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                for ((section, specs) in sections) {
                    item(key = "section-$section") {
                        Text(
                            section,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(specs, key = { it.key }) { spec ->
                        val value = if (spec.key == PseudoKeys.BED_TYPE) inputs.bedType.orcaName else PresetEditor.read(preset, spec, inputs.bedType)
                        SettingField(
                            spec = spec,
                            value = value,
                            edited = spec.key in edits,
                            onChange = { viewModel.setEdit(type, spec.key, it) },
                        )
                    }
                }
                item { Box(Modifier.padding(32.dp)) }
            }
        }
    }
}

@Composable
private fun SettingField(spec: SettingSpec, value: String?, edited: Boolean, onChange: (String?) -> Unit) {
    when (spec.kind) {
        SettingKind.BOOL -> ListItem(
            headlineContent = { Text(spec.label) },
            supportingContent = if (edited) { { Text("Changed") } } else null,
            trailingContent = {
                Switch(checked = value == "1" || value.equals("true", ignoreCase = true), onCheckedChange = { onChange(if (it) "1" else "0") })
            },
        )
        SettingKind.CHOICE -> ChoiceField(spec, value, edited, onChange)
        SettingKind.TEXT, SettingKind.GCODE -> OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = { onChange(it) },
            label = { Text(spec.label + if (edited) " (changed)" else "") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = if (spec.kind == SettingKind.GCODE) 4 else 1,
            maxLines = if (spec.kind == SettingKind.GCODE) 14 else 1,
        )
        SettingKind.FLOAT, SettingKind.INT, SettingKind.PERCENT -> NumberField(spec, value, edited, onChange)
    }
}

@Composable
private fun NumberField(spec: SettingSpec, value: String?, edited: Boolean, onChange: (String?) -> Unit) {
    val percent = spec.kind == SettingKind.PERCENT
    val min = spec.min
    val max = spec.max
    fun shown(v: String?) = v?.removeSuffix("%")?.trim().orEmpty()
    /** The typed number if it is acceptable for this setting. */
    fun accepted(input: String): Double? {
        val parsed = input.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (spec.kind == SettingKind.INT && parsed != Math.rint(parsed)) return null
        if (min != null && parsed < min) return null
        if (max != null && parsed > max) return null
        return parsed
    }
    var text by remember(spec.key) { mutableStateOf(shown(value)) }
    // Follow outside changes (reset, other preset) without fighting the user's typing.
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != shown(value).toDoubleOrNull()) text = shown(value)
    }
    val invalid = text.isNotEmpty() && accepted(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            accepted(input)?.let { onChange(formatNumber(it) + if (percent) "%" else "") }
        },
        label = { Text(spec.label + if (edited) " (changed)" else "") },
        suffix = if (spec.unit.isNotEmpty()) { { Text(spec.unit) } } else null,
        isError = invalid,
        supportingText = if (invalid && min != null && max != null) {
            { Text("${formatNumber(min)} to ${formatNumber(max)}") }
        } else {
            null
        },
        trailingIcon = if (edited) {
            { IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Refresh, "Revert") } }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (spec.kind == SettingKind.INT) KeyboardType.Number else KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ChoiceField(spec: SettingSpec, value: String?, edited: Boolean, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = spec.choices.firstOrNull { it.value == value }?.label ?: value ?: "Default"
    Box {
        ListItem(
            headlineContent = { Text(spec.label) },
            supportingContent = { Text(current + if (edited) "  (changed)" else "") },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (edited) IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Refresh, "Revert") }
                    Icon(Icons.Default.ArrowDropDown, null)
                }
            },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in spec.choices) {
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { onChange(choice.value); open = false })
            }
        }
    }
}
