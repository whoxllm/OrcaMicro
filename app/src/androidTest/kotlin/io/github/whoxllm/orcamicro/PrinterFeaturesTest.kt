package io.github.whoxllm.orcamicro

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.network.*
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.profiles.first
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.MainViewModel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PrinterFeaturesTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean){val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};assertTrue(condition())}
    private class Fixture(@Volatile var unavailable:Boolean=false, val uploadDelayMillis:Long=0):AutoCloseable {
        val server=ServerSocket(0).apply{soTimeout=200};val running=AtomicBoolean(true)
        val requests=CopyOnWriteArrayList<Pair<String,String>>()
        val connection get()=PrinterConnection("http://127.0.0.1:${server.localPort}",backend=PrinterBackend.OCTOPRINT)
        val thread=Thread {
            while(running.get())try{server.accept().use { socket->socket.soTimeout=5000;val input=socket.getInputStream().bufferedReader();val first=input.readLine();var length=0;var chunked=false
                while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt();if(line.startsWith("Transfer-Encoding:",true)&&line.contains("chunked",true))chunked=true}
                val body=StringBuilder()
                if(chunked){while(true){val size=input.readLine().substringBefore(';').toInt(16);if(size==0){input.readLine();break};repeat(size){body.append(input.read().toChar())};input.readLine()}}
                else repeat(length){body.append(input.read().toChar())}
                requests+=first to body.toString()
                val path=first.split(' ')[1].substringBefore('?')
                val response=when(path){
                    "/api/printer"->"""{"state":{"flags":{"operational":true}},"temperature":{}}"""
                    "/api/printerprofiles"->"""{"profiles":{"p":{"current":true,"volume":{"width":230,"depth":220,"height":250},"extruder":{"nozzleDiameter":0.6}}}}"""
                    "/api/files/local"->if(first.startsWith("POST"))"""{"done":true,"files":{"local":{"path":"fixture.gcode"}}}""" else """{"files":[{"print":{"success":3,"failure":1}}]}"""
                    else->"{}"
                }
                if(first.startsWith("POST /api/files/local ") && uploadDelayMillis>0)Thread.sleep(uploadDelayMillis)
                val bytes=response.toByteArray();val status=if(unavailable)"503 Service Unavailable" else "200 OK"
                socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
            }}catch(_:SocketTimeoutException){}catch(_:Exception){if(running.get())throw AssertionError("Fixture stopped unexpectedly")}
        }.apply{isDaemon=true;start()}
        override fun close(){running.set(false);server.close();thread.join(1000)}
    }
    @Test fun externalGcodeStagesLocallyAndUploadIsSeparateFromExplicitPrint() {
        val store=SettingsStore(context);val before=store.load()
        val file=File(context.cacheDir,"fixture.gcode").apply{writeText("; fixture\nG1 X1\n")}
        try{Fixture().use{fixture->ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
            main{assertTrue(vm.savePrinter("Octo fixture",fixture.connection));vm.uploadPickedGcode(Uri.fromFile(file))}
            await{vm.state.value.externalGcode!=null&&vm.state.value.printDialog}
            assertTrue(fixture.requests.isEmpty())
            main{vm.uploadLastGcode()};await{vm.state.value.uploadedFile!=null&&vm.state.value.uploadPercent==null}
            assertFalse(fixture.requests.any{it.first.contains("/api/job")||it.first.contains("/api/files/local/fixture.gcode")})
            main{vm.uploadAndPrintGcode()};await{fixture.requests.any{it.first.startsWith("POST /api/files/local/fixture.gcode ")}}
            val request=fixture.requests.single{it.first.startsWith("POST /api/files/local/fixture.gcode ")};assertTrue(Json.parseToJsonElement(request.second).jsonObject["print"]!!.jsonPrimitive.boolean)
            await{!vm.state.value.deviceBusy}
        }}}finally{store.save(before);file.delete()}
    }
    @Test fun failedUploadClearsProgressAndCanBeRetriedWithoutStartingPrint() {
        val store=SettingsStore(context);val before=store.load()
        val file=File(context.cacheDir,"retry-fixture.gcode").apply{writeText("; fixture\n"+"G1 X1\n".repeat(20000))}
        try{Fixture(unavailable=true,uploadDelayMillis=1000).use{fixture->ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
            main{vm.savePrinter("Retry fixture",fixture.connection);vm.uploadPickedGcode(Uri.fromFile(file))};await{vm.state.value.externalGcode!=null}
            main{vm.uploadLastGcode()};assertNotNull(vm.state.value.uploadPercent)
            await{vm.state.value.uploadError!=null&&vm.state.value.uploadPercent==null};assertNull(vm.state.value.uploadedFile)
            fixture.unavailable=false
            main{vm.dismissUploadError();vm.uploadLastGcode()};await{vm.state.value.uploadedFile!=null&&vm.state.value.uploadPercent==null}
            assertNull(vm.state.value.uploadError)
            assertEquals(2,fixture.requests.count{it.first.startsWith("POST /api/files/local ")})
            assertFalse(fixture.requests.any{it.first.startsWith("POST /api/job")||it.first.contains("POST /api/files/local/fixture.gcode")})
        }}}finally{store.save(before);file.delete()}
    }
    @Test fun detectedProfileNeedsConfirmationAndTemperaturePreferencesPersist() {
        val store=SettingsStore(context);val before=store.load()
        try{Fixture().use{fixture->ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
            val originalCount=vm.state.value.userPresets.size
            main{vm.savePrinter("Profile fixture",fixture.connection);vm.setTemperatureWindow(5);vm.setTelemetryInterval(30);vm.readPrinterConfiguration()}
            await{vm.state.value.configurationPreview!=null};assertEquals(originalCount,vm.state.value.userPresets.size)
            val name="Detected fixture ${System.nanoTime()}";main{vm.savePrinterConfiguration(name)}
            val profile=vm.state.value.userPresets.single{it.name==name};assertEquals(PresetType.MACHINE,profile.type);assertEquals("0.6",profile.preset.first("nozzle_diameter"));assertEquals(name,vm.state.value.printers.first{it.id==vm.state.value.activePrinterId}.machineProfile)
            await{store.load().temperatureWindowMinutes==5&&store.load().telemetryIntervalSeconds==30&&store.load().userPresets.any{it.name==name}}
            assertTrue(fixture.requests.all{it.first.startsWith("GET ")})
        }}}finally{store.save(before)}
    }
    @Test fun allPrintersAttemptEveryConnectionAndKeepOfflineErrorsSeparate() {
        val store=SettingsStore(context);val before=store.load()
        try{Fixture().use{online->Fixture(true).use{offline->ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
            // Remove saved connections only inside the isolated emulator test state.
            main{vm.state.value.printers.map{it.id}.forEach(vm::deletePrinter);vm.savePrinter("Online",online.connection);vm.savePrinter("Offline",offline.connection);vm.loadPrinterStatistics(null)}
            await{!vm.state.value.statisticsLoading&&vm.state.value.printerStatistics.size==2}
            val results=vm.state.value.printerStatistics;assertEquals(3L,results.single{it.printer.name=="Online"}.statistics!!.completed);assertNotNull(results.single{it.printer.name=="Offline"}.error)
            assertTrue(online.requests.isNotEmpty());assertTrue(offline.requests.isNotEmpty())
        }}}}finally{store.save(before)}
    }
}
