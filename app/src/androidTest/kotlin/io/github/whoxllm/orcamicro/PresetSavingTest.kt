package io.github.whoxllm.orcamicro

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.profiles.*
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetSavingTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val store=SettingsStore(instrumentation.targetContext)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean){val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};assertTrue(condition())}

    @Test fun dirtyDraftSurvivesRestartAndSavingUpdatesOnlyTheNamedPreset() {
        val previous=store.load();val name="Save fixture ${System.nanoTime()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready&&vm.state.value.inputs!=null}
                main { vm.saveUserPreset(PresetType.PROCESS,name);vm.setRawEdit(PresetType.PROCESS,"layer_height",JsonPrimitive("0.31")) }
                assertTrue(vm.state.value.presetDirty(PresetType.PROCESS))
                assertNotEquals("0.31",vm.state.value.userPresets.single{it.name==name}.preset.first("layer_height"))
                await{store.load().edits.rawProcess["layer_height"]==JsonPrimitive("0.31")}
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready&&vm.state.value.inputs!=null}
                assertTrue(vm.state.value.presetDirty(PresetType.PROCESS));assertEquals("0.31",vm.state.value.inputs!!.process.first("layer_height"))
                main{vm.saveUserPreset(PresetType.PROCESS,name)}
                assertFalse(vm.state.value.presetDirty(PresetType.PROCESS));assertEquals(1,vm.state.value.userPresets.count{it.name==name})
                assertEquals("0.31",vm.state.value.userPresets.single{it.name==name}.preset.first("layer_height"))
                main{vm.setRawEdit(PresetType.PROCESS,"layer_height",JsonPrimitive("0.3100"))}
                assertFalse(vm.state.value.presetDirty(PresetType.PROCESS))
                main{vm.setRawEdit(PresetType.PROCESS,"layer_height",JsonPrimitive("0.32"));vm.resetEdits(PresetType.PROCESS)}
                assertFalse(vm.state.value.presetDirty(PresetType.PROCESS));assertEquals(name,vm.state.value.inputs!!.process.string("name"));assertEquals("0.31",vm.state.value.inputs!!.process.first("layer_height"))
                await{store.load().userPresets.any{it.name==name&&it.preset.first("layer_height")=="0.31"}}
            }
        } finally {store.save(previous)}
    }

    @Test fun rapidSelectionsKeepLatestPresetAndEditsMadeDuringLoading() {
        val previous=store.load()
        try {ActivityScenario.launch(MainActivity::class.java).use { scenario->
            lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready&&vm.state.value.inputs!=null}
            val s=vm.state.value;val selection=s.selection!!;val catalog=s.catalog!!
            val choices=catalog.processesFor(selection.vendor,catalog.printer(selection.vendor,selection.printer)!!)
            assertTrue(choices.size>=2)
            main { vm.selectProcess(choices.first());vm.selectProcess(choices.last());vm.setRawEdit(PresetType.PROCESS,"layer_height",JsonPrimitive("0.27")) }
            await{vm.state.value.inputs!=null&&vm.state.value.inputs!!.process.string("name")==choices.last()}
            assertEquals(choices.last(),vm.state.value.selection!!.process);assertEquals("0.27",vm.state.value.inputs!!.process.first("layer_height"));assertTrue(vm.state.value.presetDirty(PresetType.PROCESS))
            main{vm.openAppSettings()};assertEquals(Screen.ABOUT,vm.state.value.screen);assertTrue(vm.state.value.appSettingsOpen)
            main{vm.back()};assertFalse(vm.state.value.appSettingsOpen);assertEquals(Screen.ABOUT,vm.state.value.screen)
            await{store.load().selection?.process==choices.last()}
        }} finally {store.save(previous)}
    }
}
