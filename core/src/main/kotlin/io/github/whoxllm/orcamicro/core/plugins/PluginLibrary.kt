package io.github.whoxllm.orcamicro.core.plugins

import io.github.whoxllm.orcamicro.core.slicing.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PluginState(
    val id: String,
    val enabled: Boolean = false,
    val builtIn: Boolean,
    val source: String,
    val entry: String,
    val permissions: PluginPermissions = PluginPermissions(),
    val approvedRequests: Set<String> = emptySet(),
    val configs: Map<String, String> = emptyMap(),
    val inspected: PluginInspectResult? = null,
)

/** One library per plugins folder. Its owner serializes operations, including suspending inspection. */
class PluginLibrary(private val folder: File, private val engine: PluginEngine) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val orderFile = folder.resolve("order.json")
    private val busy = mutableSetOf<String>()
    private val validId = Regex("[a-z0-9_]+")

    private fun directory(id: String): File {
        require(validId.matches(id))
        return folder.resolve(id)
    }

    private fun validateEntry(name: String) {
        require(name.endsWith(".py", ignoreCase = true) && name.length > 3 &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() })
    }

    private fun state(id: String): PluginState {
        val saved = json.decodeFromString<PluginState>(directory(id).resolve("plugin.json").readText())
        require(saved.id == id)
        validateEntry(saved.entry)
        return saved
    }

    private fun replace(file: File, bytes: ByteArray) {
        val temp = File.createTempFile(".write-", ".tmp", file.parentFile)
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun save(state: PluginState): PluginState {
        replace(directory(state.id).resolve("plugin.json"), json.encodeToString(state).toByteArray())
        return state
    }

    private fun writeOrder(ids: List<String>) = replace(orderFile, json.encodeToString(ids).toByteArray())

    fun list(): List<PluginState> {
        val ids = folder.listFiles().orEmpty().filter { validId.matches(it.name) && it.resolve("plugin.json").isFile }
            .map { it.name }.sorted()
        val order = if (orderFile.isFile) json.decodeFromString<List<String>>(orderFile.readText()) else emptyList()
        return (order.filter { it in ids } + ids).distinct().map(::state)
    }

    fun header(plugin: PluginState): PluginHeader? =
        PluginHeader.parse(directory(plugin.id).resolve(plugin.entry).readText())

    suspend fun importPlugin(name: String, bytes: ByteArray): PluginState {
        validateEntry(name)
        requireNotNull(PluginHeader.parse(bytes.decodeToString()))
        check(folder.isDirectory || folder.mkdirs())
        val base = name.dropLast(3).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_]"), "_")
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else base + "_" + it }
            .first { !directory(it).exists() && it !in builtInOrder }
        val order = list().map { it.id }
        val dir = directory(id)
        check(dir.mkdir())
        replace(dir.resolve(name), bytes)
        save(PluginState(id, builtIn = false, source = "py", entry = name))
        writeOrder(order + id)
        return inspect(id)
    }

    suspend fun inspect(id: String): PluginState {
        val plugin = state(id)
        val dir = directory(id).absoluteFile
        val file = dir.resolve(plugin.entry)
        val fingerprint = digest(file)
        val result = engine.inspect(PluginInspectRequest(engine.pythonHome(), file.path, dir.path))
        val inspected = result.takeUnless { !it.ok && it.error == "busy" }
        if (inspected == null) busy.add(id) else busy.remove(id)
        val saved = save(plugin.copy(inspected = inspected))
        if (inspected != null) replace(dir.resolve(".inspected"), fingerprint.toByteArray())
        return saved
    }

    fun needsTrustWarning(): Boolean = !folder.resolve(".trust-warning-seen").isFile

    fun acknowledgeTrustWarning() {
        check(folder.isDirectory || folder.mkdirs())
        replace(folder.resolve(".trust-warning-seen"), byteArrayOf())
    }

    /** An empty result means the plugin is on. Otherwise these grants need a decision. */
    suspend fun turnOn(id: String): Set<String> {
        val saved = state(id)
        val plugin = if (saved.inspected == null) inspect(id) else saved
        if (plugin.enabled) return emptySet()
        val inspected = requireNotNull(plugin.inspected)
        require(inspected.ok)
        val requested = inspected.requestedPermissions.toSet() - plugin.approvedRequests
        require(PluginPermission.ALL.containsAll(requested))
        if (requested.isEmpty()) save(plugin.copy(enabled = true))
        return requested
    }

    fun answer(id: String, requested: Set<String>, yes: Boolean) {
        if (!yes) return
        val plugin = state(id)
        val inspected = requireNotNull(plugin.inspected)
        require(inspected.ok && requested.isNotEmpty() && PluginPermission.ALL.containsAll(requested))
        require(requested == inspected.requestedPermissions.toSet() - plugin.approvedRequests)
        save(plugin.copy(enabled = true, permissions = PluginPermissions(true, plugin.permissions.allowed + requested),
            approvedRequests = plugin.approvedRequests + requested))
    }

    fun turnOff(id: String) = save(state(id).copy(enabled = false))

    fun setPermissionsEnabled(id: String, enabled: Boolean): PluginState {
        val plugin = state(id)
        return save(plugin.copy(permissions = plugin.permissions.copy(enabled = enabled)))
    }

    fun setPermission(id: String, name: String, enabled: Boolean): PluginState {
        require(name in PluginPermission.ALL)
        val plugin = state(id)
        val allowed = plugin.permissions.allowed
        return save(plugin.copy(permissions = plugin.permissions.copy(allowed = if (enabled) allowed + name else allowed - name)))
    }

    suspend fun installBuiltIns(files: Map<String, ByteArray>) {
        stageBuiltIns(files)
        for (id in builtInOrder) if ("$id.py" in files) inspect(id)
    }

    /** Install assets at startup without taking the engine away from an incoming slice. */
    fun stageBuiltIns(files: Map<String, ByteArray>) {
        check(folder.isDirectory || folder.mkdirs())
        val original = list().map { it.id }
        for (id in builtInOrder) {
            val name = "$id.py"
            val bytes = files[name] ?: continue
            val dir = directory(id)
            check(dir.isDirectory || dir.mkdir())
            val plugin = if (dir.resolve("plugin.json").isFile) state(id) else
                PluginState(id, builtIn = true, source = "builtin", entry = name)
            check(plugin.builtIn && plugin.entry == name)
            replace(dir.resolve(name), bytes)
            save(plugin.copy(inspected = null))
        }
        val added = builtInOrder.filter { it !in original && files.containsKey("$it.py") }
        writeOrder(added + original)
    }

    fun delete(id: String) {
        require(!state(id).builtIn)
        busy.remove(id)
        val remaining = list().map { it.id }.filter { it != id }
        val trash = folder.resolve(".deleted-" + java.util.UUID.randomUUID())
        Files.move(directory(id).toPath(), trash.toPath(), ATOMIC_MOVE)
        // Do not follow links in a plugin's writable data directory.
        Files.walk(trash.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        writeOrder(remaining)
    }

    fun reorder(ids: List<String>) {
        val installed = list().map { it.id }
        // Keep imports that arrived while the user was dragging at the end.
        writeOrder((ids.filter { it in installed } + installed).distinct())
    }

    fun saveConfig(id: String, capability: String, config: String): PluginState {
        val plugin = state(id)
        require(plugin.inspected?.capabilities.orEmpty().any { it.name == capability })
        require(Json.parseToJsonElement(config) is kotlinx.serialization.json.JsonObject)
        return save(plugin.copy(configs = plugin.configs + (capability to config)))
    }

    fun resetConfig(id: String, capability: String): PluginState {
        val plugin = state(id)
        return save(plugin.copy(configs = plugin.configs - capability))
    }

    fun sliceRequest(): PluginsRequest? {
        val items = list().filter { it.enabled }.flatMap { plugin ->
            val dir = directory(plugin.id).absoluteFile
            val inspected = plugin.inspected
            check(inspected?.ok == true) { plugin.entry + ": " + (inspected?.error ?: plugin.id) }
            inspected.capabilities.filter { it.type == "slicing-pipeline" }.map { capability ->
                PluginSliceItem(plugin.id, capability.name, dir.resolve(plugin.entry).path, dir.path,
                    plugin.configs[capability.name] ?: capability.defaultConfig,
                    PluginPermission.ALL.filter { it in plugin.permissions.effective() })
            }
        }
        return if (items.isEmpty()) null else PluginsRequest(engine.pythonHome(), items)
    }

    private fun digest(file: File): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return java.util.Base64.getEncoder().encodeToString(hash.digest())
    }

    suspend fun retryBusy() {
        for (id in busy.toList()) inspect(id)
    }

    suspend fun refreshChanged(enabledOnly: Boolean = false) {
        for (plugin in list()) {
            if (enabledOnly && !plugin.enabled) continue
            val dir = directory(plugin.id)
            val stamp = dir.resolve(".inspected")
            if (plugin.inspected == null || !stamp.isFile || stamp.readText() != digest(dir.resolve(plugin.entry))) {
                inspect(plugin.id)
            }
        }
    }

    companion object {
        val builtInOrder = listOf("orca_gcode_stamp", "orca_twistify", "orca_fuzzy_slices", "orca_inset")
    }
}
