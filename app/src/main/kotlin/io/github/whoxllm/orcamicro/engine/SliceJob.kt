package io.github.whoxllm.orcamicro.engine

import android.content.Context
import io.github.whoxllm.orcamicro.BuildConfig
import io.github.whoxllm.orcamicro.core.mesh.Mesh
import io.github.whoxllm.orcamicro.core.mesh.Placement
import io.github.whoxllm.orcamicro.core.mesh.StlWriter
import io.github.whoxllm.orcamicro.core.slicing.EngineRequest
import io.github.whoxllm.orcamicro.core.slicing.SliceInputs
import io.github.whoxllm.orcamicro.data.ProfileRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/** The files for one slice: model, three presets, output G-code and the engine's log. */
class SliceJob(val dir: File) {
    val model = File(dir, "model.stl")
    val machine = File(dir, "machine.json")
    val process = File(dir, "process.json")
    val filament = File(dir, "filament.json")
    val gcode = File(dir, "output.gcode")
    val log = File(dir, "engine.log")

    fun request(resourcesDir: File, placement: Placement) = EngineRequest(
        resourcesDir = resourcesDir.path,
        workDir = dir.path,
        modelPath = model.path,
        machinePath = machine.path,
        processPath = process.path,
        filamentPath = filament.path,
        outputPath = gcode.path,
        centerX = placement.centerX.toDouble(),
        centerY = placement.centerY.toDouble(),
    )

    /** The tail of the engine's log, for error reports. */
    fun logTail(maxChars: Int = 4000): String? =
        log.takeIf { it.isFile }?.readText()?.takeLast(maxChars)?.takeIf { it.isNotBlank() }

    companion object {
        private val json = Json { prettyPrint = true }

        /** Writes a fresh job into the app cache, removing older jobs. */
        fun create(context: Context, mesh: Mesh, placement: Placement, inputs: SliceInputs): SliceJob {
            val root = File(context.cacheDir, "slices")
            root.listFiles()?.forEach { it.deleteRecursively() }
            val job = SliceJob(File(root, System.currentTimeMillis().toString()).apply { mkdirs() })
            job.model.outputStream().buffered().use { StlWriter.write(placement.orient(mesh), it) }
            job.machine.writeText(json.encodeToString(JsonObject.serializer(), inputs.machine))
            job.process.writeText(json.encodeToString(JsonObject.serializer(), inputs.process))
            job.filament.writeText(json.encodeToString(JsonObject.serializer(), inputs.filament))
            return job
        }
    }
}

/** Copies the engine's runtime data (Orca's resources/info and resources/flush) out of the APK. */
object EngineResources {
    fun ensure(context: Context): File {
        val dir = File(context.filesDir, "engine-resources")
        val stamp = File(dir, ".version")
        val version = "${BuildConfig.VERSION_CODE}-${BuildConfig.ORCA_VERSION}"
        if (stamp.isFile && stamp.readText() == version) return dir
        dir.deleteRecursively()
        copyAssets(context, ProfileRepository.ENGINE_RESOURCES, dir)
        stamp.writeText(version)
        return dir
    }

    private fun copyAssets(context: Context, path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssets(context, "$path/$child", File(target, child))
    }
}
