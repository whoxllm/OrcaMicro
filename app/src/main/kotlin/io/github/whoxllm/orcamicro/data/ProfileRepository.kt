package io.github.whoxllm.orcamicro.data

import android.content.res.AssetManager
import io.github.whoxllm.orcamicro.core.profiles.Catalog
import io.github.whoxllm.orcamicro.core.profiles.PresetResolver
import io.github.whoxllm.orcamicro.core.profiles.ProfileSource
import kotlinx.serialization.json.JsonObject
import java.io.FileNotFoundException

/** OrcaSlicer's printer, process and filament presets bundled in the APK's assets. */
class ProfileRepository(private val assets: AssetManager) {
    val catalog: Catalog by lazy {
        Catalog.parse(assets.open(CATALOG).bufferedReader().use { it.readText() })
    }

    private val resolver: PresetResolver by lazy {
        PresetResolver(AssetProfileSource(assets), catalog)
    }

    /** A standalone copy of a preset, with its whole inheritance chain merged in. */
    @Synchronized
    fun resolve(vendor: String, name: String): JsonObject = resolver.resolve(vendor, name)

    private class AssetProfileSource(private val assets: AssetManager) : ProfileSource {
        override fun read(path: String): String? = try {
            assets.open("$PROFILES/$path").bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    companion object {
        const val CATALOG = "orca/catalog.json"
        const val PROFILES = "orca/profiles"
        const val ENGINE_RESOURCES = "orca/engine"
    }
}
