package io.github.whoxllm.orcamicro.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import kotlin.test.*

class PrinterIntegrationTest {
    private class Fixture:AutoCloseable {
        val requests=CopyOnWriteArrayList<Triple<String,String,String>>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val replies=mutableMapOf<String,(String)->Pair<Int,String>>()
        val url get()="http://127.0.0.1:${server.address.port}"
        init{server.createContext("/"){e->val path=e.requestURI.path;val query=e.requestURI.rawQuery.orEmpty();requests+=Triple(e.requestMethod,path,e.requestBody.readBytes().decodeToString());assertEquals("fixture",e.requestHeaders.getFirst("X-Api-Key"));val (code,text)=replies[path]?.invoke(query) ?: (404 to "{}");if(code==204){e.sendResponseHeaders(204,-1);e.close()}else{val bytes=text.toByteArray();e.responseHeaders.set("Content-Type","application/json");e.sendResponseHeaders(code,bytes.size.toLong());e.responseBody.use{it.write(bytes)}}};server.start()}
        fun json(path:String,text:String){replies[path]={200 to text}}
        fun octo()=OctoPrintClient(PrinterConnection(url,apiKey="fixture",backend=PrinterBackend.OCTOPRINT))
        fun moon()=MoonrakerClient(PrinterConnection(url,apiKey="fixture"))
        override fun close(){server.stop(0)}
    }
    @Test fun `OctoPrint telemetry uses server ETA and historical timestamps`()=Fixture().use { f->
        f.json("/api/printer","""{"state":{"text":"Printing","flags":{"operational":true,"printing":true}},"temperature":{"tool0":{"actual":211.5,"target":215},"bed":{"actual":59,"target":60},"history":[{"time":1700000000,"tool0":{"actual":200,"target":215}},{"time":1700000002,"tool0":{"actual":201,"target":215}}]}}""")
        f.json("/api/job","""{"progress":{"completion":25,"printTime":600,"printTimeLeft":1800},"job":{"file":{"name":"cube.gcode","date":1}}}""")
        val client=f.octo();val s=client.snapshot();assertTrue(s.ready);assertTrue(s.printing);assertEquals(0.25f,s.progress);assertEquals(600.0,s.printSeconds);assertEquals(1800.0,s.remainingSeconds);assertFalse(s.positionKnown);assertTrue(s.startEstimated)
        val history=client.temperatureHistory().single();assertEquals(listOf(200f,201f),history.temperatures);assertEquals(listOf(1700000000000L,1700000002000L),history.timestampsMillis)
        assertTrue(f.requests.all{it.first=="GET"})
    }
    @Test fun `OctoPrint upload never starts or selects a file and print is a separate request`()=Fixture().use { f->
        f.json("/api/files/local","""{"done":true,"files":{"local":{"path":"folder/cube test.gcode"}}}""")
        f.json("/api/printer","""{"state":{"flags":{"operational":true}},"temperature":{}}""");f.json("/api/job","{}")
        f.replies["/api/files/local/folder/cube test.gcode"]={204 to ""}
        val file=Files.createTempFile("octo-fixture-",".gcode").toFile().apply{writeText("; fixture\nG1 X10\n")}
        try {
            val progress=mutableListOf<Int>();val client=f.octo();assertEquals("folder/cube test.gcode",client.upload(file,"cube test.gcode"){progress+=it})
            val upload=f.requests.single();assertEquals("POST",upload.first);assertTrue(upload.third.contains("name=\"print\"\r\n\r\nfalse"));assertTrue(upload.third.contains("name=\"select\"\r\n\r\nfalse"));assertTrue(upload.third.contains(file.readText()));assertEquals(listOf(0,99,100),progress)
            client.startPrint("folder/cube test.gcode");val last=f.requests.last();assertEquals("POST",last.first);assertEquals("select",Json.parseToJsonElement(last.third).jsonObject["command"]!!.jsonPrimitive.content);assertTrue(Json.parseToJsonElement(last.third).jsonObject["print"]!!.jsonPrimitive.boolean)
        }finally{file.delete()}
    }
    @Test fun `OctoPrint counters recurse through files and do not invent global durations`()=Fixture().use { f->
        f.json("/api/files/local","""{"files":[{"type":"folder","children":[{"print":{"success":5,"failure":2}}]},{"print":{"success":3,"failure":1}}]}""")
        val stats=f.octo().statistics();assertEquals(8L,stats.completed);assertEquals(3L,stats.failed);assertNull(stats.printSeconds);assertNotNull(stats.note)
    }
    @Test fun `OctoPrint reads profile without sending configuration commands`()=Fixture().use { f->
        f.json("/api/printerprofiles","""{"profiles":{"printer":{"current":true,"volume":{"width":220,"depth":220,"height":250,"origin":"center"},"extruder":{"nozzleDiameter":0.6}}}}""")
        val p=f.octo().readConfiguration();assertEquals("-110.0x-110.0",p.values["printable_area"]!!.jsonArray.first().jsonPrimitive.content);assertEquals("0.6",p.values["nozzle_diameter"]!!.jsonArray.first().jsonPrimitive.content);assertFalse("gcode_flavor" in p.values);assertTrue(f.requests.all{it.first=="GET"})
    }
    @Test fun `Moonraker paginates history and keeps cancellations separate from errors`()=Fixture().use { f->
        val jobs=(0..500).map{i->buildJsonObject{put("job_id",i.toString());put("filename","cube.gcode");put("status",listOf("completed","error","cancelled","in_progress")[i%4]);put("print_duration",10);put("total_duration",15);put("filament_used",100);put("start_time",1700000000+i)}}
        f.replies["/server/history/list"]={q->val start=if("start=500" in q)500 else 0;200 to buildJsonObject{put("result",buildJsonObject{put("count",501);put("jobs",JsonArray(jobs.drop(start).take(500)))})}.toString()}
        val stats=f.moon().statistics();assertEquals(126L,stats.completed);assertEquals(125L,stats.failed);assertEquals(125L,stats.cancelled);assertEquals(125L,stats.active);assertEquals(5010.0,stats.printSeconds);assertFalse(stats.limited);assertEquals(2,f.requests.size)
    }
    @Test fun `Moonraker print start uses metadata and ETA is based on progress not total duration`()=Fixture().use { f->
        f.json("/printer/objects/list","""{"result":{"objects":["webhooks","print_stats","virtual_sdcard"]}}""")
        f.json("/printer/objects/query","""{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"printing","filename":"cube.gcode","print_duration":600,"total_duration":900},"virtual_sdcard":{"progress":0.25}}}}""")
        f.json("/server/files/metadata","""{"result":{"print_start_time":1700000000,"estimated_time":2000}}""")
        val s=f.moon().snapshot();assertEquals(1700000000000L,s.startedAtMillis);assertFalse(s.startEstimated);assertEquals(600.0,s.printSeconds);assertEquals(900.0,s.totalSeconds);assertEquals(1800.0,s.remainingSeconds)
    }
    @Test fun `timing avoids bogus estimates at zero progress or invalid values`() {
        assertNull(PrintTiming.remaining(0f,0.0,null));assertEquals(1000.0,PrintTiming.remaining(0f,0.0,1000.0));assertNull(PrintTiming.remaining(Float.NaN,100.0,null));assertEquals(1800.0,PrintTiming.remaining(.25f,600.0,null))
    }
    @Test fun `aggregation retains unknown OctoPrint duration and empty success is unknown`() {
        assertNull(PrinterStatistics().successRate)
        val result=PrinterStatistics.combine(listOf(PrinterStatistics(completed=3,failed=1,printSeconds=50.0),PrinterStatistics(completed=5,printSeconds=null)))
        assertEquals(8L,result.completed);assertEquals(1L,result.failed);assertNull(result.printSeconds);assertEquals(8*100.0/9,result.successRate)
    }
}
