package io.github.whoxllm.orcamicro.cad

import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class CadPlane(
    val origin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val normal: FloatArray = floatArrayOf(0f, 0f, 1f),
    val xAxis: FloatArray = floatArrayOf(1f, 0f, 0f),
    val yAxis: FloatArray = floatArrayOf(0f, 1f, 0f),
)

data class CadSketchEntity(
    val type: String, // "line", "arc", "circle", "point"
    val p0x: Double = 0.0,
    val p0y: Double = 0.0,
    val p1x: Double = 0.0,
    val p1y: Double = 0.0,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    val radius: Double = 0.0,
    val startAngle: Double = 0.0,
    val endAngle: Double = 0.0,
    val construction: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("p0", buildJsonArray { add(p0x); add(p0y) })
        put("p1", buildJsonArray { add(p1x); add(p1y) })
        put("center", buildJsonArray { add(cx); add(cy) })
        put("radius", radius)
        put("start_angle", startAngle)
        put("end_angle", endAngle)
        put("construction", construction)
    }

    companion object {
        fun line(x0: Double, y0: Double, x1: Double, y1: Double, construction: Boolean = false) =
            CadSketchEntity("line", p0x = x0, p0y = y0, p1x = x1, p1y = y1, construction = construction)

        fun circle(cx: Double, cy: Double, radius: Double, construction: Boolean = false) =
            CadSketchEntity("circle", p0x = cx, p0y = cy, cx = cx, cy = cy, radius = radius, construction = construction)

        fun arc(cx: Double, cy: Double, radius: Double, startAngle: Double, endAngle: Double): CadSketchEntity {
            val x0 = cx + radius * kotlin.math.cos(startAngle)
            val y0 = cy + radius * kotlin.math.sin(startAngle)
            val x1 = cx + radius * kotlin.math.cos(endAngle)
            val y1 = cy + radius * kotlin.math.sin(endAngle)
            return CadSketchEntity("arc", p0x = x0, p0y = y0, p1x = x1, p1y = y1, cx = cx, cy = cy, radius = radius, startAngle = startAngle, endAngle = endAngle)
        }

        fun rectangle(x0: Double, y0: Double, x1: Double, y1: Double): List<CadSketchEntity> = listOf(
            line(x0, y0, x1, y0),
            line(x1, y0, x1, y1),
            line(x1, y1, x0, y1),
            line(x0, y1, x0, y0),
        )

        fun fromJson(obj: JsonObject): CadSketchEntity {
            val p0 = obj["p0"]?.jsonArray
            val p1 = obj["p1"]?.jsonArray
            val c = obj["center"]?.jsonArray
            return CadSketchEntity(
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "line",
                p0x = p0?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                p0y = p0?.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                p1x = p1?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                p1y = p1?.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                cx = c?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                cy = c?.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                radius = obj["radius"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                startAngle = obj["start_angle"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                endAngle = obj["end_angle"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                construction = obj["construction"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}

data class CadSketchConstraint(
    val type: String,
    val ea: Int = -1,
    val eb: Int = -1,
    val ra: String = "p0",
    val rb: String = "p0",
    val value: Double = 0.0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("ea", ea)
        put("eb", eb)
        put("ra", ra)
        put("rb", rb)
        put("value", value)
    }

    companion object {
        fun fromJson(obj: JsonObject) = CadSketchConstraint(
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "coincident",
            ea = obj["ea"]?.jsonPrimitive?.intOrNull ?: -1,
            eb = obj["eb"]?.jsonPrimitive?.intOrNull ?: -1,
            ra = obj["ra"]?.jsonPrimitive?.contentOrNull ?: "p0",
            rb = obj["rb"]?.jsonPrimitive?.contentOrNull ?: "p0",
            value = obj["value"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
}

data class CadFeatureInfo(
    val index: Int,
    val type: String,
    val name: String,
    val enabled: Boolean,
    val sketchRef: Int = -1,
    val distance: Double = 0.0,
    val symmetric: Boolean = false,
    val flip: Boolean = false,
    val mode: String = "new",
    val size: Double = 0.0,
    val diameter: Double = 0.0,
    val depth: Double = 0.0,
    val through: Boolean = true,
    val plane: CadPlane? = null,
    val entities: List<CadSketchEntity> = emptyList(),
    val constraints: List<CadSketchConstraint> = emptyList(),
)

data class CadBodyInfo(
    val index: Int,
    val name: String,
    val volume: Double,
    val surfaceArea: Double,
    val faceCount: Int,
    val edgeCount: Int,
)

data class CadBounds(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float) {
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val centerX get() = (minX + maxX) * 0.5f
    val centerY get() = (minY + maxY) * 0.5f
    val centerZ get() = (minZ + maxZ) * 0.5f
}

data class CadDocumentState(
    val ok: Boolean,
    val error: String? = null,
    val topoGeneration: Long = 1L,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val meshPath: String? = null,
    val triangleCount: Int = 0,
    val bodyCount: Int = 0,
    val volumeMm3: Double = 0.0,
    val bounds: CadBounds? = null,
    val features: List<CadFeatureInfo> = emptyList(),
    val bodies: List<CadBodyInfo> = emptyList(),
    val featureIndex: Int = -1,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CadDocumentState {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return CadDocumentState(ok = false, error = "Invalid CAD engine response")
            val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
            if (!ok) {
                return CadDocumentState(ok = false, error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "CAD error")
            }
            val bounds = obj["bounds"]?.takeIf { it !is JsonNull }?.jsonObject?.let { b ->
                val mn = b["min"]?.jsonArray
                val mx = b["max"]?.jsonArray
                if (mn != null && mx != null && mn.size >= 3 && mx.size >= 3) {
                    CadBounds(
                        mn[0].jsonPrimitive.float, mn[1].jsonPrimitive.float, mn[2].jsonPrimitive.float,
                        mx[0].jsonPrimitive.float, mx[1].jsonPrimitive.float, mx[2].jsonPrimitive.float,
                    )
                } else null
            }
            val features = obj["features"]?.jsonArray.orEmpty().map { el ->
                val f = el.jsonObject
                val pl = f["plane"]?.jsonObject?.let { p ->
                    fun v3(k: String) = p[k]?.jsonArray?.let { a -> floatArrayOf(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float, a[2].jsonPrimitive.float) }
                    CadPlane(v3("origin") ?: floatArrayOf(0f,0f,0f), v3("normal") ?: floatArrayOf(0f,0f,1f), v3("x_axis") ?: floatArrayOf(1f,0f,0f), v3("y_axis") ?: floatArrayOf(0f,1f,0f))
                }
                CadFeatureInfo(
                    index = f["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    type = f["type"]?.jsonPrimitive?.contentOrNull ?: "other",
                    name = f["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    enabled = f["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    sketchRef = f["sketch_ref"]?.jsonPrimitive?.intOrNull ?: -1,
                    distance = f["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    symmetric = f["symmetric"]?.jsonPrimitive?.booleanOrNull ?: false,
                    flip = f["flip"]?.jsonPrimitive?.booleanOrNull ?: false,
                    mode = f["mode"]?.jsonPrimitive?.contentOrNull ?: "new",
                    size = f["size"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    diameter = f["diameter"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    depth = f["depth"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    through = f["through"]?.jsonPrimitive?.booleanOrNull ?: true,
                    plane = pl,
                    entities = f["entities"]?.jsonArray.orEmpty().map { CadSketchEntity.fromJson(it.jsonObject) },
                    constraints = f["constraints"]?.jsonArray.orEmpty().map { CadSketchConstraint.fromJson(it.jsonObject) },
                )
            }
            val bodies = obj["bodies"]?.jsonArray.orEmpty().map { el ->
                val b = el.jsonObject
                CadBodyInfo(
                    index = b["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    name = b["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    volume = b["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    surfaceArea = b["surface_area"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    faceCount = b["face_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    edgeCount = b["edge_count"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
            return CadDocumentState(
                ok = true,
                topoGeneration = obj["topo_generation"]?.jsonPrimitive?.longOrNull ?: 1L,
                canUndo = obj["can_undo"]?.jsonPrimitive?.booleanOrNull ?: false,
                canRedo = obj["can_redo"]?.jsonPrimitive?.booleanOrNull ?: false,
                meshPath = obj["mesh_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                triangleCount = obj["triangle_count"]?.jsonPrimitive?.intOrNull ?: 0,
                bodyCount = obj["body_count"]?.jsonPrimitive?.intOrNull ?: 0,
                volumeMm3 = obj["volume_mm3"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                bounds = bounds,
                features = features,
                bodies = bodies,
                featureIndex = obj["feature_index"]?.jsonPrimitive?.intOrNull ?: -1,
            )
        }
    }
}

/** Compact binary mesh + B-rep edge segments written by native/bridge/cad.cpp. */
class CadDisplayMesh(
    val positions: FloatArray,
    val triBodyIds: IntArray,
    val triFaceIds: IntArray,
    val edgePositions: FloatArray,
    val edgeBodyIds: IntArray,
    val edgeIds: IntArray,
) {
    val triangleCount: Int get() = triBodyIds.size
    val edgeSegmentCount: Int get() = edgeIds.size

    /** Ray-triangle intersection (Möller–Trumbore); returns (bodyId, faceId) of closest hit. */
    fun pickFace(origin: FloatArray, target: FloatArray): Pair<Int, Int>? {
        var dx = target[0] - origin[0]
        var dy = target[1] - origin[1]
        var dz = target[2] - origin[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return null
        dx /= len; dy /= len; dz /= len

        var bestT = Float.POSITIVE_INFINITY
        var bestBody = -1
        var bestFace = -1
        val eps = 1e-6f
        for (i in 0 until triangleCount) {
            val base = i * 9
            val v0x = positions[base]; val v0y = positions[base + 1]; val v0z = positions[base + 2]
            val e1x = positions[base + 3] - v0x; val e1y = positions[base + 4] - v0y; val e1z = positions[base + 5] - v0z
            val e2x = positions[base + 6] - v0x; val e2y = positions[base + 7] - v0y; val e2z = positions[base + 8] - v0z

            val hx = dy * e2z - dz * e2y
            val hy = dz * e2x - dx * e2z
            val hz = dx * e2y - dy * e2x
            val a = e1x * hx + e1y * hy + e1z * hz
            if (abs(a) < eps) continue
            val f = 1f / a
            val sx = origin[0] - v0x; val sy = origin[1] - v0y; val sz = origin[2] - v0z
            val u = f * (sx * hx + sy * hy + sz * hz)
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = f * (dx * qx + dy * qy + dz * qz)
            if (v < 0f || u + v > 1f) continue
            val t = f * (e2x * qx + e2y * qy + e2z * qz)
            if (t > eps && t < bestT) {
                bestT = t
                bestBody = triBodyIds[i]
                bestFace = triFaceIds[i]
            }
        }
        return if (bestFace >= 0) bestBody to bestFace else null
    }

    /** Closest B-rep edge segment to the ray within [maxDistanceMm]; returns (bodyId, edgeId). */
    fun pickEdge(origin: FloatArray, target: FloatArray, maxDistanceMm: Float): Pair<Int, Int>? {
        var rx = target[0] - origin[0]
        var ry = target[1] - origin[1]
        var rz = target[2] - origin[2]
        val rLen = sqrt(rx * rx + ry * ry + rz * rz)
        if (rLen < 1e-6f) return null
        rx /= rLen; ry /= rLen; rz /= rLen

        var bestDist = maxDistanceMm
        var bestBody = -1
        var bestEdge = -1
        for (i in 0 until edgeSegmentCount) {
            val base = i * 6
            val ax = edgePositions[base]; val ay = edgePositions[base + 1]; val az = edgePositions[base + 2]
            val sx = edgePositions[base + 3] - ax; val sy = edgePositions[base + 4] - ay; val sz = edgePositions[base + 5] - az
            val wx = origin[0] - ax; val wy = origin[1] - ay; val wz = origin[2] - az
            val a = 1f // ray dir is unit length
            val b = rx * sx + ry * sy + rz * sz
            val c = sx * sx + sy * sy + sz * sz
            val d = rx * wx + ry * wy + rz * wz
            val e = sx * wx + sy * wy + sz * wz
            val denom = a * c - b * b
            val sc: Float
            val tc: Float
            if (denom < 1e-6f) {
                sc = 0f
                tc = if (c > 1e-6f) (e / c).coerceIn(0f, 1f) else 0f
            } else {
                tc = ((a * e - b * d) / denom).coerceIn(0f, 1f)
                sc = (b * tc - d).coerceAtLeast(0f)
            }
            val px = origin[0] + sc * rx - (ax + tc * sx)
            val py = origin[1] + sc * ry - (ay + tc * sy)
            val pz = origin[2] + sc * rz - (az + tc * sz)
            val dist = sqrt(px * px + py * py + pz * pz)
            if (dist < bestDist) {
                bestDist = dist
                bestBody = edgeBodyIds[i]
                bestEdge = edgeIds[i]
            }
        }
        return if (bestEdge >= 0) bestBody to bestEdge else null
    }

    companion object {
        fun read(file: File): CadDisplayMesh? {
            if (!file.isFile || file.length() < 16) return null
            val bytes = file.readBytes()
            if (bytes[0] != 'O'.code.toByte() || bytes[1] != 'C'.code.toByte() ||
                bytes[2] != 'A'.code.toByte() || bytes[3] != 'D'.code.toByte()
            ) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val version = buf.int
            if (version != 1) return null
            val triCount = buf.int
            val edgeSegCount = buf.int
            val expectedBytes = 16L + triCount.toLong() * 44L + edgeSegCount.toLong() * 32L
            if (bytes.size.toLong() < expectedBytes) return null

            val positions = FloatArray(triCount * 9)
            val triBody = IntArray(triCount)
            val triFace = IntArray(triCount)
            for (i in 0 until triCount) {
                val base = i * 9
                for (k in 0 until 9) positions[base + k] = buf.float
                triBody[i] = buf.int
                triFace[i] = buf.int
            }

            val edgePos = FloatArray(edgeSegCount * 6)
            val edgeBody = IntArray(edgeSegCount)
            val edgeIds = IntArray(edgeSegCount)
            for (i in 0 until edgeSegCount) {
                val base = i * 6
                for (k in 0 until 6) edgePos[base + k] = buf.float
                edgeBody[i] = buf.int
                edgeIds[i] = buf.int
            }
            return CadDisplayMesh(positions, triBody, triFace, edgePos, edgeBody, edgeIds)
        }
    }
}
