package io.github.whoxllm.orcamicro.engine

/**
 * OrcaSlicer's slicing core (liborcamicro_engine.so, built from native/). Only the :engine
 * process loads it, so a crash inside the engine cannot take the app down.
 */
object NativeEngine {
    private val loadError: Throwable? = try {
        System.loadLibrary("orcamicro_engine")
        null
    } catch (t: Throwable) {
        t
    }

    val isAvailable: Boolean get() = loadError == null

    /** Why the library could not be loaded, if it could not. */
    val unavailableReason: String? get() = loadError?.let { "${it.javaClass.simpleName}: ${it.message}" }

    /** Runs a whole slice; blocks until done. See native/bridge/engine.hpp for the JSON fields. */
    fun slice(requestJson: String): String = nativeSlice(requestJson)

    /** {"percent":N,"text":"..."} for the slice in progress. */
    fun progress(): String = nativeProgress()

    fun version(): String = nativeVersion()

    private external fun nativeSlice(requestJson: String): String
    private external fun nativeProgress(): String
    private external fun nativeVersion(): String
}
