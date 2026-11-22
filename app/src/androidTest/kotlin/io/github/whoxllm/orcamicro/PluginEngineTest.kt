package io.github.whoxllm.orcamicro

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.profiles.Catalog
import io.github.whoxllm.orcamicro.core.settings.BedShape
import io.github.whoxllm.orcamicro.core.settings.PresetEdits
import io.github.whoxllm.orcamicro.core.slicing.EngineResponse
import io.github.whoxllm.orcamicro.core.slicing.PluginInspectRequest
import io.github.whoxllm.orcamicro.core.slicing.PluginInspectResult
import io.github.whoxllm.orcamicro.core.slicing.PluginPermission
import io.github.whoxllm.orcamicro.core.slicing.PluginSliceItem
import io.github.whoxllm.orcamicro.core.slicing.PluginsRequest
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import io.github.whoxllm.orcamicro.data.ProfileRepository
import io.github.whoxllm.orcamicro.engine.EngineClient
import io.github.whoxllm.orcamicro.engine.EngineEvent
import io.github.whoxllm.orcamicro.engine.EngineResources
import io.github.whoxllm.orcamicro.engine.NativeEngine
import io.github.whoxllm.orcamicro.engine.SliceJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PluginEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val profiles = ProfileRepository(context.assets)
    private lateinit var engine: EngineClient
    private lateinit var pythonHome: String
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        assertTrue("engine failed to load: ${NativeEngine.unavailableReason}", NativeEngine.isAvailable)
        engine = EngineClient(context)
        pythonHome = engine.pythonHome()
        tempRoot = File(context.filesDir, "plugin-engine-tests").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        if (::tempRoot.isInitialized) {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun inspectAllFourBundledPlugins() = runBlocking {
        val dir = newPluginDir("bundled")
        val stamp = extractAssetPlugin("plugins/orca_gcode_stamp.py", dir)
        val twistify = extractAssetPlugin("plugins/orca_twistify.py", dir)
        val fuzzy = extractAssetPlugin("plugins/orca_fuzzy_slices.py", dir)
        val inset = extractAssetPlugin("plugins/orca_inset.py", dir)

        val stampRes = inspect(stamp, dir)
        assertTrue("stamp inspect failed: ${stampRes.error}", stampRes.ok)
        assertEquals("G-code Stamp", stampRes.name)
        assertEquals("slicing-pipeline", stampRes.capabilities.single().type)
        assertEquals("G-code Stamp", stampRes.capabilities.single().name)
        assertNull(stampRes.capabilities.single().configUi)

        val twistRes = inspect(twistify, dir)
        assertTrue("twistify inspect failed: ${twistRes.error}", twistRes.ok)
        assertEquals("Twistify", twistRes.name)
        assertEquals("slicing-pipeline", twistRes.capabilities.single().type)
        val twistUi = requireNotNull(twistRes.capabilities.single().configUi) { "Twistify should expose configUi" }
        assertTrue("Twistify configUi should be non-empty HTML", twistUi.isNotBlank() && "window.orca" in twistUi)

        val fuzzyRes = inspect(fuzzy, dir)
        assertTrue("fuzzy inspect failed: ${fuzzyRes.error}", fuzzyRes.ok)
        assertEquals("Fuzzy Slices", fuzzyRes.name)
        assertEquals("slicing-pipeline", fuzzyRes.capabilities.single().type)

        val insetRes = inspect(inset, dir)
        assertTrue("inset inspect failed: ${insetRes.error}", insetRes.ok)
        assertEquals("Inset Every Slice", insetRes.name)
        assertEquals("slicing-pipeline", insetRes.capabilities.single().type)
    }

    @Test
    fun gcodeStampInsertsConfiguredStampText() {
        val dir = newPluginDir("stamp")
        val plugin = extractAssetPlugin("plugins/orca_gcode_stamp.py", dir)
        val item = PluginSliceItem(
            pluginId = "stamp",
            capability = "G-code Stamp",
            path = plugin.path,
            dir = dir.path,
            config = """{"stamp_text":"ORCAMICRO_PLUGIN_STAMP_2026"}""",
            allowed = emptyList(),
        )
        val outcome = sliceBox(plugins = PluginsRequest(pythonHome, listOf(item)))
        assertTrue("slice failed: ${outcome.response.error}", outcome.response.ok)
        assertTrue("expected stamp plugin message: ${outcome.response.pluginMessages}",
            outcome.response.pluginMessages.any { it.pluginId == "stamp" && it.status == "ok" })
        assertTrue("stamp missing from output G-code",
            outcome.gcodeText.contains("ORCAMICRO_PLUGIN_STAMP_2026"))
    }

    @Test
    fun twistifyFuzzySlicesAndInsetEachChangeGcode() = runBlocking {
        val plain = sliceBox(size = 20f, height = 10f)
        assertTrue("plain slice failed: ${plain.response.error}", plain.response.ok)
        val plainMoves = movementLines(plain.gcodeText)

        val bundled = newPluginDir("transforms")
        for ((asset, capName, cfgOverride) in listOf(
            Triple("plugins/orca_twistify.py", "Twistify", null),
            Triple("plugins/orca_fuzzy_slices.py", "Fuzzy Slices", null),
            Triple("plugins/orca_inset.py", "Inset Every Slice", null),
        )) {
            val file = extractAssetPlugin(asset, bundled)
            val info = inspect(file, bundled)
            assertTrue("inspect $capName failed: ${info.error}", info.ok)
            val cap = info.capabilities.single()
            val item = PluginSliceItem(
                pluginId = capName,
                capability = cap.name,
                path = file.path,
                dir = bundled.path,
                config = cfgOverride ?: cap.defaultConfig,
                allowed = emptyList(),
            )
            val out = sliceBox(size = 20f, height = 10f, plugins = PluginsRequest(pythonHome, listOf(item)))
            assertTrue("$capName slice failed: ${out.response.error}", out.response.ok)
            assertTrue("missing ok pluginMessage for $capName: ${out.response.pluginMessages}",
                out.response.pluginMessages.any { it.pluginId == capName && it.status == "ok" })
            assertNotEquals("$capName did not alter sliced G-code", plainMoves, movementLines(out.gcodeText))
        }
    }

    @Test
    fun socketPermissionBlockedThenAllowed() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val acceptThread = thread(isDaemon = true, name = "test-sock-server") {
            while (running.get()) {
                try {
                    server.accept().use { conn ->
                        conn.getOutputStream().write("LOCAL_SOCKET_OK".toByteArray())
                        conn.getOutputStream().flush()
                    }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
        try {
            val dir = newPluginDir("sock")
            val plugin = writePlugin(
                dir,
                "sock_plugin.py",
                """
                import json, socket, orca

                class SockCap(orca.slicing.SlicingPipelineCapabilityBase):
                    def get_name(self):
                        return "SockCap"

                    def execute(self, ctx):
                        if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                            return orca.ExecutionResult.success()
                        port = json.loads(self.get_config())["port"]
                        try:
                            with socket.create_connection(("127.0.0.1", port), timeout=3.0) as s:
                                payload = s.recv(64).decode("utf-8")
                            with open(ctx.gcode_path, "a", encoding="utf-8") as f:
                                f.write(f"; SOCK={payload}\n")
                            return orca.ExecutionResult.success("socket ok")
                        except PermissionError:
                            return orca.ExecutionResult.skipped("blocked")

                @orca.plugin
                class SockPkg(orca.base):
                    def register_capabilities(self):
                        orca.register_capability(SockCap)
                """.trimIndent(),
            )
            val cfg = """{"port":${server.localPort}}"""

            val blocked = sliceBox(
                plugins = PluginsRequest(
                    pythonHome,
                    listOf(PluginSliceItem("sock", "SockCap", plugin.path, dir.path, cfg, emptyList())),
                ),
            )
            assertTrue("blocked slice failed: ${blocked.response.error}", blocked.response.ok)
            assertTrue(
                "expected blocked network_socket message, got: ${blocked.response.pluginMessages}",
                blocked.response.pluginMessages.any {
                    it.status == "blocked" && it.permission == PluginPermission.NETWORK_SOCKET
                },
            )
            assertFalse(blocked.gcodeText.contains("SOCK=LOCAL_SOCKET_OK"))

            val allowed = sliceBox(
                plugins = PluginsRequest(
                    pythonHome,
                    listOf(
                        PluginSliceItem(
                            "sock",
                            "SockCap",
                            plugin.path,
                            dir.path,
                            cfg,
                            listOf(PluginPermission.NETWORK_SOCKET),
                        ),
                    ),
                ),
            )
            assertTrue("allowed slice failed: ${allowed.response.error}", allowed.response.ok)
            assertTrue("unexpected blocked message: ${allowed.response.pluginMessages}",
                allowed.response.pluginMessages.none { it.status == "blocked" })
            assertTrue("socket payload missing from G-code", allowed.gcodeText.contains("; SOCK=LOCAL_SOCKET_OK"))
        } finally {
            running.set(false)
            runCatching { server.close() }
            acceptThread.join(1000)
        }
    }

    @Test
    fun secondSliceCannotReuseFirstSliceSocketPermission() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val acceptThread = thread(isDaemon = true, name = "test-seq-server") {
            while (running.get()) {
                try {
                    server.accept().use { conn ->
                        conn.getOutputStream().write("SEQ_OK".toByteArray())
                        conn.getOutputStream().flush()
                    }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
        try {
            val dir = newPluginDir("seq_sock")
            val plugin = writePlugin(
                dir,
                "seq_sock.py",
                """
                import json, socket, orca

                class SeqSock(orca.slicing.SlicingPipelineCapabilityBase):
                    def get_name(self):
                        return "SeqSock"

                    def execute(self, ctx):
                        if ctx.step != orca.slicing.Step.psGCodePostProcess:
                            return orca.ExecutionResult.success()
                        port = json.loads(self.get_config())["port"]
                        try:
                            with socket.create_connection(("127.0.0.1", port), timeout=3.0) as s:
                                _ = s.recv(16)
                            return orca.ExecutionResult.success("connected")
                        except PermissionError:
                            return orca.ExecutionResult.skipped("blocked")

                @orca.plugin
                class SeqSockPkg(orca.base):
                    def register_capabilities(self):
                        orca.register_capability(SeqSock)
                """.trimIndent(),
            )
            val cfg = """{"port":${server.localPort}}"""

            val first = sliceBox(
                plugins = PluginsRequest(
                    pythonHome,
                    listOf(
                        PluginSliceItem(
                            "seq",
                            "SeqSock",
                            plugin.path,
                            dir.path,
                            cfg,
                            listOf(PluginPermission.NETWORK_SOCKET),
                        ),
                    ),
                ),
            )
            assertTrue(first.response.ok)
            assertTrue(first.response.pluginMessages.any { it.status == "ok" })

            val second = sliceBox(
                plugins = PluginsRequest(
                    pythonHome,
                    listOf(PluginSliceItem("seq", "SeqSock", plugin.path, dir.path, cfg, emptyList())),
                ),
            )
            assertTrue(second.response.ok)
            assertTrue(
                "permission leaked across slices: ${second.response.pluginMessages}",
                second.response.pluginMessages.any {
                    it.status == "blocked" && it.permission == PluginPermission.NETWORK_SOCKET
                },
            )
        } finally {
            running.set(false)
            runCatching { server.close() }
            acceptThread.join(1000)
        }
    }

    @Test
    fun httpRequestBlockedThenAllowedAgainstLocalServer() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val acceptThread = thread(isDaemon = true, name = "test-http-server") {
            while (running.get()) {
                try {
                    server.accept().use { conn ->
                        val reader = conn.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = "LOCAL_HTTP_OK"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        conn.getOutputStream().write(resp.toByteArray())
                        conn.getOutputStream().flush()
                    }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
        try {
            val dir = newPluginDir("http")
            val plugin = writePlugin(
                dir,
                "http_plugin.py",
                """
                import json, urllib.request, orca

                class HttpCap(orca.slicing.SlicingPipelineCapabilityBase):
                    def get_name(self):
                        return "HttpCap"

                    def execute(self, ctx):
                        if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                            return orca.ExecutionResult.success()
                        port = json.loads(self.get_config())["port"]
                        try:
                            with urllib.request.urlopen(f"http://127.0.0.1:{port}/test", timeout=3.0) as resp:
                                body = resp.read().decode("utf-8")
                            with open(ctx.gcode_path, "a", encoding="utf-8") as f:
                                f.write(f"; HTTP={body}\n")
                            return orca.ExecutionResult.success("http ok")
                        except PermissionError:
                            return orca.ExecutionResult.skipped("blocked")

                @orca.plugin
                class HttpPkg(orca.base):
                    def register_capabilities(self):
                        orca.register_capability(HttpCap)
                """.trimIndent(),
            )
            val cfg = """{"port":${server.localPort}}"""

            val blocked = sliceBox(
                plugins = PluginsRequest(
                    pythonHome,
                    listOf(PluginSliceItem("http", "HttpCap", plugin.path, dir.path, cfg, emptyList())),
                ),
            )
            assertTrue(blocked.response.ok)
            assertTrue(
                "expected blocked network_http, got: ${blocked.response.pluginMessages}",
                blocked.response.pluginMessages.any {
                    it.status == "blocked" && it.permission == PluginPermission.NETWORK_HTTP
                },
            )
            assertFalse(blocked.gcodeText.contains("HTTP=LOCAL_HTTP_OK"))

            val allowed = sliceBox(
                plugins = PluginsRequest(
                    pythonHome,
                    listOf(
                        PluginSliceItem(
                            "http",
                            "HttpCap",
                            plugin.path,
                            dir.path,
                            cfg,
                            listOf(PluginPermission.NETWORK_HTTP),
                        ),
                    ),
                ),
            )
            assertTrue("http slice failed: ${allowed.response.error}", allowed.response.ok)
            assertTrue("unexpected block: ${allowed.response.pluginMessages}",
                allowed.response.pluginMessages.none { it.status == "blocked" })
            assertTrue("HTTP response missing in G-code", allowed.gcodeText.contains("; HTTP=LOCAL_HTTP_OK"))
        } finally {
            running.set(false)
            runCatching { server.close() }
            acceptThread.join(1000)
        }
    }

    @Test
    fun fsReadSharedPrefsBlockedAllowedAndCaughtVsUncaught() {
        val prefsSaved = context.getSharedPreferences("plugin_guard_test", Context.MODE_PRIVATE)
            .edit()
            .putString("secret", "ORCA_PREF_42")
            .commit()
        assertTrue("failed to commit test shared_prefs", prefsSaved)
        val prefsPath = "/data/data/${context.packageName}/shared_prefs/plugin_guard_test.xml"

        val dir = newPluginDir("fs_read")
        val plugin = writePlugin(
            dir,
            "fs_read_plugin.py",
            """
            import json, orca

            class FsReadCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "FsReadCap"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess:
                        return orca.ExecutionResult.success()
                    cfg = json.loads(self.get_config())
                    path = cfg["path"]
                    if cfg.get("catch", True):
                        try:
                            with open(path, "r", encoding="utf-8") as f:
                                data = f.read()
                            return orca.ExecutionResult.success(f"read:{len(data)}")
                        except PermissionError:
                            return orca.ExecutionResult.skipped("blocked")
                    with open(path, "r", encoding="utf-8") as f:
                        data = f.read()
                    return orca.ExecutionResult.success(f"read:{len(data)}")

            @orca.plugin
            class FsReadPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(FsReadCap)
            """.trimIndent(),
        )

        val escape = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        val caughtCfg = """{"path":"${escape(prefsPath)}","catch":true}"""
        val uncaughtCfg = """{"path":"${escape(prefsPath)}","catch":false}"""

        val blockedCaught = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("fs", "FsReadCap", plugin.path, dir.path, caughtCfg, emptyList())),
            ),
        )
        assertTrue("caught PermissionError should keep slice ok", blockedCaught.response.ok)
        assertTrue(
            "expected blocked fs_read message: ${blockedCaught.response.pluginMessages}",
            blockedCaught.response.pluginMessages.any {
                it.status == "blocked" && it.permission == PluginPermission.FS_READ
            },
        )

        val blockedUncaught = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("fs", "FsReadCap", plugin.path, dir.path, uncaughtCfg, emptyList())),
            ),
        )
        assertFalse("uncaught PermissionError should fail slice", blockedUncaught.response.ok)
        assertEquals("plugin", blockedUncaught.response.stage)
        assertTrue(
            "expected blocked fs_read message on failure: ${blockedUncaught.response.pluginMessages}",
            blockedUncaught.response.pluginMessages.any {
                it.status == "blocked" && it.permission == PluginPermission.FS_READ
            },
        )

        val allowed = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(
                    PluginSliceItem(
                        "fs",
                        "FsReadCap",
                        plugin.path,
                        dir.path,
                        caughtCfg,
                        listOf(PluginPermission.FS_READ),
                    ),
                ),
            ),
        )
        assertTrue("allowed fs_read slice failed: ${allowed.response.error}", allowed.response.ok)
        assertTrue("unexpected blocked message: ${allowed.response.pluginMessages}",
            allowed.response.pluginMessages.none { it.status == "blocked" })
        assertTrue("expected ok message for shared_prefs read: ${allowed.response.pluginMessages}",
            allowed.response.pluginMessages.any { it.pluginId == "fs" && it.status == "ok" })
    }

    @Test
    fun writeToOwnPluginDirAndSliceWorkDirSucceedsWithoutPermissions() {
        val dir = newPluginDir("own_write")
        val plugin = writePlugin(
            dir,
            "own_write.py",
            """
            import json, os, orca

            class OwnWrite(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "OwnWrite"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                        return orca.ExecutionResult.success()
                    cfg = json.loads(self.get_config())
                    with open(cfg["data_file"], "w", encoding="utf-8") as f:
                        f.write("data_write_ok")
                    work_file = os.path.join(os.path.dirname(ctx.gcode_path), "plugin_work.txt")
                    with open(work_file, "w", encoding="utf-8") as f:
                        f.write("work_write_ok")
                    return orca.ExecutionResult.success("wrote both")

            @orca.plugin
            class OwnWritePkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(OwnWrite)
            """.trimIndent(),
        )
        val dataFile = File(File(dir, "data"), "plugin_local.txt")
        val cfg = """{"data_file":"${dataFile.path}"}"""
        val out = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("own", "OwnWrite", plugin.path, dir.path, cfg, emptyList())),
            ),
        )
        assertTrue("slice failed: ${out.response.error}", out.response.ok)
        assertTrue("unexpected blocked message: ${out.response.pluginMessages}",
            out.response.pluginMessages.none { it.status == "blocked" })
        assertEquals("data_write_ok", dataFile.readText())
        assertEquals("work_write_ok", File(out.job.dir, "plugin_work.txt").readText())
    }

    @Test
    fun writesOutsideOwnDataUnderPluginsRootAlwaysBlocked() {
        val pluginsRoot = File(tempRoot, "plugins").apply { mkdirs() }
        val dir = File(pluginsRoot, "self_plugin").apply { mkdirs() }
        val otherDir = File(pluginsRoot, "other_plugin").apply { mkdirs() }
        val pluginJson = File(dir, "plugin.json").apply { writeText("""{"id":"self_plugin"}""") }
        val orderJson = File(pluginsRoot, "order.json").apply { writeText("""["self_plugin"]""") }
        val otherPy = File(otherDir, "other.py").apply { writeText("# keep") }
        val otherInjected = File(otherDir, "injected.py")

        val plugin = writePlugin(
            dir,
            "self_plugin.py",
            """
            import json, orca

            class TamperCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "TamperCap"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess:
                        return orca.ExecutionResult.success()
                    target = json.loads(self.get_config())["target"]
                    try:
                        with open(target, "w", encoding="utf-8") as f:
                            f.write("TAMPERED")
                        return orca.ExecutionResult.success("wrote")
                    except PermissionError:
                        return orca.ExecutionResult.skipped("blocked")

            @orca.plugin
            class TamperPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(TamperCap)
            """.trimIndent(),
        )
        val originalMain = plugin.readText()

        val targets = listOf(
            "w_plugin_json" to pluginJson.path,
            "w_main_file" to plugin.path,
            "w_other_plugin" to otherInjected.path,
            "w_order_json" to orderJson.path,
        )
        val items = targets.map { (id, path) ->
            PluginSliceItem(
                pluginId = id,
                capability = "TamperCap",
                path = plugin.path,
                dir = dir.path,
                config = """{"target":"$path"}""",
                allowed = PluginPermission.ALL,
            )
        }
        val out = sliceBox(plugins = PluginsRequest(pythonHome, items))
        assertTrue("slice failed: ${out.response.error}", out.response.ok)
        val blocked = out.response.pluginMessages.filter {
            it.status == "blocked" && it.permission == PluginPermission.FS_READWRITE
        }
        assertEquals(
            "expected all 4 tamper writes blocked even with ALL permissions: ${out.response.pluginMessages}",
            4,
            blocked.size,
        )
        assertEquals(targets.map { it.first }.toSet(), blocked.map { it.pluginId }.toSet())
        assertEquals("""{"id":"self_plugin"}""", pluginJson.readText())
        assertEquals(originalMain, plugin.readText())
        assertFalse("write to other plugin folder must not succeed", otherInjected.exists())
        assertEquals("""["self_plugin"]""", orderJson.readText())
        assertEquals("# keep", otherPy.readText())
    }

    @Test
    fun subprocessBlocked() {
        val dir = newPluginDir("proc")
        val plugin = writePlugin(
            dir,
            "proc_plugin.py",
            """
            import subprocess, orca

            class ProcCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "ProcCap"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess:
                        return orca.ExecutionResult.success()
                    try:
                        subprocess.run(["/system/bin/true"], check=False)
                        return orca.ExecutionResult.success("ran")
                    except PermissionError:
                        return orca.ExecutionResult.skipped("blocked")

            @orca.plugin
            class ProcPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(ProcCap)
            """.trimIndent(),
        )
        val out = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("proc", "ProcCap", plugin.path, dir.path, "{}", emptyList())),
            ),
        )
        assertTrue(out.response.ok)
        assertTrue(
            "expected blocked process message: ${out.response.pluginMessages}",
            out.response.pluginMessages.any { it.status == "blocked" && it.permission == PluginPermission.PROCESS },
        )
    }

    @Test
    fun threadingBlockedThenAllowed() {
        val dir = newPluginDir("thread")
        val plugin = writePlugin(
            dir,
            "thread_plugin.py",
            """
            import threading, orca

            class ThreadCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "ThreadCap"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                        return orca.ExecutionResult.success()
                    ran = []
                    def work():
                        ran.append("THREAD_RAN")
                    try:
                        t = threading.Thread(target=work)
                        t.start()
                        t.join(timeout=3.0)
                        if ran:
                            with open(ctx.gcode_path, "a", encoding="utf-8") as f:
                                f.write("; THREAD=THREAD_RAN\n")
                        return orca.ExecutionResult.success("thread ok")
                    except PermissionError:
                        return orca.ExecutionResult.skipped("blocked")

            @orca.plugin
            class ThreadPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(ThreadCap)
            """.trimIndent(),
        )

        val blocked = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("thr", "ThreadCap", plugin.path, dir.path, "{}", emptyList())),
            ),
        )
        assertTrue(blocked.response.ok)
        assertTrue(
            "expected blocked threading message: ${blocked.response.pluginMessages}",
            blocked.response.pluginMessages.any {
                it.status == "blocked" && it.permission == PluginPermission.THREADING
            },
        )
        assertFalse(blocked.gcodeText.contains("THREAD=THREAD_RAN"))

        val allowed = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(
                    PluginSliceItem(
                        "thr",
                        "ThreadCap",
                        plugin.path,
                        dir.path,
                        "{}",
                        listOf(PluginPermission.THREADING),
                    ),
                ),
            ),
        )
        assertTrue("allowed thread slice failed: ${allowed.response.error}", allowed.response.ok)
        assertTrue(allowed.response.pluginMessages.none { it.status == "blocked" })
        assertTrue("thread marker missing", allowed.gcodeText.contains("; THREAD=THREAD_RAN"))
    }

    @Test
    fun ctypesAlwaysBlockedEvenWithAllPermissions() {
        val dir = newPluginDir("native")
        val plugin = writePlugin(
            dir,
            "ctypes_plugin.py",
            """
            import ctypes, orca

            class CtypesCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "CtypesCap"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess:
                        return orca.ExecutionResult.success()
                    try:
                        ctypes.CDLL(None)
                        return orca.ExecutionResult.success("loaded")
                    except PermissionError:
                        return orca.ExecutionResult.skipped("blocked")

            @orca.plugin
            class CtypesPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(CtypesCap)
            """.trimIndent(),
        )
        val out = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("native", "CtypesCap", plugin.path, dir.path, "{}", PluginPermission.ALL)),
            ),
        )
        assertTrue(out.response.ok)
        assertTrue(
            "expected blocked native message: ${out.response.pluginMessages}",
            out.response.pluginMessages.any { it.status == "blocked" && it.permission == "native" },
        )
    }

    @Test
    fun requestPermissionsReportedByInspect() = runBlocking {
        val dir = newPluginDir("req_perm")
        val plugin = writePlugin(
            dir,
            "req_perm.py",
            """
            import orca
            orca.request_permissions(network_http=["http://127.0.0.1"])

            class ReqCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "ReqCap"
                def execute(self, ctx):
                    return orca.ExecutionResult.success()

            @orca.plugin
            class ReqPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(ReqCap)
            """.trimIndent(),
        )
        val res = inspect(plugin, dir)
        assertTrue("inspect failed: ${res.error}", res.ok)
        assertTrue(
            "expected network_http in requestedPermissions: ${res.requestedPermissions}",
            PluginPermission.NETWORK_HTTP in res.requestedPermissions,
        )
    }

    @Test
    fun inspectSyntaxErrorReturnsFailureAndClearError() = runBlocking {
        val dir = newPluginDir("syntax_err")
        val broken = writePlugin(
            dir,
            "broken.py",
            """
            def invalid_syntax(:
                pass
            """.trimIndent(),
        )
        val res = inspect(broken, dir)
        assertFalse("syntax error plugin should fail inspect", res.ok)
        val err = requireNotNull(res.error) { "syntax error inspect must populate error" }
        assertTrue("expected SyntaxError details, got: $err",
            err.contains("SyntaxError", ignoreCase = true) || err.contains("syntax", ignoreCase = true))
    }

    @Test
    fun twoFilePluginImportsSiblingModuleAndRuns() {
        val dir = newPluginDir("two_file")
        writePlugin(
            dir,
            "helper_mod.py",
            """
            STAMP = "ORCAMICRO_TWO_FILE_OK"
            """.trimIndent(),
        )
        val main = writePlugin(
            dir,
            "main.py",
            """
            import helper_mod
            import orca

            class TwoFileCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "TwoFileCap"

                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                        return orca.ExecutionResult.success()
                    with open(ctx.gcode_path, "a", encoding="utf-8") as f:
                        f.write(f"; {helper_mod.STAMP}\n")
                    return orca.ExecutionResult.success("two-file ok")

            @orca.plugin
            class TwoFilePkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(TwoFileCap)
            """.trimIndent(),
        )
        val out = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("twofile", "TwoFileCap", main.path, dir.path, "{}", emptyList())),
            ),
        )
        assertTrue("two-file slice failed: ${out.response.error}", out.response.ok)
        assertTrue(out.gcodeText.contains("; ORCAMICRO_TWO_FILE_OK"))
    }

    @Test
    fun raisingPluginFailsSliceAtPluginStageWithPluginName() {
        val dir = newPluginDir("raising")
        val plugin = writePlugin(
            dir,
            "boom.py",
            """
            # /// script
            # [tool.orcaslicer.plugin]
            # name = "BoomPlugin"
            # ///
            import orca

            class BoomCap(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "BoomCap"

                def execute(self, ctx):
                    if ctx.step == orca.slicing.Step.posSlice:
                        raise RuntimeError("intentional boom")
                    return orca.ExecutionResult.success()

            @orca.plugin
            class BoomPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(BoomCap)
            """.trimIndent(),
        )
        val out = sliceBox(
            plugins = PluginsRequest(
                pythonHome,
                listOf(PluginSliceItem("boom", "BoomCap", plugin.path, dir.path, "{}", emptyList())),
            ),
        )
        assertFalse("raising plugin should fail slice", out.response.ok)
        assertEquals("plugin", out.response.stage)
        val err = requireNotNull(out.response.error) { "raising plugin should include error" }
        assertTrue("expected plugin name in error, got: $err",
            "BoomPlugin" in err || "BoomCap" in err)
        assertTrue("expected exception message in error, got: $err", "intentional boom" in err)
    }

    @Test
    fun desktopSlicingPipelinePluginPresetKeyIgnoredWhenNoPluginsSent() {
        val edits = PresetEdits(
            rawProcess = mapOf(
                "slicing_pipeline_plugin" to JsonArray(listOf(JsonPrimitive("Twistify"), JsonPrimitive("DesktopOnlyPlugin"))),
            ),
        )
        val out = sliceBox(edits = edits, plugins = null)
        assertTrue("slice with desktop preset key failed: ${out.response.error}", out.response.ok)
        assertTrue("no G-code written", out.job.gcode.isFile && out.job.gcode.length() > 1000)
        assertTrue(out.response.pluginMessages.isEmpty())
    }

    @Test
    fun runOrderSwappingTwoPluginsSwapsOutput() {
        val dir = newPluginDir("order")
        val pluginA = writePlugin(
            dir,
            "order_a.py",
            """
            import orca

            class OrderA(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "OrderA"
                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                        return orca.ExecutionResult.success()
                    with open(ctx.gcode_path, "a", encoding="utf-8") as f:
                        f.write("; ORDER_MARKER_A\n")
                    return orca.ExecutionResult.success("A")

            @orca.plugin
            class OrderAPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(OrderA)
            """.trimIndent(),
        )
        val pluginB = writePlugin(
            dir,
            "order_b.py",
            """
            import orca

            class OrderB(orca.slicing.SlicingPipelineCapabilityBase):
                def get_name(self):
                    return "OrderB"
                def execute(self, ctx):
                    if ctx.step != orca.slicing.Step.psGCodePostProcess or not ctx.gcode_path:
                        return orca.ExecutionResult.success()
                    with open(ctx.gcode_path, "a", encoding="utf-8") as f:
                        f.write("; ORDER_MARKER_B\n")
                    return orca.ExecutionResult.success("B")

            @orca.plugin
            class OrderBPkg(orca.base):
                def register_capabilities(self):
                    orca.register_capability(OrderB)
            """.trimIndent(),
        )
        val itemA = PluginSliceItem("orderA", "OrderA", pluginA.path, dir.path, "{}", emptyList())
        val itemB = PluginSliceItem("orderB", "OrderB", pluginB.path, dir.path, "{}", emptyList())

        val ab = sliceBox(plugins = PluginsRequest(pythonHome, listOf(itemA, itemB)))
        assertTrue(ab.response.ok)
        val abIdxA = ab.gcodeText.indexOf("; ORDER_MARKER_A")
        val abIdxB = ab.gcodeText.indexOf("; ORDER_MARKER_B")
        assertTrue("both markers missing in AB: A=$abIdxA B=$abIdxB", abIdxA >= 0 && abIdxB >= 0 && abIdxA < abIdxB)

        val ba = sliceBox(plugins = PluginsRequest(pythonHome, listOf(itemB, itemA)))
        assertTrue(ba.response.ok)
        val baIdxA = ba.gcodeText.indexOf("; ORDER_MARKER_A")
        val baIdxB = ba.gcodeText.indexOf("; ORDER_MARKER_B")
        assertTrue("expected swapped marker order in BA: A=$baIdxA B=$baIdxB", baIdxB >= 0 && baIdxA > baIdxB)
    }

    @Test
    fun inspectWhileSliceRunsReturnsBusy() {
        val dir = newPluginDir("busy_check")
        val stamp = extractAssetPlugin("plugins/orca_gcode_stamp.py", dir)

        ActivityScenario.launch(MainActivity::class.java).use {
            val edits = PresetEdits(
                rawProcess = mapOf(
                    "layer_height" to JsonPrimitive("0.05"),
                    "sparse_infill_density" to JsonPrimitive("40%"),
                ),
            )
            val prepared = prepareJob(size = 20f, height = 20f, edits = edits, plugins = null)
            runBlocking {
                withTimeout(5 * 60 * 1000L) {
                    val progressSeen = CompletableDeferred<Unit>()
                    val events = ArrayList<EngineEvent>()
                    val sliceJob = async(Dispatchers.Default) {
                        engine.slice(prepared.requestJson).collect { ev ->
                            events += ev
                            if (ev is EngineEvent.Progress && !progressSeen.isCompleted) {
                                progressSeen.complete(Unit)
                            }
                        }
                        if (!progressSeen.isCompleted) {
                            progressSeen.completeExceptionally(
                                IllegalStateException("slice ended before emitting progress: $events"),
                            )
                        }
                        events
                    }
                    progressSeen.await()
                    assertFalse("slice finished before inspect could run", sliceJob.isCompleted)
                    val busyResult = engine.inspect(PluginInspectRequest(pythonHome, stamp.path, dir.path))
                    assertFalse("inspect should report not ok while slicing", busyResult.ok)
                    assertEquals("busy", busyResult.error)

                    val finalEvents = sliceJob.await()
                    val finished = finalEvents.lastOrNull() as? EngineEvent.Finished
                    assertTrue("slice did not finish cleanly: $finalEvents", finished?.response?.ok == true)
                }
            }
        }
    }

    private class SliceOutcome(
        val job: SliceJob,
        val response: EngineResponse,
        val gcodeText: String,
    )

    private class Prepared(
        val job: SliceJob,
        val requestJson: String,
    )

    private fun newPluginDir(name: String): File =
        File(tempRoot, name).apply {
            deleteRecursively()
            mkdirs()
        }

    private fun extractAssetPlugin(assetPath: String, targetDir: File): File {
        val out = File(targetDir, assetPath.substringAfterLast('/'))
        context.assets.open(assetPath).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun writePlugin(dir: File, filename: String, content: String): File =
        File(dir, filename).apply { writeText(content + "\n") }

    private suspend fun inspect(file: File, dir: File): PluginInspectResult =
        withTimeout(60_000L) {
            engine.inspect(PluginInspectRequest(pythonHome, file.path, dir.path))
        }

    private fun prepareJob(
        size: Float = 10f,
        height: Float = 4f,
        edits: PresetEdits = PresetEdits(),
        plugins: PluginsRequest? = null,
    ): Prepared {
        val catalog = profiles.catalog
        val vendor = "Custom"
        val printerName = "MyMarlin 0.4 nozzle"
        val printer = requireNotNull(catalog.printer(vendor, printerName)) { "$printerName missing" }
        val process = requireNotNull(catalog.defaultProcessFor(vendor, printer))
        val filament = catalog.filamentsFor(vendor, printer)
            .first { it.vendor == Catalog.LIBRARY_VENDOR && it.name == "Generic PLA @System" }
        val inputs = SliceInputs.build(
            profiles.resolve(vendor, printerName),
            profiles.resolve(vendor, process),
            profiles.resolve(filament.vendor, filament.name),
            edits,
        )
        val bed = requireNotNull(BedShape.of(inputs.machine))
        val placement = Placement(centerX = bed.centerX, centerY = bed.centerY)
        val job = SliceJob.create(context, box(size, size, height), placement, inputs)
        val resources = EngineResources.ensure(context)
        return Prepared(job, job.request(resources, placement, plugins).toJson())
    }

    private fun sliceBox(
        size: Float = 10f,
        height: Float = 4f,
        edits: PresetEdits = PresetEdits(),
        plugins: PluginsRequest? = null,
    ): SliceOutcome {
        val prepared = prepareJob(size, height, edits, plugins)
        val response = EngineResponse.parse(NativeEngine.slice(prepared.requestJson))
        val gcodeText = if (prepared.job.gcode.isFile) prepared.job.gcode.readText() else ""
        return SliceOutcome(prepared.job, response, gcodeText)
    }

    private fun movementLines(gcode: String): List<String> =
        gcode.lineSequence()
            .filter { it.startsWith("G0 ") || it.startsWith("G1 ") }
            .toList()

    private fun box(w: Float, d: Float, h: Float): Mesh {
        val v = Array(8) { i ->
            floatArrayOf(if (i and 1 != 0) w else 0f, if (i and 2 != 0) d else 0f, if (i and 4 != 0) h else 0f)
        }
        val quads = listOf(
            intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 6, 7, 3), intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5),
        )
        val out = ArrayList<Float>()
        for (q in quads) {
            for (t in listOf(intArrayOf(q[0], q[1], q[2]), intArrayOf(q[0], q[2], q[3]))) {
                for (i in t) out.addAll(v[i].toList())
            }
        }
        return Mesh(out.toFloatArray())
    }
}
