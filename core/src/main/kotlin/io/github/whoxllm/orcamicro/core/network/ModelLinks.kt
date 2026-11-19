package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import java.net.URI
import java.net.URLDecoder

sealed interface ModelLink {
    data class Page(val url:String):ModelLink
    data class File(val url:String,val name:String,val source:String?=null):ModelLink
}

object ModelLinks {
    fun parse(input:String):ModelLink? {
        val text=input.trim()
        if(!text.contains("://"))return null
        val uri=runCatching { URI(text) }.getOrElse { error(tr("Проверьте ссылку на модель")) }
        if(uri.scheme.equals("orcaslicer",true) || uri.scheme in setOf("prusaslicer","bambustudio")) {
            require(uri.host in setOf("open","model","import")) { tr("Используйте orcaslicer://open?file=HTTPS-ссылка") }
            val query=uri.rawQuery.orEmpty().split('&').filter{it.contains('=')}.associate {
                URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('='),"UTF-8")
            }
            val file=query["file"]
            val url=file ?: query["url"] ?: error(tr("В ссылке нет file или url"))
            val target=https(url)
            if(file==null && ModelSource.of(target.toString())!=null)return ModelLink.Page(target.toString())
            return ModelLink.File(target.toString(),cleanName(query["name"] ?: target.path.substringAfterLast('/').ifBlank{"model.stl"}),query["source"]?.takeIf{ModelSource.of(it)!=null})
        }
        val target=https(text)
        return if(target.path.substringAfterLast('.').lowercase() in setOf("stl","3mf","zip"))ModelLink.File(target.toString(),cleanName(target.path.substringAfterLast('/')))
        else if(ModelSource.of(text)!=null)ModelLink.Page(text)
        else error(tr("Откройте ссылку на модель площадки или прямую HTTPS-ссылку на STL, ZIP, 3MF"))
    }
    private fun https(text:String):URI = URI(text).also { require(it.scheme=="https"&&!it.host.isNullOrBlank()&&it.userInfo==null) { tr("Для модели нужна HTTPS-ссылка") } }
    private fun cleanName(name:String)=name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\r\\n]")," ").take(180).ifBlank{"model.stl"}
}
