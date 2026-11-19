package io.github.whoxllm.orcamicro.cad

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks to [CadService] in the :cad process. Automatically reconnects and restores
 * the active CAD session from its auto-saved recipe if :cad was killed.
 */
class CadClient(private val context: Context) {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var connectDeferred: CompletableDeferred<Messenger>? = null
    @Volatile private var lastSessionDir: String? = null
    @Volatile private var lastRecipePath: String? = null
    @Volatile private var needsRestore = false

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CadService.MSG_REPLY) {
            val json = message.data.getString(CadService.KEY_JSON)
                ?: """{"ok":false,"error":"Empty CAD reply"}"""
            pending.remove(message.arg1)?.complete(json)
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder)
            service = m
            connectDeferred?.complete(m)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            needsRestore = lastSessionDir != null
            val error = """{"ok":false,"error":"CAD process restarted"}"""
            pending.keys.toList().forEach { id -> pending.remove(id)?.complete(error) }
            connectDeferred?.completeExceptionally(IllegalStateException("CAD service disconnected"))
            connectDeferred = null
        }
    }

    private suspend fun ensureConnected(): Messenger = connectMutex.withLock {
        service?.let { return it }
        val deferred = CompletableDeferred<Messenger>()
        connectDeferred = deferred
        val intent = Intent(appContext, CadService::class.java)
        val ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!ok) {
            connectDeferred = null
            error("Could not start CAD service")
        }
        bound = true
        deferred.await()
    }

    private suspend fun sendRaw(requestJson: String): String {
        val messenger = ensureConnected()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        val msg = Message.obtain(null, CadService.MSG_COMMAND, id, 0).apply {
            data = Bundle().apply { putString(CadService.KEY_JSON, requestJson) }
            replyTo = incoming
        }
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            pending.remove(id)
            service = null
            bound = false
            needsRestore = lastSessionDir != null
            return """{"ok":false,"error":"CAD process unavailable"}"""
        }
        return deferred.await()
    }

    suspend fun command(request: JsonObject): CadDocumentState {
        val op = request["op"]?.jsonPrimitive?.contentOrNull
        if (needsRestore && op != "open" && op != "step_to_stl") {
            val dir = lastSessionDir
            val recipe = lastRecipePath
            if (dir != null) {
                needsRestore = false
                sendRaw(buildJsonObject {
                    put("op", "open")
                    put("session_dir", dir)
                    if (recipe != null) put("recipe_path", recipe)
                }.toString())
            }
        }
        val reply = sendRaw(request.toString())
        return CadDocumentState.parse(reply)
    }

    suspend fun open(sessionDir: File, recipeFile: File? = null): CadDocumentState {
        sessionDir.mkdirs()
        lastSessionDir = sessionDir.absolutePath
        lastRecipePath = recipeFile?.absolutePath
        needsRestore = false
        return command(buildJsonObject {
            put("op", "open")
            put("session_dir", sessionDir.absolutePath)
            if (recipeFile != null) put("recipe_path", recipeFile.absolutePath)
        })
    }

    suspend fun save(recipeFile: File): CadDocumentState {
        lastRecipePath = recipeFile.absolutePath
        return command(buildJsonObject {
            put("op", "save")
            put("recipe_path", recipeFile.absolutePath)
        })
    }

    suspend fun exportStl(stlFile: File): CadDocumentState = command(buildJsonObject {
        put("op", "export_stl")
        put("stl_path", stlFile.absolutePath)
    })

    suspend fun exportStep(stepFile: File): CadDocumentState = command(buildJsonObject {
        put("op", "export_step")
        put("step_path", stepFile.absolutePath)
    })

    suspend fun stepToStl(stepFile: File, stlFile: File, linearDeflection: Double = 0.01): CadDocumentState =
        command(buildJsonObject {
            put("op", "step_to_stl")
            put("step_path", stepFile.absolutePath)
            put("stl_path", stlFile.absolutePath)
            put("linear_deflection", linearDeflection)
        })

    suspend fun addSketch(
        plane: String = "XY",
        name: String? = null,
        shape: String = "rectangle",
        width: Double = 20.0,
        height: Double = 20.0,
        radius: Double = 10.0,
        entities: List<CadSketchEntity> = emptyList(),
        constraints: List<CadSketchConstraint> = emptyList(),
        bodyIndex: Int = 0,
        faceId: Int = -1,
        topoGeneration: Long? = null,
    ): CadDocumentState = command(buildJsonObject {
        put("op", "add_sketch")
        put("plane", plane)
        if (name != null) put("name", name)
        if (plane == "face") {
            put("body_index", bodyIndex)
            put("face_id", faceId)
            if (topoGeneration != null) put("topo_generation", topoGeneration)
        }
        if (entities.isNotEmpty()) {
            put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
            if (constraints.isNotEmpty()) {
                put("constraints", buildJsonArray { constraints.forEach { add(it.toJson()) } })
            }
        } else {
            put("shape", shape)
            put("width", width)
            put("height", height)
            put("radius", radius)
        }
    })

    suspend fun updateSketch(
        index: Int,
        entities: List<CadSketchEntity>,
        constraints: List<CadSketchConstraint> = emptyList(),
    ): CadDocumentState = command(buildJsonObject {
        put("op", "update_sketch")
        put("index", index)
        put("entities", buildJsonArray { entities.forEach { add(it.toJson()) } })
        put("constraints", buildJsonArray { constraints.forEach { add(it.toJson()) } })
    })

    suspend fun addExtrude(
        sketchRef: Int,
        distance: Double,
        mode: String = "new",
        symmetric: Boolean = false,
        flip: Boolean = false,
        targetBody: Int = -1,
        name: String? = null,
    ): CadDocumentState = command(buildJsonObject {
        put("op", "add_extrude")
        put("sketch_ref", sketchRef)
        put("distance", distance)
        put("mode", mode)
        put("symmetric", symmetric)
        put("flip", flip)
        put("target_body", targetBody)
        if (name != null) put("name", name)
    })

    suspend fun addFillet(
        radius: Double,
        edgeIds: List<Int> = emptyList(),
        faceGroup: String = "all",
        targetBody: Int = -1,
        topoGeneration: Long? = null,
        name: String? = null,
    ): CadDocumentState = command(buildJsonObject {
        put("op", "add_fillet")
        put("radius", radius)
        if (edgeIds.isNotEmpty()) {
            put("edge_ids", buildJsonArray { edgeIds.forEach { add(it) } })
            if (topoGeneration != null) put("topo_generation", topoGeneration)
        } else {
            put("face_group", faceGroup)
        }
        put("target_body", targetBody)
        if (name != null) put("name", name)
    })

    suspend fun addChamfer(
        distance: Double,
        edgeIds: List<Int> = emptyList(),
        faceGroup: String = "all",
        targetBody: Int = -1,
        topoGeneration: Long? = null,
        name: String? = null,
    ): CadDocumentState = command(buildJsonObject {
        put("op", "add_chamfer")
        put("distance", distance)
        if (edgeIds.isNotEmpty()) {
            put("edge_ids", buildJsonArray { edgeIds.forEach { add(it) } })
            if (topoGeneration != null) put("topo_generation", topoGeneration)
        } else {
            put("face_group", faceGroup)
        }
        put("target_body", targetBody)
        if (name != null) put("name", name)
    })

    suspend fun addHole(
        diameter: Double,
        depth: Double = 10.0,
        through: Boolean = true,
        x: Double = 0.0,
        y: Double = 0.0,
        plane: String = "XY",
        bodyIndex: Int = 0,
        faceId: Int = -1,
        topoGeneration: Long? = null,
        targetBody: Int = -1,
        name: String? = null,
    ): CadDocumentState = command(buildJsonObject {
        put("op", "add_hole")
        put("diameter", diameter)
        put("depth", depth)
        put("through", through)
        put("x", x)
        put("y", y)
        put("plane", plane)
        if (plane == "face") {
            put("body_index", bodyIndex)
            put("face_id", faceId)
            if (topoGeneration != null) put("topo_generation", topoGeneration)
        }
        put("target_body", targetBody)
        if (name != null) put("name", name)
    })

    suspend fun addBoolean(mode: String, targetBody: Int, toolBody: Int, keepTool: Boolean = false, name: String? = null): CadDocumentState =
        command(buildJsonObject {
            put("op", "add_boolean")
            put("mode", mode)
            put("target_body", targetBody)
            put("tool_body", toolBody)
            put("keep_tool", keepTool)
            if (name != null) put("name", name)
        })

    suspend fun importStep(stepFile: File, name: String? = null): CadDocumentState = command(buildJsonObject {
        put("op", "import_step")
        put("step_path", stepFile.absolutePath)
        if (name != null) put("name", name)
    })

    suspend fun removeFeature(index: Int): CadDocumentState = command(buildJsonObject {
        put("op", "remove_feature")
        put("index", index)
    })

    suspend fun setFeatureEnabled(index: Int, enabled: Boolean): CadDocumentState = command(buildJsonObject {
        put("op", "set_feature_enabled")
        put("index", index)
        put("enabled", enabled)
    })

    suspend fun moveFeature(index: Int, delta: Int): CadDocumentState = command(buildJsonObject {
        put("op", "move_feature")
        put("index", index)
        put("delta", delta)
    })

    suspend fun editFeature(index: Int, updates: JsonObjectBuilder.() -> Unit): CadDocumentState = command(buildJsonObject {
        put("op", "edit_feature")
        put("index", index)
        updates()
    })

    suspend fun undo(): CadDocumentState = command(buildJsonObject { put("op", "undo") })
    suspend fun redo(): CadDocumentState = command(buildJsonObject { put("op", "redo") })

    fun cancelRunning() {
        try {
            service?.send(Message.obtain(null, CadService.MSG_CANCEL))
        } catch (_: RemoteException) {
            // Already dead.
        }
    }

    fun close() {
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        service = null
    }
}
