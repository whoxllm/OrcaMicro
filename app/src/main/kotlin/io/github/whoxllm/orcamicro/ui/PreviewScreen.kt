package io.github.whoxllm.orcamicro.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.whoxllm.orcamicro.core.gcode.Feature
import io.github.whoxllm.orcamicro.core.gcode.GcodeStats
import io.github.whoxllm.orcamicro.render.PreviewView
import io.github.whoxllm.orcamicro.render.SceneView
import io.github.whoxllm.orcamicro.render.ToolpathRenderer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun PreviewScreen(state: UiState, viewModel: MainViewModel) {
    val result = state.lastResult
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportGcode(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview") },
                navigationIcon = { IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (result != null) {
                        IconButton(onClick = { shareGcode(context, result.gcode, gcodeName(result.modelName)) }) {
                            Icon(Icons.Default.Share, "Share G-code")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (result == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Nothing sliced yet") }
            return@Scaffold
        }
        val toolpaths = result.toolpaths
        val layerCount = toolpaths.layers.size
        var layer by remember(result) { mutableIntStateOf(max(0, layerCount - 1)) }
        var singleLayer by remember { mutableStateOf(false) }
        var travels by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(emptySet<Feature>()) }
        val renderer = remember { ToolpathRenderer() }
        val framed = remember { arrayOfNulls<Any>(1) }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SceneView(renderer, Modifier.fillMaxSize()) { r ->
                    val bed = state.bed
                    if (bed != null && framed[0] !== result) {
                        r.camera.frame(bed.centerX, bed.centerY, 0f, max(bed.width, bed.depth) * 0.6f)
                        framed[0] = result
                    }
                    r.setBed(bed, state.printableHeight)
                    r.setToolpaths(toolpaths)
                    r.setView(PreviewView(layer, singleLayer, travels, hidden))
                }
                StatsCard(toolpaths.stats, layerCount, Modifier.align(Alignment.TopStart).padding(12.dp))
            }
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
                    if (layerCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val z = toolpaths.layers[layer.coerceIn(0, layerCount - 1)].z
                            Text("Layer ${layer + 1} of $layerCount  ·  Z %.2f mm".format(z), modifier = Modifier.weight(1f))
                            IconButton(onClick = { layer = max(0, layer - 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Layer down") }
                            IconButton(onClick = { layer = minOf(layerCount - 1, layer + 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Layer up") }
                        }
                        if (layerCount > 1) {
                            Slider(
                                value = layer.toFloat(),
                                onValueChange = { layer = it.roundToInt().coerceIn(0, layerCount - 1) },
                                valueRange = 0f..(layerCount - 1).toFloat(),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = singleLayer, onClick = { singleLayer = !singleLayer }, label = { Text("This layer only") })
                        FilterChip(selected = travels, onClick = { travels = !travels }, label = { Text("Travel moves") })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(toolpaths.features.sortedBy { it.ordinal }) { feature ->
                            FilterChip(
                                selected = feature !in hidden,
                                onClick = { hidden = if (feature in hidden) hidden - feature else hidden + feature },
                                label = { Text(feature.label) },
                                leadingIcon = {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000 or feature.rgb.toLong())))
                                },
                            )
                        }
                    }
                    if (result.warnings.isNotEmpty()) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Text(
                                result.warnings.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Button(
                        onClick = { export.launch(gcodeName(result.modelName)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.Done, null)
                        Text("Save G-code", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: GcodeStats, layers: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            stats.printSeconds?.let { Text("Print time  ${formatDuration(it)}", style = MaterialTheme.typography.labelLarge) }
            val length = stats.filamentMillimetres?.let { "%.2f m".format(it / 1000.0) }
            val weight = stats.filamentGrams?.let { "%.1f g".format(it) }
            val filament = listOfNotNull(length, weight).joinToString("  ·  ")
            if (filament.isNotEmpty()) Text("Filament  $filament", style = MaterialTheme.typography.labelLarge)
            Text("$layers layers", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun gcodeName(modelName: String): String = modelName.substringBeforeLast('.').ifBlank { "model" } + ".gcode"

private fun shareGcode(context: Context, file: File, name: String) {
    // Share a copy with a readable name; the job folder file is always output.gcode.
    val shared = File(file.parentFile, name).apply { if (!exists()) file.copyTo(this, overwrite = true) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share G-code"))
}
