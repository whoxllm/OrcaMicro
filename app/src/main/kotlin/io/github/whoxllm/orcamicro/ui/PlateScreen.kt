package io.github.whoxllm.orcamicro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.render.PlateRenderer
import io.github.whoxllm.orcamicro.render.SceneView
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun PlateScreen(state: UiState, viewModel: MainViewModel) {
    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.openModel(it) }
    }
    var showPlacement by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OrcaMicro")
                        Text(
                            state.model?.name ?: "No model",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (state.model != null) {
                        IconButton(onClick = viewModel::removeModel) { Icon(Icons.Default.Delete, "Remove model") }
                    }
                    IconButton(onClick = { viewModel.navigate(Screen.ABOUT) }) { Icon(Icons.Default.Info, "About") }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(onClick = { openModel.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Open STL") }
                    IconButton(onClick = { showPlacement = true }, enabled = state.model != null) {
                        Icon(Icons.Default.Edit, "Move, rotate and scale")
                    }
                    IconButton(onClick = { viewModel.navigate(Screen.PRESETS) }) { Icon(Icons.Default.Build, "Printer and presets") }
                    IconButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) { Icon(Icons.Default.Settings, "Print settings") }
                    if (state.lastResult != null) {
                        TextButton(onClick = { viewModel.navigate(Screen.PREVIEW) }) { Text("Preview") }
                    }
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = viewModel::slice,
                        icon = { Icon(Icons.Default.PlayArrow, null) },
                        text = { Text("Slice") },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val renderer = remember { PlateRenderer() }
            // Plain holder, not state: framing happens in the view update and must not recompose.
            val framedBed = remember { arrayOfNulls<BedShape>(1) }
            SceneView(renderer, Modifier.fillMaxSize()) { r ->
                val bed = state.bed
                if (bed != null && framedBed[0] != bed) {
                    r.camera.frame(bed.centerX, bed.centerY, 0f, max(bed.width, bed.depth) * 0.6f)
                    framedBed[0] = bed
                }
                r.setBed(bed, state.printableHeight)
                r.setModel(state.model?.placed, state.model?.fits ?: true)
            }
            PresetSummary(state, onClick = { viewModel.navigate(Screen.PRESETS) }, modifier = Modifier.align(Alignment.TopCenter))
            val model = state.model
            if (model == null) {
                Card(Modifier.align(Alignment.Center).padding(32.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Open an STL file to start", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { openModel.launch(arrayOf("*/*")) }) { Text("Open STL") }
                    }
                }
            } else {
                ModelInfo(model, Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
        }
    }

    val model = state.model
    if (showPlacement && model != null) {
        PlacementSheet(
            model = model,
            state = state,
            onChange = viewModel::setPlacement,
            onCenter = viewModel::centerModel,
            onDismiss = { showPlacement = false },
        )
    }
}

@Composable
private fun PresetSummary(state: UiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val selection = state.selection ?: return
    Card(
        modifier = modifier.padding(12.dp).fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(selection.printer, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${selection.process}  ·  ${selection.filament.name}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModelInfo(model: ModelState, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "%.1f × %.1f × %.1f mm".format(model.size.x, model.size.y, model.size.z),
                style = MaterialTheme.typography.labelLarge,
            )
            if (!model.fits) {
                Text("Outside the build volume", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PlacementSheet(
    model: ModelState,
    state: UiState,
    onChange: (Placement) -> Unit,
    onCenter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val p = model.placement
    val bed = state.bed
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Place the model", style = MaterialTheme.typography.titleMedium)
            Text(
                "%.1f × %.1f × %.1f mm".format(model.size.x, model.size.y, model.size.z) +
                    if (model.fits) "" else "  ·  does not fit",
                color = if (model.fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            HorizontalDivider()
            Text("Rotate", style = MaterialTheme.typography.labelLarge)
            RotateRow("X", p.rotationX) { onChange(p.copy(rotationX = it)) }
            RotateRow("Y", p.rotationY) { onChange(p.copy(rotationY = it)) }
            RotateRow("Z", p.rotationZ) { onChange(p.copy(rotationZ = it)) }
            LabeledSlider("Turn on the bed", "${p.rotationZ.roundToInt()}°", p.rotationZ, -180f..180f) {
                onChange(p.copy(rotationZ = it.roundToInt().toFloat()))
            }
            HorizontalDivider()
            LabeledSlider("Scale", "${p.scalePercent.roundToInt()}%", p.scalePercent, 10f..400f) {
                onChange(p.copy(scalePercent = it.roundToInt().toFloat()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in listOf(50f, 100f, 200f)) {
                    OutlinedButton(onClick = { onChange(p.copy(scalePercent = preset)) }) { Text("${preset.roundToInt()}%") }
                }
            }
            if (bed != null) {
                HorizontalDivider()
                LabeledSlider("Position X", "%.1f mm".format(p.centerX), p.centerX, bed.minX..bed.maxX) {
                    onChange(p.copy(centerX = it))
                }
                LabeledSlider("Position Y", "%.1f mm".format(p.centerY), p.centerY, bed.minY..bed.maxY) {
                    onChange(p.copy(centerY = it))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = onCenter) { Text("Center") }
                OutlinedButton(onClick = {
                    onChange(Placement(centerX = bed?.centerX ?: p.centerX, centerY = bed?.centerY ?: p.centerY))
                }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun RotateRow(axis: String, degrees: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$axis  ${normalize(degrees).roundToInt()}°", modifier = Modifier.width(72.dp))
        OutlinedButton(onClick = { onChange(normalize(degrees - 90f)) }) { Text("−90°") }
        OutlinedButton(onClick = { onChange(normalize(degrees + 90f)) }) { Text("+90°") }
        TextButton(onClick = { onChange(0f) }) { Text("0°") }
    }
}

private fun normalize(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** Shows [message] in [host] once, then clears it. */
@Composable
fun MessageEffect(message: String?, host: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            onShown()
            host.showSnackbar(message)
        }
    }
}
