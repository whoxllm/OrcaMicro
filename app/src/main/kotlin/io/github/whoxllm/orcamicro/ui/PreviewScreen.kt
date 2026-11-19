package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.style.TextOverflow
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
    val renderer = remember { ToolpathRenderer() }
    var cameraViews by remember { mutableStateOf(false) }
    val result = state.lastResult
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportGcode(it) }
    }

    Scaffold(
        topBar = {
            DesktopHeader(state, viewModel) {
                if (result != null) IconButton(onClick = { shareGcode(context, result.gcode, gcodeName(result.modelName)) }) { Icon(Icons.Default.Share, tr("Поделиться G-code")) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (result == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(tr("Сначала добавьте STL и нарежьте модель")) }
            return@Scaffold
        }
        val toolpaths = result.toolpaths
        val layerCount = toolpaths.layers.size
        var layer by remember(result) { mutableIntStateOf(max(0, layerCount - 1)) }
        var singleLayer by remember { mutableStateOf(false) }
        var travels by remember { mutableStateOf(false) }
        var hidden by remember { mutableStateOf(emptySet<Feature>()) }
        val framed = remember { arrayOfNulls<Any>(1) }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Предпросмотр траекторий"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Box {
                    IconButton(onClick={cameraViews=true}) { OrcaIcon(OrcaAction.Center,tr("Виды камеры")) }
                    androidx.compose.material3.DropdownMenu(expanded=cameraViews,onDismissRequest={cameraViews=false}) {
                        for((title,angles) in listOf(tr("Изометрия") to (-60f to 30f),tr("Сверху") to (-90f to 89f),tr("Спереди") to (-90f to 0f))) {
                            androidx.compose.material3.DropdownMenuItem(text={Text(title)},onClick={renderer.camera.view(angles.first,angles.second);renderer.requestFrame?.invoke();cameraViews=false})
                        }
                        androidx.compose.material3.DropdownMenuItem(text={Text(tr("Вместить стол в экран"))},onClick={state.bed?.let { renderer.camera.frame(it.centerX,it.centerY,0f,max(it.width,it.depth)*0.6f) };renderer.requestFrame?.invoke();cameraViews=false})
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SceneView(renderer, Modifier.fillMaxSize()) { r ->
                    val bed = state.bed
                    if (bed != null && framed[0] !== result) {
                        r.camera.frame(bed.centerX, bed.centerY, 0f, max(bed.width, bed.depth) * 0.6f)
                        framed[0] = result
                    }
                    r.setRenderOptions(state.renderOptions)
                    r.modelColor = state.modelRgb()
                    r.setBed(bed, state.printableHeight)
                    r.setToolpaths(toolpaths)
                    r.setView(PreviewView(layer, singleLayer, travels, hidden))
                }
                StatsCard(toolpaths.stats, layerCount, Modifier.align(Alignment.TopStart).padding(12.dp))
            }
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.48f).dp).navigationBarsPadding()) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (layerCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val z = toolpaths.layers[layer.coerceIn(0, layerCount - 1)].z
                            Text(tr("Слой {0}/{1} · %.2f мм" ,layer + 1,layerCount).format(z), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { layer = max(0, layer - 1) }) { Icon(Icons.Default.KeyboardArrowDown, tr("Предыдущий слой")) }
                            IconButton(onClick = { layer = minOf(layerCount - 1, layer + 1) }) { Icon(Icons.Default.KeyboardArrowUp, tr("Следующий слой")) }
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
                        FilterChip(selected = singleLayer, onClick = { singleLayer = !singleLayer }, label = { Text(tr("Один слой")) })
                        FilterChip(selected = travels, onClick = { travels = !travels }, label = { Text(tr("Перемещения")) })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(toolpaths.features.sortedBy { it.ordinal }) { feature ->
                            FilterChip(
                                selected = feature !in hidden,
                                onClick = { hidden = if (feature in hidden) hidden - feature else hidden + feature },
                                label = { Text(feature.labelRu) },
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
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.OutlinedButton(onClick = { export.launch(gcodeName(result.modelName)) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) { Text(tr("Сохранить")) }
                        UploadSplitButton(state,viewModel,Modifier.weight(1f))
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
            stats.printSeconds?.let { Text(tr("Время  {0}" ,formatDuration(it)), style = MaterialTheme.typography.labelLarge) }
            val length = stats.filamentMillimetres?.let { tr("%.2f м").format(it / 1000.0) }
            val weight = stats.filamentGrams?.let { tr("%.1f г").format(it) }
            val filament = listOfNotNull(length, weight).joinToString("  ·  ")
            if (filament.isNotEmpty()) Text(tr("Пластик  {0}" ,filament), style = MaterialTheme.typography.labelLarge)
            Text(tr("{0} слоёв" ,layers), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> tr("{0}ч {1}мин" ,h,m)
        m > 0 -> tr("{0}мин {1}с" ,m,seconds % 60)
        else -> tr("{0}с" ,seconds)
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
    context.startActivity(Intent.createChooser(intent, tr("Поделиться G-code")))
}
