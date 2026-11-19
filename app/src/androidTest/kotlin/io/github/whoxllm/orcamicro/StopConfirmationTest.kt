package io.github.whoxllm.orcamicro

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.network.PrinterConnection
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StopConfirmationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean) {val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};assertTrue(condition())}
    @Test fun stopDoesNotReachMoonrakerUntilExactConfirmationAndCannotBeReused() {
        val saved=SettingsStore(context);val before=saved.load()
        val server=ServerSocket(0).apply {soTimeout=200};val requests=CopyOnWriteArrayList<String>();val running=AtomicBoolean(true)
        val listener=Thread {
            while(running.get())try {server.accept().use { socket->socket.soTimeout=3000;val input=socket.getInputStream().bufferedReader();val first=input.readLine();var length=0
                while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                repeat(length){input.read()};requests+=first
                val body="{\"result\":\"ok\"}";socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            }}catch(_:SocketTimeoutException){}catch(_:Exception){if(running.get())throw AssertionError("Fixture server stopped unexpectedly")}
        }.apply{isDaemon=true;start()}
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                main{assertTrue(vm.savePrinter("Test only",PrinterConnection("http://127.0.0.1:${server.localPort}")))}
                main{assertFalse(vm.confirmEmergencyStop("Stop Klipper!"));vm.requestEmergencyStop()}
                assertNotNull(vm.state.value.stopChallenge)
                main{for(text in listOf("","Stop klipper!","Stop Klipper! ","Stop Klipper!\n"))assertFalse(vm.confirmEmergencyStop(text))}
                SystemClock.sleep(200);assertTrue(requests.isEmpty())
                main{vm.dismissEmergencyStop();assertFalse(vm.confirmEmergencyStop("Stop Klipper!"));vm.requestEmergencyStop()}
                main{assertTrue(vm.confirmEmergencyStop("Stop Klipper!"));assertFalse(vm.confirmEmergencyStop("Stop Klipper!"))}
                await{requests.size==1};assertTrue(requests.single().startsWith("POST /printer/emergency_stop "))
                main{vm.requestEmergencyStop();assertTrue(vm.savePrinter("Other fixture",PrinterConnection("http://127.0.0.1:1")));assertFalse(vm.confirmEmergencyStop("Stop Klipper!"))}
                SystemClock.sleep(200);assertEquals(1,requests.size)
                await{saved.load().printers.size==vm.state.value.printers.size}
            }
        }finally{running.set(false);server.close();listener.join(1000);saved.save(before)}
    }
}
