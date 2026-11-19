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

    fun attribute(name: String): Int = locations.getOrPut("a:${name}") { GLES20.glGetAttribLocation(id, name) }

    fun uniform(name: String): Int = locations.getOrPut("u:${name}") { GLES20.glGetUniformLocation(id, name) }

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
    @Volatile var freeOrbit = false
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
        pitch = (pitch + dyDegrees).coerceIn(if (freeOrbit) -89f else -5f, 89f)
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
    fun view(yaw: Float, pitch: Float) { this.yaw = yaw; this.pitch = pitch.coerceIn(-89f, 89f) }

    @Synchronized fun clipPlanes() = floatArrayOf(max(0.5f, distance/200f), distance*20f+2000f)

    /** Unprojects the touch ray, intersecting the actual XY bed plane. */
    @Synchronized
    fun ray(x: Float, y: Float, width: Int, height: Int): Pair<FloatArray, FloatArray>? {
        if (width <= 0 || height <= 0) return null
        val vp = FloatArray(16); val inv = FloatArray(16)
        viewProjection(width, height, vp)
        if (!Matrix.invertM(inv, 0, vp, 0)) return null
        fun point(z: Float): FloatArray {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(2f*x/width-1f, 1f-2f*y/height, z, 1f), 0)
            return FloatArray(3) { out[it]/out[3] }
        }
        return point(-1f) to point(1f)
    }

    fun bedPoint(x: Float, y: Float, width: Int, height: Int): FloatArray? {
        val (a,b) = ray(x,y,width,height) ?: return null
        val dz = b[2]-a[2]
        if (kotlin.math.abs(dz) < 0.0001f) return null
        val t = -a[2]/dz
        if (t < 0f) return null
        return floatArrayOf(a[0]+t*(b[0]-a[0]), a[1]+t*(b[1]-a[1]))
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
    const val BED_VERTEX = """
        uniform mat4 uMvp;
        attribute vec3 aPosition;
        varying vec2 vWorld;
        void main() { vWorld=aPosition.xy; gl_Position=uMvp*vec4(aPosition,1.0); }
    """
    const val BED_FRAGMENT = """
        precision mediump float;
        varying vec2 vWorld;
        uniform float uTexture;
        float hash(vec2 p) { return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453); }
        void main() {
            float c=0.28;
            if (uTexture>0.5 && uTexture<1.5) c=0.28+(hash(floor(vWorld*5.0))-0.5)*0.12;
            if (uTexture>1.5) { vec2 p=floor(vWorld*1.5); c=0.22+mod(p.x+p.y,2.0)*0.09; }
            gl_FragColor=vec4(vec3(c),1.0);
        }
    """
    const val POST_VERTEX = """#version 300 es
        in vec2 aPosition;
        out vec2 vUv;
        void main() { vUv=aPosition*0.5+0.5; gl_Position=vec4(aPosition,0.0,1.0); }
    """
    const val POST_FRAGMENT = """#version 300 es
        precision highp float;
        uniform sampler2D uImage;
        uniform sampler2D uDepth;
        uniform vec2 uPixel;
        uniform vec2 uClip;
        uniform float uAo;
        in vec2 vUv;
        out vec4 fragColor;
        float linearDepth(vec2 uv) {
            float z=texture(uDepth,uv).r*2.0-1.0;
            return 2.0*uClip.x*uClip.y/(uClip.y+uClip.x-z*(uClip.y-uClip.x));
        }
        vec3 viewPosition(vec2 uv) {
            float d=linearDepth(uv);
            return vec3((uv*2.0-1.0)*vec2(uPixel.y/uPixel.x,1.0)*0.36397023*d,-d);
        }
        void main() {
            vec3 c=texture(uImage,vUv).rgb;
            vec3 center=viewPosition(vUv);
            // Estimate the surface normal from reconstructed view positions. Comparing
            // against the tangent plane avoids falsely shading a flat, tilted bed.
            vec3 n=normalize(cross(dFdx(center),dFdy(center)));
            if(n.z<0.0) n=-n;
            float occ=0.0;
            if(uAo>0.5 && texture(uDepth,vUv).r<0.99999) {
                for(int i=0;i<12;i++) {
                    float a=float(i)*2.399963;
                    vec2 offset=vec2(cos(a),sin(a))*uPixel*(3.0+float(i)*0.8);
                    vec3 delta=viewPosition(clamp(vUv+offset,vec2(0.0),vec2(1.0)))-center;
                    occ+=step(0.12,dot(n,delta))*max(0.0,1.0-length(delta)/14.0);
                }
            }
            fragColor=vec4(c*(1.0-0.55*occ/12.0),1.0);
        }
    """
    const val LINE_VERTEX = """
        uniform mat4 uMvp;
        attribute vec3 aPosition;
        attribute vec4 aColor;
        uniform vec4 uColor;
        uniform float uUseVertexColor;
        uniform float uBrightness;
        uniform mediump float uShadow;
        varying vec4 vColor;
        void main() {
            vec3 pos = mix(aPosition, vec3(aPosition.xy + vec2(0.35,0.25)*aPosition.z, 0.04), uShadow);
            gl_Position = uMvp * vec4(pos, 1.0);
            vec4 c = mix(uColor, aColor, uUseVertexColor);
            vColor = mix(vec4(c.rgb * uBrightness, c.a), vec4(0.0,0.0,0.0,0.18), uShadow);
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
        uniform vec2 uOffset;
        uniform mediump float uShadow;
        varying vec3 vNormal;
        void main() {
            vec3 world = aPosition + vec3(uOffset,0.0);
            vec3 pos = mix(world, vec3(world.xy + vec2(0.35,0.25)*world.z, 0.04), uShadow);
            gl_Position = uMvp * vec4(pos, 1.0);
            vNormal = aNormal;
        }
    """

    const val MESH_FRAGMENT = """
        precision mediump float;
        uniform vec4 uColor;
        uniform mediump float uShadow;
        uniform float uBrightness;
        varying vec3 vNormal;
        void main() {
            vec3 n = normalize(vNormal);
            float key = max(dot(n, normalize(vec3(0.4, -0.6, 0.8))), 0.0);
            float fill = max(dot(n, normalize(vec3(-0.6, 0.5, 0.3))), 0.0) * 0.35;
            float light = 0.28 + key * 0.62 + fill;
            gl_FragColor = mix(vec4(uColor.rgb * min(light, 1.15) * uBrightness, uColor.a), vec4(0.0,0.0,0.0,0.22), uShadow);
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
