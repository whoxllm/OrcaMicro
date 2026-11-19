package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** Official OctoPrint REST API. Files are always uploaded with print=false. */
class OctoPrintClient(connection:PrinterConnection):PrinterClient {
    private val config=connection.normalized()
    private fun JsonObject.obj(key:String)=(this[key] as? JsonObject) ?: JsonObject(emptyMap())
    private fun JsonObject.num(key:String)=this[key]?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()}
    private fun JsonObject.text(key:String)=this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.flag(key:String)=this[key]?.jsonPrimitive?.booleanOrNull==true
    private fun connect(path:String)= (URI(config.apiUrl+path).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout=10000;readTimeout=15000;instanceFollowRedirects=false;setRequestProperty("X-Api-Key",config.apiKey);setRequestProperty("Accept","application/json")
    }
    private fun read(http:HttpURLConnection):JsonObject {
        val code=http.responseCode
        require(code !in setOf(401,403)){tr("OctoPrint: проверьте API-ключ и права доступа")}
        require(code in 200..299){"OctoPrint: HTTP ${code}"}
        if(code==204)return JsonObject(emptyMap())
        val bytes=http.inputStream.use{it.readNBytes(4*1024*1024+1)};require(bytes.size<=4*1024*1024){tr("Ответ OctoPrint слишком большой")}
        return Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: error(tr("Неверный ответ OctoPrint"))
    }
    private fun get(path:String):JsonObject {val h=connect(path);return try{read(h)}finally{h.disconnect()}}
    private fun post(path:String,body:JsonObject) {val h=connect(path).apply{requestMethod="POST";doOutput=true;setRequestProperty("Content-Type","application/json")};try{h.outputStream.use{it.write(body.toString().toByteArray())};read(h)}finally{h.disconnect()}}
    override fun snapshot():KlipperSnapshot {
        val p=get("/api/printer");val j=get("/api/job");val flags=p.obj("state").obj("flags");val progress=j.obj("progress")
        val state=when{flags.flag("paused")||flags.flag("pausing")->"paused";flags.flag("printing")->"printing";flags.flag("error")||flags.flag("closedOrError")->"error";else->"standby"}
        val heaters=p.obj("temperature").mapNotNull{(name,v)->val o=v as? JsonObject ?: return@mapNotNull null;val t=o.num("actual") ?: return@mapNotNull null;HeaterState(name,t.toFloat(),o.num("target")?.toFloat(),max=when(name){"bed"->150f;"chamber"->120f;else->350f})}
        val elapsed=progress.num("printTime")?.coerceAtLeast(0.0) ?: 0.0
        return KlipperSnapshot(ready=flags.flag("operational")&&!flags.flag("error")&&!flags.flag("closedOrError"),message=p.obj("state").text("text"),heaters=heaters,printState=state,filename=j.obj("job").obj("file").text("display").ifBlank{j.obj("job").obj("file").text("name")},
            progress=((progress.num("completion") ?: 0.0)/100).toFloat().coerceIn(0f,1f),printSeconds=elapsed,totalSeconds=elapsed,
            startedAtMillis=(System.currentTimeMillis()-(elapsed*1000).toLong()).takeIf{elapsed>0&&state in setOf("printing","paused")},remainingSeconds=progress.num("printTimeLeft")?.takeIf{it>=0&&state=="printing"},startEstimated=true,positionKnown=false)
    }
    override fun temperatureHistory():List<TemperatureSeries> {
        val records=get("/api/printer?history=true&limit=600").obj("temperature")["history"]?.jsonArray.orEmpty().mapNotNull{it as? JsonObject}
        val names=records.flatMap{it.keys}.filter{it!="time"}.distinct()
        return names.map{name->TemperatureSeries(name,records.map{it.obj(name).num("actual")?.toFloat()},records.map{it.obj(name).num("target")?.toFloat()},records.map{((it.num("time") ?: 0.0)*1000).toLong()})}
    }
    override fun info():String {get("/api/version");return "OctoPrint · "+get("/api/connection").obj("current").text("state")}
    override fun setHeater(name:String,target:Float) {
        val s=snapshot();val h=s.heaters.firstOrNull{it.name==name} ?: error(tr("Нагреватель недоступен"))
        require(s.ready&&target.isFinite()&&target>=0&&target<=(h.max ?: 0f)){tr("Недопустимая температура")}
        when(name){"bed","chamber"->post("/api/printer/${name}",buildJsonObject{put("command","target");put("target",target)})
            else->{require(name.matches(Regex("tool[0-9]+")));post("/api/printer/tool",buildJsonObject{put("command","target");put("targets",buildJsonObject{put(name,target)})})}}
    }
    override fun jog(axis:String,amount:Float) {val s=snapshot();require(s.ready&&!s.printing&&axis in listOf("X","Y","Z")&&amount.isFinite()&&kotlin.math.abs(amount) in 0.01f..50f){tr("Перемещение недоступно")};post("/api/printer/printhead",buildJsonObject{put("command","jog");put(axis.lowercase(),amount);put("absolute",false)})}
    override fun home(axes:String) {val s=snapshot();require(s.ready&&!s.printing);require(axes in listOf("","X","Y","Z","XY"));post("/api/printer/printhead",buildJsonObject{put("command","home");put("axes",JsonArray((axes.ifEmpty{"XYZ"}).map{JsonPrimitive(it.lowercase())}))})}
    private fun command(gcode:String)=post("/api/printer/command",buildJsonObject{put("command",gcode)})
    override fun setFan(percent:Int){require(percent in 0..100);command("M106 S${percent*255/100}")}
    override fun heatersOff(){snapshot().heaters.filter{it.target!=null}.forEach{setHeater(it.name,0f)}}
    override fun motorsOff(){val s=snapshot();require(s.ready&&!s.printing);command("M18")}
    override fun emergencyStop()=command("M112")
    override fun pause()=post("/api/job",buildJsonObject{put("command","pause");put("action","pause")})
    override fun resume()=post("/api/job",buildJsonObject{put("command","pause");put("action","resume")})
    override fun cancelPrint()=post("/api/job",buildJsonObject{put("command","cancel")})
    override fun startPrint(filename:String) {require(filename.isNotBlank()&&!filename.startsWith('/')&&filename.split('/').none{it==".."});val s=snapshot();require(s.ready&&!s.printing){tr("Принтер не готов к новой печати")};val path=filename.split('/').joinToString("/"){URLEncoder.encode(it,"UTF-8").replace("+","%20")};post("/api/files/local/${path}",buildJsonObject{put("command","select");put("print",true)})}
    override fun upload(file:File,name:String,onProgress:(Int)->Unit):String {
        require(file.isFile&&file.length()>0);val filename=name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\r\\n\\\"\\\\]"),"_");require(filename.endsWith(".gcode",true)||filename.endsWith(".gco",true)||filename.endsWith(".gc",true))
        val boundary="Orca${UUID.randomUUID()}";val h=connect("/api/files/local").apply{requestMethod="POST";doOutput=true;setRequestProperty("Content-Type","multipart/form-data; boundary=${boundary}");setChunkedStreamingMode(65536)}
        try {
            onProgress(0);h.outputStream.buffered(65536).use { out->
                fun text(s:String)=out.write(s.toByteArray())
                for(field in listOf("select","print"))text("--${boundary}\r\nContent-Disposition: form-data; name=\"${field}\"\r\n\r\nfalse\r\n")
                text("--${boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use{input->val b=ByteArray(65536);var sent=0L;var previous=-1;while(true){val n=input.read(b);if(n<0)break;out.write(b,0,n);sent+=n;val percent=(sent*99/file.length()).toInt();if(percent!=previous){onProgress(percent);previous=percent}}}
                text("\r\n--${boundary}--\r\n")
            }
            val result=read(h);require(result.flag("done")){tr("OctoPrint не подтвердил загрузку")};val path=result.obj("files").obj("local").text("path");require(path.isNotBlank());onProgress(100);return path
        }finally{h.disconnect()}
    }
    override fun statistics():PrinterStatistics {
        val files=get("/api/files/local?recursive=true")["files"]?.jsonArray ?: error(tr("История файлов OctoPrint недоступна"))
        var success=0L;var failed=0L;var count=0
        fun visit(entries:JsonArray){for(e in entries){require(++count<=20000){tr("Слишком много файлов OctoPrint")};val f=e.jsonObject;success+=(f.obj("print").num("success") ?: 0.0).toLong().coerceAtLeast(0);failed+=(f.obj("print").num("failure") ?: 0.0).toLong().coerceAtLeast(0);(f["children"] as? JsonArray)?.let(::visit)}}
        visit(files)
        return PrinterStatistics(completed=success,failed=failed,printSeconds=null,totalSeconds=null,filamentMm=null,longestSeconds=null,note=tr("OctoPrint: счётчики доступных файлов. Удалённые файлы не учтены; общее время REST API не предоставляет."))
    }
    override fun readConfiguration():DetectedPrinterConfig {
        val profiles=get("/api/printerprofiles").obj("profiles").values.mapNotNull{it as? JsonObject}
        val profile=profiles.firstOrNull{it.flag("current")} ?: profiles.firstOrNull{it.flag("default")} ?: error(tr("Нет активного профиля OctoPrint"))
        return PrinterConfiguration.octoprint(profile)
    }
}
