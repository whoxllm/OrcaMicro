package io.github.whoxllm.orcamicro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.cad.*
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.render.CadRenderer
import io.github.whoxllm.orcamicro.render.CadSceneView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class CadPickMode { AUTO, FACE, EDGE }

/**
 * Touch-first parametric CAD screen powered by OrcaSlicer's CadDocument in the :cad process.
 */
@Composable
fun DesignScreen(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cad = viewModel.cadClient
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)

    val sessionDir = remember { File(context.filesDir, "cad-session").apply { mkdirs() } }
    val recipeFile = remember(state.activeProjectId) {
        viewModel.activeCadRecipeFile() ?: File(sessionDir, "current.recipe")
    }

    var doc by remember { mutableStateOf(CadDocumentState(ok = true)) }
    var displayMesh by remember { mutableStateOf<CadDisplayMesh?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(CadPickMode.AUTO) }
    var selectedFace by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedEdges by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }

    // Active sheets / overlays
    var sketchPlaneDialog by remember { mutableStateOf(false) }
    var sketchMode by remember { mutableStateOf<Pair<String, Pair<Int, Int>?>?>(null) } // plane kind + optional (body, face)
    var showExtrudeSheet by remember { mutableStateOf(false) }
    var showFilletSheet by remember { mutableStateOf<Boolean?>(null) } // true=fillet, false=chamfer
    var showHoleSheet by remember { mutableStateOf(false) }
    var showTreeSheet by remember { mutableStateOf(false) }

    val renderer = remember { CadRenderer() }
    var framedOnce by remember { mutableStateOf(false) }

    fun applyResult(result: CadDocumentState) {
        if (!result.ok) {
            scope.launch { snackbar.showSnackbar(result.error ?: tr("Ошибка CAD")) }
            return
        }
        val prevGen = doc.topoGeneration
        doc = result
        if (result.topoGeneration != prevGen) {
            selectedFace = null
            selectedEdges = emptySet()
        }
        val path = result.meshPath
        displayMesh = path?.let { CadDisplayMesh.read(File(it)) }
        if (!framedOnce && result.bounds != null) {
            val b = result.bounds
            val span = maxOf(b.sizeX, b.sizeY, b.sizeZ, 30f)
            renderer.camera.frame(b.centerX, b.centerY, b.centerZ, span * 1.4f)
            framedOnce = true
        }
    }

    fun runCad(block: suspend () -> CadDocumentState) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { block() }
                applyResult(res)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: tr("Ошибка CAD"))
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(recipeFile) {
        runCad { cad.open(sessionDir, recipeFile) }
    }

    val importStepLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCad {
                val tmp = File(sessionDir, "import.step")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: error(tr("Файл недоступен"))
                try {
                    cad.importStep(tmp)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    if (sketchMode != null) {
        val (planeKind, faceRef) = sketchMode!!
        val label = if (planeKind == "face" && faceRef != null) tr("Грань #{0}", faceRef.second + 1) else planeKind
        SketchEditorOverlay(
            planeLabel = label,
            initialEntities = emptyList(),
            initialConstraints = emptyList(),
            onCancel = { sketchMode = null },
            onDone = { entities, constraints ->
                sketchMode = null
                runCad {
                    cad.addSketch(
                        plane = planeKind,
                        entities = entities,
                        constraints = constraints,
                        bodyIndex = faceRef?.first ?: 0,
                        faceId = faceRef?.second ?: -1,
                        topoGeneration = if (planeKind == "face") doc.topoGeneration else null,
                    )
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            DesktopHeader(state, viewModel) {
                IconButton(onClick = { runCad { cad.undo() } }, enabled = doc.canUndo && !busy) {
                    Icon(Icons.Default.ArrowBack, tr("Отменить"))
                }
                IconButton(onClick = { runCad { cad.redo() } }, enabled = doc.canRedo && !busy) {
                    Icon(Icons.Default.ArrowForward, tr("Повторить"))
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = {
                        if (selectedFace != null) sketchMode = "face" to selectedFace
                        else sketchPlaneDialog = true
                    }, enabled = !busy) { Text(tr("Эскиз")) }

                    FilledTonalButton(
                        onClick = { showExtrudeSheet = true },
                        enabled = !busy && doc.features.any { it.type == "sketch" && it.enabled },
                    ) { Text(tr("Выдавить")) }

                    FilledTonalButton(
                        onClick = { showFilletSheet = true },
                        enabled = !busy && doc.bodyCount > 0,
                    ) { Text(tr("Скругление")) }

                    FilledTonalButton(
                        onClick = { showFilletSheet = false },
                        enabled = !busy && doc.bodyCount > 0,
                    ) { Text(tr("Фаска")) }

                    FilledTonalButton(
                        onClick = { showHoleSheet = true },
                        enabled = !busy && doc.bodyCount > 0,
                    ) { Text(tr("Отверстие")) }

                    OutlinedButton(onClick = { showTreeSheet = true }, enabled = !busy) {
                        Text(tr("Дерево ({0})", doc.features.size))
                    }

                    OutlinedButton(onClick = { importStepLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                        Text("STEP")
                    }

                    Button(
                        onClick = {
                            runCad {
                                val stl = File(sessionDir, "cad-export.stl")
                                val saved = cad.save(recipeFile)
                                if (!saved.ok) return@runCad saved
                                val exported = cad.exportStl(stl)
                                if (exported.ok) {
                                    withContext(Dispatchers.Main) {
                                        viewModel.sendCadToPlate(stl, recipeFile, tr("CAD-деталь"))
                                    }
                                }
                                exported
                            }
                        },
                        enabled = !busy && doc.bodyCount > 0,
                    ) { Text(tr("На стол")) }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CadSceneView(
                renderer = renderer,
                modifier = Modifier.fillMaxSize(),
                onTap = { x, y, w, h ->
                    val mesh = displayMesh ?: return@CadSceneView
                    val ray = renderer.camera.ray(x, y, w, h) ?: return@CadSceneView
                    val edgeTol = (renderer.camera.clipPlanes()[0] * 0.08f).coerceIn(0.8f, 4.0f)
                    when (pickMode) {
                        CadPickMode.EDGE -> {
                            val edge = mesh.pickEdge(ray.first, ray.second, edgeTol)
                            selectedEdges = if (edge != null) {
                                if (edge in selectedEdges) selectedEdges - edge else selectedEdges + edge
                            } else emptySet()
                        }
                        CadPickMode.FACE -> {
                            selectedFace = mesh.pickFace(ray.first, ray.second)
                        }
                        CadPickMode.AUTO -> {
                            val edge = mesh.pickEdge(ray.first, ray.second, edgeTol * 0.7f)
                            if (edge != null) {
                                selectedFace = null
                                selectedEdges = if (edge in selectedEdges) selectedEdges - edge else selectedEdges + edge
                            } else {
                                selectedEdges = emptySet()
                                selectedFace = mesh.pickFace(ray.first, ray.second)
                            }
                        }
                    }
                },
            ) { r ->
                r.setBed(BedShape.rectangle(-100f, -100f, 100f, 100f), 120f)
                r.setRenderOptions(state.renderOptions)
                if (!framedOnce) {
                    r.camera.frame(0f, 0f, 0f, 65f)
                    framedOnce = true
                }
                r.displayMesh = displayMesh
                r.selectedFace = selectedFace
                r.selectedEdges = selectedEdges
            }

            // Top-left selection / dimensions badge
            Column(Modifier.align(Alignment.TopStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    val info = when {
                        selectedEdges.isNotEmpty() -> tr("Выбрано рёбер: {0}", selectedEdges.size)
                        selectedFace != null -> tr("Выбрана грань #{0}", selectedFace!!.second + 1)
                        doc.bounds != null -> tr("%.1f × %.1f × %.1f мм · %.0f мм³").format(
                            doc.bounds!!.sizeX, doc.bounds!!.sizeY, doc.bounds!!.sizeZ, doc.volumeMm3,
                        )
                        else -> tr("Создайте эскиз, чтобы начать модель")
                    }
                    Text(info, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = pickMode == CadPickMode.AUTO, onClick = { pickMode = CadPickMode.AUTO }, label = { Text(tr("Авто")) })
                    FilterChip(selected = pickMode == CadPickMode.FACE, onClick = { pickMode = CadPickMode.FACE }, label = { Text(tr("Грань")) })
                    FilterChip(selected = pickMode == CadPickMode.EDGE, onClick = { pickMode = CadPickMode.EDGE }, label = { Text(tr("Ребро")) })
                    if (selectedFace != null || selectedEdges.isNotEmpty()) {
                        AssistChip(onClick = { selectedFace = null; selectedEdges = emptySet() }, label = { Text(tr("Сброс")) })
                    }
                }
            }

            if (doc.features.isEmpty() && !busy) {
                Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Параметрический CAD"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("Нарисуйте 2D-эскиз и выдавите деталь прямо на телефоне."), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { sketchMode = "XY" to null }) { Text(tr("Новый эскиз (XY)")) }
                    }
                }
            }

            if (busy) {
                Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(tr("Пересчёт геометрии…"))
                        TextButton(onClick = { cad.cancelRunning(); busy = false }) { Text(tr("Отмена")) }
                    }
                }
            }
        }
    }

    // Choose sketch plane dialog
    if (sketchPlaneDialog) {
        AlertDialog(
            onDismissRequest = { sketchPlaneDialog = false },
            title = { Text(tr("Плоскость эскиза")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sketchPlaneDialog = false; sketchMode = "XY" to null }, modifier = Modifier.fillMaxWidth()) { Text("XY (" + tr("Горизонтальная") + ")") }
                    OutlinedButton(onClick = { sketchPlaneDialog = false; sketchMode = "XZ" to null }, modifier = Modifier.fillMaxWidth()) { Text("XZ (" + tr("Фронтальная") + ")") }
                    OutlinedButton(onClick = { sketchPlaneDialog = false; sketchMode = "YZ" to null }, modifier = Modifier.fillMaxWidth()) { Text("YZ (" + tr("Боковая") + ")") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sketchPlaneDialog = false }) { Text(tr("Отмена")) } },
        )
    }

    // Extrude sheet
    if (showExtrudeSheet) {
        val sketches = doc.features.filter { it.type == "sketch" && it.enabled }
        var sketchRef by remember { mutableStateOf(sketches.lastOrNull()?.index ?: -1) }
        var distText by remember { mutableStateOf("10") }
        var mode by remember { mutableStateOf(if (doc.bodyCount == 0) "new" else "add") }
        var symmetric by remember { mutableStateOf(false) }
        var flip by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { showExtrudeSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Выдавливание"), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sk in sketches) {
                        FilterChip(selected = sketchRef == sk.index, onClick = { sketchRef = sk.index }, label = { Text(sk.name) })
                    }
                }
                OutlinedTextField(distText, { distText = it }, label = { Text(tr("Высота (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("new" to tr("Новое тело"), "add" to tr("Добавить"), "cut" to tr("Вырезать"), "intersect" to tr("Пересечение")).forEach { (m, lbl) ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(lbl) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(symmetric, { symmetric = it }); Text(tr("Симметрично")) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(flip, { flip = it }); Text(tr("Развернуть")) }
                }
                Button(
                    onClick = {
                        val d = distText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.05 } ?: return@Button
                        showExtrudeSheet = false
                        runCad { cad.addExtrude(sketchRef = sketchRef, distance = d, mode = mode, symmetric = symmetric, flip = flip) }
                    },
                    enabled = sketchRef >= 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Применить")) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Fillet / Chamfer sheet
    val isFillet = showFilletSheet
    if (isFillet != null) {
        var sizeText by remember { mutableStateOf("1.5") }
        var faceGroup by remember { mutableStateOf("top") }
        ModalBottomSheet(onDismissRequest = { showFilletSheet = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isFillet) tr("Скругление") else tr("Фаска"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(sizeText, { sizeText = it }, label = { Text(if (isFillet) tr("Радиус (мм)") else tr("Размер (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (selectedEdges.isNotEmpty()) {
                    Text(tr("Будет применено к выбранным рёбрам ({0})", selectedEdges.size), style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("top" to tr("Верх"), "bottom" to tr("Низ"), "lateral" to tr("Боковые"), "all" to tr("Все")).forEach { (g, lbl) ->
                            FilterChip(selected = faceGroup == g, onClick = { faceGroup = g }, label = { Text(lbl) })
                        }
                    }
                }
                Button(
                    onClick = {
                        val sz = sizeText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.05 } ?: return@Button
                        val edges = selectedEdges.map { it.second }
                        val bodyIdx = selectedEdges.firstOrNull()?.first ?: -1
                        showFilletSheet = null
                        runCad {
                            if (isFillet) cad.addFillet(radius = sz, edgeIds = edges, faceGroup = faceGroup, targetBody = bodyIdx, topoGeneration = doc.topoGeneration)
                            else cad.addChamfer(distance = sz, edgeIds = edges, faceGroup = faceGroup, targetBody = bodyIdx, topoGeneration = doc.topoGeneration)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Применить")) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Hole sheet
    if (showHoleSheet) {
        var diaText by remember { mutableStateOf("5") }
        var depthText by remember { mutableStateOf("10") }
        var xText by remember { mutableStateOf("0") }
        var yText by remember { mutableStateOf("0") }
        var through by remember { mutableStateOf(true) }
        ModalBottomSheet(onDismissRequest = { showHoleSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Отверстие"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(diaText, { diaText = it }, label = { Text(tr("Диаметр (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(through, { through = it }); Text(tr("Насквозь")) }
                if (!through) {
                    OutlinedTextField(depthText, { depthText = it }, label = { Text(tr("Глубина (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(xText, { xText = it }, label = { Text("X (мм)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(yText, { yText = it }, label = { Text("Y (мм)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(
                    onClick = {
                        val d = diaText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.1 } ?: return@Button
                        val depth = depthText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.1 } ?: 10.0
                        val hx = xText.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val hy = yText.replace(',', '.').toDoubleOrNull() ?: 0.0
                        val face = selectedFace
                        showHoleSheet = false
                        runCad {
                            cad.addHole(
                                diameter = d,
                                depth = depth,
                                through = through,
                                x = hx,
                                y = hy,
                                plane = if (face != null) "face" else "XY",
                                bodyIndex = face?.first ?: 0,
                                faceId = face?.second ?: -1,
                                topoGeneration = if (face != null) doc.topoGeneration else null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Применить")) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Feature tree sheet
    if (showTreeSheet) {
        ModalBottomSheet(onDismissRequest = { showTreeSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Дерево построений"), style = MaterialTheme.typography.titleMedium)
                if (doc.features.isEmpty()) {
                    Text(tr("Нет операций"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(doc.features, key = { it.index }) { f ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = f.enabled, onCheckedChange = { on -> runCad { cad.setFeatureEnabled(f.index, on) } })
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    val detail = when (f.type) {
                                        "extrude" -> "${f.mode} · ${"%.1f".format(f.distance)} мм"
                                        "fillet", "chamfer" -> "${"%.1f".format(f.size)} мм"
                                        "hole" -> "Ø${"%.1f".format(f.diameter)} мм"
                                        else -> f.type
                                    }
                                    Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { runCad { cad.removeFeature(f.index) } }) {
                                    Icon(Icons.Default.Delete, tr("Удалить"))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
