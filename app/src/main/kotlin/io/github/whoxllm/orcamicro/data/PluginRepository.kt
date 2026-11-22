package io.github.whoxllm.orcamicro.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.whoxllm.orcamicro.BuildConfig
import io.github.whoxllm.orcamicro.core.plugins.*
import io.github.whoxllm.orcamicro.core.slicing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class InstalledPlugin(val state: PluginState, val header: PluginHeader?) {
    val name get() = state.inspected?.name.orEmpty().ifBlank { header?.name.orEmpty().ifBlank { state.entry } }
    val description get() = state.inspected?.description.orEmpty().ifBlank { header?.description.orEmpty() }
    val version get() = state.inspected?.version.orEmpty().ifBlank { header?.version.orEmpty() }
    val author get() = state.inspected?.author.orEmpty().ifBlank { header?.author.orEmpty() }
}

class PluginRepository(private val context: Context, engine: PluginEngine) {
    private val library = PluginLibrary(context.filesDir.resolve("plugins"), engine)
    private val mutex = Mutex()
    private val items = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins = items.asStateFlow()
    private var initialized = false

    suspend fun <T> edit(action: suspend PluginLibrary.() -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) {
                val preferences = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
                if (preferences.getInt("assetsVersion", -1) != BuildConfig.VERSION_CODE) {
                    val names = context.assets.list("plugins").orEmpty().toSet()
                    val assets = PluginLibrary.builtInOrder.map { "$it.py" }.filter { it in names }
                        .associateWith { name -> context.assets.open("plugins/$name").use { it.readBytes() } }
                    if (assets.isNotEmpty()) library.stageBuiltIns(assets)
                    if (assets.size == PluginLibrary.builtInOrder.size) {
                        preferences.edit().putInt("assetsVersion", BuildConfig.VERSION_CODE).apply()
                    }
                }
                initialized = true
            }
            try {
                library.action()
            } finally {
                items.value = library.list().map { InstalledPlugin(it, library.header(it)) }
            }
        }
    }

    suspend fun importFile(uri: Uri) = edit {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        importPlugin(name, bytes)
    }
}
