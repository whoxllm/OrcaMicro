package io.github.whoxllm.orcamicro

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.network.CatalogCredentials
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.data.CatalogCredentialStore
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QuickPrintWorkflowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+30_000
        while(SystemClock.uptimeMillis()<deadline){if(condition())return;SystemClock.sleep(50)}
        assertTrue("Quick Print workflow timed out",condition())
    }
    @Test fun quickPrintPlacementAndPresetEditingReturnToPreparation() {
        val store=SettingsStore(context);val previous=store.load();val oldModel=store.modelFile.takeIf{it.exists()}?.readBytes()
        val library=File(context.filesDir,"library");val existing=library.listFiles().orEmpty().map{it.name}.toSet()
        val file=File(context.cacheDir,"quick-print-placement.stl").apply { writeText("""solid cube
facet normal 0 0 1
outer loop
vertex 0 0 0
vertex 20 0 0
vertex 0 20 10
endloop
endfacet
endsolid cube
""") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                main{vm.openModel(Uri.fromFile(file))}
                await{vm.state.value.model?.name==file.name&&!vm.state.value.libraryBusy}
                val card=vm.state.value.libraryModels.first{it.filename==file.name}
                main{vm.openLibraryModel(card.id,setup=true)}
                await{vm.state.value.screen==Screen.PRINT_SETUP&&!vm.state.value.libraryBusy}
                assertEquals(card.id,vm.state.value.activeModelCard?.id)
                val before=vm.state.value.model!!.placement
                main{vm.editQuickPrintPlacement();vm.moveModelBy(5f,-3f)}
                await{vm.state.value.model?.placement?.centerX==before.centerX+5f}
                assertTrue(vm.state.value.quickPrintPlacement)
                main{vm.back()};assertEquals(Screen.PRINT_SETUP,vm.state.value.screen);assertFalse(vm.state.value.quickPrintPlacement)
                assertEquals(before.centerY-3f,vm.state.value.model!!.placement.centerY,0f)
                main{vm.openSettings(PresetType.PROCESS);vm.back()};assertEquals(Screen.PRINT_SETUP,vm.state.value.screen)
                main{vm.openPreset(PresetType.FILAMENT);vm.back()};assertEquals(Screen.PRINT_SETUP,vm.state.value.screen)
                main{vm.editQuickPrintPlacement();vm.finishQuickPrintPlacement()};assertEquals(Screen.PRINT_SETUP,vm.state.value.screen)
                main{vm.finishModelMove()};await{store.load().activeModelId==card.id}
            }
        } finally {store.save(previous);if(oldModel!=null)store.modelFile.writeBytes(oldModel)else store.modelFile.delete();file.delete();library.listFiles().orEmpty().filter{it.name !in existing}.forEach{it.delete()} }
    }
    @Test fun catalogKeysUseDeviceBoundEncryptionOutsideSavedProfiles() {
        val store=CatalogCredentialStore(context);val previous=store.load()
        try {
            val value=CatalogCredentials(thingiverseToken="test-personal-thing-key",cultsUser="fixture",cultsKey="test-cults-key",printablesCookie="sessionid=test-cookie",makerWorldToken="test-bambu-key")
            store.save(value);assertEquals(value,store.load())
            val disk=File(context.noBackupFilesDir,"catalog-api.enc").readText()
            for(secret in listOf(value.thingiverseToken,value.cultsKey,value.printablesCookie,value.makerWorldToken))assertFalse(disk.contains(secret))
            assertFalse(File(context.filesDir,"state.json").takeIf{it.isFile}?.readText().orEmpty().contains(value.thingiverseToken))
        }finally{store.save(previous)}
    }
}
