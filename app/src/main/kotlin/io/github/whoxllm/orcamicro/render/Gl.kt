package io.github.whoxllm.orcamicro.render

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A compiled shader program with cached attribute and uniform locations. */
class GlProgram(vertexSource: String, fragmentSource: String) {
    val id: Int = GLES20.glCreateProgram()
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader link failed: ${GLES20.glGetProgramInfoLog(id)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun attribute(name: String): Int = locations.getOrPut("a:$name") { GLES20.glGetAttribLocation(id, name) }

    fun uniform(name: String): Int = locations.getOrPut("u:$name") { GLES20.glGetUniformLocation(id, name) }

    fun use() = GLES20.glUseProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }
}

/** A vertex buffer object holding [count] vertices. */
class GlBuffer(data: ByteBuffer, val count: Int) {
    val id: Int

    init {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        id = ids[0]
        data.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.capacity(), data, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun delete() = GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
}

fun directBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

/** [values] in a direct, native-order buffer ready for glBufferData. */
fun floatBytes(values: FloatArray): ByteBuffer = directBuffer(values.size * 4).also { it.asFloatBuffer().put(values) }

/**
 * Orbit camera around a target point, with Z up like a printer. Touch handling on the UI thread
 * and drawing on the GL thread both use it, so every method is synchronized.
 */
class OrbitCamera {
    private var yaw = -60f
    private var pitch = 30f
    private var distance = 400f
    private val target = floatArrayOf(110f, 110f, 0f)

    @Synchronized
    fun frame(centerX: Float, centerY: Float, centerZ: Float, radius: Float) {
        target[0] = centerX; target[1] = centerY; target[2] = centerZ
        distance = max(radius * 2.4f, 30f)
    }

    @Synchronized
    fun rotate(dxDegrees: Float, dyDegrees: Float) {
        yaw -= dxDegrees
        pitch = (pitch + dyDegrees).coerceIn(-5f, 89f)
    }

    @Synchronized
    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(5f, 5000f)
    }

    /** Moves the target in the view plane; [dx]/[dy] are fractions of the view height. */
    @Synchronized
    fun pan(dx: Float, dy: Float) {
        val scale = distance * 0.8f
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val rightX = -sin(y).toFloat()
        val rightY = cos(y).toFloat()
        // Camera "up" projected into the world.
        val upX = (-sin(p) * cos(y)).toFloat()
        val upY = (-sin(p) * sin(y)).toFloat()
        val upZ = cos(p).toFloat()
        target[0] -= (rightX * dx - upX * dy) * scale
        target[1] -= (rightY * dx - upY * dy) * scale
        target[2] = (target[2] + upZ * dy * scale).coerceIn(-500f, 2000f)
    }

    @Synchronized
    fun viewProjection(width: Int, height: Int, out: FloatArray) {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val eyeX = target[0] + (distance * cos(p) * cos(y)).toFloat()
        val eyeY = target[1] + (distance * cos(p) * sin(y)).toFloat()
        val eyeZ = target[2] + (distance * sin(p)).toFloat()
        val view = FloatArray(16)
        val projection = FloatArray(16)
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, target[0], target[1], target[2], 0f, 0f, 1f)
        val aspect = width.toFloat() / max(1, height)
        Matrix.perspectiveM(projection, 0, 40f, aspect, max(0.5f, distance / 200f), distance * 20f + 2000f)
        Matrix.multiplyMM(out, 0, projection, 0, view, 0)
    }
}

/** Shaders shared by the plate and preview scenes. */
object Shaders {
    const val LINE_VERTEX = """
        uniform mat4 uMvp;
        attribute vec3 aPosition;
        attribute vec4 aColor;
        uniform vec4 uColor;
        uniform float uUseVertexColor;
        uniform float uBrightness;
        varying vec4 vColor;
        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            vec4 c = mix(uColor, aColor, uUseVertexColor);
            vColor = vec4(c.rgb * uBrightness, c.a);
        }
    """

    const val LINE_FRAGMENT = """
        precision mediump float;
        varying vec4 vColor;
        void main() { gl_FragColor = vColor; }
    """

    const val MESH_VERTEX = """
        uniform mat4 uMvp;
        attribute vec3 aPosition;
        attribute vec3 aNormal;
        varying vec3 vNormal;
        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            vNormal = aNormal;
        }
    """

    const val MESH_FRAGMENT = """
        precision mediump float;
        uniform vec4 uColor;
        varying vec3 vNormal;
        void main() {
            vec3 n = normalize(vNormal);
            float key = max(dot(n, normalize(vec3(0.4, -0.6, 0.8))), 0.0);
            float fill = max(dot(n, normalize(vec3(-0.6, 0.5, 0.3))), 0.0) * 0.35;
            float light = 0.28 + key * 0.62 + fill;
            gl_FragColor = vec4(uColor.rgb * min(light, 1.15), uColor.a);
        }
    """
}

/** Packs an 0xRRGGBB colour as normalized floats. */
fun rgba(rgb: Int, alpha: Float = 1f) = floatArrayOf(
    ((rgb shr 16) and 0xff) / 255f, ((rgb shr 8) and 0xff) / 255f, (rgb and 0xff) / 255f, alpha,
)

fun maxLineWidth(): Float {
    val range = FloatArray(2)
    GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
    return min(max(range[1], 1f), 3f)
}
