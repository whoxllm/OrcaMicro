package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OrcaMicroApp(viewModel: MainViewModel, onInstallUpdate:()->Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OrcaMicroTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.loadError ?: "", style = MaterialTheme.typography.bodyLarge)
                }
                else -> {
                    BackHandler(enabled = state.screen != Screen.HOME || state.quickPrintPlacement) { viewModel.back() }
                    when (state.screen) {
                        Screen.HOME -> HomeScreen(state,viewModel)
                        Screen.PLATE, Screen.SETTINGS -> PlateScreen(state, viewModel)
                        Screen.PRESETS -> PresetsScreen(state, viewModel)
                        Screen.PREVIEW -> PreviewScreen(state, viewModel)
                        Screen.DEVICE -> PrinterScreen(state, viewModel)
                        Screen.ABOUT -> AboutScreen(state, viewModel)
                        Screen.LIBRARY -> ModelLibraryScreen(state, viewModel)
                        Screen.PRINT_SETUP -> PrintSetupScreen(state, viewModel)
                    }
                    if(state.archiveChoices.isNotEmpty())AlertDialog(onDismissRequest=viewModel::dismissArchive,title={Text(tr("Выберите деталь из архива"))},text={
                        LazyColumn(Modifier.heightIn(max=420.dp)) { items(state.archiveChoices) { entry->TextButton(onClick={viewModel.chooseArchiveEntry(entry)}){Text(entry)} } }
                    },confirmButton={TextButton(onClick=viewModel::dismissArchive){Text(tr("Закрыть"))}})
                    if(state.catalogFileModel!=null && !state.libraryBusy)CatalogFilesDialog(state,viewModel)
                    if(state.projectSettings!=null)ProjectSettingsDialog(state,viewModel)
                    if(state.printDialog && state.uploadPercent==null && state.uploadError==null)PrintSendDialog(state,viewModel)
                    if(state.stopChallenge!=null)EmergencyStopDialog(state,viewModel)
                    if (state.uploadPercent != null || state.libraryBusy) TransferProgressDialog(state)
                    state.uploadError?.let { error -> AlertDialog(onDismissRequest = viewModel::dismissUploadError, title = { Text(tr("Не удалось загрузить")) }, text = { Text(error) }, confirmButton = { TextButton(onClick = viewModel::dismissUploadError) { Text("OK") } }) }
                    UpdateUi(state,onInstallUpdate)
                    SliceDialogs(state.slice, onCancel = viewModel::cancelSlice, onDismiss = viewModel::dismissSliceError)
                }
            }
        }
    }
}

@Composable
private fun SliceDialogs(slice: SliceState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (slice) {
        is SliceState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Нарезка")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(slice.text.ifBlank { tr("Обработка модели") }, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { slice.percent.coerceIn(0,100) / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    Text("${slice.percent}%", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(tr("Отмена")) } },
        )
        is SliceState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr("Ошибка нарезки")) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(slice.message)
                    slice.details?.let {
                        Text(tr("Журнал движка"), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        else -> Unit
    }
}
