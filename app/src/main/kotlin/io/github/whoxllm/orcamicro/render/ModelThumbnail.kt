package io.github.whoxllm.orcamicro.render

import android.graphics.Bitmap
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.mesh.ModelPreview
import io.github.whoxllm.orcamicro.core.gcode.GcodeThumbnail
import io.github.whoxllm.orcamicro.core.gcode.GcodeThumbnails
import java.io.ByteArrayOutputStream
import java.io.File

object ModelThumbnail {
    /** 2x supersampling gives small edges proper alpha coverage on both dark and light printer UIs. */
    fun render(mesh:Mesh, placement:Placement, color:Int):Bitmap {
        val large=Bitmap.createBitmap(ModelPreview.render(mesh,placement,color),800,800,Bitmap.Config.ARGB_8888)
        return try { Bitmap.createScaledBitmap(large,400,400,true) } finally { large.recycle() }
    }

    fun write(gcode:File, mesh:Mesh, placement:Placement, color:Int) {
        val image=render(mesh,placement,color)
        try {
            val small=Bitmap.createScaledBitmap(image,32,32,true)
            try { GcodeThumbnails.embed(gcode,listOf(thumbnail(small),thumbnail(image))) }
            finally { small.recycle() }
        } finally { image.recycle() }
    }

    private fun thumbnail(image:Bitmap):GcodeThumbnail {
        val output=ByteArrayOutputStream()
        check(image.compress(Bitmap.CompressFormat.PNG,100,output))
        return GcodeThumbnail(image.width,image.height,output.toByteArray())
    }
}
