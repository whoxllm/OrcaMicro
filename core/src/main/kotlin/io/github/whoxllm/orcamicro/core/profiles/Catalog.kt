package io.github.whoxllm.orcamicro.core.profiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Index of OrcaSlicer's bundled presets, generated at build time by [CatalogBuilder] and shipped
 * with the app as catalog.json. It lists every printer a user can pick and which process and
 * filament presets go with it, plus where each preset file lives so [PresetResolver] can follow
 * `inherits` chains without scanning thousands of files on the phone.
 */
@Serializable
data class Catalog(
    val orcaVersion: String,
    val vendors: List<CatalogVendor>,
) {
    fun vendor(name: String): CatalogVendor? = vendors.firstOrNull { it.name == name }

    /** Vendors that have printers, in display order. */
    val printerVendors: List<CatalogVendor> get() = vendors.filter { it.printers.isNotEmpty() }

    fun printer(vendor: String, name: String): CatalogPrinter? = vendor(vendor)?.printers?.firstOrNull { it.name == name }

    /** Process presets a user can pick for [printer]: its own, then the vendor's common ones. */
    fun processesFor(vendor: String, printer: CatalogPrinter): List<String> {
        val v = vendor(vendor) ?: return emptyList()
        return (printer.processes + v.commonProcesses).distinct()
    }

    /**
     * The process to start [printer] with: its own default when the profile names one, otherwise
     * a standard-quality preset rather than the alphabetically first (often the slowest, 0.08 mm).
     * Presets with layers as thick as the nozzle are passed over: a few vendors make one their
     * small nozzles' default, and OrcaSlicer refuses to slice it.
     */
    fun defaultProcessFor(vendor: String, printer: CatalogPrinter): String? {
        val processes = processesFor(vendor, printer)
        val nozzle = printer.nozzle.toDoubleOrNull()
        val usable = processes.filter { name ->
            val layer = layerHeight(name)
            nozzle == null || layer == null || layer < nozzle
        }.ifEmpty { processes }
        val target = (nozzle ?: 0.4) / 2
        return printer.defaultProcess?.takeIf { it in usable }
            ?: usable.firstOrNull { "Standard" in it }
            ?: usable.mapNotNull { name -> layerHeight(name)?.let { name to it } }
                .minByOrNull { (_, layer) -> abs(layer - target) }?.first
            ?: usable.firstOrNull()
    }

    /** Filament presets for [printer]: its vendor's own first, then OrcaSlicer's generic library. */
    fun filamentsFor(vendor: String, printer: CatalogPrinter): List<PresetRef> {
        val v = vendor(vendor) ?: return emptyList()
        val own = (printer.filaments + v.commonFilaments).distinct().map { PresetRef(vendor, it) }
        val library = vendor(LIBRARY_VENDOR)?.commonFilaments.orEmpty().map { PresetRef(LIBRARY_VENDOR, it) }
        return own + library
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /** OrcaSlicer's vendor-neutral filament library, available to every printer. */
        const val LIBRARY_VENDOR = "OrcaFilamentLibrary"

        private val json = Json { ignoreUnknownKeys = true }

        private val layerHeightPrefix = Regex("""^(\d*\.\d+)\s*mm""")

        /** Layer height from a process name such as "0.20mm Standard @..." or ".20mm Standard @...". */
        internal fun layerHeight(processName: String): Double? =
            layerHeightPrefix.find(processName)?.groupValues?.get(1)?.toDoubleOrNull()

        fun parse(text: String): Catalog = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class CatalogVendor(
    val name: String,
    /** Every preset of this vendor (machine, process, filament, abstract or not) to its file, relative to the vendor folder. */
    val presets: Map<String, String>,
    val printers: List<CatalogPrinter> = emptyList(),
    /** Instantiable processes that do not name compatible printers, so suit all of this vendor's printers. */
    val commonProcesses: List<String> = emptyList(),
    /** Instantiable filaments that do not name compatible printers. */
    val commonFilaments: List<String> = emptyList(),
)

@Serializable
data class CatalogPrinter(
    /** Machine preset name, e.g. "Creality Ender-3 V3 SE 0.4 nozzle". */
    val name: String,
    /** Printer model, e.g. "Creality Ender-3 V3 SE". */
    val model: String,
    /** Nozzle diameter as Orca writes it, e.g. "0.4". */
    val nozzle: String,
    /** Process presets that list this printer as compatible. */
    val processes: List<String> = emptyList(),
    /** Filament presets of the same vendor that list this printer as compatible. */
    val filaments: List<String> = emptyList(),
    val defaultProcess: String? = null,
    val defaultFilament: PresetRef? = null,
)

@Serializable
data class PresetRef(val vendor: String, val name: String)
