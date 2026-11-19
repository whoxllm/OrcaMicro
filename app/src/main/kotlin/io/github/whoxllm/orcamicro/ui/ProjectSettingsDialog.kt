package io.github.whoxllm.orcamicro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.settings.OrcaOptions
import io.github.whoxllm.orcamicro.core.i18n.tr

@Composable fun ProjectSettingsDialog(state:UiState,vm:MainViewModel) {
    val settings=state.projectSettings ?: return
    var machine by remember{mutableStateOf(false)};var process by remember{mutableStateOf(true)};var filament by remember{mutableStateOf(true)}
    AlertDialog(onDismissRequest=vm::dismissProjectSettings,title={Text(tr("Проект 3MF"))},text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(tr("Геометрия открыта. Можно также применить параметры проекта OrcaSlicer / Bambu Studio."))
            listOf(Triple(PresetType.MACHINE,machine,{v:Boolean->machine=v}),Triple(PresetType.PROCESS,process,{v:Boolean->process=v}),Triple(PresetType.FILAMENT,filament,{v:Boolean->filament=v})).forEach{(type,checked,set)->
                val count=OrcaOptions.all.count{it.type==type&&!it.isGcode&&it.key in settings}
                val label=when(type){PresetType.MACHINE->tr("Принтер");PresetType.PROCESS->tr("Процесс");PresetType.FILAMENT->tr("Филамент")}
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked,set,enabled=count>0);Text("$label · $count")}
            }
            Text(tr("Стартовый и конечный G-code остаются из вашего профиля. Используется первый материал; покраска и мультиматериальные задания не поддерживаются."),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={Button(onClick={vm.applyProjectSettings(machine,process,filament)},enabled=machine||process||filament){Text(tr("Применить"))}},dismissButton={TextButton(onClick=vm::dismissProjectSettings){Text(tr("Только геометрия"))}})
}
