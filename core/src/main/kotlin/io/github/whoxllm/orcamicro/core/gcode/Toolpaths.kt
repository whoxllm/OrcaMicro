package io.github.whoxllm.orcamicro.core.gcode

/**
 * What an extrusion is for, as OrcaSlicer labels it in `;TYPE:` comments. Colours follow
 * OrcaSlicer's preview so the layer view looks familiar.
 */
enum class Feature(val label: String, val rgb: Int) {
    OUTER_WALL("Outer wall", 0xFF7D38),
    INNER_WALL("Inner wall", 0xFFE64D),
    OVERHANG_WALL("Overhang wall", 0x1F1FFF),
    SPARSE_INFILL("Sparse infill", 0xB03029),
    SOLID_INFILL("Internal solid infill", 0x9654CC),
    TOP_SURFACE("Top surface", 0xF04040),
    BOTTOM_SURFACE("Bottom surface", 0x665CC7),
    BRIDGE("Bridge", 0x4D80BA),
    INTERNAL_BRIDGE("Internal bridge", 0x4D80BA),
    GAP_FILL("Gap infill", 0xFFFFFF),
    IRONING("Ironing", 0xFF8C69),
    SKIRT("Skirt", 0x00876E),
    BRIM("Brim", 0x003B6E),
    SUPPORT("Support", 0x00FF00),
    SUPPORT_INTERFACE("Support interface", 0x008000),
    SUPPORT_TRANSITION("Support transition", 0x004000),
    PRIME_TOWER("Prime tower", 0xB3E3AB),
    CUSTOM("Custom", 0x5ED194),
    OTHER("Other", 0xE6B3B3);

    companion object {
        private val byLabel: Map<String, Feature> = buildMap {
            for (f in Feature.entries) put(f.label.lowercase(), f)
            // PrusaSlicer-style names, in case a start G-code or other tool emits them.
            put("perimeter", INNER_WALL)
            put("external perimeter", OUTER_WALL)
            put("overhang perimeter", OVERHANG_WALL)
            put("internal infill", SPARSE_INFILL)
            put("solid infill", SOLID_INFILL)
            put("top solid infill", TOP_SURFACE)
            put("bridge infill", BRIDGE)
            put("gap fill", GAP_FILL)
            put("skirt/brim", SKIRT)
            put("support material", SUPPORT)
            put("support material interface", SUPPORT_INTERFACE)
            put("wipe tower", PRIME_TOWER)
        }

        fun fromLabel(label: String): Feature = byLabel[label.trim().lowercase()] ?: OTHER
    }
}

/**
 * The moves of one layer. Segments are stored flat as x0,y0,z0,x1,y1,z1 so the renderer can
 * upload them directly; [features] holds one [Feature] ordinal per extrusion segment.
 */
class ToolpathLayer(
    val z: Float,
    val height: Float,
    val extrusions: FloatArray,
    val features: ByteArray,
    val travels: FloatArray,
) {
    val extrusionCount: Int get() = features.size
    val travelCount: Int get() = travels.size / FLOATS_PER_SEGMENT

    companion object {
        const val FLOATS_PER_SEGMENT = 6
    }
}

data class GcodeStats(
    /** OrcaSlicer's estimate for the whole print, in seconds. */
    val printSeconds: Long? = null,
    val filamentMillimetres: Double? = null,
    val filamentGrams: Double? = null,
)

class Toolpaths(
    val layers: List<ToolpathLayer>,
    val stats: GcodeStats,
    /** Features that occur anywhere in the print, for the legend. */
    val features: Set<Feature>,
) {
    val segmentCount: Int get() = layers.sumOf { it.extrusionCount }
}
