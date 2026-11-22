package io.github.whoxllm.orcamicro.core.slicing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Desktop OrcaSlicer's permission names, used in requests and in PluginPermissions. */
object PluginPermission {
    const val FS_READ = "fs_read"
    const val FS_READWRITE = "fs_readwrite"
    const val NETWORK_HTTP = "network_http"
    const val NETWORK_SOCKET = "network_socket"
    const val PROCESS = "process"
    const val THREADING = "threading"
    const val EVENTS = "events"
    val ALL = listOf(FS_READ, FS_READWRITE, NETWORK_HTTP, NETWORK_SOCKET, PROCESS, THREADING, EVENTS)
}

/** A plugin's permissions switch and the seven permissions under it. All off by default. */
@Serializable
data class PluginPermissions(
    /** The permissions switch. False: none of the seven apply. */
    val enabled: Boolean = false,
    /** Permission names (PluginPermission) switched on. Only used when enabled is true. */
    val allowed: Set<String> = emptySet(),
) {
    /** What the engine actually allows. */
    fun effective(): Set<String> = if (enabled) allowed else emptySet()
}

/** One enabled plugin capability for a slice. Field names match native/bridge/plugins.cpp. */
@Serializable
data class PluginSliceItem(
    @SerialName("plugin_id") val pluginId: String,
    /** Capability class name registered with orca.register_capability(). */
    val capability: String,
    /** Absolute path of the plugin's main file (not always plugin.py). */
    val path: String,
    /** The plugin's folder: on sys.path while loading, always readable and writable. */
    val dir: String,
    /** JSON text returned to the plugin by self.get_config(). */
    val config: String,
    /** PluginPermissions.effective(): permission names the audit hook allows for this item. */
    val allowed: List<String> = emptyList(),
)

/** Added to EngineRequest as `plugins`. Null or empty items: Python is never started. */
@Serializable
data class PluginsRequest(
    @SerialName("python_home") val pythonHome: String,
    /** In run order. */
    val items: List<PluginSliceItem>,
)

/** What one plugin's step said; returned in EngineResponse.pluginMessages. */
@Serializable
data class PluginMessage(
    @SerialName("plugin_id") val pluginId: String,
    val capability: String,
    val step: String,
    /** "ok", "skipped", "error" or "blocked" (a locked action was refused). */
    val status: String,
    /** For "blocked": the permission name that was missing, or "native" for native-code loading. */
    val permission: String? = null,
    val message: String = "",
)

/** Ask the engine to load a plugin and describe it. */
@Serializable
data class PluginInspectRequest(
    @SerialName("python_home") val pythonHome: String,
    /** Absolute path of the plugin's main file. */
    val path: String,
    val dir: String,
)

@Serializable
data class PluginCapabilityInfo(
    val name: String,
    /** "slicing-pipeline" for everything 1.3 runs; other types are listed but not runnable. */
    val type: String,
    @SerialName("default_config") val defaultConfig: String = "{}",
    /** Full HTML page from get_config_ui(), or null when the capability has no settings page. */
    @SerialName("config_ui") val configUi: String? = null,
)

@Serializable
data class PluginInspectResult(
    val ok: Boolean,
    val name: String = "",
    val description: String = "",
    val author: String = "",
    val version: String = "",
    val type: String = "",
    val capabilities: List<PluginCapabilityInfo> = emptyList(),
    /** Permission names the plugin asked for with orca.request_permissions() while loading. */
    @SerialName("requested_permissions") val requestedPermissions: List<String> = emptyList(),
    /** Set when ok is false: why the file can't be used (syntax error, wrong Python, no capability...). */
    val error: String? = null,
)

/** Implemented by the engine side (app/engine), used by the app side. */
interface PluginEngine {
    suspend fun inspect(request: PluginInspectRequest): PluginInspectResult
    /** Absolute path of the extracted CPython home; extracts it on first use. */
    fun pythonHome(): String
}
