package io.github.whoxllm.orcamicro.core.settings

import io.github.whoxllm.orcamicro.core.profiles.PresetType

enum class SettingKind { FLOAT, INT, PERCENT, BOOL, CHOICE, TEXT, GCODE }

data class Choice(val value: String, val label: String)

/**
 * One user-editable OrcaSlicer option. [key] is the Orca option name, or one of the
 * [PseudoKeys] that map onto several Orca options.
 */
data class SettingSpec(
    val key: String,
    val type: PresetType,
    val section: String,
    val label: String,
    val kind: SettingKind,
    val unit: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val choices: List<Choice> = emptyList(),
    /** Orca stores the option as a per-extruder list (coFloats, coInts, coStrings). */
    val perExtruder: Boolean = false,
)

/** Settings that do not exist under one name in OrcaSlicer. */
object PseudoKeys {
    /** Width and depth of the rectangular bed; both rewrite `printable_area`. */
    const val BED_WIDTH = "bed_width"
    const val BED_DEPTH = "bed_depth"

    /** Bed type; written to the process preset as `curr_bed_type`, which picks the bed temperature set. */
    const val BED_TYPE = "bed_type"

    /** Bed temperature for the selected bed type (`hot_plate_temp`, `textured_plate_temp`, ...). */
    const val BED_TEMPERATURE = "bed_temperature"
    const val BED_TEMPERATURE_FIRST_LAYER = "bed_temperature_first_layer"
}

/** OrcaSlicer's bed types and the filament options holding their temperatures. */
enum class BedType(val orcaName: String, val label: String, val temperatureKey: String) {
    COOL("Cool Plate", "Smooth cool plate", "cool_plate_temp"),
    ENGINEERING("Engineering Plate", "Engineering plate", "eng_plate_temp"),
    HIGH_TEMP("High Temp Plate", "Smooth PEI / glass (high temp)", "hot_plate_temp"),
    TEXTURED_PEI("Textured PEI Plate", "Textured PEI", "textured_plate_temp"),
    TEXTURED_COOL("Textured Cool Plate", "Textured cool plate", "textured_cool_plate_temp"),
    SUPERTACK("Supertack Plate", "Cool plate (SuperTack)", "supertack_plate_temp");

    val firstLayerTemperatureKey: String get() = "${temperatureKey}_initial_layer"

    companion object {
        /** Parses a printer's `default_bed_type`, which Orca stores as a name or as its enum number (1 = Cool Plate). */
        fun fromOrca(value: String?): BedType? {
            if (value.isNullOrBlank()) return null
            entries.firstOrNull { it.orcaName == value }?.let { return it }
            return value.toIntOrNull()?.let { entries.getOrNull(it - 1) }
        }
    }
}

/** The options OrcaMicro lets users change, grouped the way the settings screen shows them. */
object PrintSettings {
    private fun choices(vararg pairs: Pair<String, String>) = pairs.map { Choice(it.first, it.second) }

    val machine: List<SettingSpec> = listOf(
        SettingSpec(PseudoKeys.BED_WIDTH, PresetType.MACHINE, "Printer", "Bed width (X)", SettingKind.FLOAT, "mm", 10.0, 2000.0),
        SettingSpec(PseudoKeys.BED_DEPTH, PresetType.MACHINE, "Printer", "Bed depth (Y)", SettingKind.FLOAT, "mm", 10.0, 2000.0),
        SettingSpec("printable_height", PresetType.MACHINE, "Printer", "Max print height (Z)", SettingKind.FLOAT, "mm", 10.0, 2000.0),
        SettingSpec("nozzle_diameter", PresetType.MACHINE, "Printer", "Nozzle diameter", SettingKind.FLOAT, "mm", 0.1, 2.0, perExtruder = true),
        SettingSpec(
            "gcode_flavor", PresetType.MACHINE, "Printer", "G-code flavour", SettingKind.CHOICE,
            choices = choices(
                "marlin2" to "Marlin 2", "marlin" to "Marlin (legacy)", "klipper" to "Klipper",
                "reprapfirmware" to "RepRapFirmware", "repetier" to "Repetier", "smoothie" to "Smoothie",
            ),
        ),
        SettingSpec(
            PseudoKeys.BED_TYPE, PresetType.MACHINE, "Printer", "Bed surface", SettingKind.CHOICE,
            choices = BedType.entries.map { Choice(it.orcaName, it.label) },
        ),
        SettingSpec("retraction_length", PresetType.MACHINE, "Retraction", "Retraction length", SettingKind.FLOAT, "mm", 0.0, 20.0, perExtruder = true),
        SettingSpec("retraction_speed", PresetType.MACHINE, "Retraction", "Retraction speed", SettingKind.FLOAT, "mm/s", 1.0, 200.0, perExtruder = true),
        SettingSpec("z_hop", PresetType.MACHINE, "Retraction", "Z hop", SettingKind.FLOAT, "mm", 0.0, 5.0, perExtruder = true),
        SettingSpec("machine_start_gcode", PresetType.MACHINE, "Custom G-code", "Start G-code", SettingKind.GCODE),
        SettingSpec("machine_end_gcode", PresetType.MACHINE, "Custom G-code", "End G-code", SettingKind.GCODE),
    )

    val process: List<SettingSpec> = listOf(
        SettingSpec("layer_height", PresetType.PROCESS, "Quality", "Layer height", SettingKind.FLOAT, "mm", 0.02, 1.0),
        SettingSpec("initial_layer_print_height", PresetType.PROCESS, "Quality", "First layer height", SettingKind.FLOAT, "mm", 0.02, 1.0),
        SettingSpec(
            "seam_position", PresetType.PROCESS, "Quality", "Seam position", SettingKind.CHOICE,
            choices = choices("aligned" to "Aligned", "nearest" to "Nearest", "back" to "Back", "random" to "Random"),
        ),
        SettingSpec("spiral_mode", PresetType.PROCESS, "Quality", "Spiral vase", SettingKind.BOOL),
        SettingSpec("wall_loops", PresetType.PROCESS, "Walls and surfaces", "Wall loops", SettingKind.INT, "", 0.0, 50.0),
        SettingSpec("top_shell_layers", PresetType.PROCESS, "Walls and surfaces", "Top layers", SettingKind.INT, "", 0.0, 100.0),
        SettingSpec("bottom_shell_layers", PresetType.PROCESS, "Walls and surfaces", "Bottom layers", SettingKind.INT, "", 0.0, 100.0),
        SettingSpec("sparse_infill_density", PresetType.PROCESS, "Infill", "Infill density", SettingKind.PERCENT, "%", 0.0, 100.0),
        SettingSpec(
            "sparse_infill_pattern", PresetType.PROCESS, "Infill", "Infill pattern", SettingKind.CHOICE,
            choices = choices(
                "grid" to "Grid", "gyroid" to "Gyroid", "cubic" to "Cubic", "adaptivecubic" to "Adaptive cubic",
                "crosshatch" to "Cross hatch", "honeycomb" to "Honeycomb", "3dhoneycomb" to "3D honeycomb",
                "triangles" to "Triangles", "tri-hexagon" to "Tri-hexagon", "line" to "Line",
                "rectilinear" to "Rectilinear", "lightning" to "Lightning", "concentric" to "Concentric",
            ),
        ),
        SettingSpec("enable_support", PresetType.PROCESS, "Support", "Generate supports", SettingKind.BOOL),
        SettingSpec(
            "support_type", PresetType.PROCESS, "Support", "Support type", SettingKind.CHOICE,
            choices = choices("normal(auto)" to "Normal", "tree(auto)" to "Tree"),
        ),
        SettingSpec("support_threshold_angle", PresetType.PROCESS, "Support", "Overhang threshold", SettingKind.INT, "°", 0.0, 90.0),
        SettingSpec("support_on_build_plate_only", PresetType.PROCESS, "Support", "On build plate only", SettingKind.BOOL),
        SettingSpec(
            "brim_type", PresetType.PROCESS, "Adhesion", "Brim", SettingKind.CHOICE,
            choices = choices(
                "auto_brim" to "Auto", "no_brim" to "None", "outer_only" to "Outer only",
                "outer_and_inner" to "Outer and inner", "brim_ears" to "Mouse ears",
            ),
        ),
        SettingSpec("brim_width", PresetType.PROCESS, "Adhesion", "Brim width", SettingKind.FLOAT, "mm", 0.0, 100.0),
        SettingSpec("skirt_loops", PresetType.PROCESS, "Adhesion", "Skirt loops", SettingKind.INT, "", 0.0, 20.0),
        SettingSpec("initial_layer_speed", PresetType.PROCESS, "Speed", "First layer", SettingKind.FLOAT, "mm/s", 1.0, 1000.0),
        SettingSpec("outer_wall_speed", PresetType.PROCESS, "Speed", "Outer wall", SettingKind.FLOAT, "mm/s", 1.0, 1000.0),
        SettingSpec("inner_wall_speed", PresetType.PROCESS, "Speed", "Inner wall", SettingKind.FLOAT, "mm/s", 1.0, 1000.0),
        SettingSpec("sparse_infill_speed", PresetType.PROCESS, "Speed", "Sparse infill", SettingKind.FLOAT, "mm/s", 1.0, 1000.0),
        SettingSpec("top_surface_speed", PresetType.PROCESS, "Speed", "Top surface", SettingKind.FLOAT, "mm/s", 1.0, 1000.0),
        SettingSpec("travel_speed", PresetType.PROCESS, "Speed", "Travel", SettingKind.FLOAT, "mm/s", 1.0, 2000.0),
    )

    val filament: List<SettingSpec> = listOf(
        SettingSpec("nozzle_temperature", PresetType.FILAMENT, "Temperature", "Nozzle", SettingKind.INT, "°C", 0.0, 500.0, perExtruder = true),
        SettingSpec("nozzle_temperature_initial_layer", PresetType.FILAMENT, "Temperature", "Nozzle, first layer", SettingKind.INT, "°C", 0.0, 500.0, perExtruder = true),
        SettingSpec(PseudoKeys.BED_TEMPERATURE, PresetType.FILAMENT, "Temperature", "Bed", SettingKind.INT, "°C", 0.0, 200.0, perExtruder = true),
        SettingSpec(PseudoKeys.BED_TEMPERATURE_FIRST_LAYER, PresetType.FILAMENT, "Temperature", "Bed, first layer", SettingKind.INT, "°C", 0.0, 200.0, perExtruder = true),
        SettingSpec("filament_diameter", PresetType.FILAMENT, "Filament", "Diameter", SettingKind.FLOAT, "mm", 1.0, 3.5, perExtruder = true),
        SettingSpec("filament_flow_ratio", PresetType.FILAMENT, "Filament", "Flow ratio", SettingKind.FLOAT, "", 0.5, 1.5, perExtruder = true),
        SettingSpec("fan_min_speed", PresetType.FILAMENT, "Cooling", "Fan minimum", SettingKind.INT, "%", 0.0, 100.0, perExtruder = true),
        SettingSpec("fan_max_speed", PresetType.FILAMENT, "Cooling", "Fan maximum", SettingKind.INT, "%", 0.0, 100.0, perExtruder = true),
    )

    val all: List<SettingSpec> = machine + process + filament

    fun forType(type: PresetType): List<SettingSpec> = all.filter { it.type == type }
}
