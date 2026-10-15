package io.github.whoxllm.orcamicro.render

import android.opengl.GLES20
import io.github.whoxllm.orcamicro.core.mesh.Mesh

/** The bed with the placed model on it. */
class PlateRenderer : SceneRenderer() {
    private lateinit var meshProgram: GlProgram

    @Volatile private var mesh: Mesh? = null
    @Volatile private var fits = true
    private var meshBuffer: GlBuffer? = null
    private var uploaded: Mesh? = null

    /** The model as placed on the bed; [fits] tints it red when it does not fit the build volume. */
    fun setModel(mesh: Mesh?, fits: Boolean) {
        this.mesh = mesh
        this.fits = fits
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
        GLES20.glUniformMatrix4fv(meshProgram.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4fv(meshProgram.uniform("uColor"), 1, if (fits) MODEL_COLOR else OUTSIDE_COLOR, 0)
        val position = meshProgram.attribute("aPosition")
        val normal = meshProgram.attribute("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer.id)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buffer.count)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        val MODEL_COLOR = rgba(0x2BB59B)
        val OUTSIDE_COLOR = rgba(0xE5484D)
    }
}
