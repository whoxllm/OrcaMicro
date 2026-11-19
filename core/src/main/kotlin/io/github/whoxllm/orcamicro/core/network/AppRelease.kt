package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** Only stable ARM64 releases from this application's own GitHub repository. */
@Serializable
data class AppRelease(
    val version:String,val notes:String,val pageUrl:String,val apkUrl:String,val apkName:String,val bytes:Long,val sha256:String,
    val publishedAtMillis:Long?=null,val tag:String="v${version}",
) {
    fun newerThan(installed:String,installedPublishedAtMillis:Long?=null):Boolean {
        if(version==installed.removePrefix("v"))return false
        if(publishedAtMillis!=null&&installedPublishedAtMillis!=null&&publishedAtMillis!=installedPublishedAtMillis)
            return publishedAtMillis>installedPublishedAtMillis
        return ReleaseVersion.compare(version,installed)>0
    }
}

data class ReleaseCheck(val update:AppRelease?,val installedPublishedAtMillis:Long?)

object ReleaseVersion {
    /** Numeric components are compared in order, with missing trailing components equal to zero. */
    fun parts(text:String):List<Long>? {
        val pieces=text.removePrefix("v").split('.')
        if(pieces.any { it.isEmpty()||it.any { c->c !in '0'..'9' } })return null
        return pieces.map { it.toLongOrNull() ?: return null }
    }
    fun compare(first:String,second:String):Int {
        val a=parts(first) ?: error(tr("Некорректная версия релиза"));val b=parts(second) ?: error(tr("Некорректная установленная версия"))
        for(i in 0 until maxOf(a.size,b.size)) { val left=a.getOrElse(i){0};val right=b.getOrElse(i){0};if(left!=right)return left.compareTo(right) }
        return 0
    }
}

object AppReleases {
    const val REPOSITORY="whoxllm/OrcaMicro"
    const val API="https://api.github.com/repos/${REPOSITORY}/releases"
    const val MAX_APK_BYTES=96L*1024*1024
    /** The release asset CI publishes for a version, e.g. OrcaMicro-arm64-v1.1.0.apk. */
    fun apkName(version:String)="OrcaMicro-arm64-v${version}.apk"
    private const val PAGE_SIZE=100
    private const val MAX_PAGES=10

    fun check(installed:String,cachedInstalledPublishedAtMillis:Long?=null):ReleaseCheck =
        checkUsing(installed,cachedInstalledPublishedAtMillis,::getJson)

    internal fun checkUsing(installed:String,cachedInstalledPublishedAtMillis:Long?,fetch:(String)->JsonElement):ReleaseCheck {
        require(ReleaseVersion.parts(installed)!=null) { tr("Некорректная установленная версия") }
        val releases=mutableListOf<JsonElement>()
        for(page in 1..MAX_PAGES) {
            val result=fetch("${API}?per_page=${PAGE_SIZE}&page=${page}")
            val items=result as? JsonArray ?: error(tr("Неполный ответ GitHub"))
            releases.addAll(items)
            if(items.size<PAGE_SIZE)break
            require(page<MAX_PAGES) { tr("Слишком много релизов для проверки обновлений") }
        }
        val fromList=installedDate(releases,installed)
        val baseline=fromList ?: cachedInstalledPublishedAtMillis ?: runCatching {
            publishedAt(fetch("${API}/tags/v${installed.removePrefix("v")}") as? JsonObject)
        }.getOrNull()
        return select(releases,installed,baseline)
    }

    private fun installedDate(releases:List<JsonElement>,installed:String):Long? = releases.asSequence().mapNotNull { it as? JsonObject }
        .firstOrNull { it["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v")==installed.removePrefix("v") }
        ?.let(::publishedAt)

    internal fun select(releases:List<JsonElement>,installed:String,knownInstalledDate:Long?=null):ReleaseCheck {
        val baseline=installedDate(releases,installed) ?: knownInstalledDate
        val candidates=releases.mapNotNull { runCatching { decode(it) }.getOrNull() }.filter { it.newerThan(installed,baseline) }
        val update=candidates.maxWithOrNull(compareBy<AppRelease> { it.publishedAtMillis ?: Long.MIN_VALUE }
            .thenComparator { a,b->ReleaseVersion.compare(a.version,b.version) })
        return ReleaseCheck(update,baseline)
    }

    private fun publishedAt(root:JsonObject?):Long? = root?.get("published_at")?.jsonPrimitive?.contentOrNull?.let { date->runCatching { Instant.parse(date).toEpochMilli() }.getOrNull() }

    fun decode(root:JsonElement):AppRelease? {
        val obj=root as? JsonObject ?: error(tr("Неполный ответ GitHub"))
        if(obj["draft"]?.jsonPrimitive?.booleanOrNull==true||obj["prerelease"]?.jsonPrimitive?.booleanOrNull==true)return null
        val tag=obj["tag_name"]?.jsonPrimitive?.content ?: error(tr("В релизе нет версии"))
        if(ReleaseVersion.parts(tag)==null)return null
        val version=tag.removePrefix("v")
        val name=apkName(version)
        val asset=(obj["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.singleOrNull { it["name"]?.jsonPrimitive?.content==name } ?: return null
        val size=asset["size"]?.jsonPrimitive?.longOrNull ?: return null
        require(size in 1..MAX_APK_BYTES) { tr("Размер обновления не поддерживается") }
        val digest=asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:") ?: return null
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { tr("GitHub не предоставил SHA-256 обновления") }
        val page="https://github.com/${REPOSITORY}/releases/tag/${tag}"
        val url=asset["browser_download_url"]?.jsonPrimitive?.content ?: return null
        require(url=="https://github.com/${REPOSITORY}/releases/download/${tag}/${name}") { tr("Неверный источник APK обновления") }
        return AppRelease(version,obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(48_000),page,url,name,size,digest.lowercase(),publishedAt(obj),tag)
    }
    fun verifyDownload(file:File,release:AppRelease) {
        require(file.isFile&&file.length()==release.bytes) { tr("Обновление скачано не полностью. Повторите загрузку.") }
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input->val b=ByteArray(64*1024);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)} }
        val actual=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        require(actual==release.sha256) { tr("Контрольная сумма обновления не совпала. APK не будет установлен.") }
    }
    private fun getJson(url:String):JsonElement {
        val http=(URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout=12_000;readTimeout=15_000;instanceFollowRedirects=false
            setRequestProperty("Accept","application/vnd.github+json");setRequestProperty("User-Agent","OrcaMicro-Updater")
        }
        try {
            if(http.responseCode==404)return buildJsonObject { put("draft",true) }
            require(http.responseCode in 200..299) { if(http.responseCode in setOf(403,429))tr("GitHub временно ограничил проверки. Повторите позже.") else tr("Проверка обновления: HTTP {0}" ,http.responseCode) }
            val bytes=http.inputStream.use { it.readNBytes(8*1024*1024+1) };require(bytes.size<=8*1024*1024) { tr("Слишком большой ответ GitHub") }
            return Json.parseToJsonElement(bytes.decodeToString())
        }finally{http.disconnect()}
    }
}

/** Exact, case-sensitive text. No whitespace normalization or saved authorization. */
object EmergencyStopConfirmation {
    const val TEXT="Stop Klipper!"
    fun matches(text:String)=text==TEXT
}
