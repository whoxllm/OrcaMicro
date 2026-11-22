package io.github.whoxllm.orcamicro

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.MainViewModel
import io.github.whoxllm.orcamicro.ui.SliceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginsWorkflowTest {
    @Test fun sliceUsesEnabledPluginsAndReadsTheirOutput() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(app)
        val saved = store.load()
        val oldModel = store.modelFile.takeIf { it.exists() }?.readBytes()
        val model = File.createTempFile("plugin-workflow", ".stl", app.cacheDir)
        val points = listOf("0 0 0", "12 0 0", "0 12 0", "0 0 12")
        model.writeText(buildString {
            appendLine("solid tetrahedron")
            for (face in listOf("021", "013", "032", "123")) {
                appendLine("facet normal 0 0 0\nouter loop")
                face.forEach { appendLine("vertex " + points[it.digitToInt()]) }
                appendLine("endloop\nendfacet")
            }
            appendLine("endsolid tetrahedron")
        })
        val script = """
            # /// script
            # [tool.orcaslicer.plugin]
            # name = "Workflow stats"
            # ///
            import orca
            class Stats(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self): return "Workflow stats"
                def execute(self, ctx):
                    if ctx.step == orca.slicing.Step.psGCodePostProcess:
                        try:
                            with open("${model.absolutePath}", "rb"): pass
                        except PermissionError: pass
                        with open(ctx.gcode_path, "a") as output:
                            output.write("\n; total filament used [g] = 321.25\n")
                    return orca.ExecutionResult.success()
            @orca.plugin
            class Package(orca.base):
                def register_capabilities(self): orca.register_capability(Stats)
        """.trimIndent()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MainViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
                await { vm.state.value.ready }
                runBlocking {
                    val stamp = "orca_gcode_stamp"
                    val wasOn = vm.plugins.edit { list().single { it.id == stamp }.enabled }
                    val id = vm.plugins.edit { importPlugin("workflow_stats.py", script.toByteArray()).id }
                    try {
                        vm.plugins.edit { turnOn(stamp); turnOn(id) }
                        scenario.onActivity { vm.openModel(Uri.fromFile(model)) }
                        await { vm.state.value.model?.name == model.name && vm.state.value.inputs != null }
                        scenario.onActivity { vm.slice() }
                        await { vm.state.value.lastResult?.modelName == model.name || vm.state.value.slice is SliceState.Failed }
                        val done = requireNotNull(vm.state.value.lastResult) { vm.state.value.slice.toString() }
                        assertTrue(done.gcode.readText().contains("processed by the OrcaSlicer G-code Stamp plugin"))
                        assertEquals(321.25, done.toolpaths.stats.filamentGrams!!, 0.0001)
                        val expectedWarning = io.github.whoxllm.orcamicro.core.i18n.tr(
                            "{0} запросил разрешение «{1}». Заблокировано.", "Workflow stats",
                            io.github.whoxllm.orcamicro.core.i18n.tr("Чтение файлов"),
                        )
                        assertTrue(done.warnings.toString(), expectedWarning in done.warnings)
                    } finally { vm.plugins.edit { delete(id); if (!wasOn) turnOff(stamp) } }
                }
            }
        } finally {
            store.save(saved)
            if (oldModel == null) store.modelFile.delete() else store.modelFile.writeBytes(oldModel)
            model.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 60_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the plugin workflow", condition())
    }
}
