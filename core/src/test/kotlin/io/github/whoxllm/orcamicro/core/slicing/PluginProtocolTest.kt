package io.github.whoxllm.orcamicro.core.slicing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginProtocolTest {
    @Test
    fun `engine request round-trips without plugins`() {
        val request = EngineRequest(
            resourcesDir = "/data/user/0/app/files/engine-resources",
            workDir = "/data/user/0/app/cache/slices/1",
            modelPath = "/data/user/0/app/cache/slices/1/model.stl",
            machinePath = "/data/user/0/app/cache/slices/1/machine.json",
            processPath = "/data/user/0/app/cache/slices/1/process.json",
            filamentPath = "/data/user/0/app/cache/slices/1/filament.json",
            outputPath = "/data/user/0/app/cache/slices/1/output.gcode",
            centerX = 110.0,
            centerY = 110.0,
        )
        val encoded = request.toJson()
        assertTrue("\"plugins\":null" in encoded)
        val decoded = EngineJson.decodeFromString(EngineRequest.serializer(), encoded)
        assertEquals(request, decoded)
        assertNull(decoded.plugins)
    }

    @Test
    fun `engine request round-trips with plugins in run order`() {
        val request = EngineRequest(
            resourcesDir = "/files/engine-resources",
            workDir = "/cache/slices/2",
            modelPath = "/cache/slices/2/model.stl",
            machinePath = "/cache/slices/2/machine.json",
            processPath = "/cache/slices/2/process.json",
            filamentPath = "/cache/slices/2/filament.json",
            outputPath = "/cache/slices/2/output.gcode",
            centerX = 100.0,
            centerY = 100.0,
            plugins = PluginsRequest(
                pythonHome = "/files/python",
                items = listOf(
                    PluginSliceItem(
                        pluginId = "orca_twistify",
                        capability = "Twistify",
                        path = "/files/plugins/orca_twistify/orca_twistify.py",
                        dir = "/files/plugins/orca_twistify",
                        config = """{"twist_deg_per_mm": 2.0}""",
                        allowed = listOf(PluginPermission.FS_READ),
                    ),
                    PluginSliceItem(
                        pluginId = "orca_gcode_stamp",
                        capability = "GCodeStamp",
                        path = "/files/plugins/orca_gcode_stamp/orca_gcode_stamp.py",
                        dir = "/files/plugins/orca_gcode_stamp",
                        config = "{}",
                        allowed = emptyList(),
                    ),
                ),
            ),
        )
        val encoded = request.toJson()
        assertTrue("\"python_home\":\"/files/python\"" in encoded)
        assertTrue("\"plugin_id\":\"orca_twistify\"" in encoded)
        val decoded = EngineJson.decodeFromString(EngineRequest.serializer(), encoded)
        assertEquals(request, decoded)
        val plugins = requireNotNull(decoded.plugins)
        assertEquals(2, plugins.items.size)
        assertEquals(listOf(PluginPermission.FS_READ), plugins.items[0].allowed)
    }

    @Test
    fun `engine response round-trips plugin messages and defaults`() {
        val minimal = EngineResponse.parse("""{"ok":true,"gcode_path":"/tmp/out.gcode"}""")
        assertTrue(minimal.ok)
        assertTrue(minimal.pluginMessages.isEmpty())

        val response = EngineResponse(
            ok = true,
            gcodePath = "/cache/slices/2/output.gcode",
            warnings = listOf("note"),
            pluginMessages = listOf(
                PluginMessage(
                    pluginId = "orca_gcode_stamp",
                    capability = "GCodeStamp",
                    step = "psGCodePostProcess",
                    status = "ok",
                    message = "stamped",
                ),
                PluginMessage(
                    pluginId = "custom_net",
                    capability = "NetCap",
                    step = "posSlice",
                    status = "blocked",
                    permission = PluginPermission.NETWORK_SOCKET,
                    message = "socket blocked",
                ),
                PluginMessage(
                    pluginId = "custom_native",
                    capability = "NativeCap",
                    step = "posSlice",
                    status = "blocked",
                    permission = "native",
                    message = "ctypes blocked",
                ),
            ),
        )
        val encoded = EngineJson.encodeToString(EngineResponse.serializer(), response)
        assertTrue("\"plugin_messages\"" in encoded)
        val decoded = EngineResponse.parse(encoded)
        assertEquals(response, decoded)
        assertEquals(PluginPermission.NETWORK_SOCKET, decoded.pluginMessages[1].permission)
        assertEquals("native", decoded.pluginMessages[2].permission)
    }

    @Test
    fun `inspect request and result round-trip`() {
        val inspectReq = PluginInspectRequest(
            pythonHome = "/files/python",
            path = "/files/plugins/orca_twistify/orca_twistify.py",
            dir = "/files/plugins/orca_twistify",
        )
        val reqJson = EngineJson.encodeToString(PluginInspectRequest.serializer(), inspectReq)
        assertTrue("\"python_home\"" in reqJson)
        assertEquals(inspectReq, EngineJson.decodeFromString(PluginInspectRequest.serializer(), reqJson))

        val inspectRes = PluginInspectResult(
            ok = true,
            name = "Twistify",
            description = "Twists layers",
            author = "SoftFever",
            version = "0.03",
            type = "slicing-pipeline",
            capabilities = listOf(
                PluginCapabilityInfo(
                    name = "Twistify",
                    type = "slicing-pipeline",
                    defaultConfig = """{"twist_deg_per_mm": 1.0}""",
                    configUi = "<html><body>UI</body></html>",
                ),
            ),
            requestedPermissions = listOf(PluginPermission.NETWORK_HTTP),
        )
        val resJson = EngineJson.encodeToString(PluginInspectResult.serializer(), inspectRes)
        assertTrue("\"default_config\"" in resJson)
        assertTrue("\"config_ui\"" in resJson)
        assertTrue("\"requested_permissions\"" in resJson)
        val decodedRes = EngineJson.decodeFromString(PluginInspectResult.serializer(), resJson)
        assertEquals(inspectRes, decodedRes)
        assertEquals("<html><body>UI</body></html>", requireNotNull(decodedRes.capabilities.single().configUi))

        val errorRes = PluginInspectResult(ok = false, error = "SyntaxError: invalid syntax")
        val errorJson = EngineJson.encodeToString(PluginInspectResult.serializer(), errorRes)
        val decodedErr = EngineJson.decodeFromString(PluginInspectResult.serializer(), errorJson)
        assertFalse(decodedErr.ok)
        assertEquals("SyntaxError: invalid syntax", requireNotNull(decodedErr.error))
    }

    @Test
    fun `permissions effective respects master switch`() {
        val off = PluginPermissions(enabled = false, allowed = setOf(PluginPermission.NETWORK_HTTP, PluginPermission.FS_READ))
        assertTrue(off.effective().isEmpty())

        val on = off.copy(enabled = true)
        assertEquals(setOf(PluginPermission.NETWORK_HTTP, PluginPermission.FS_READ), on.effective())
        assertEquals(7, PluginPermission.ALL.size)
    }
}
