package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

@Serializable
data class CatalogCredentials(
    val thingiverseToken: String = "",
    val cultsUser: String = "",
    val cultsKey: String = "",
    val printablesCookie: String = "",
    val makerWorldToken: String = "",
) {
    fun validate() { listOf(thingiverseToken,cultsUser,cultsKey,printablesCookie,makerWorldToken).forEach { require(it.length<=16384&&!it.contains('\r')&&!it.contains('\n')) { tr("Ключ или cookie должен быть одной строкой") } };require(!cultsUser.contains(':')) { tr("Проверьте имя Cults") } }
}

data class CatalogPage(val models: List<LibraryModel>, val total: Int, val nextOffset: Int?)
data class CatalogFile(val id:String,val name:String,val url:String?=null,val kind:String="direct")
data class CatalogDetails(val model:LibraryModel,val files:List<CatalogFile>,val notice:String?=null)

/** Only JSON APIs. No WebView, HTML parsing, shared service tokens or account passwords. */
class ModelCatalogClient(
    private val credentials: CatalogCredentials = CatalogCredentials(),
    private val transport: (String,JsonObject?,Map<String,String>)->JsonElement = ::requestJson,
) {
    init { credentials.validate() }
    fun search(source:ModelSource,query:String,tags:List<String> = emptyList(),offset:Int=0):CatalogPage {
        require(offset>=0)
        val text=(listOf(query.trim())+tags).filter { it.isNotBlank() }.joinToString(" ").take(500)
        val root:JsonElement
        val values:List<JsonElement>
        val total:Int
        when(source) {
            ModelSource.PRINTABLES -> {
                root=gql(PRINTABLES,"query Search(\$q:String!,\$offset:Int!){searchPrints2(query:\$q,printType:print,ordering:${if(text.isBlank())"popular" else "best_match"},limit:${PAGE_SIZE},offset:\$offset){items{${PRINT_FIELDS}} totalCount}}",buildJsonObject { put("q",text);put("offset",offset) })
                val batch=root.obj("data").obj("searchPrints2");values=batch.array("items");total=batch.int("totalCount") ?: values.size
            }
            ModelSource.MAKERWORLD -> {
                val path=if(text.isBlank())"search-service/select/design/nav?navKey=Trending" else "search-service/select/design2?keyword=${enc(text)}"
                root=get("${MAKERWORLD}/${path}&limit=${PAGE_SIZE}&offset=${offset}")
                values=root.array("hits");total=root.int("total") ?: values.size
            }
            ModelSource.THINGIVERSE -> {
                requireThingiverse()
                val path=if(text.isBlank())"popular" else if(query.isBlank()&&tags.size==1)"tags/${enc(tags.single())}/things" else "search/${enc(text)}"
                root=get("${THINGIVERSE}/${path}?page=${offset/PAGE_SIZE+1}&per_page=${PAGE_SIZE}&type=things")
                values=if(root is JsonArray)root.toList() else root.array("hits")
                total=root.int("total") ?: if(values.size==PAGE_SIZE)offset+PAGE_SIZE+1 else offset+values.size
            }
            ModelSource.CULTS -> {
                requireCults()
                val batch=if(text.isBlank())"creationsBatch(sort:BY_LIKES,limit:${PAGE_SIZE},offset:${offset})" else "creationsSearchBatch(query:${JsonPrimitive(text)},limit:${PAGE_SIZE},offset:${offset})"
                root=gql(CULTS,"{${batch}{total results{${CULTS_FIELDS}}}}")
                val data=root.obj("data").obj(if(text.isBlank())"creationsBatch" else "creationsSearchBatch")
                values=data.array("results");total=data.int("total") ?: values.size
            }
        }
        return CatalogPage(values.mapNotNull { decode(source,it) },total,if(values.isNotEmpty()&&offset+values.size<total)offset+values.size else null)
    }

    fun details(url:String):CatalogDetails {
        val source=ModelSource.of(url) ?: error(tr("Нужна ссылка Thingiverse, Printables, Cults3D или MakerWorld"))
        val id=modelId(source,url)
        return when(source) {
            ModelSource.PRINTABLES -> {
                val root=gql(PRINTABLES,"query Detail(\$id:ID!){print(id:\$id){${PRINT_FIELDS} stls{id name}}}",buildJsonObject { put("id",id) })
                val item=root.obj("data")["print"]?.takeUnless { it==JsonNull } ?: error(tr("Модель не найдена"))
                CatalogDetails(decode(source,item) ?: error(tr("Неполный ответ Printables")),item.array("stls").map { CatalogFile(it.text("id") ?: error(tr("Нет ID файла")),it.text("name") ?: "model.stl",kind="printables") })
            }
            ModelSource.MAKERWORLD -> {
                val item=get("${MAKERWORLD}/design-service/design/${id}")
                val model=decode(source,item) ?: error(tr("Модель не найдена"))
                val raw=item.obj("designExtension").array("model_files").mapNotNull { f-> f.text("modelUrl")?.takeIf(::isHttps)?.let { CatalogFile(f.text("modelName").orEmpty(),f.text("modelName") ?: "model.stl",it) } }
                val instances=item.array("instances").mapNotNull { f->f.text("id")?.let { CatalogFile(it,(f.text("title") ?: tr("Профиль {0}" ,it))+".3mf",kind="makerworld") } }
                CatalogDetails(model,raw+instances,if(raw.isEmpty()&&credentials.makerWorldToken.isBlank())tr("Для загрузки 3MF MakerWorld может потребовать ваш Bambu access token в настройках API.") else null)
            }
            ModelSource.THINGIVERSE -> {
                requireThingiverse()
                val model=decode(source,get("${THINGIVERSE}/things/${id}")) ?: error(tr("Модель не найдена"))
                val root=get("${THINGIVERSE}/things/${id}/files")
                val files=(root as? JsonArray).orEmpty().mapNotNull { f->val name=f.text("name") ?: return@mapNotNull null
                    if(name.substringAfterLast('.').lowercase() !in setOf("stl","3mf","zip"))return@mapNotNull null
                    (f.text("download_url") ?: f.text("public_url"))?.takeIf(::isHttps)?.let { CatalogFile(f.text("id") ?: name,name,it) }
                }
                CatalogDetails(model,files)
            }
            ModelSource.CULTS -> {
                requireCults()
                val root=gql(CULTS,"query Detail(\$slug:String!){creation(slug:\$slug){${CULTS_FIELDS}}}",buildJsonObject { put("slug",id) })
                val model=decode(source,root.obj("data")["creation"] ?: JsonNull) ?: error(tr("Модель не найдена"))
                CatalogDetails(model,emptyList(),tr("Cults API не предоставляет файлы моделей. Прикрепите STL, ZIP или 3MF, который вы получили у автора или приобрели ранее."))
            }
        }
    }

    /** Resolve short-lived links only immediately before the requested download. */
    fun downloadUrl(model:LibraryModel,file:CatalogFile):String {
        file.url?.let { require(isHttps(it));return it }
        return when(file.kind) {
            "printables" -> {
                val root=gql(PRINTABLES,"mutation Download(\$id:ID!,\$print:ID!){getDownloadLink(id:\$id,printId:\$print,fileType:stl,source:model_detail){ok output{link} errors{messages}}}",buildJsonObject { put("id",file.id);put("print",model.remoteId ?: error(tr("Нет ID модели"))) })
                val result=root.obj("data").obj("getDownloadLink")
                require(result.bool("ok")==true) { tr("Printables не разрешил загрузку. Проверьте доступ и session cookie в настройках API или прикрепите свой файл.") }
                result.obj("output").text("link")?.takeIf(::isHttps) ?: error(tr("Printables не вернул ссылку на файл"))
            }
            "makerworld" -> {
                val root=get("${MAKERWORLD}/design-service/instance/${file.id}/f3mf?type=download")
                (root.text("url") ?: root.text("link"))?.takeIf(::isHttps) ?: error(tr("MakerWorld не вернул файл. Проверьте Bambu access token или прикрепите свой 3MF."))
            }
            else -> error(tr("Файл недоступен"))
        }
    }

    /** Authorization is attached only to its exact API host, including across file redirects. */
    fun headers(url:String):Map<String,String> {
        val uri=URI(url)
        if(uri.scheme!="https"||uri.userInfo!=null)return emptyMap()
        return when(uri.host) {
            "api.thingiverse.com" -> if(credentials.thingiverseToken.isNotBlank())mapOf("Authorization" to "Bearer ${credentials.thingiverseToken}") else emptyMap()
            "cults3d.com" -> if(credentials.cultsUser.isNotBlank()&&credentials.cultsKey.isNotBlank())mapOf("Authorization" to "Basic "+Base64.getEncoder().encodeToString("${credentials.cultsUser}:${credentials.cultsKey}".toByteArray())) else emptyMap()
            "api.printables.com" -> if(credentials.printablesCookie.isNotBlank())mapOf("Cookie" to credentials.printablesCookie) else emptyMap()
            "api.bambulab.com" -> if(credentials.makerWorldToken.isNotBlank())mapOf("Authorization" to "Bearer ${credentials.makerWorldToken}") else emptyMap()
            else -> emptyMap()
        }
    }
    private fun requireThingiverse()=require(credentials.thingiverseToken.isNotBlank()) { tr("Thingiverse: добавьте ваш App Token в настройках API каталога.") }
    private fun requireCults()=require(credentials.cultsUser.isNotBlank()&&credentials.cultsKey.isNotBlank()) { tr("Cults3D: добавьте ваш ник и API-ключ в настройках API каталога.") }
    private fun get(url:String)=transport(url,null,headers(url)).also(::checkApiError)
    private fun gql(url:String,query:String,variables:JsonObject=buildJsonObject{}):JsonElement {
        val result=transport(url,buildJsonObject { put("query",query);put("variables",variables) },headers(url))
        require(result.array("errors").isEmpty()) { tr("API изменился или отклонил запрос. {0}" ,result.array("errors").firstOrNull()?.text("message").orEmpty().take(180)) }
        return result
    }
    private fun checkApiError(result:JsonElement) { require(result.text("error").isNullOrBlank()&&(result.int("code") ?: 0)==0) { tr("API отклонил запрос. Проверьте доступ к модели и ключ площадки.") } }

    companion object {
        const val PAGE_SIZE=20
        const val PRINTABLES="https://api.printables.com/graphql/"
        const val MAKERWORLD="https://api.bambulab.com/v1"
        const val THINGIVERSE="https://api.thingiverse.com"
        const val CULTS="https://cults3d.com/graphql"
        private const val PRINT_FIELDS="id name slug premium price tags{name} image{filePath} user{publicUsername handle} license{name}"
        private const val CULTS_FIELDS="name(locale:EN) url(locale:EN) illustrationImageUrl license{name(locale:EN)} tags(locale:EN) price(currency:USD){cents} creator{nick}"
        fun modelId(source:ModelSource,url:String):String {
            require(ModelSource.of(url)==source) { tr("Проверьте площадку ссылки") }
            val path=URI(url).path.orEmpty()
            return when(source) {
                ModelSource.PRINTABLES -> Regex("/(?:[a-z]{2}/)?model/(\\d+)(?:[-/]|$)").find(path)?.groupValues?.get(1)
                ModelSource.MAKERWORLD -> Regex("/(?:[a-z]{2}/)?models/(\\d+)(?:[-/]|$)").find(path)?.groupValues?.get(1)
                ModelSource.THINGIVERSE -> Regex("/thing:(\\d+)(?:/|$)").find(path)?.groupValues?.get(1)
                ModelSource.CULTS -> Regex("/(?:[a-z]{2}/)?3d-model/[^/]+/([^/]+)").find(path)?.groupValues?.get(1)
            } ?: error(tr("Нужна ссылка на конкретную модель, а не на главную или поиск"))
        }
        fun decode(source:ModelSource,item:JsonElement):LibraryModel? {
            if(item !is JsonObject)return null
            val title=(item.text("name") ?: item.text("title"))?.takeIf { it.isNotBlank() } ?: return null
            val id=item.text("id") ?: item.text("url")?.let { runCatching { modelId(source,it) }.getOrNull() } ?: return null
            val url=when(source) {
                ModelSource.PRINTABLES -> "https://www.printables.com/model/${id}-${item.text("slug").orEmpty()}"
                ModelSource.MAKERWORLD -> "https://makerworld.com/en/models/${id}-${item.text("slug").orEmpty()}"
                ModelSource.THINGIVERSE -> "https://www.thingiverse.com/thing:${id}"
                ModelSource.CULTS -> item.text("url") ?: return null
            }
            val user=when(source) { ModelSource.PRINTABLES -> item.obj("user");ModelSource.MAKERWORLD -> item.obj("designCreator");else -> item.obj("creator") }
            val image=when(source) {
                ModelSource.PRINTABLES -> item.obj("image").text("filePath")?.let(::printablesThumbnail)
                ModelSource.MAKERWORLD -> item.text("cover") ?: item.text("coverUrl")
                ModelSource.THINGIVERSE -> item.text("thumbnail") ?: item.obj("default_image").text("url")
                ModelSource.CULTS -> item.text("illustrationImageUrl")
            }
            val license=if(item["license"] is JsonObject)item.obj("license").text("name") else item.text("license")
            val cents=item.obj("price").int("cents")
            val paid=when(source) {
                ModelSource.PRINTABLES -> item.bool("premium")
                ModelSource.MAKERWORLD -> item.obj("paidSetting").bool("isPaid") ?: item.bool("isPaid")
                ModelSource.THINGIVERSE -> false // Thingiverse's public Things are free to download.
                ModelSource.CULTS -> cents?.let { it>0 }
            }
            return LibraryModel(id="${source.name.lowercase()}:${id}",source=source,url=url,title=title.take(180),imageUrl=image?.takeIf(::isHttps),
                tags=item.array("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.text("name") }.take(20),
                author=user.text("publicUsername") ?: user.text("name") ?: user.text("nick") ?: user.text("handle"),license=license,
                paid=paid,priceLabel=cents?.takeIf { it>0 }?.let { "%.2f USD".format(java.util.Locale.US,it/100.0) },remoteId=id)
        }
        private fun enc(text:String)=URLEncoder.encode(text,"UTF-8").replace("+","%20")
        private fun printablesThumbnail(path:String):String {
            val url=if(isHttps(path))path else "https://media.printables.com/${path.removePrefix("/")}"
            val uri=URI(url);val extension=uri.path.substringAfterLast('.').lowercase()
            // Original photos can be several MB. Use the provider's actual thumbnail
            // route (the extension directory must match the original, including jpeg).
            return if(uri.host=="media.printables.com"&&!uri.path.contains("/thumbs/")&&extension in setOf("jpg","jpeg","png","webp"))
                "https://media.printables.com/${uri.rawPath.removePrefix("/").substringBeforeLast('/')}/thumbs/inside/640x480/${extension}/${uri.rawPath.substringAfterLast('/').substringBeforeLast('.')}.webp"
            else url
        }
        private fun isHttps(url:String)=runCatching { URI(url).let { it.scheme=="https"&&!it.host.isNullOrBlank()&&it.userInfo==null } }.getOrDefault(false)
        private fun requestJson(url:String,body:JsonObject?,headers:Map<String,String>):JsonElement {
            require(isHttps(url))
            val http=(URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout=12000;readTimeout=20000;instanceFollowRedirects=false
                setRequestProperty("User-Agent","OrcaMicro");setRequestProperty("Accept","application/json")
                headers.forEach { (key,value)->setRequestProperty(key,value) }
                if(body!=null){requestMethod="POST";doOutput=true;setRequestProperty("Content-Type","application/json");outputStream.use { it.write(body.toString().toByteArray()) } }
            }
            try {
                val status=http.responseCode
                require(status in 200..299) { when(status){401,403 -> tr("Площадка отклонила доступ (HTTP {0}). Проверьте ваш ключ/сессию в настройках API." ,status);429 -> tr("Слишком много запросов к площадке. Повторите позже.");else -> tr("API площадки: HTTP {0}" ,status)} }
                val bytes=http.inputStream.use { it.readNBytes(4*1024*1024+1) }
                require(bytes.size<=4*1024*1024) { tr("Слишком большой ответ каталога") }
                return runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrElse { error(tr("Площадка вернула страницу вместо JSON API")) }
            } finally { http.disconnect() }
        }
    }
}

private fun JsonElement.obj(key:String):JsonObject=(this as? JsonObject)?.get(key) as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement.array(key:String):List<JsonElement> =((this as? JsonObject)?.get(key) as? JsonArray)?.toList().orEmpty()
private fun JsonElement.text(key:String):String?=((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonElement.int(key:String):Int?=((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
private fun JsonElement.bool(key:String):Boolean?=((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
