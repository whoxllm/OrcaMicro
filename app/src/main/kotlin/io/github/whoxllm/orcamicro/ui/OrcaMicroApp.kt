package io.github.whoxllm.orcamicro.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OrcaMicroApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OrcaMicroTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.loadError ?: "", style = MaterialTheme.typography.bodyLarge)
                }
                else -> {
                    BackHandler(enabled = state.screen != Screen.PLATE) { viewModel.back() }
                    when (state.screen) {
                        Screen.PLATE -> PlateScreen(state, viewModel)
                        Screen.PRESETS -> PresetsScreen(state, viewModel)
                        Screen.SETTINGS -> SettingsScreen(state, viewModel)
                        Screen.PREVIEW -> PreviewScreen(state, viewModel)
                        Screen.ABOUT -> AboutScreen(onBack = viewModel::back)
                    }
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
            title = { Text("Slicing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(slice.text.ifBlank { "Working" }, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { slice.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${slice.percent}%", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is SliceState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Slicing failed") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(slice.message)
                    slice.details?.let {
                        Text("Engine log", style = MaterialTheme.typography.labelLarge)
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
