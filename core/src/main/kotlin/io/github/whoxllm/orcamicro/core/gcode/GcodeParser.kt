package io.github.whoxllm.orcamicro.core.gcode

import java.io.BufferedReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Reads the G-code OrcaSlicer writes into per-layer toolpaths for the preview, plus the print
 * statistics from its comments. It understands G0/G1, G2/G3 arcs (I/J form), G90/G91, M82/M83
 * and G92, and Orca's `;LAYER_CHANGE`, `;Z:`, `;HEIGHT:` and `;TYPE:` comments (and their
 * Bambu-style `; CHANGE_LAYER` / `; FEATURE:` spellings).
 */
class GcodeParser {
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var e = 0.0
    private var absoluteXyz = true
    private var absoluteE = true
    private var feature = Feature.CUSTOM

    private val layers = ArrayList<ToolpathLayer>()
    private val featuresSeen = HashSet<Feature>()
    private var current: LayerBuilder? = null
    private var pendingLayer = false
    private var sawLayerChange = false
    private var pendingZ: Float? = null
    private var pendingHeight = 0f

    private var printSeconds: Long? = null
    private var totalSeconds: Long? = null
    private var filamentMm: Double? = null
    private var filamentGrams: Double? = null

    fun parse(reader: BufferedReader): Toolpaths {
        reader.forEachLine { parseLine(it) }
        finishLayer()
        return Toolpaths(
            layers = layers.toList(),
            stats = GcodeStats(totalSeconds ?: printSeconds, filamentMm, filamentGrams),
            features = featuresSeen.toSet(),
        )
    }

    private fun parseLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val semicolon = line.indexOf(';')
        if (semicolon >= 0) parseComment(line.substring(semicolon + 1))
        val code = (if (semicolon >= 0) line.substring(0, semicolon) else line).trim()
        if (code.isEmpty()) return
        val words = code.split(' ', '\t').filter { it.isNotEmpty() }
        when (words[0].uppercase()) {
            "G0", "G00", "G1", "G01" -> linearMove(words)
            "G2", "G02" -> arcMove(words, clockwise = true)
            "G3", "G03" -> arcMove(words, clockwise = false)
            "G90" -> absoluteXyz = true
            "G91" -> absoluteXyz = false
            "M82" -> absoluteE = true
            "M83" -> absoluteE = false
            "G92" -> setPosition(words)
        }
    }

    private fun parseComment(comment: String) {
        val text = comment.trim()
        when {
            text == "LAYER_CHANGE" || text == "CHANGE_LAYER" -> startLayer()
            text.startsWith("Z:") -> setLayerZ(text.substring(2))
            text.startsWith("Z_HEIGHT:") -> setLayerZ(text.substring(9))
            text.startsWith("HEIGHT:") -> setLayerHeight(text.substring(7))
            text.startsWith("LAYER_HEIGHT:") -> setLayerHeight(text.substring(13))
            text.startsWith("TYPE:") -> feature = Feature.fromLabel(text.substring(5))
            text.startsWith("FEATURE:") -> feature = Feature.fromLabel(text.substring(8))
            text.startsWith("estimated printing time (normal mode)") ->
                printSeconds = parseDuration(text.substringAfter('='))
            text.startsWith("model printing time:") && text.contains("total estimated time:") ->
                totalSeconds = parseDuration(text.substringAfter("total estimated time:"))
            text.startsWith("filament used [mm]") -> filamentMm = sumList(text.substringAfter('='))
            text.startsWith("total filament used [g]") -> filamentGrams = sumList(text.substringAfter('='))
            text.startsWith("filament used [g]") && filamentGrams == null -> filamentGrams = sumList(text.substringAfter('='))
        }
    }

    private fun startLayer() {
        val prelude = current
        if (!sawLayerChange && prelude != null) {
            // Purge lines from the start G-code come before the first layer marker; show them with layer 1.
            prelude.awaitingZ = true
        } else {
            finishLayer()
            pendingLayer = true
        }
        sawLayerChange = true
        pendingZ = null
    }

    private fun setLayerZ(text: String) {
        val value = text.trim().toFloatOrNull() ?: return
        pendingZ = value
        current?.takeIf { it.awaitingZ }?.let { it.z = value; it.awaitingZ = false }
    }

    private fun setLayerHeight(text: String) {
        val value = text.trim().toFloatOrNull() ?: return
        pendingHeight = value
        current?.let { if (it.extrusionCount == 0 || it.height == 0f) it.height = value }
    }

    private class Params(words: List<String>) {
        val values = HashMap<Char, Double>(8)

        init {
            for (i in 1 until words.size) {
                val w = words[i]
                if (w.length < 2) continue
                w.substring(1).toDoubleOrNull()?.let { values[w[0].uppercaseChar()] = it }
            }
        }

        operator fun get(c: Char): Double? = values[c]
    }

    private fun target(p: Params): Triple<Float, Float, Float> {
        fun axis(c: Char, now: Float): Float {
            val v = p[c] ?: return now
            return if (absoluteXyz) v.toFloat() else now + v.toFloat()
        }
        return Triple(axis('X', x), axis('Y', y), axis('Z', z))
    }

    private fun extrusionDelta(p: Params): Double {
        val v = p['E'] ?: return 0.0
        return if (absoluteE) v - e else v
    }

    private fun advanceE(p: Params) {
        val v = p['E'] ?: return
        e = if (absoluteE) v else e + v
    }

    private fun linearMove(words: List<String>) {
        val p = Params(words)
        val (nx, ny, nz) = target(p)
        val de = extrusionDelta(p)
        val moved = nx != x || ny != y
        if (moved) {
            if (de > 1e-6) addExtrusion(x, y, z, nx, ny, nz) else addTravel(x, y, z, nx, ny, nz)
        }
        x = nx; y = ny; z = nz
        advanceE(p)
    }

    private fun arcMove(words: List<String>, clockwise: Boolean) {
        val p = Params(words)
        val (nx, ny, nz) = target(p)
        val i = p['I']?.toFloat()
        val j = p['J']?.toFloat()
        if (i == null && j == null) {
            linearMove(words) // R-form arcs are rare in slicer output; draw them straight.
            return
        }
        val cx = x + (i ?: 0f)
        val cy = y + (j ?: 0f)
        val radius = hypot(x - cx, y - cy)
        val start = atan2(y - cy, x - cx)
        var end = atan2(ny - cy, nx - cx)
        var sweep = end - start
        if (clockwise && sweep >= 0f) sweep -= (2 * PI).toFloat()
        if (!clockwise && sweep <= 0f) sweep += (2 * PI).toFloat()
        end = start + sweep
        val segments = ceil(abs(sweep) * radius / ARC_SEGMENT_MM).toInt().coerceIn(1, 128)
        val extruding = extrusionDelta(p) > 1e-6
        var px = x; var py = y; var pz = z
        for (k in 1..segments) {
            val t = k.toFloat() / segments
            val a = start + (end - start) * t
            val sx = if (k == segments) nx else cx + radius * cos(a)
            val sy = if (k == segments) ny else cy + radius * sin(a)
            val sz = z + (nz - z) * t
            if (extruding) addExtrusion(px, py, pz, sx, sy, sz) else addTravel(px, py, pz, sx, sy, sz)
            px = sx; py = sy; pz = sz
        }
        x = nx; y = ny; z = nz
        advanceE(p)
    }

    private fun setPosition(words: List<String>) {
        val p = Params(words)
        p['X']?.let { x = it.toFloat() }
        p['Y']?.let { y = it.toFloat() }
        p['Z']?.let { z = it.toFloat() }
        p['E']?.let { e = it }
    }

    private fun layer(): LayerBuilder {
        val existing = current
        if (existing != null && !pendingLayer) return existing
        pendingLayer = false
        return LayerBuilder(pendingZ ?: z, pendingHeight).also { current = it }
    }

    private fun addExtrusion(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        layer().addExtrusion(x0, y0, z0, x1, y1, z1, feature)
        featuresSeen.add(feature)
    }

    private fun addTravel(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        // Travels before the first extrusion of a layer belong to it; travels outside layers are dropped.
        val layer = if (pendingLayer || current != null) layer() else return
        layer.addTravel(x0, y0, z0, x1, y1, z1)
    }

    private fun finishLayer() {
        val layer = current ?: return
        current = null
        if (layer.extrusionCount > 0) layers.add(layer.build())
    }

    private class LayerBuilder(var z: Float, var height: Float) {
        /** Set on the pre-layer builder once the first layer marker is seen, until its Z comment arrives. */
        var awaitingZ = false
        private var extrusions = FloatArray(6 * 256)
        private var features = ByteArray(256)
        private var travels = FloatArray(6 * 64)
        var extrusionCount = 0
            private set
        private var travelCount = 0

        fun addExtrusion(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, feature: Feature) {
            if (extrusionCount == features.size) {
                features = features.copyOf(features.size * 2)
                extrusions = extrusions.copyOf(extrusions.size * 2)
            }
            put(extrusions, extrusionCount * 6, x0, y0, z0, x1, y1, z1)
            features[extrusionCount++] = feature.ordinal.toByte()
        }

        fun addTravel(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            if ((travelCount + 1) * 6 > travels.size) travels = travels.copyOf(travels.size * 2)
            put(travels, travelCount * 6, x0, y0, z0, x1, y1, z1)
            travelCount++
        }

        private fun put(a: FloatArray, o: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            a[o] = x0; a[o + 1] = y0; a[o + 2] = z0; a[o + 3] = x1; a[o + 4] = y1; a[o + 5] = z1
        }

        fun build() = ToolpathLayer(
            z = z,
            height = height,
            extrusions = extrusions.copyOf(extrusionCount * 6),
            features = features.copyOf(extrusionCount),
            travels = travels.copyOf(travelCount * 6),
        )
    }

    companion object {
        private const val ARC_SEGMENT_MM = 0.5f

        fun parse(reader: BufferedReader): Toolpaths = GcodeParser().parse(reader)

        /** Parses Orca's "1d 2h 3m 4s" style durations. */
        fun parseDuration(text: String): Long? {
            var total = 0L
            var found = false
            for (token in text.trim().split(' ').filter { it.isNotEmpty() }) {
                val unit = token.last()
                val value = token.dropLast(1).toDoubleOrNull() ?: continue
                val factor = when (unit) {
                    'd' -> 86_400
                    'h' -> 3_600
                    'm' -> 60
                    's' -> 1
                    else -> continue
                }
                total += (value * factor).toLong()
                found = true
            }
            return if (found) total else null
        }

        private fun sumList(text: String): Double? {
            val values = text.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            return if (values.isEmpty()) null else values.sum()
        }
    }
}
