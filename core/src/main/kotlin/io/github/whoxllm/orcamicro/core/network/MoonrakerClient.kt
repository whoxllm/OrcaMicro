package io.github.whoxllm.orcamicro.core.network

import io.github.whoxllm.orcamicro.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

@Serializable
data class PrinterConnection(val apiUrl: String = "", val webUrl: String = "", val apiKey: String = "", val backend:PrinterBackend = PrinterBackend.MOONRAKER) {
    fun normalized(): PrinterConnection {
        val api = normalizeUrl(apiUrl).let{if(backend==PrinterBackend.OCTOPRINT)it.removeSuffix("/api")else it}
        val parsed = URI(api)
        val defaultWeb = if (parsed.port == 7125) URI(parsed.scheme, null, parsed.host, -1, null, null, null).toString() else api
        return copy(apiUrl = api, webUrl = normalizeUrl(webUrl.ifBlank { defaultWeb }))
    }

    companion object {
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { tr("Укажите адрес принтера") }
            val uri = try { URI(if ("://" in trimmed) trimmed else "http://${trimmed}") }
                catch (e: java.net.URISyntaxException) { throw IllegalArgumentException(tr("Проверьте адрес принтера")) }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in -1..65535) { tr("Нужен адрес вида http://printer.local или http://192.168.1.10:7125") }
            return uri.toASCIIString().trimEnd('/')
        }
    }
}

/** Moonraker HTTP API. Upload and start are separate requests. */
class MoonrakerClient(connection: PrinterConnection) : PrinterClient {
    private val config = connection.normalized()
    private var objects: Set<String>? = null
    private var heaterConfig: JsonObject? = null
    private var jobFile:String?=null
    private var jobInfoAt=0L
    private var previousPrintState=""
    private var previousPrintDuration=0.0
    private var jobMetadata:JsonObject?=null
    private var jobStart:Long?=null

    override fun snapshot(): KlipperSnapshot {
        val available = objects ?: get("/printer/objects/list")["objects"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet().also { objects = it }
        val names = available.filter { it in setOf("webhooks", "heaters", "toolhead", "print_stats", "virtual_sdcard", "fan") ||
            it.startsWith("extruder") || it == "heater_bed" || it.startsWith("heater_generic ") || it.startsWith("temperature_sensor ") || it.startsWith("temperature_fan ") ||
            (it == "configfile" && heaterConfig == null) }
        val status = post("/printer/objects/query", buildJsonObject { put("objects", buildJsonObject { names.forEach { put(it, JsonNull) } }) })["status"]?.jsonObject ?: error(tr("Нет телеметрии Klipper"))
        if (heaterConfig == null) heaterConfig = status["configfile"]?.jsonObject?.get("config") as? JsonObject
        fun fields(name: String) = status[name] as? JsonObject ?: JsonObject(emptyMap())
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun JsonObject.float(key: String, default: Float = 0f) = this[key]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() } ?: default
        fun vector(key: String) = (fields("toolhead")[key] as? JsonArray)?.take(3)?.map { it.jsonPrimitive.floatOrNull?.takeIf(Float::isFinite) ?: 0f }?.takeIf { it.size == 3 } ?: listOf(0f, 0f, 0f)
        val heaters = status.mapNotNull { (name, value) ->
            val data = value as? JsonObject ?: return@mapNotNull null
            val temperature = data["temperature"]?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite) ?: return@mapNotNull null
            val limits = heaterConfig?.get(name) as? JsonObject
            HeaterState(name, temperature, data["target"]?.jsonPrimitive?.floatOrNull, limits?.float("min_temp") ?: 0f,
                limits?.get("max_temp")?.jsonPrimitive?.floatOrNull?.takeIf(Float::isFinite))
        }.sortedBy { when(it.name) { "extruder" -> "0"; "heater_bed" -> "1"; else -> it.name } }
        val print=fields("print_stats");val filename=print.text("filename");val now=System.currentTimeMillis()
        if(filename!=jobFile||print.text("state")!=previousPrintState||(print["print_duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0)<previousPrintDuration||now-jobInfoAt>30_000) {
            jobFile=filename;jobInfoAt=now;jobStart=null;jobMetadata=null
            if(filename.isNotBlank()) {
                jobMetadata=runCatching{get("/server/files/metadata?filename="+java.net.URLEncoder.encode(filename,"UTF-8"))}.getOrNull()
                jobStart=jobMetadata?.get("print_start_time")?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>0}?.let{(it*1000).toLong()}
                if(jobStart==null)jobStart=runCatching{get("/server/history/list?limit=1&order=desc")["jobs"]?.jsonArray?.firstOrNull()?.jsonObject?.takeIf{it.text("filename")==filename}?.get("start_time")?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>0}?.let{(it*1000).toLong()}}.getOrNull()
            }
        }
        val printSeconds=print["print_duration"]?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0} ?: 0.0
        previousPrintState=print.text("state");previousPrintDuration=printSeconds
        val totalSeconds=print["total_duration"]?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0} ?: 0.0
        val progress=fields("virtual_sdcard").float("progress").coerceIn(0f,1f)
        return KlipperSnapshot(fields("webhooks").text("state") == "ready", fields("webhooks").text("state_message"), heaters,
            fields("toolhead").text("homed_axes"), vector("position"), vector("axis_minimum"), vector("axis_maximum"),
            fields("print_stats").text("state"), fields("print_stats").text("filename"), fields("virtual_sdcard").float("progress").coerceIn(0f, 1f),
            if("fan" in available) fields("fan").float("speed") else null,
            printSeconds=printSeconds,totalSeconds=totalSeconds,startedAtMillis=jobStart ?: (now-(totalSeconds*1000).toLong()).takeIf{totalSeconds>0&&print.text("state") in setOf("printing","paused")},
            remainingSeconds=if(print.text("state")=="printing")PrintTiming.remaining(progress,printSeconds,jobMetadata?.get("estimated_time")?.jsonPrimitive?.doubleOrNull)else null,
            startEstimated=jobStart==null)
    }

    override fun temperatureHistory(): List<TemperatureSeries> = get("/server/temperature_store").mapNotNull { (name, value) ->
        val data = value as? JsonObject ?: return@mapNotNull null
        fun series(key: String) = (data[key] as? JsonArray)?.takeLast(600)?.map { it.jsonPrimitive.floatOrNull?.takeIf(Float::isFinite) }.orEmpty()
        TemperatureSeries(name, series("temperatures"), series("targets")).takeIf { it.temperatures.isNotEmpty() }
    }

    override fun setHeater(name: String, target: Float) = script(KlipperControl.heater(snapshot(), name, target))
    override fun jog(axis: String, amount: Float) = script(KlipperControl.move(snapshot(), axis, amount))
    override fun home(axes: String) = script(KlipperControl.home(snapshot(), axes))
    override fun setFan(percent: Int) = script(KlipperControl.fan(snapshot(), percent))
    override fun heatersOff() = script("TURN_OFF_HEATERS")
    override fun motorsOff() { val s = snapshot(); require(s.ready && !s.printing) { tr("Двигатели заняты печатью") }; script("M84") }
    override fun emergencyStop() { post("/printer/emergency_stop") }
    override fun pause() { post("/printer/print/pause") }
    override fun resume() { post("/printer/print/resume") }
    override fun cancelPrint() { post("/printer/print/cancel") }
    override fun startPrint(filename: String) {
        require(filename.isNotBlank() && !filename.contains('\n') && !filename.startsWith('/') && filename.split('/').none { it == ".." })
        val s = snapshot()
        require(s.ready && !s.printing) { tr("Принтер не готов к новой печати") }
        post("/printer/print/start", buildJsonObject { put("filename", filename) })
    }

    private fun script(command: String) { post("/printer/gcode/script", buildJsonObject { put("script", command) }) }
    private fun get(path: String): JsonObject {
        val http = connect(path)
        return try { readResponse(http)["result"]?.jsonObject ?: error(tr("Пустой ответ Moonraker")) } finally { http.disconnect() }
    }
    private fun post(path: String, body: JsonObject = JsonObject(emptyMap())): JsonObject {
        val http = connect(path).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
        return try {
            http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val result = readResponse(http)["result"]
            result as? JsonObject ?: JsonObject(emptyMap())
        } finally { http.disconnect() }
    }

    override fun info(): String {
        val http = connect("/server/info")
        return try {
            val result = readResponse(http)["result"]?.jsonObject ?: error(tr("Адрес ответил без данных Moonraker"))
            val state = result["klippy_state"]?.jsonPrimitive?.content.orEmpty()
            if (state == "ready") tr("Klipper готов") else {
                val label = when(state) { "error" -> tr("ошибка Klipper"); "shutdown" -> tr("Klipper остановлен"); "startup" -> tr("Klipper запускается"); "disconnected" -> tr("нет связи с Klipper"); else -> state }
                val detail = if(state in setOf("error","shutdown")) runCatching {
                    val printer=connect("/printer/info")
                    try { readResponse(printer)["result"]?.jsonObject?.get("state_message")?.jsonPrimitive?.content?.lineSequence()?.firstOrNull() }
                    finally { printer.disconnect() }
                }.getOrNull() else null
                tr("Moonraker подключён · {0}" ,label) + (detail?.let { "\n${it}" } ?: "")
            }
        } finally { http.disconnect() }
    }

    override fun statistics():PrinterStatistics {
        val all=mutableListOf<PrintHistoryJob>();val seen=mutableSetOf<String>();var offset=0
        while(offset<10000) {
            if(Thread.currentThread().isInterrupted)error(tr("Загрузка отменена"))
            val page=get("/server/history/list?limit=500&start=${offset}&order=desc")["jobs"]?.jsonArray ?: error(tr("История Moonraker недоступна"))
            for(entry in page) {
                val j=entry.jsonObject
                fun number(key:String)=j[key]?.jsonPrimitive?.doubleOrNull?.takeIf{it.isFinite()&&it>=0} ?: 0.0
                fun time(key:String)=number(key).takeIf{it>0}?.let{(it*1000).toLong()}
                val id=j["job_id"]?.jsonPrimitive?.content ?: error(tr("В истории нет идентификатора"))
                if(seen.add(id))all+=PrintHistoryJob(id,j["filename"]?.jsonPrimitive?.content.orEmpty(),j["status"]?.jsonPrimitive?.content.orEmpty(),time("start_time"),time("end_time"),number("print_duration"),number("total_duration"),number("filament_used"))
            }
            offset+=page.size
            if(page.size<500)return PrinterStatistics.from(all)
        }
        return PrinterStatistics.from(all,limited=true)
    }
    override fun readConfiguration():DetectedPrinterConfig {
        val status=post("/printer/objects/query",buildJsonObject{put("objects",buildJsonObject{put("configfile",JsonNull);put("toolhead",JsonNull)})})["status"]?.jsonObject ?: error(tr("Нет конфигурации Klipper"))
        val cfg=(status["configfile"]?.jsonObject?.get("settings") ?: status["configfile"]?.jsonObject?.get("config"))?.jsonObject ?: error(tr("Конфигурация Klipper недоступна"))
        return PrinterConfiguration.klipper(cfg,status["toolhead"]?.jsonObject)
    }

    override fun upload(file: File, name: String, onProgress: (Int) -> Unit): String {
        require(file.isFile && file.length() > 0) { tr("G-code пуст или недоступен") }
        val filename = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\r\\n\"\\\\]"), "_")
        require(filename.endsWith(".gcode", true) || filename.endsWith(".gco", true) || filename.endsWith(".gc", true)) { tr("Выберите файл G-code") }
        val boundary = "OrcaAndroid${UUID.randomUUID()}"
        val http = connect("/server/files/upload").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=${boundary}")
            setChunkedStreamingMode(64 * 1024)
        }
        try {
            onProgress(0)
            http.outputStream.buffered(64 * 1024).use { out ->
                fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
                text("--${boundary}\r\nContent-Disposition: form-data; name=\"root\"\r\n\r\ngcodes\r\n")
                text("--${boundary}\r\nContent-Disposition: form-data; name=\"print\"\r\n\r\nfalse\r\n")
                text("--${boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var previous = -1
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        out.write(buffer, 0, size)
                        sent += size
                        val percent = (sent * 99 / file.length()).toInt()
                        if (percent != previous) { onProgress(percent); previous = percent }
                    }
                }
                text("\r\n--${boundary}--\r\n")
            }
            val response = readResponse(http)
            require(response["result"] != null || response["item"] != null) { tr("Moonraker не подтвердил загрузку") }
            onProgress(100)
            val item = (response["result"] as? JsonObject)?.get("item") as? JsonObject ?: response["item"] as? JsonObject
            return item?.get("path")?.jsonPrimitive?.contentOrNull ?: filename
        } finally { http.disconnect() }
    }

    private fun connect(path: String): HttpURLConnection = (URI(config.apiUrl + path).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json")
        if (config.apiKey.isNotBlank()) setRequestProperty("X-Api-Key", config.apiKey)
    }

    private fun readResponse(http: HttpURLConnection): JsonObject {
        val code = http.responseCode
        if (code == 401 || code == 403) error(tr("Moonraker требует авторизацию. Укажите API-ключ или разрешите телефон в trusted_clients."))
        require(code in 200..299) { "Moonraker: HTTP ${code}" }
        val body = http.inputStream.use { stream -> stream.readBytesLimited(4 * 1024 * 1024) }
        val json = Json.parseToJsonElement(body) as? JsonObject ?: error(tr("Адрес вернул не ответ Moonraker"))
        require(json["error"] == null) { "Moonraker: " + ((json["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: tr("запрос отклонён")) }
        return json
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(out.size() + count <= limit) { tr("Ответ принтера слишком большой") }
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }
}
