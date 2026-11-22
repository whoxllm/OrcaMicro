package io.github.whoxllm.orcamicro.core.plugins

import io.github.whoxllm.orcamicro.core.slicing.*
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.*
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class PluginLibraryTest {
    @TempDir lateinit var folder: File
    private val script = javaClass.getResource("/plugins/twistify.py")!!.readBytes()
    private val result = PluginInspectResult(true, name = "Twistify", capabilities = listOf(
        PluginCapabilityInfo("Twist", "slicing-pipeline", """{"angle":5}"""),
        PluginCapabilityInfo("Panel", "dock-panel"),
        PluginCapabilityInfo("Stamp", "slicing-pipeline"),
    ))
    private var reply = result
    private val engine = object : PluginEngine {
        override fun pythonHome() = "/python"
        override suspend fun inspect(request: PluginInspectRequest): PluginInspectResult {
            assertTrue(File(request.path).isFile)
            assertEquals(File(request.path).parent, request.dir)
            return reply
        }
    }
    @Test fun `trust warning is acknowledged once across restarts`() {
        assertTrue(library().needsTrustWarning())
        library().acknowledgeTrustWarning()
        assertFalse(library().needsTrustWarning())
    }

    @Test fun `turning on asks for new permissions and respects the answer`() = run {
        val lib = library()
        reply = result.copy(requestedPermissions = listOf("network_http"))
        val id = lib.importPlugin("demo.py", script).id
        val ask = setOf("network_http")
        assertEquals(ask, lib.turnOn(id))
        assertFalse(lib.list().single().enabled)
        lib.answer(id, ask, false)
        assertEquals(PluginPermissions(), lib.list().single().permissions)
        assertEquals(ask, lib.turnOn(id))
        lib.answer(id, ask, true)
        val on = library().list().single()
        assertTrue(on.enabled)
        assertEquals(ask, on.approvedRequests)
        assertEquals(ask, on.permissions.effective())
        lib.turnOff(id)
        assertTrue(lib.turnOn(id).isEmpty())
        assertTrue(lib.list().single().enabled)
    }

    @Test fun `refreshing an enabled plugin never grants new requests`() = run {
        val lib = library()
        val id = lib.importPlugin("demo.py", script).id
        assertTrue(lib.turnOn(id).isEmpty())
        reply = result.copy(requestedPermissions = listOf("process"))
        val updated = lib.inspect(id)
        assertTrue(updated.enabled)
        assertTrue(updated.permissions.effective().isEmpty())
        lib.setPermission(id, "process", true)
        assertTrue(lib.list().single().permissions.effective().isEmpty())
        lib.setPermissionsEnabled(id, true)
        assertEquals(setOf("process"), lib.list().single().permissions.effective())
        lib.setPermissionsEnabled(id, false)
        assertEquals(setOf("process"), library().list().single().permissions.allowed)
    }

    @Test fun `built-ins refresh in place and only imports can be deleted`() = run {
        val lib = library()
        val files = PluginLibrary.builtInOrder.reversed().associate { (it + ".py") to script }
        lib.installBuiltIns(files)
        assertEquals(listOf("orca_gcode_stamp", "orca_twistify", "orca_fuzzy_slices", "orca_inset"), lib.list().map { it.id })
        assertTrue(lib.list().all { it.builtIn && !it.enabled && it.source == "builtin" })
        val id = "orca_twistify"
        lib.turnOn(id)
        reply = result.copy(version = "2")
        val changed = script + "# new".toByteArray()
        lib.installBuiltIns(mapOf((id + ".py") to changed))
        val refreshed = lib.list().single { it.id == id }
        assertTrue(refreshed.enabled)
        assertEquals("2", refreshed.inspected?.version)
        assertContentEquals(changed, folder.resolve("$id/$id.py").readBytes())
        assertFailsWith<IllegalArgumentException> { lib.delete(id) }
        val imported = lib.importPlugin("$id.py", script)
        assertEquals("orca_twistify_2", imported.id)
        lib.delete(imported.id)
        assertFalse(folder.resolve(imported.id).exists())
        assertEquals(4, library().list().size)
    }

    @Test fun `run order survives missing ids and a new import comes last`() = run {
        val lib = library()
        val a = lib.importPlugin("a.py", script).id
        val b = lib.importPlugin("b.py", script).id
        lib.reorder(listOf(b, a))
        assertEquals(listOf(b, a), library().list().map { it.id })
        folder.resolve("order.json").writeText("""["gone","b","a","b"]""")
        val last = lib.importPlugin("c.py", script).id
        assertEquals(listOf(b, a, last), library().list().map { it.id })
    }

    @Test fun `slice items follow run order and use each capability config and effective grants`() = run {
        val lib = library()
        val first = lib.importPlugin("a.py", script).id
        val second = lib.importPlugin("b.py", script).id
        lib.importPlugin("off.py", script)
        lib.turnOn(first)
        lib.turnOn(second)
        lib.reorder(listOf(second, first))
        lib.setPermission(first, "network_http", true)
        lib.setPermission(second, "events", true)
        lib.setPermissionsEnabled(second, true)
        lib.saveConfig(second, "Twist", """{"angle":9}""")
        val items = library().sliceRequest()!!.items
        assertEquals(listOf(second, second, first, first), items.map { it.pluginId })
        assertEquals(listOf("Twist", "Stamp", "Twist", "Stamp"), items.map { it.capability })
        assertEquals(listOf("""{"angle":9}""", "{}", """{"angle":5}""", "{}"), items.map { it.config })
        assertEquals(listOf(listOf("events"), listOf("events"), emptyList(), emptyList()), items.map { it.allowed })
        assertEquals(folder.resolve("b/b.py").absolutePath, items.first().path)
        assertEquals(folder.resolve("b").absolutePath, items.first().dir)
        assertEquals("/python", lib.sliceRequest()!!.pythonHome)
        lib.resetConfig(second, "Twist")
        assertFalse(lib.list().first().configs.containsKey("Twist"))
        assertEquals("""{"angle":5}""", lib.sliceRequest()!!.items.first().config)
        lib.turnOff(first)
        lib.turnOff(second)
        assertNull(lib.sliceRequest())
    }

    @Test fun `busy inspections retry on restart and changed bytes invalidate cached capabilities`() = run {
        val lib = library()
        reply = PluginInspectResult(false, error = "busy")
        val pending = lib.importPlugin("demo.py", script)
        assertNull(pending.inspected)
        assertEquals("Twistify", lib.header(pending)?.name)
        reply = result
        library().refreshChanged()
        assertEquals(result, lib.list().single().inspected)
        reply = result.copy(version = "2")
        lib.refreshChanged()
        assertEquals(result, lib.list().single().inspected)
        folder.resolve("demo/demo.py").appendText("# changed\n")
        lib.refreshChanged()
        assertEquals("2", lib.list().single().inspected?.version)
        reply = PluginInspectResult(false, error = "syntax error")
        assertEquals("syntax error", lib.inspect("demo").inspected?.error)
    }

    private fun library() = PluginLibrary(folder, engine)
    private fun <T> run(block: suspend () -> T): T {
        val completion = CompletableFuture<T>()
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                result.fold(completion::complete, completion::completeExceptionally)
            }
        })
        return completion.join()
    }

    @Test fun `imports preserve entry bytes and allocate permanent unique ids`() = run {
        val lib = library()
        val first = lib.importPlugin("My Plugin.py", script)
        val second = lib.importPlugin("My Plugin.py", script)
        assertEquals("my_plugin", first.id)
        assertEquals("my_plugin_2", second.id)
        assertEquals("My Plugin.py", first.entry)
        assertContentEquals(script, folder.resolve("my_plugin/My Plugin.py").readBytes())
        assertEquals("py", first.source)
        assertFalse(first.enabled)
        assertFalse(first.builtIn)
        assertEquals(result, first.inspected)
        assertEquals(listOf(first, second), library().list())
        assertFailsWith<IllegalArgumentException> { lib.importPlugin("../escape.py", script) }
        assertFailsWith<IllegalArgumentException> { lib.importPlugin("plain.py", "print(1)".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { lib.importPlugin("package.whl", script) }
        Unit
    }
}
