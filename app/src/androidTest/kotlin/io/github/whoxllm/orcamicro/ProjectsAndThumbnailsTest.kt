package io.github.whoxllm.orcamicro

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.gcode.GcodeParser
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.StlWriter
import io.github.whoxllm.orcamicro.core.profiles.*
import io.github.whoxllm.orcamicro.data.*
import io.github.whoxllm.orcamicro.ui.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ProjectsAndThumbnailsTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val store=SettingsStore(context)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) { if(condition())return;SystemClock.sleep(50) }
        assertTrue("Timed out waiting for project operation",condition())
    }
    private fun fixture(test:(File)->Unit) {
        val previous=store.load();val model=store.modelFile.takeIf { it.isFile }?.readBytes()
        val projects=File(context.filesDir,"projects");val oldIds=projects.list().orEmpty().toSet()
        val library=File(context.filesDir,"library");val oldFiles=library.list().orEmpty().toSet()
        val source=File(context.cacheDir,"preview-fixture.stl")
        store.save(SavedState())
        try { source.outputStream().use { StlWriter.write(box(),it) };test(source) }
        finally {
            store.save(previous)
            if(model==null)store.modelFile.delete()else store.modelFile.writeBytes(model)
            source.delete()
            projects.listFiles().orEmpty().filter { it.name !in oldIds }.forEach { it.deleteRecursively() }
            library.listFiles().orEmpty().filter { it.name !in oldFiles }.forEach { it.deleteRecursively() }
        }
    }

    @Test fun projectsRestorePlacementPresetsAndColourAfterRestartAndCacheCleanup() = fixture { source ->
        lateinit var id:String
        var savedPlacement=io.github.whoxllm.orcamicro.core.mesh.Placement()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm:MainViewModel;scenario.onActivity { vm=ViewModelProvider(it)[MainViewModel::class.java] }
            await { vm.state.value.ready && vm.state.value.inputs!=null }
            assertEquals(Screen.HOME,vm.state.value.screen)
            main { vm.openModel(Uri.fromFile(source)) }
            await { !vm.state.value.libraryBusy && vm.state.value.model!=null && !vm.state.value.projectSaving }
            id=vm.state.value.activeProjectId!!
            savedPlacement=vm.state.value.model!!.placement.copy(rotationZ=35f,centerX=90f,centerY=100f)
            main { vm.setRawEdit(PresetType.PROCESS,"layer_height",JsonPrimitive("0.28"));vm.setModelColor("#33CC77");vm.setPlacement(savedPlacement) }
            await { !vm.state.value.projectSaving && vm.state.value.recentProjects.any { it.document.id==id && it.document.placement==savedPlacement && it.document.color==0x33CC77 } }
            val preview=BitmapFactory.decodeFile(vm.state.value.recentProjects.first { it.document.id==id }.thumbnail.path)
            assertNotNull(preview);assertEquals(400,preview.width);preview.recycle()
            main { vm.newProject() }
            await { !vm.state.value.libraryBusy && vm.state.value.model==null }
            main { vm.openModel(Uri.fromFile(source)) }
            await { !vm.state.value.libraryBusy && vm.state.value.model!=null && vm.state.value.activeProjectId!=id && !vm.state.value.projectSaving }
            main { vm.setRawEdit(PresetType.PROCESS,"layer_height",JsonPrimitive("0.36"));vm.openRecentProject(id) }
            await { !vm.state.value.libraryBusy && vm.state.value.activeProjectId==id && !vm.state.value.projectSaving }
            assertEquals(savedPlacement,vm.state.value.model!!.placement)
            assertEquals("0.28",vm.state.value.inputs!!.process.first("layer_height"))
            assertEquals(0x33CC77,vm.state.value.modelRgb())
            await { store.load().activeProjectId==id }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm:MainViewModel;scenario.onActivity { vm=ViewModelProvider(it)[MainViewModel::class.java] }
            await { vm.state.value.ready && vm.state.value.inputs!=null && !vm.state.value.projectSaving }
            assertEquals(Screen.HOME,vm.state.value.screen)
            assertEquals(id,vm.state.value.activeProjectId);assertEquals(savedPlacement,vm.state.value.model!!.placement)
            main { vm.cleanModelCache(all=true) }
            await { vm.state.value.modelCacheBytes==0L }
            main { vm.openRecentProject(id) }
            await { !vm.state.value.libraryBusy && vm.state.value.model!=null && !vm.state.value.projectSaving }
            assertEquals("0.28",vm.state.value.inputs!!.process.first("layer_height"))
            main { vm.deleteRecentProject(id) }
            await { !vm.state.value.libraryBusy && vm.state.value.recentProjects.none { it.document.id==id } }
            assertFalse(File(context.filesDir,"projects/$id").exists())
            assertNull(vm.state.value.activeProjectId);assertNull(vm.state.value.model)
            await { store.load().modelName==null }
        }
    }

    @Test fun nativeSliceProducesTwoDecodablePngThumbnailsWithoutChangingToolpaths() = fixture { source ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm:MainViewModel;scenario.onActivity { vm=ViewModelProvider(it)[MainViewModel::class.java] }
            await { vm.state.value.ready && vm.state.value.inputs!=null }
            main { vm.selectPrinter("Custom","MyMarlin 0.4 nozzle") }
            await { vm.state.value.inputs?.machine?.string("name")=="MyMarlin 0.4 nozzle" }
            main { vm.openModel(Uri.fromFile(source)) }
            await { !vm.state.value.libraryBusy && vm.state.value.model!=null && !vm.state.value.projectSaving }
            main { vm.setModelColor("#33CC77");vm.slice() }
            await(180000) { vm.state.value.lastResult!=null || vm.state.value.slice is SliceState.Failed }
            assertFalse(vm.state.value.slice.toString(),vm.state.value.slice is SliceState.Failed)
            val result=vm.state.value.lastResult!!
            val gcode=result.gcode.readText()
            val blocks=Regex("; thumbnail begin (\\d+)x(\\d+) (\\d+)\n(.*?); thumbnail end",RegexOption.DOT_MATCHES_ALL).findAll(gcode).toList()
            assertEquals(2,blocks.size)
            assertEquals(listOf("32","400"),blocks.map { it.groupValues[1] })
            for(block in blocks) {
                val encoded=block.groupValues[4].lineSequence().filter { it.startsWith("; ") }.joinToString("") { it.removePrefix("; ") }
                assertEquals(block.groupValues[3].toInt(),encoded.length)
                val bytes=Base64.getDecoder().decode(encoded)
                val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                assertNotNull(image);assertEquals(block.groupValues[1].toInt(),image.width);assertEquals(image.width,image.height)
                assertEquals(0,image.getPixel(0,0))
                val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                assertTrue(pixels.any { (it ushr 24)>200 && ((it shr 8) and 255)>((it shr 16) and 255) })
                image.recycle()
                if(block.groupValues[1]=="400")File(context.filesDir,"verified-preview.png").writeBytes(bytes)
            }
            val parsed=result.gcode.bufferedReader().use { GcodeParser.parse(it) }
            assertTrue(parsed.layers.size>5);assertEquals(result.toolpaths.layers.size,parsed.layers.size)
            File(context.filesDir,"verified-preview.gcode").writeText(gcode)
            await { !vm.state.value.projectSaving }
        }
    }

    private fun box():Mesh {
        val v=Array(8) { i->floatArrayOf(if(i and 1!=0)12f else 0f,if(i and 2!=0)16f else 0f,if(i and 4!=0)20f else 0f) }
        val quads=listOf(intArrayOf(0,2,3,1),intArrayOf(4,5,7,6),intArrayOf(0,1,5,4),intArrayOf(2,6,7,3),intArrayOf(0,4,6,2),intArrayOf(1,3,7,5))
        return Mesh(quads.flatMap { q->listOf(q[0],q[1],q[2],q[0],q[2],q[3]).flatMap { v[it].toList() } }.toFloatArray())
    }
}
