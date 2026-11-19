package io.github.whoxllm.orcamicro.render

import android.opengl.GLES20
import io.github.whoxllm.orcamicro.core.mesh.Mesh

/** The bed with the placed model on it. */
class PlateRenderer : SceneRenderer() {
    private lateinit var meshProgram: GlProgram

    @Volatile private var mesh: Mesh? = null
    @Volatile private var fits = true
    private var meshBuffer: GlBuffer? = null
    @Volatile var offsetX = 0f
    @Volatile var offsetY = 0f
    @Volatile var modelColor: Int = 0xE6B83C
    private var uploaded: Mesh? = null

    /** The model as placed on the bed; [fits] tints it red when it does not fit the build volume. */
    fun setModel(mesh: Mesh?, fits: Boolean) {
        this.mesh = mesh
        this.fits = fits
    }

    fun hitModel(x: Float, y: Float, width: Int, height: Int): Boolean {
        val bounds = mesh?.bounds() ?: return false
        val (a,b) = camera.ray(x,y,width,height) ?: return false
        val lo = floatArrayOf(bounds.min.x+offsetX, bounds.min.y+offsetY, bounds.min.z)
        val hi = floatArrayOf(bounds.max.x+offsetX, bounds.max.y+offsetY, bounds.max.z)
        var near = 0f; var far = Float.POSITIVE_INFINITY
        for (i in 0..2) {
            val d = b[i]-a[i]
            if (kotlin.math.abs(d) < 0.00001f) { if (a[i] < lo[i] || a[i] > hi[i]) return false }
            else {
                val t1=(lo[i]-a[i])/d; val t2=(hi[i]-a[i])/d
                near=maxOf(near,minOf(t1,t2)); far=minOf(far,maxOf(t1,t2))
                if (far < near) return false
            }
        }
        return true
    }

    override fun onCreateResources() {
        meshProgram = GlProgram(Shaders.MESH_VERTEX, Shaders.MESH_FRAGMENT)
        meshBuffer = null
        uploaded = null
    }

    override fun drawScene() {
        val current = mesh
        if (current !== uploaded) {
            meshBuffer?.delete()
            meshBuffer = current?.let { GlBuffer(meshVertices(it), it.triangleCount * 3) }
            uploaded = current
        }
        val buffer = meshBuffer ?: return
        meshProgram.use()
        GLES20.glUniform2f(meshProgram.uniform("uOffset"),offsetX,offsetY)
        GLES20.glUniformMatrix4fv(meshProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4fv(meshProgram.uniform("uColor"), 1, if (fits) rgba(modelColor) else OUTSIDE_COLOR, 0)
        val position = meshProgram.attribute("aPosition")
        val normal = meshProgram.attribute("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer.id)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glUniform1f(meshProgram.uniform("uBrightness"), options.brightness)
        if(options.shadows) {
            GLES20.glDepthMask(false)
            GLES20.glUniform1f(meshProgram.uniform("uShadow"),1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,buffer.count)
            GLES20.glDepthMask(true)
        }
        GLES20.glUniform1f(meshProgram.uniform("uShadow"),0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buffer.count)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        val MODEL_COLOR = rgba(0xE6B83C)
        val OUTSIDE_COLOR = rgba(0xE5484D)
    }
}
