package io.github.whoxllm.orcamicro.core.settings

import kotlinx.serialization.Serializable

@Serializable
enum class PlateTexture { SMOOTH, PEI, CARBON, NONE }

@Serializable
data class RenderOptions(
    val ssao: Boolean = false,
    val shadows: Boolean = false,
    val grid: Boolean = true,
    val volume: Boolean = true,
    val texture: PlateTexture = PlateTexture.SMOOTH,
    val brightness: Float = 1f,
    val lineWidth: Float = 1.5f,
    val featureColors: Boolean = true,
    val background: Int = 0x454545,
    val freeCamera: Boolean = false,
    val resolution: Float = 1f,
)
