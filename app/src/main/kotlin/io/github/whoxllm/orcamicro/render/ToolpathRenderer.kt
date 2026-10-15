package io.github.whoxllm.orcamicro.render

import android.opengl.GLES20
import io.github.whoxllm.orcamicro.core.gcode.Feature
import io.github.whoxllm.orcamicro.core.gcode.ToolpathLayer
import io.github.whoxllm.orcamicro.core.gcode.Toolpaths
import java.nio.ByteBuffer

/** What the preview shows: layers up to [layer], optionally just that one, plus travels and feature filters. */
data class PreviewView(
    val layer: Int,
    val singleLayer: Boolean = false,
    val showTravels: Boolean = false,
    val hiddenFeatures: Set<Feature> = emptySet(),
)

/**
 * Draws sliced toolpaths as coloured lines, one colour per feature. All extrusion segments go
 * into one vertex buffer in layer order, so "layers up to N" is a single draw call; the current
 * layer is drawn again at full brightness over the dimmed layers below it.
 */
class ToolpathRenderer : SceneRenderer() {
    @Volatile private var toolpaths: Toolpaths? = null
    @Volatile private var view = PreviewView(0)

    private var extrusions: GlBuffer? = null
    private var travels: GlBuffer? = null
    private var layerStarts = IntArray(1)
    private var travelStarts = IntArray(1)
    private var builtFor: Pair<Toolpaths, Set<Feature>>? = null

    fun setToolpaths(toolpaths: Toolpaths?) {
        this.toolpaths = toolpaths
    }

    fun setView(view: PreviewView) {
        this.view = view
    }

    override fun onCreateResources() {
        extrusions = null
        travels = null
        builtFor = null
    }

    override fun drawScene() {
        val paths = toolpaths ?: return
        val current = view
        if (builtFor?.first !== paths || builtFor?.second != current.hiddenFeatures) {
            build(paths, current.hiddenFeatures)
        }
        val layers = paths.layers.size
        if (layers == 0) return
        val layer = current.layer.coerceIn(0, layers - 1)
        val buffer = extrusions ?: return

        if (!current.singleLayer && layer > 0) {
            drawColored(buffer, 0, layerStarts[layer], 0.55f)
        }
        // The current layer goes on top of everything below it.
        drawColored(buffer, layerStarts[layer], layerStarts[layer + 1] - layerStarts[layer], 1f)
        if (current.showTravels) {
            travels?.let { drawRange(it, travelStarts[layer], travelStarts[layer + 1] - travelStarts[layer]) }
        }
    }

    private fun build(paths: Toolpaths, hidden: Set<Feature>) {
        extrusions?.delete()
        travels?.delete()
        val layers = paths.layers
        layerStarts = IntArray(layers.size + 1)
        travelStarts = IntArray(layers.size + 1)
        var visible = 0
        var travelCount = 0
        for ((i, layer) in layers.withIndex()) {
            layerStarts[i] = visible * 2
            travelStarts[i] = travelCount * 2
            visible += if (hidden.isEmpty()) layer.extrusionCount else layer.features.count { Feature.entries[it.toInt()] !in hidden }
            travelCount += layer.travelCount
        }
        layerStarts[layers.size] = visible * 2
        travelStarts[layers.size] = travelCount * 2

        // 3 floats of position + 4 bytes of colour per vertex.
        val data = directBuffer(maxOf(1, visible * 2) * VERTEX_BYTES)
        val colors = Feature.entries.map { packColor(it.rgb) }
        for (layer in layers) appendExtrusions(data, layer, hidden, colors)
        extrusions = GlBuffer(data, visible * 2)

        val travelData = directBuffer(maxOf(1, travelCount * 2) * 12)
        val travelFloats = travelData.asFloatBuffer()
        for (layer in layers) travelFloats.put(layer.travels)
        travels = GlBuffer(travelData, travelCount * 2)
        builtFor = paths to hidden
    }

    private fun appendExtrusions(data: ByteBuffer, layer: ToolpathLayer, hidden: Set<Feature>, colors: List<Int>) {
        val segments = layer.extrusions
        for (s in 0 until layer.extrusionCount) {
            val feature = layer.features[s].toInt()
            if (hidden.isNotEmpty() && Feature.entries[feature] in hidden) continue
            val color = colors[feature]
            val o = s * ToolpathLayer.FLOATS_PER_SEGMENT
            data.putFloat(segments[o]).putFloat(segments[o + 1]).putFloat(segments[o + 2]).putInt(color)
            data.putFloat(segments[o + 3]).putFloat(segments[o + 4]).putFloat(segments[o + 5]).putInt(color)
        }
    }

    /** RGBA bytes in memory order, as a native-order int. */
    private fun packColor(rgb: Int): Int {
        val bytes = directBuffer(4)
        bytes.put(((rgb shr 16) and 0xff).toByte()).put(((rgb shr 8) and 0xff).toByte()).put((rgb and 0xff).toByte()).put(0xff.toByte())
        return bytes.getInt(0)
    }

    private fun drawColored(buffer: GlBuffer, first: Int, count: Int, brightness: Float) {
        if (count <= 0) return
        lineProgram.use()
        GLES20.glLineWidth(lineWidth)
        GLES20.glUniformMatrix4fv(lineProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform1f(lineProgram.uniform("uUseVertexColor"), 1f)
        GLES20.glUniform1f(lineProgram.uniform("uBrightness"), brightness)
        GLES20.glUniform4f(lineProgram.uniform("uColor"), 1f, 1f, 1f, 1f)
        val position = lineProgram.attribute("aPosition")
        val color = lineProgram.attribute("aColor")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer.id)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, VERTEX_BYTES, 0)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 12)
        GLES20.glDrawArrays(GLES20.GL_LINES, first, count)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawRange(buffer: GlBuffer, first: Int, count: Int) {
        if (count <= 0) return
        lineProgram.use()
        GLES20.glLineWidth(1f)
        GLES20.glUniformMatrix4fv(lineProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform1f(lineProgram.uniform("uUseVertexColor"), 0f)
        GLES20.glUniform1f(lineProgram.uniform("uBrightness"), 1f)
        GLES20.glUniform4fv(lineProgram.uniform("uColor"), 1, TRAVEL_COLOR, 0)
        val position = lineProgram.attribute("aPosition")
        val color = lineProgram.attribute("aColor")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer.id)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glDrawArrays(GLES20.GL_LINES, first, count)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        const val VERTEX_BYTES = 16
        val TRAVEL_COLOR = rgba(0x38489B)
    }
}
