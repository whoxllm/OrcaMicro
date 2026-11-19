package io.github.whoxllm.orcamicro

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.network.*
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LibraryWorkflowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+30_000
        while(SystemClock.uptimeMillis()<deadline){if(condition())return;SystemClock.sleep(50)}
        assertTrue("Workflow timed out",condition())
    }
    @Test fun zipChoicePrinterFleetTagsAndCacheSurviveRestart() {
        val store=SettingsStore(context);val previous=store.load();val model=store.modelFile.takeIf{it.exists()}?.readBytes()
        val library=File(context.filesDir,"library");val existing=library.listFiles().orEmpty().map{it.name}.toSet()
        store.save(previous.copy(connection=PrinterConnection("http://127.0.0.1:1"),printers=emptyList(),activePrinterId=null,libraryModels=emptyList(),modelCacheDays=7))
        val zip=File(context.cacheDir,"test-library.zip")
        val stl="""solid triangle
facet normal 0 0 1
outer loop
vertex 0 0 0
vertex 20 0 0
vertex 0 20 10
endloop
endfacet
endsolid triangle
"""
        ZipOutputStream(zip.outputStream()).use { out->for(name in listOf("left.stl","parts/right.stl")){out.putNextEntry(ZipEntry(name));out.write(stl.toByteArray());out.closeEntry()} }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                assertEquals(1,vm.state.value.printers.size)
                main{assertTrue(vm.savePrinter("Second printer",PrinterConnection("http://127.0.0.1:2")))}
                assertEquals(2,vm.state.value.printers.size);assertEquals("http://127.0.0.1:2",vm.state.value.connection.apiUrl)
                main{vm.openModel(Uri.fromFile(zip))}
                await{vm.state.value.archiveChoices.size==2&&!vm.state.value.libraryBusy}
                assertEquals(listOf("left.stl","parts/right.stl"),vm.state.value.archiveChoices)
                main{vm.chooseArchiveEntry("parts/right.stl")}
                await{vm.state.value.model?.name=="right.stl"&&!vm.state.value.libraryBusy}
                assertEquals(1,vm.state.value.model!!.mesh.triangleCount)
                val card=vm.state.value.libraryModels.single()
                main{vm.setModelTags(card.id,"#fixture, dual");vm.setCacheDays(0)}
                assertEquals(listOf("fixture","dual"),vm.state.value.libraryModels.single().tags)
                val cacheFile=File(library,card.localFile!!);assertTrue(cacheFile.isFile)
                assertTrue(cacheFile.setLastModified(System.currentTimeMillis()-8L*24*60*60*1000))
                main{vm.cleanModelCache()};SystemClock.sleep(150);assertTrue(cacheFile.exists())
                main{vm.setCacheDays(7)}
                await{vm.state.value.libraryModels.single().localFile==null}
                assertFalse(cacheFile.exists());assertNotNull("Model on the bed must survive cache expiry",vm.state.value.model)
                main{vm.setCacheDays(0);vm.navigate(Screen.PRINT_SETUP)}
                await{store.load().printers.size==2&&store.load().modelCacheDays==0}
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                assertEquals(2,vm.state.value.printers.size);assertEquals(0,vm.state.value.modelCacheDays)
                assertEquals(listOf("fixture","dual"),vm.state.value.libraryModels.single().tags)
                await{vm.state.value.model!=null}
            }
        } finally {store.save(previous);if(model!=null)store.modelFile.writeBytes(model)else store.modelFile.delete();zip.delete();library.listFiles().orEmpty().filter{it.name !in existing}.forEach{it.delete()} }
    }
    @Test fun threeMfImportsWithAndroidXmlParser() {
        val store=SettingsStore(context);val previous=store.load();val model=store.modelFile.takeIf{it.exists()}?.readBytes()
        val library=File(context.filesDir,"library");val existing=library.listFiles().orEmpty().map{it.name}.toSet()
        val file=File(context.cacheDir,"android-geometry.3mf")
        val xml="""<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" unit="millimeter"><resources><object id="1"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="20" y="0" z="0"/><vertex x="0" y="20" z="10"/></vertices><triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>"""
        ZipOutputStream(file.outputStream()).use{it.putNextEntry(ZipEntry("3D/3dmodel.model"));it.write(xml.toByteArray());it.closeEntry()}
        try {
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                main{vm.openModel(Uri.fromFile(file))}
                await{vm.state.value.model?.name=="android-geometry.3mf"&&!vm.state.value.libraryBusy}
                assertEquals(1,vm.state.value.model!!.mesh.triangleCount);assertEquals(20f,vm.state.value.model!!.size.x,0f)
                await{store.load().modelName=="android-geometry.3mf"}
            }
        } finally {store.save(previous);if(model!=null)store.modelFile.writeBytes(model)else store.modelFile.delete();file.delete();library.listFiles().orEmpty().filter{it.name !in existing}.forEach{it.delete()} }
    }
}
