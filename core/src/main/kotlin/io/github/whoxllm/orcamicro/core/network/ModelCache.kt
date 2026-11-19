package io.github.whoxllm.orcamicro.core.network

import java.io.File

object ModelCache {
    val durations = listOf(1,3,7,14,30,0)
    fun clean(directory:File,days:Int,now:Long=System.currentTimeMillis(),all:Boolean=false):Long {
        require(days in durations)
        if(days==0&&!all)return 0L
        val threshold=now-days*24L*60*60*1000
        var bytes=0L
        directory.listFiles().orEmpty().filter { it.isFile&&it.name.matches(Regex("[a-zA-Z0-9.-]+"))&&(all||it.lastModified()<threshold) }.forEach {
            val length=it.length();if(it.delete())bytes+=length
        }
        return bytes
    }
}
