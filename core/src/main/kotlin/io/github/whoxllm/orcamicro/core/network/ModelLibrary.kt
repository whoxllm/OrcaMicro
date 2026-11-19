package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

@Serializable
enum class ModelSource(val title: String, val home: String) {
    THINGIVERSE("Thingiverse", "https://www.thingiverse.com"),
    PRINTABLES("Printables", "https://www.printables.com"),
    CULTS("Cults3D", "https://cults3d.com"),
    MAKERWORLD("MakerWorld", "https://makerworld.com");

    fun search(query: String,tags:List<String> = emptyList()): String {
        val q=URLEncoder.encode((listOf(query.trim())+tags).filter { it.isNotBlank() }.joinToString(" "),"UTF-8")
        if(query.isBlank()&&tags.size==1) {
            val tag=URLEncoder.encode(tags.first(),"UTF-8").replace("+","%20")
            return when(this) { THINGIVERSE -> "${home}/tag:${tag}";PRINTABLES -> "${home}/tag/${tag}";CULTS -> "${home}/en/tags/${tag}";MAKERWORLD -> "${home}/en/search/models?keyword=${tag}" }
        }
        return when(this) {
            THINGIVERSE -> "${home}/search?q=${q}&type=things"
            PRINTABLES -> "${home}/search/models?q=${q}"
            CULTS -> "${home}/en/search?q=${q}"
            MAKERWORLD -> "${home}/en/search/models?keyword=${q}"
        }
    }
    companion object {
        fun of(url:String):ModelSource? = runCatching {
            val uri=URI(url); if(uri.scheme!="https" || uri.userInfo!=null) return null
            entries.firstOrNull { source -> val host=URI(source.home).host.removePrefix("www.");uri.host==host || uri.host=="www.${host}" }
        }.getOrNull()
    }
}

@Serializable
data class LibraryModel(
    val id: String = UUID.randomUUID().toString(),
    val source: ModelSource?,
    val url: String,
    val title: String,
    val imageUrl: String? = null,
    val filename: String? = null,
    val localFile: String? = null,
    val tags: List<String> = emptyList(),
    val author: String? = null,
    val license: String? = null,
    /** null means the provider did not supply a price; never infer price from a license. */
    val paid: Boolean? = null,
    val priceLabel: String? = null,
    val remoteId: String? = null,
)

object ModelTags {
    fun parse(text:String):List<String> = text.split(',', ';', '\n').map { it.trim().removePrefix("#").lowercase().take(80) }.filter { it.isNotEmpty() }.distinct().take(20)
    fun matches(model:LibraryModel,query:String,tags:List<String>):Boolean {
        val words=query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.all { word-> listOf(model.title,model.filename.orEmpty(),model.source?.title.orEmpty(),model.author.orEmpty(),model.tags.joinToString(" ")).any { it.contains(word,true) } } && tags.all { tag->model.tags.any { it.equals(tag,true) } }
    }
}

/** Streams a website-authorized download. Cookies are resolved separately for each redirect host. */
object ModelDownload {
    fun fetch(url:String, target:File, maxBytes:Long=96L*1024*1024, headers:(String)->Map<String,String> = { emptyMap() }, progress:(Long,Long)->Unit = { _,_-> }):String =
        fetchUsing(url,target,maxBytes,headers,progress) { it.toURL().openConnection() as HttpURLConnection }

    internal fun fetchUsing(url:String, target:File, maxBytes:Long, headers:(String)->Map<String,String>, progress:(Long,Long)->Unit, open:(URI)->HttpURLConnection):String {
        var current=url
        try {
            repeat(8) {
                val uri=URI(current)
                require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null) { tr("Для модели нужна прямая HTTPS-ссылка") }
                val http=open(uri).apply {
                    connectTimeout=15_000;readTimeout=30_000;instanceFollowRedirects=false
                    setRequestProperty("User-Agent","OrcaMicro")
                    headers(current).forEach { (key,value) -> require(!value.contains('\r')&&!value.contains('\n'));setRequestProperty(key,value) }
                }
                try {
                    if(http.responseCode in setOf(301,302,303,307,308)) { current=uri.resolve(http.getHeaderField("Location") ?: error(tr("Нет адреса перенаправления"))).toString();return@repeat }
                    require(http.responseCode in 200..299) { tr("Загрузка: HTTP {0}. Проверьте ключ API и доступ к модели или прикрепите свой файл." ,http.responseCode) }
                    require(http.contentType?.lowercase()?.contains("text/html")!=true) { tr("Площадка вернула страницу вместо файла модели. Проверьте доступ или прикрепите свой STL / ZIP / 3MF.") }
                    val total=http.contentLengthLong
                    require(total<=maxBytes) { tr("Файл больше 96 МБ") }
                    progress(0,total)
                    http.inputStream.use { input -> target.outputStream().use { output ->
                        val buffer=ByteArray(64*1024);var received=0L
                        while(true) { if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException();val size=input.read(buffer);if(size<0)break;received+=size;require(received<=maxBytes) { tr("Файл больше 96 МБ") };output.write(buffer,0,size);progress(received,total) }
                        require(received>0 && (total<0 || received==total)) { tr("Загрузка модели оборвалась") }
                    } }
                    return current
                } finally { http.disconnect() }
            }
            error(tr("Слишком много перенаправлений"))
        } catch(e:Exception) { target.delete();throw e }
    }
}
