package io.github.whoxllm.orcamicro.core.mesh

import kotlin.math.*

/** Fixed isometric, depth-tested preview. No GL context, screenshot or triangle-sized copy is needed. */
object ModelPreview {
    fun render(mesh: Mesh, placement: Placement, color: Int, size: Int = 800): IntArray {
        require(size in 16..1024)
        require(!mesh.isEmpty)
        val p = mesh.positions
        val rotation = Placement.rotationMatrix(placement.rotationX, placement.rotationY, placement.rotationZ)
        // Right, screen-up and towards the camera. The model is framed independently of its bed position.
        val view = floatArrayOf(.7071068f,.7071068f,0f, -.3535534f,.3535534f,.8660254f, .6123724f,-.6123724f,.5f)
        val m = FloatArray(9) { i ->
            val row=i/3; val col=i%3
            (0..2).sumOf { k -> (view[row*3+k]*rotation[k*3+col]).toDouble() }.toFloat()
        }
        val center=mesh.bounds().center
        var minX=Float.POSITIVE_INFINITY; var maxX=Float.NEGATIVE_INFINITY
        var minY=Float.POSITIVE_INFINITY; var maxY=Float.NEGATIVE_INFINITY
        var i=0
        while(i<p.size) {
            val x=p[i]-center.x; val y=p[i+1]-center.y; val z=p[i+2]-center.z
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "Non-finite model coordinates" }
            val u=m[0]*x+m[1]*y+m[2]*z; val v=m[3]*x+m[4]*y+m[5]*z
            minX=min(minX,u); maxX=max(maxX,u); minY=min(minY,v); maxY=max(maxY,v)
            i+=3
        }
        val extent=max(maxX-minX,maxY-minY)
        require(extent.isFinite() && extent>0f) { "Empty model bounds" }
        val scale=(size-1)*.86f/extent
        val cx=(minX+maxX)*.5f; val cy=(minY+maxY)*.5f
        val pixels=IntArray(size*size)
        val depth=FloatArray(pixels.size) { Float.NEGATIVE_INFINITY }
        val x=FloatArray(3); val y=FloatArray(3); val z=FloatArray(3)
        i=0
        while(i<p.size) {
            if(i % 9000 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
            for(k in 0..2) {
                val j=i+k*3; val a=p[j]-center.x; val b=p[j+1]-center.y; val c=p[j+2]-center.z
                x[k]=((m[0]*a+m[1]*b+m[2]*c)-cx)*scale+(size-1)*.5f
                y[k]=(cy-(m[3]*a+m[4]*b+m[5]*c))*scale+(size-1)*.5f
                z[k]=(m[6]*a+m[7]*b+m[8]*c)*scale
            }
            val ax=x[1]-x[0]; val ay=y[1]-y[0]; val az=z[1]-z[0]
            val bx=x[2]-x[0]; val by=y[2]-y[0]; val bz=z[2]-z[0]
            val area=ax*by-ay*bx
            if(abs(area)>.0001f) {
                var nx=ay*bz-az*by; var ny=az*bx-ax*bz; var nz=area
                if(nz<0f) { nx=-nx;ny=-ny;nz=-nz }
                val length=sqrt(nx*nx+ny*ny+nz*nz)
                val light=.36f+.64f*max(0f,(-.3f*nx-.5f*ny+.8124f*nz)/length)
                fun channel(shift:Int)=(((color shr shift) and 255)*light).roundToInt().coerceIn(0,255)
                val shaded=(255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
                val left=floor(x.min()).toInt().coerceIn(0,size-1);val right=ceil(x.max()).toInt().coerceIn(0,size-1)
                val top=floor(y.min()).toInt().coerceIn(0,size-1);val bottom=ceil(y.max()).toInt().coerceIn(0,size-1)
                val inv=1f/area
                for(py in top..bottom) for(px in left..right) {
                    val dx=px+.5f-x[0]; val dy=py+.5f-y[0]
                    val b1=(dx*by-dy*bx)*inv;val b2=(ax*dy-ay*dx)*inv;val b0=1f-b1-b2
                    if(b0>=0f && b1>=0f && b2>=0f) {
                        val d=b0*z[0]+b1*z[1]+b2*z[2];val index=py*size+px
                        if(d>depth[index]) { depth[index]=d;pixels[index]=shaded }
                    }
                }
            }
            i+=9
        }
        return pixels
    }
}
