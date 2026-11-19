package io.github.whoxllm.orcamicro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.profiles.string

fun UiState.presetDirty(type: PresetType) = changedPresetKeys[type].orEmpty().isNotEmpty()
fun UiState.presetSuffix(type: PresetType) = if (presetDirty(type)) " *" else ""

/** The disk updates an existing user preset; bundled presets are saved as a user copy. */
@Composable
fun PresetSaveAction(state: UiState, vm: MainViewModel, type: PresetType, saveAs: Boolean = false) {
    var dialog by remember(type) { mutableStateOf(false) }
    var name by remember(type) { mutableStateOf("") }
    val current = vm.presetFor(type)?.string("name").orEmpty()
    val own = state.userPresets.any { it.type == type && it.name == current }
    IconButton(onClick = {
        if (own && !saveAs) vm.saveUserPreset(type, current)
        else { name = current + tr(" — копия"); dialog = true }
    }, enabled = state.inputs != null) {
        OrcaIcon(OrcaAction.Save, if (saveAs) tr("Сохранить профиль как") else tr("Сохранить профиль"))
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(tr("Сохранить в Мои профили")) }, text = {
        androidx.compose.foundation.layout.Column {
            OutlinedTextField(name, { name = it }, label = { Text(tr("Имя профиля")) }, singleLine = true)
            if (state.userPresets.any { it.type == type && it.name == name.trim() }) Text(tr("Профиль с этим именем будет обновлён."))
        }
    }, confirmButton = { TextButton(onClick = { vm.saveUserPreset(type, name); dialog = false }, enabled = name.isNotBlank()) { Text(tr("Сохранить")) } }, dismissButton = { TextButton(onClick = { dialog = false }) { Text(tr("Отмена")) } })
}

@Composable
fun PresetDraftNotice(state: UiState, vm: MainViewModel, type: PresetType) {
    if(state.presetDirty(type)) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(tr("* Не сохранено в профиль"),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        PresetSaveAction(state,vm,type)
    }
}
