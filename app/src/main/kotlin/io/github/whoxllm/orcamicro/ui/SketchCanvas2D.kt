package io.github.whoxllm.orcamicro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.cad.CadSketchConstraint
import io.github.whoxllm.orcamicro.cad.CadSketchEntity
import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlin.math.*

enum class SketchTool { SELECT, LINE, RECT, CIRCLE, ARC }

/**
 * Full-screen 2D touch sketch editor with tap-to-place points, magnetic endpoint/grid snapping,
 * quick dimension entry, and basic geometric constraints (Horizontal, Vertical, Equal, Length/Radius).
 */
@Composable
fun SketchEditorOverlay(
    planeLabel: String,
    initialEntities: List<CadSketchEntity>,
    initialConstraints: List<CadSketchConstraint>,
    onCancel: () -> Unit,
    onDone: (List<CadSketchEntity>, List<CadSketchConstraint>) -> Unit,
) {
    var tool by remember { mutableStateOf(SketchTool.RECT) }
    var entities by remember { mutableStateOf(initialEntities) }
    var constraints by remember { mutableStateOf(initialConstraints) }
    var pendingPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var selectedEntity by remember { mutableStateOf<Int?>(null) }
    var snapGridMm by remember { mutableStateOf(5.0) }
    var pxPerMm by remember { mutableStateOf(8f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var showQuickRect by remember { mutableStateOf(false) }
    var showQuickCircle by remember { mutableStateOf(false) }
    var showDimensionDialog by remember { mutableStateOf(false) }
    var dimText by remember { mutableStateOf("") }

    fun snapPoint(rawX: Double, rawY: Double): Pair<Double, Double> {
        val snapTolMm = (22f / pxPerMm).toDouble()
        // Magnetic snap to existing endpoints and origin first.
        val candidates = ArrayList<Pair<Double, Double>>()
        candidates.add(0.0 to 0.0)
        for (e in entities) {
            when (e.type) {
                "line", "arc" -> {
                    candidates.add(e.p0x to e.p0y)
                    candidates.add(e.p1x to e.p1y)
                }
                "circle" -> candidates.add(e.cx to e.cy)
            }
        }
        var best: Pair<Double, Double>? = null
        var bestDist = snapTolMm
        for (pt in candidates) {
            val d = hypot(pt.first - rawX, pt.second - rawY)
            if (d < bestDist) {
                bestDist = d
                best = pt
            }
        }
        if (best != null) return best
        val step = snapGridMm
        return (round(rawX / step) * step) to (round(rawY / step) * step)
    }

    fun pickEntity(x: Double, y: Double): Int? {
        val tol = (20f / pxPerMm).toDouble()
        var bestIdx: Int? = null
        var bestDist = tol
        entities.forEachIndexed { idx, e ->
            val d = when (e.type) {
                "circle" -> abs(hypot(x - e.cx, y - e.cy) - e.radius)
                "arc" -> abs(hypot(x - e.cx, y - e.cy) - e.radius)
                else -> pointToSegmentDist(x, y, e.p0x, e.p0y, e.p1x, e.p1y)
            }
            if (d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        return bestIdx
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Top bar
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onCancel) { Text(tr("Отмена")) }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Эскиз · {0}", planeLabel), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("Элементов: {0} · сетка {1} мм", entities.size, snapGridMm.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { snapGridMm = when (snapGridMm) { 1.0 -> 5.0; 5.0 -> 10.0; else -> 1.0 } }) {
                        Text("${snapGridMm.toInt()} мм")
                    }
                    Button(
                        onClick = { onDone(entities, constraints) },
                        enabled = entities.isNotEmpty(),
                    ) { Text(tr("Готово")) }
                }
            }

            // Tool strip
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = tool == SketchTool.LINE, onClick = { tool = SketchTool.LINE; pendingPoints = emptyList() }, label = { Text(tr("Линия")) })
                    FilterChip(selected = tool == SketchTool.RECT, onClick = { tool = SketchTool.RECT; pendingPoints = emptyList() }, label = { Text(tr("Прямоугольник")) })
                    FilterChip(selected = tool == SketchTool.CIRCLE, onClick = { tool = SketchTool.CIRCLE; pendingPoints = emptyList() }, label = { Text(tr("Окружность")) })
                    FilterChip(selected = tool == SketchTool.ARC, onClick = { tool = SketchTool.ARC; pendingPoints = emptyList() }, label = { Text(tr("Дуга")) })
                    FilterChip(selected = tool == SketchTool.SELECT, onClick = { tool = SketchTool.SELECT; pendingPoints = emptyList() }, label = { Text(tr("Выбор")) })
                    AssistChip(onClick = { showQuickRect = true }, label = { Text(tr("+ Прямоуг. W×H")) })
                    AssistChip(onClick = { showQuickCircle = true }, label = { Text(tr("+ Круг Ø")) })
                }
            }

            // Interactive 2D Canvas
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E2226))) {
                var canvasSize by remember { mutableStateOf(Size.Zero) }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                pxPerMm = (pxPerMm * zoom).coerceIn(1.5f, 40f)
                                panOffset += pan
                            }
                        }
                        .pointerInput(tool, entities, pendingPoints, pxPerMm, panOffset, snapGridMm) {
                            detectTapGestures { offset ->
                                val cx = size.width / 2f + panOffset.x
                                val cy = size.height / 2f + panOffset.y
                                val rawX = ((offset.x - cx) / pxPerMm).toDouble()
                                val rawY = ((cy - offset.y) / pxPerMm).toDouble()
                                if (tool == SketchTool.SELECT) {
                                    selectedEntity = pickEntity(rawX, rawY)
                                    return@detectTapGestures
                                }
                                val pt = snapPoint(rawX, rawY)
                                when (tool) {
                                    SketchTool.LINE -> {
                                        if (pendingPoints.isEmpty()) {
                                            pendingPoints = listOf(pt)
                                        } else {
                                            val p0 = pendingPoints.last()
                                            if (hypot(pt.first - p0.first, pt.second - p0.second) > 0.1) {
                                                entities = entities + CadSketchEntity.line(p0.first, p0.second, pt.first, pt.second)
                                                // Chain lines unless closing onto an existing point
                                                val closed = entities.dropLast(1).any { e ->
                                                    hypot(e.p0x - pt.first, e.p0y - pt.second) < 0.05 ||
                                                        hypot(e.p1x - pt.first, e.p1y - pt.second) < 0.05
                                                }
                                                pendingPoints = if (closed) emptyList() else listOf(pt)
                                            }
                                        }
                                    }
                                    SketchTool.RECT -> {
                                        if (pendingPoints.isEmpty()) {
                                            pendingPoints = listOf(pt)
                                        } else {
                                            val p0 = pendingPoints.first()
                                            if (abs(pt.first - p0.first) > 0.1 && abs(pt.second - p0.second) > 0.1) {
                                                entities = entities + CadSketchEntity.rectangle(p0.first, p0.second, pt.first, pt.second)
                                            }
                                            pendingPoints = emptyList()
                                        }
                                    }
                                    SketchTool.CIRCLE -> {
                                        if (pendingPoints.isEmpty()) {
                                            pendingPoints = listOf(pt)
                                        } else {
                                            val c = pendingPoints.first()
                                            val r = hypot(pt.first - c.first, pt.second - c.second)
                                            if (r > 0.1) {
                                                entities = entities + CadSketchEntity.circle(c.first, c.second, r)
                                            }
                                            pendingPoints = emptyList()
                                        }
                                    }
                                    SketchTool.ARC -> {
                                        if (pendingPoints.size < 2) {
                                            pendingPoints = pendingPoints + pt
                                        } else {
                                            val c = pendingPoints[0]
                                            val startPt = pendingPoints[1]
                                            val r = hypot(startPt.first - c.first, startPt.second - c.second)
                                            if (r > 0.1) {
                                                val a0 = atan2(startPt.second - c.second, startPt.first - c.first)
                                                val a1 = atan2(pt.second - c.second, pt.first - c.first)
                                                entities = entities + CadSketchEntity.arc(c.first, c.second, r, a0, a1)
                                            }
                                            pendingPoints = emptyList()
                                        }
                                    }
                                    SketchTool.SELECT -> Unit
                                }
                            }
                        }
                ) {
                    canvasSize = size
                    val cx = size.width / 2f + panOffset.x
                    val cy = size.height / 2f + panOffset.y
                    fun toScreen(x: Double, y: Double) = Offset(cx + (x * pxPerMm).toFloat(), cy - (y * pxPerMm).toFloat())

                    // Grid
                    val stepPx = (snapGridMm * pxPerMm).toFloat().coerceAtLeast(10f)
                    val gridColor = Color(0xFF2D333B)
                    var gx = cx % stepPx
                    while (gx < size.width) {
                        drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                        gx += stepPx
                    }
                    var gy = cy % stepPx
                    while (gy < size.height) {
                        drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                        gy += stepPx
                    }
                    // Axes
                    drawLine(Color(0x88E5484D), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2f)
                    drawLine(Color(0x884CAF50), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
                    drawCircle(Color(0xFF009688), radius = 5f, center = Offset(cx, cy))

                    // Entities
                    entities.forEachIndexed { idx, e ->
                        val isSel = selectedEntity == idx
                        val strokeColor = if (isSel) Color(0xFFFFB300) else Color(0xFF4DD0E1)
                        val width = if (isSel) 5f else 3f
                        when (e.type) {
                            "line" -> {
                                val a = toScreen(e.p0x, e.p0y)
                                val b = toScreen(e.p1x, e.p1y)
                                drawLine(strokeColor, a, b, strokeWidth = width)
                                drawCircle(strokeColor, 5f, a)
                                drawCircle(strokeColor, 5f, b)
                            }
                            "circle" -> {
                                val c = toScreen(e.cx, e.cy)
                                val rPx = (e.radius * pxPerMm).toFloat()
                                drawCircle(strokeColor, rPx, c, style = Stroke(width = width))
                                drawCircle(strokeColor, 4f, c)
                            }
                            "arc" -> {
                                val c = toScreen(e.cx, e.cy)
                                val rPx = (e.radius * pxPerMm).toFloat()
                                var sweep = Math.toDegrees(e.endAngle - e.startAngle).toFloat()
                                if (sweep <= 0f) sweep += 360f
                                val startDeg = -Math.toDegrees(e.startAngle).toFloat()
                                drawArc(
                                    color = strokeColor,
                                    startAngle = startDeg,
                                    sweepAngle = -sweep,
                                    useCenter = false,
                                    topLeft = Offset(c.x - rPx, c.y - rPx),
                                    size = Size(rPx * 2f, rPx * 2f),
                                    style = Stroke(width = width),
                                )
                                drawCircle(strokeColor, 5f, toScreen(e.p0x, e.p0y))
                                drawCircle(strokeColor, 5f, toScreen(e.p1x, e.p1y))
                            }
                        }
                    }

                    // Pending points
                    pendingPoints.forEach { pt ->
                        val o = toScreen(pt.first, pt.second)
                        drawCircle(Color(0xFFFFB300), 8f, o, style = Stroke(width = 3f))
                    }
                }

                // Prompt hint pill
                val hint = when (tool) {
                    SketchTool.LINE -> if (pendingPoints.isEmpty()) tr("Нажмите начальную точку линии") else tr("Нажмите следующую точку (или замкните контур)")
                    SketchTool.RECT -> if (pendingPoints.isEmpty()) tr("Нажмите первый угол прямоугольника") else tr("Нажмите противоположный угол")
                    SketchTool.CIRCLE -> if (pendingPoints.isEmpty()) tr("Нажмите центр окружности") else tr("Нажмите точку на окружности")
                    SketchTool.ARC -> when (pendingPoints.size) { 0 -> tr("Нажмите центр дуги"); 1 -> tr("Нажмите начало дуги"); else -> tr("Нажмите конец дуги") }
                    SketchTool.SELECT -> tr("Нажмите на элемент, чтобы задать размер или ограничение")
                }
                Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                    Text(hint, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Bottom contextual bar (constraints / clear / undo entity)
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val sel = selectedEntity
                    if (sel != null && sel in entities.indices) {
                        val e = entities[sel]
                        if (e.type == "line") {
                            OutlinedButton(onClick = {
                                constraints = constraints + CadSketchConstraint("horizontal", ea = sel)
                            }) { Text(tr("Гориз.")) }
                            OutlinedButton(onClick = {
                                constraints = constraints + CadSketchConstraint("vertical", ea = sel)
                            }) { Text(tr("Верт.")) }
                            OutlinedButton(onClick = {
                                val cur = hypot(e.p1x - e.p0x, e.p1y - e.p0y)
                                dimText = "%.1f".format(cur)
                                showDimensionDialog = true
                            }) { Text(tr("Длина")) }
                        } else if (e.type == "circle" || e.type == "arc") {
                            OutlinedButton(onClick = {
                                dimText = "%.1f".format(e.radius)
                                showDimensionDialog = true
                            }) { Text(tr("Радиус")) }
                        }
                        OutlinedButton(onClick = {
                            entities = entities.filterIndexed { i, _ -> i != sel }
                            constraints = constraints.filter { it.ea != sel && it.eb != sel }
                            selectedEntity = null
                        }) { Text(tr("Удалить")) }
                    } else {
                        OutlinedButton(
                            onClick = { if (entities.isNotEmpty()) entities = entities.dropLast(1); pendingPoints = emptyList() },
                            enabled = entities.isNotEmpty(),
                        ) { Text(tr("Убрать последний")) }
                        OutlinedButton(
                            onClick = { entities = emptyList(); constraints = emptyList(); pendingPoints = emptyList() },
                            enabled = entities.isNotEmpty(),
                        ) { Text(tr("Очистить")) }
                    }
                }
            }
        }
    }

    if (showQuickRect) {
        var wText by remember { mutableStateOf("40") }
        var hText by remember { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { showQuickRect = false },
            title = { Text(tr("Прямоугольник по центру")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(wText, { wText = it }, label = { Text(tr("Ширина (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(hText, { hText = it }, label = { Text(tr("Высота (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val w = wText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.1 } ?: return@Button
                    val h = hText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.1 } ?: return@Button
                    entities = entities + CadSketchEntity.rectangle(-w / 2.0, -h / 2.0, w / 2.0, h / 2.0)
                    showQuickRect = false
                }) { Text(tr("Добавить")) }
            },
            dismissButton = { TextButton(onClick = { showQuickRect = false }) { Text(tr("Отмена")) } },
        )
    }

    if (showQuickCircle) {
        var dText by remember { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { showQuickCircle = false },
            title = { Text(tr("Окружность по центру")) },
            text = {
                OutlinedTextField(dText, { dText = it }, label = { Text(tr("Диаметр (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val d = dText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.1 } ?: return@Button
                    entities = entities + CadSketchEntity.circle(0.0, 0.0, d / 2.0)
                    showQuickCircle = false
                }) { Text(tr("Добавить")) }
            },
            dismissButton = { TextButton(onClick = { showQuickCircle = false }) { Text(tr("Отмена")) } },
        )
    }

    if (showDimensionDialog && selectedEntity != null && selectedEntity!! in entities.indices) {
        val idx = selectedEntity!!
        val e = entities[idx]
        AlertDialog(
            onDismissRequest = { showDimensionDialog = false },
            title = { Text(if (e.type == "line") tr("Задать длину (мм)") else tr("Задать радиус (мм)")) },
            text = {
                OutlinedTextField(dimText, { dimText = it }, label = { Text(tr("Значение (мм)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val v = dimText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.05 } ?: return@Button
                    if (e.type == "line") {
                        constraints = constraints + CadSketchConstraint("distance", ea = idx, eb = idx, ra = "p0", rb = "p1", value = v)
                        val cur = hypot(e.p1x - e.p0x, e.p1y - e.p0y).coerceAtLeast(1e-6)
                        val scale = v / cur
                        val updated = e.copy(p1x = e.p0x + (e.p1x - e.p0x) * scale, p1y = e.p0y + (e.p1y - e.p0y) * scale)
                        entities = entities.mapIndexed { i, item -> if (i == idx) updated else item }
                    } else {
                        constraints = constraints + CadSketchConstraint("radius", ea = idx, value = v)
                        val updated = e.copy(radius = v)
                        entities = entities.mapIndexed { i, item -> if (i == idx) updated else item }
                    }
                    showDimensionDialog = false
                }) { Text(tr("Применить")) }
            },
            dismissButton = { TextButton(onClick = { showDimensionDialog = false }) { Text(tr("Отмена")) } },
        )
    }
}

private fun pointToSegmentDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq < 1e-9) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
