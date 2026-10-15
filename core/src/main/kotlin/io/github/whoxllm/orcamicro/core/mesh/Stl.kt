package io.github.whoxllm.orcamicro.core.mesh

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlFormatException(message: String) : Exception(message)

/** Reads binary and ASCII STL files. */
object StlReader {
    private const val HEADER_BYTES = 80
    private const val TRIANGLE_BYTES = 50

    fun read(bytes: ByteArray): Mesh {
        if (looksBinary(bytes)) return readBinary(bytes)
        return readAscii(bytes)
    }

    // ASCII files start with "solid", but so do some binary headers; the size check decides.
    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES + 4) return false
        val count = ByteBuffer.wrap(bytes, HEADER_BYTES, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        return HEADER_BYTES + 4 + count * TRIANGLE_BYTES == bytes.size.toLong()
    }

    private fun readBinary(bytes: ByteArray): Mesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getInt(HEADER_BYTES)
        val positions = FloatArray(count * Mesh.FLOATS_PER_TRIANGLE)
        var offset = HEADER_BYTES + 4
        var p = 0
        repeat(count) {
            var o = offset + 12 // skip the facet normal
            repeat(Mesh.FLOATS_PER_TRIANGLE) {
                positions[p++] = buffer.getFloat(o)
                o += 4
            }
            offset += TRIANGLE_BYTES
        }
        return checked(Mesh(positions))
    }

    private fun readAscii(bytes: ByteArray): Mesh {
        val text = String(bytes, Charsets.US_ASCII)
        if (!text.trimStart().startsWith("solid", ignoreCase = true)) {
            throw StlFormatException("Not an STL file")
        }
        val values = FloatList()
        var index = 0
        while (true) {
            index = text.indexOf("vertex", index, ignoreCase = true)
            if (index < 0) break
            index += "vertex".length
            repeat(3) {
                while (index < text.length && text[index].isWhitespace()) index++
                val start = index
                while (index < text.length && !text[index].isWhitespace()) index++
                values.add(text.substring(start, index).toFloatOrNull()
                    ?: throw StlFormatException("Bad vertex coordinate near character $start"))
            }
        }
        if (values.size % Mesh.FLOATS_PER_TRIANGLE != 0) throw StlFormatException("Incomplete facet in ASCII STL")
        return checked(Mesh(values.toArray()))
    }

    private fun checked(mesh: Mesh): Mesh {
        if (mesh.isEmpty) throw StlFormatException("The STL file has no triangles")
        if (mesh.positions.any { !it.isFinite() }) throw StlFormatException("The STL file has invalid coordinates")
        return mesh
    }

    private class FloatList {
        private var data = FloatArray(4096)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

/** Writes binary STL, the format handed to the slicing engine. */
object StlWriter {
    fun write(mesh: Mesh, out: OutputStream) {
        val header = ByteArray(80)
        "OrcaMicro".toByteArray().copyInto(header)
        out.write(header)
        val buffer = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mesh.triangleCount).array())
        val p = mesh.positions
        for (t in 0 until mesh.triangleCount) {
            val i = t * Mesh.FLOATS_PER_TRIANGLE
            buffer.clear()
            // Facet normal from the winding order; slicers recompute it, but write a correct one.
            val ux = p[i + 3] - p[i]; val uy = p[i + 4] - p[i + 1]; val uz = p[i + 5] - p[i + 2]
            val vx = p[i + 6] - p[i]; val vy = p[i + 7] - p[i + 1]; val vz = p[i + 8] - p[i + 2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 0f) { nx /= length; ny /= length; nz /= length }
            buffer.putFloat(nx).putFloat(ny).putFloat(nz)
            for (k in 0 until Mesh.FLOATS_PER_TRIANGLE) buffer.putFloat(p[i + k])
            buffer.putShort(0)
            out.write(buffer.array(), 0, 50)
        }
    }
}
