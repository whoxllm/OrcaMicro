package io.github.whoxllm.orcamicro.core.mesh

import kotlin.math.max
import kotlin.math.min

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
}

/** Axis-aligned bounding box, in millimetres. */
data class Bounds(val min: Vec3, val max: Vec3) {
    val size: Vec3 get() = max - min
    val center: Vec3 get() = Vec3((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
}

/**
 * A triangle soup: nine floats (three xyz vertices) per triangle, in millimetres.
 * This is the layout STL files use and the layout the renderer uploads.
 */
class Mesh(val positions: FloatArray) {
    init {
        require(positions.size % FLOATS_PER_TRIANGLE == 0) { "positions must hold whole triangles" }
    }

    val triangleCount: Int get() = positions.size / FLOATS_PER_TRIANGLE

    val isEmpty: Boolean get() = positions.isEmpty()

    fun bounds(): Bounds {
        if (positions.isEmpty()) return Bounds(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < positions.size) {
            val x = positions[i]
            val y = positions[i + 1]
            val z = positions[i + 2]
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
            i += 3
        }
        return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
    }

    /** Returns a new mesh with [transform] applied to every vertex. */
    fun map(transform: (x: Float, y: Float, z: Float, out: FloatArray) -> Unit): Mesh {
        val result = FloatArray(positions.size)
        val vertex = FloatArray(3)
        var i = 0
        while (i < positions.size) {
            transform(positions[i], positions[i + 1], positions[i + 2], vertex)
            result[i] = vertex[0]
            result[i + 1] = vertex[1]
            result[i + 2] = vertex[2]
            i += 3
        }
        return Mesh(result)
    }

    companion object {
        const val FLOATS_PER_TRIANGLE = 9
    }
}
