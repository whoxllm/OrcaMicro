package io.github.whoxllm.orcamicro.core.network

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class LibraryAndControlTest {
    private val ready=KlipperSnapshot(ready=true,heaters=listOf(HeaterState("extruder",200f,200f,0f,260f),HeaterState("heater_generic chamber",25f,0f,0f,80f)),homedAxes="xyz",position=listOf(110f,110f,10f),axisMinimum=listOf(0f,0f,0f),axisMaximum=listOf(220f,220f,250f),fan=0f)

    @Test fun `jog bounds printing and coordinate modes are enforced`() {
        val command=KlipperControl.move(ready,"X",10f)
        assertTrue(command.startsWith("SAVE_GCODE_STATE"));assertTrue(command.contains("G91\nG1 X10.00 F1800"));assertTrue(command.endsWith("RESTORE_GCODE_STATE NAME=ORCA_ANDROID_JOG"));assertFalse(command.contains("FORCE_MOVE"))
        assertFails { KlipperControl.move(ready.copy(homedAxes="xy"),"Z",1f) }
        assertFails { KlipperControl.move(ready,"X",Float.NaN) }
        assertFails { KlipperControl.move(ready.copy(position=listOf(219f,110f,10f)),"X",10f) }
        assertFails { KlipperControl.move(ready.copy(printState="printing"),"X",1f) }
        assertFails { KlipperControl.home(ready.copy(printState="paused"),"") }
        assertEquals("G28 X Y",KlipperControl.home(ready,"XY"))
    }
    @Test fun `heater names limits and special chamber controls are validated`() {
        assertEquals("SET_HEATER_TEMPERATURE HEATER=\"chamber\" TARGET=60.00",KlipperControl.heater(ready,"heater_generic chamber",60f))
        assertFails { KlipperControl.heater(ready,"extruder",261f) }
        assertFails { KlipperControl.heater(ready,"extruder",Float.POSITIVE_INFINITY) }
        assertFails { KlipperControl.heater(ready,"extruder\nM112",200f) }
        assertFails { KlipperControl.heater(ready.copy(ready=false),"extruder",200f) }
        assertFails { KlipperControl.heater(ready.copy(heaters=listOf(HeaterState("extruder",25f,0f))),"extruder",200f) }
        assertTrue(KlipperControl.heater(ready.copy(heaters=listOf(HeaterState("extruder",25f,0f))),"extruder",0f).endsWith("TARGET=0.00"))
    }
    @Test fun `links accept pages signed download URLs and encoded desktop deep links`() {
        assertEquals(ModelLink.Page("https://www.printables.com/model/3161"),ModelLinks.parse("https://www.printables.com/model/3161"))
        assertEquals(ModelLink.File("https://cdn.example.com/model.3mf?sig=a%2Bb","model.3mf"),ModelLinks.parse("orcaslicer://open?file=https%3A%2F%2Fcdn.example.com%2Fmodel.3mf%3Fsig%3Da%252Bb"))
        assertTrue(ModelLinks.parse("https://cdn.example.com/cube.stl") is ModelLink.File)
        assertNull(ModelLinks.parse("держатель телефона"))
        for(link in listOf("javascript://run", "orcaslicer://print?file=https%3A%2F%2Fexample.com%2Fcube.stl","orcaslicer://open?file=file%3A%2F%2F%2Fetc%2Fpasswd","https://user:pass@printables.com/model/1","https://printables.com.evil.example/model/1"))assertFails(link){ModelLinks.parse(link)}
    }
    @Test fun `tag filtering is exact normalized and combines with the text query`() {
        val model=LibraryModel(source=ModelSource.PRINTABLES,url="https://www.printables.com/model/1",title="Phone holder",tags=ModelTags.parse("#Phone, stand, PHONE"))
        assertEquals(listOf("phone","stand"),model.tags)
        assertTrue(ModelTags.matches(model,"holder",listOf("stand","phone")))
        assertFalse(ModelTags.matches(model,"holder",listOf("sta")))
        assertFalse(ModelTags.matches(model,"case",emptyList()))
        assertEquals("https://www.printables.com/tag/phone",ModelSource.PRINTABLES.search("",listOf("phone")))
        assertTrue(ModelSource.MAKERWORLD.search("phone holder",listOf("stand")).contains("keyword=phone+holder+stand"))
    }
    @Test fun `cache expires after selected days and manual clear preserves unrelated directories`() {
        val root=Files.createTempDirectory("orca-model-cache").toFile();val now=20L*24*60*60*1000
        try {
            val old=root.resolve("old.bin").apply { writeText("old");setLastModified(now-8L*24*60*60*1000) }
            val fresh=root.resolve("fresh.bin").apply { writeText("fresh");setLastModified(now-6L*24*60*60*1000) }
            root.resolve("subdirectory").mkdir()
            assertEquals(3L,ModelCache.clean(root,7,now));assertFalse(old.exists());assertTrue(fresh.exists())
            assertEquals(0L,ModelCache.clean(root,0,now+100L*24*60*60*1000));assertTrue(fresh.exists())
            assertEquals(5L,ModelCache.clean(root,0,now,all=true));assertTrue(root.resolve("subdirectory").isDirectory)
        } finally {root.deleteRecursively()}
    }
    @Test fun `Moonraker telemetry history commands and explicit start use real API routes`() {
        val requests=CopyOnWriteArrayList<Pair<String,String>>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { e->
            assertEquals("fixture-key",e.requestHeaders.getFirst("X-Api-Key"))
            val body=e.requestBody.readBytes().toString(Charsets.UTF_8);requests+=e.requestURI.path to body
            val response=when(e.requestURI.path) {
                "/printer/objects/list" -> """{"result":{"objects":["webhooks","toolhead","extruder","heater_bed","temperature_sensor chamber","configfile","print_stats","virtual_sdcard","fan"]}}"""
                "/printer/objects/query" -> {
                    assertEquals("POST",e.requestMethod)
                    assertTrue(Json.parseToJsonElement(body).jsonObject["objects"]!!.jsonObject.containsKey("extruder"))
                    """{"result":{"status":{"webhooks":{"state":"ready"},"toolhead":{"homed_axes":"xyz","position":[110,110,10,0],"axis_minimum":[0,0,0,0],"axis_maximum":[220,220,250,0]},"extruder":{"temperature":202.5,"target":210},"heater_bed":{"temperature":60,"target":60},"temperature_sensor chamber":{"temperature":35},"configfile":{"config":{"extruder":{"min_temp":"0","max_temp":"260"},"heater_bed":{"min_temp":"0","max_temp":"120"}}},"print_stats":{"state":"standby","filename":""},"virtual_sdcard":{"progress":0},"fan":{"speed":0.5}}}}"""
                }
                "/server/temperature_store" -> """{"result":{"extruder":{"temperatures":[200,201,null,202],"targets":[210,210,210,210]}}}"""
                else -> """{"result":"ok"}"""
            }.toByteArray()
            e.sendResponseHeaders(200,response.size.toLong());e.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client=MoonrakerClient(PrinterConnection("http://127.0.0.1:${server.address.port}",apiKey="fixture-key"))
            val snapshot=client.snapshot();assertTrue(snapshot.ready);assertEquals(3,snapshot.heaters.size);assertEquals(260f,snapshot.heaters.first { it.name=="extruder" }.max)
            assertEquals(listOf(200f,201f,null,202f),client.temperatureHistory().single().temperatures)
            client.setHeater("extruder",220f);client.jog("Y",1f);client.setFan(30)
            assertTrue(requests.any { it.first=="/printer/gcode/script"&&it.second.contains("SET_HEATER_TEMPERATURE") })
            assertFalse(requests.any { it.first=="/printer/print/start" })
            client.startPrint("folder/cube.gcode")
            assertEquals("folder/cube.gcode",Json.parseToJsonElement(requests.last().second).jsonObject["filename"]!!.jsonPrimitive.content)
            client.emergencyStop();assertEquals("/printer/emergency_stop",requests.last().first)
        } finally {server.stop(0)}
    }
}
