package io.github.whoxllm.orcamicro.core.network

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class AppReleaseTest {
    private fun release(tag:String="v0.6.2",digest:String="a".repeat(64),url:String="https://github.com/whoxllm/OrcaMicro/releases/download/$tag/OrcaMicro-arm64-v${tag.removePrefix("v")}.apk",size:Long=55228255,published:String?=null)=buildJsonObject {
        published?.let { put("published_at",it) }
        put("tag_name",tag);put("body","## Changes\nSTOP confirmation\nUpdater");put("draft",false);put("prerelease",false)
        put("assets",buildJsonArray {add(buildJsonObject {put("name","OrcaMicro-arm64-v${tag.removePrefix("v")}.apk");put("size",size);put("digest","sha256:$digest");put("browser_download_url",url)})})
    }
    @Test fun `numeric version comparison handles patch releases and skips unstable versions`() {
        assertTrue(ReleaseVersion.compare("0.6.10","0.6.2")>0);assertTrue(ReleaseVersion.compare("v0.6.2","0.6.0")>0)
        assertEquals(0,ReleaseVersion.compare("0.6.2","v0.6.2"));assertTrue(ReleaseVersion.compare("0.6.2","0.7.0")<0)
        for(text in listOf("","0..6",".0.6","0.6.","0.6.3-beta","nightly","999999999999999999999.0.1"))assertNull(ReleaseVersion.parts(text))
    }
    @Test fun `four component releases are downloadable and compare numerically`() {
        assertEquals(listOf(0L,6L,7L,1L),ReleaseVersion.parts("v0.6.7.1"))
        assertTrue(ReleaseVersion.compare("0.6.7.1","0.6.7")>0)
        assertTrue(ReleaseVersion.compare("0.6.7.10","0.6.7.9")>0)
        assertTrue(ReleaseVersion.compare("0.6.8","0.6.7.99")>0)
        assertEquals(0,ReleaseVersion.compare("0.6.7.0","0.6.7"))
        val update=AppReleases.decode(release(tag="v0.6.7.1"))!!
        assertEquals("0.6.7.1",update.version);assertTrue(update.newerThan("0.6.6"))
        assertEquals("OrcaMicro-arm64-v0.6.7.1.apk",update.apkName)
    }
    @Test fun `versions have no component count limit and do not compare component sums`() {
        assertTrue(ReleaseVersion.compare("0.6.7.1.0.0.0.2","0.6.7.1.0.0.0.1")>0)
        assertTrue(ReleaseVersion.compare("1.0.0","0.9.9")>0)
        assertTrue(ReleaseVersion.compare("0.6.10","0.6.9.999")>0)
        assertEquals(0,ReleaseVersion.compare("v0.6.7.1.0.0.0.0","0.6.7.1"))
        val tag="v0.6.7.1.2.3.4.5"
        assertEquals(tag.removePrefix("v"),AppReleases.decode(release(tag))!!.version)
    }
    @Test fun `publication date chooses the update independent of list order or numeric label`() {
        val current=release("v2.0.0",published="2026-11-05T10:00:00Z")
        val new=release("v1.0.0.0.0.0.0.1",published="2026-11-05T12:00:00Z")
        val old=release("v99.0.0",published="2026-11-04T12:00:00Z")
        val update=AppReleases.select(listOf(new,current,old),"2.0.0")
        assertEquals("1.0.0.0.0.0.0.1",update.update!!.version)
        assertTrue(update.update!!.newerThan("2.0.0",update.installedPublishedAtMillis))
        assertNull(AppReleases.select(listOf(old,current),"2.0.0").update)
        assertNull(AppReleases.select(listOf(new,current),"1.0.0.0.0.0.0.1").update)
    }
    @Test fun `missing dates use numeric comparison and older cached releases still deserialize`() {
        assertEquals("0.6.9.0.0.0.0.1",AppReleases.select(listOf(release("v0.6.9.0.0.0.0.1")),"0.6.8").update!!.version)
        val legacy=Json.encodeToString(AppRelease.serializer(),AppReleases.decode(release())!!)
        val restored=Json.decodeFromString(AppRelease.serializer(),legacy)
        assertNull(restored.publishedAtMillis);assertEquals("v0.6.2",restored.tag)
        val withoutDefaults=Json.parseToJsonElement(legacy).jsonObject.let { JsonObject(it-"publishedAtMillis"-"tag") }
        assertEquals(restored,Json.decodeFromJsonElement(AppRelease.serializer(),withoutDefaults))
    }
    @Test fun `draft incomplete invalid and prerelease assets cannot hide a valid update`() {
        val valid=release("v0.6.9",published="2026-11-05T12:00:00Z")
        val newer=release("v0.6.10",published="2026-11-06T12:00:00Z")
        val entries=listOf(valid,JsonObject(newer+("prerelease" to JsonPrimitive(true))),JsonObject(newer+("draft" to JsonPrimitive(true))),JsonObject(newer+("assets" to JsonArray(emptyList()))),release("v0.6.11",digest="bad"))
        assertEquals("0.6.9",AppReleases.select(entries,"0.6.8").update!!.version)
    }
    @Test fun `pagination includes older pages and finds baseline even without the installed APK asset`() {
        val calls=mutableListOf<String>()
        val current=release("v0.6.8",published="2026-11-05T10:00:00Z").let { JsonObject(it+("assets" to JsonArray(emptyList()))) }
        val next=release("v0.6.7.1.2.3.4.5",published="2026-11-06T10:00:00Z")
        val result=AppReleases.checkUsing("0.6.8",null){url->calls+=url;when {
            url.endsWith("page=1")->JsonArray(List(100){release("v0.1.0",published="2026-01-01T10:00:00Z")})
            url.endsWith("page=2")->JsonArray(listOf(current,next))
            else->error("Unexpected request $url")
        }}
        assertEquals(2,calls.size);assertEquals("0.6.7.1.2.3.4.5",result.update!!.version);assertNotNull(result.installedPublishedAtMillis)
    }
    @Test fun `missing current release is looked up by tag and cached baseline avoids repeated lookup`() {
        val date=java.time.Instant.parse("2026-11-05T10:00:00Z").toEpochMilli()
        val next=release("v0.0.1",published="2026-11-06T10:00:00Z")
        val calls=mutableListOf<String>()
        val found=AppReleases.checkUsing("0.6.8",null){url->calls+=url;if(url.contains("/tags/"))release("v0.6.8",published="2026-11-05T10:00:00Z")else JsonArray(listOf(next))}
        assertEquals(2,calls.size);assertEquals("0.0.1",found.update!!.version);assertEquals(date,found.installedPublishedAtMillis)
        calls.clear()
        assertEquals("0.0.1",AppReleases.checkUsing("0.6.8",date){url->calls+=url;JsonArray(listOf(next))}.update!!.version)
        assertEquals(1,calls.size)
    }
    @Test fun `only stable ARM64 releases from the app repository are offered`() {
        val update=AppReleases.decode(release())!!
        assertTrue(update.newerThan("0.6.0"));assertFalse(update.newerThan("0.6.2"));assertTrue(update.notes.contains("STOP confirmation"))
        assertEquals("OrcaMicro-arm64-v0.6.2.apk",update.apkName)
        assertFails {AppReleases.decode(release(url="https://github.com/other/project/releases/download/v0.6.2/model.apk"))}
        assertFails {AppReleases.decode(release(digest="invalid"))}
        assertFails {AppReleases.decode(release(size=AppReleases.MAX_APK_BYTES+1))}
        assertNull(AppReleases.decode(JsonObject(release()+ ("draft" to JsonPrimitive(true)))))
        assertNull(AppReleases.decode(JsonObject(release()+ ("prerelease" to JsonPrimitive(true)))))
        assertNull(AppReleases.decode(JsonObject(release()+ ("assets" to JsonArray(emptyList())))))
    }
    @Test fun `truncated or modified bootstrap downloads cannot be installed`() {
        val file=Files.createTempFile("update",".apk").toFile()
        try {
            file.writeText("signed APK fixture")
            val digest=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
            val update=AppReleases.decode(release(digest=digest,size=file.length()))!!
            AppReleases.verifyDownload(file,update)
            file.appendText("changed");assertFails {AppReleases.verifyDownload(file,update)}
            file.writeText("x".repeat(update.bytes.toInt()));assertFails {AppReleases.verifyDownload(file,update)}
        }finally{file.delete()}
    }
    @Test fun `STOP requires the user's exact case sensitive phrase`() {
        assertTrue(EmergencyStopConfirmation.matches("Stop Klipper!"))
        for(text in listOf("","Stop klipper!","Stop Klipper","stop klipper!","Stop Klipper! "," Stop Klipper!","Stop Klipper!\n","STOP KLIPPER!"))assertFalse(EmergencyStopConfirmation.matches(text),text)
    }
}
