package io.github.whoxllm.orcamicro

import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.profiles.*
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.data.*
import io.github.whoxllm.orcamicro.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TouchWorkflowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(action: () -> Unit)=instrumentation.runOnMainSync(action)
    private fun await(test: () -> Boolean) {
        val deadline=SystemClock.uptimeMillis()+20_000
        while(SystemClock.uptimeMillis()<deadline) { if(test()) return; SystemClock.sleep(50) }
        assertTrue("Timed out waiting for workflow",test())
    }
    private fun surface(view: View): GLSurfaceView? {
        if(view is GLSurfaceView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) surface(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun legacyPresetsMigrateLibrarySurvivesSwitchAndDoubleTapDragsWithoutRebuildingMesh() {
        val store=SettingsStore(context)
        val previous=store.load()
        val model=store.modelFile.takeIf { it.isFile }?.readBytes()
        val machine=Json.parseToJsonElement("""{"name":"Legacy printer","type":"machine","inherits":"MyKlipper 0.4 nozzle"}""").jsonObject
        val repo=ProfileRepository(context.assets)
        val imported=PresetExchange.import(machine.toString(),PresetType.MACHINE) { repo.resolve("Custom",it) }
        val catalog=repo.catalog
        val p=catalog.printer("Custom","MyKlipper 0.4 nozzle")!!
        val selection=PresetSelection("Custom",p.name,catalog.defaultProcessFor("Custom",p)!!,catalog.filamentsFor("Custom",p).first())
        store.save(SavedState(selection=selection,edits=PresetEdits(importedMachine=imported)))
        store.modelFile.delete()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MainViewModel
                var activity: MainActivity?=null
                scenario.onActivity { activity=it; vm=ViewModelProvider(it)[MainViewModel::class.java] }
                await { vm.state.value.ready }
                assertEquals(1,vm.state.value.userPresets.size)
                val bundle=File(context.cacheDir,"test-presets.json").apply { writeText("""{"profiles":[{"type":"filament","name":"Red","inherits":"Generic PETG @System","default_filament_colour":["#FF0000"],"nozzle_temperature":["230","235"]},{"type":"filament","name":"Black","inherits":"Generic PETG @System","default_filament_colour":["#111111"]}]}""") }
                main { vm.importPreset(Uri.fromFile(bundle),PresetType.FILAMENT) }
                await { vm.state.value.userPresets.size==3 }
                val red=vm.state.value.userPresets.first { it.name=="Red" }
                main { vm.selectUserPreset(red); vm.resetEdits(PresetType.FILAMENT) }
                assertEquals("Red",vm.state.value.inputs!!.filament.string("name"))
                main { vm.selectFilament(selection.filament) }
                await { vm.state.value.edits.importedFilament==null }
                assertEquals(3,vm.state.value.userPresets.size)
                main { vm.selectUserPreset(red); vm.setModelColor("00FF00"); vm.saveUserPreset(PresetType.FILAMENT,"Green") }
                assertEquals(4,vm.state.value.userPresets.size)
                assertEquals("#00FF00",vm.state.value.inputs!!.filament.first("default_filament_colour"))
                val stl=File(context.cacheDir,"touch.stl").apply { writeText("""solid triangle
facet normal 0 0 1
outer loop
vertex 0 0 0
vertex 20 0 0
vertex 10 20 20
endloop
endfacet
endsolid triangle
""") }
                main { vm.openModel(Uri.fromFile(stl)); vm.navigate(Screen.PLATE) }
                await { vm.state.value.model!=null }
                var gl: GLSurfaceView?=null
                await { main { gl=surface(activity!!.window.decorView) }; gl?.width?.let { it>0 }==true }
                SystemClock.sleep(600)
                val start=vm.state.value.model!!
                val v=gl!!; val x=v.width/2f; val y=v.height/2f
                val time=SystemClock.uptimeMillis()
                main {
                    fun event(at: Long,action: Int,px: Float,py: Float) { MotionEvent.obtain(time,at,action,px,py,0).also { v.dispatchTouchEvent(it); it.recycle() } }
                    event(time,MotionEvent.ACTION_DOWN,x,y); event(time+40,MotionEvent.ACTION_UP,x,y)
                    event(time+120,MotionEvent.ACTION_DOWN,x+3,y+2)
                    event(time+180,MotionEvent.ACTION_MOVE,x+33,y+12)
                    event(time+240,MotionEvent.ACTION_MOVE,x+63,y+22)
                    event(time+280,MotionEvent.ACTION_UP,x+63,y+22)
                }
                await { vm.state.value.model!!.placement != start.placement }
                val bed=vm.state.value.bed!!
                val camera=io.github.whoxllm.orcamicro.render.OrbitCamera().apply { frame(bed.centerX,bed.centerY,0f,maxOf(bed.width,bed.depth)*0.6f) }
                val from=camera.bedPoint(x+3,y+2,v.width,v.height)!!
                val to=camera.bedPoint(x+63,y+22,v.width,v.height)!!
                assertEquals(to[0]-from[0],vm.state.value.model!!.placement.centerX-start.placement.centerX,0.1f)
                assertEquals(to[1]-from[1],vm.state.value.model!!.placement.centerY-start.placement.centerY,0.1f)
                assertSame("XY movement must reuse mesh and normals",start.placed,vm.state.value.model!!.placed)
                assertTrue(kotlin.math.abs(vm.state.value.model!!.offsetX)+kotlin.math.abs(vm.state.value.model!!.offsetY)>0.5f)
                await { store.load().userPresets.size==4 && store.load().placement==vm.state.value.model!!.placement }
                assertEquals(vm.state.value.model!!.placement,store.load().placement)
            }
        } finally {
            store.save(previous)
            if(model!=null) store.modelFile.writeBytes(model) else store.modelFile.delete()
        }
    }
}
