package io.github.whoxllm.orcamicro.core.mesh

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the user placed a model on the bed. Rotations are applied about the model's own centre
 * in X, then Y, then Z order; scale is uniform. The engine receives the rotated and scaled mesh
 * plus [centerX]/[centerY], and drops the model so it rests on the bed, so [place] does the same
 * for the preview.
 */
@Serializable
data class Placement(
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val scalePercent: Float = 100f,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
) {
    /** Rotation and scale only, about the model centre: the mesh written for the engine. */
    fun orient(mesh: Mesh): Mesh {
        val c = mesh.bounds().center
        val s = scalePercent / 100f
        val m = rotationMatrix(rotationX, rotationY, rotationZ)
        return mesh.map { x, y, z, out ->
            val dx = x - c.x
            val dy = y - c.y
            val dz = z - c.z
            out[0] = (m[0] * dx + m[1] * dy + m[2] * dz) * s
            out[1] = (m[3] * dx + m[4] * dy + m[5] * dz) * s
            out[2] = (m[6] * dx + m[7] * dy + m[8] * dz) * s
        }
    }

    /** The mesh as it will sit on the bed: oriented, footprint centred on the chosen point, resting on z = 0. */
    fun place(mesh: Mesh): Mesh {
        val oriented = orient(mesh)
        val b = oriented.bounds()
        val shiftX = centerX - (b.min.x + b.max.x) / 2f
        val shiftY = centerY - (b.min.y + b.max.y) / 2f
        val shiftZ = -b.min.z
        return oriented.map { x, y, z, out ->
            out[0] = x + shiftX
            out[1] = y + shiftY
            out[2] = z + shiftZ
        }
    }

    companion object {
        /** Row-major 3x3 rotation, applying X, then Y, then Z (angles in degrees). */
        fun rotationMatrix(degX: Float, degY: Float, degZ: Float): FloatArray {
            val ax = Math.toRadians(degX.toDouble())
            val ay = Math.toRadians(degY.toDouble())
            val az = Math.toRadians(degZ.toDouble())
            val cx = cos(ax).toFloat(); val sx = sin(ax).toFloat()
            val cy = cos(ay).toFloat(); val sy = sin(ay).toFloat()
            val cz = cos(az).toFloat(); val sz = sin(az).toFloat()
            // R = Rz * Ry * Rx
            return floatArrayOf(
                cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                -sy, cy * sx, cy * cx,
            )
        }
    }
}
