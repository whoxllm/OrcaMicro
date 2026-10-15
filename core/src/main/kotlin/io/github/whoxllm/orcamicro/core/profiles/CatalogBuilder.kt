package io.github.whoxllm.orcamicro.core.profiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Builds the [Catalog] from an OrcaSlicer resources/profiles folder.
 *
 * Compatibility follows OrcaSlicer's preset lists: a process or filament preset suits the
 * printers it names in `compatible_printers`. Presets that name none suit every printer of their
 * vendor. `compatible_printers_condition` expressions are not evaluated; presets that rely on one
 * are offered only to printers that would otherwise have nothing to choose from.
 */
class CatalogBuilder(
    private val profilesDir: File,
    /** Vendors whose printers are left out. Bambu Lab printers need G-code features this engine does not produce. */
    private val excludedPrinterVendors: Set<String> = setOf("BBL"),
) {
    private val source = DirectoryProfileSource(profilesDir)

    fun build(orcaVersion: String): Catalog {
        val indexes = vendorIndexes()
        val presetsOnly = Catalog(orcaVersion, indexes.map { (name, index) -> CatalogVendor(name, index.presetPaths()) })
        val resolver = PresetResolver(source, presetsOnly)
        val libraryPresets = indexes.firstOrNull { it.first == Catalog.LIBRARY_VENDOR }?.second?.presetPaths()?.keys.orEmpty()
        val vendors = indexes.map { (name, index) ->
            if (name in excludedPrinterVendors) {
                CatalogVendor(name, index.presetPaths())
            } else {
                buildVendor(name, index, resolver, libraryPresets)
            }
        }
        return Catalog(orcaVersion, vendors)
    }

    private fun vendorIndexes(): List<Pair<String, VendorIndex>> {
        val files = profilesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        return files.sortedBy { it.name.lowercase() }.map { file ->
            file.nameWithoutExtension to PresetResolver.json.decodeFromString(VendorIndex.serializer(), file.readText())
        }
    }

    private fun buildVendor(vendor: String, index: VendorIndex, resolver: PresetResolver, libraryPresets: Set<String>): CatalogVendor {
        val ownPresets = index.presetPaths()
        fun ref(name: String): PresetRef? = when (name) {
            in ownPresets -> PresetRef(vendor, name)
            in libraryPresets -> PresetRef(Catalog.LIBRARY_VENDOR, name)
            else -> null
        }
        val printers = index.machineList.mapNotNull { entry ->
            val raw = rawPreset(vendor, entry) ?: return@mapNotNull null
            if (raw.string("instantiation") != "true") return@mapNotNull null
            val flat = resolver.resolve(vendor, entry.name)
            if (flat.string("printer_technology") == "SLA") return@mapNotNull null
            CatalogPrinter(
                name = entry.name,
                model = flat.string("printer_model").orEmpty(),
                nozzle = flat.string("printer_variant") ?: flat.first("nozzle_diameter").orEmpty(),
                defaultProcess = flat.string("default_print_profile")?.takeIf { it.isNotEmpty() },
                defaultFilament = flat.first("default_filament_profile")?.takeIf { it.isNotEmpty() }?.let { ref(it) },
            )
        }
        val printerNames = printers.map { it.name }.toSet()

        val processes = compatibility(vendor, index.processList, resolver, printerNames)
        val filaments = compatibility(vendor, index.filamentList, resolver, printerNames)

        // A few printers have no process preset of their own in OrcaSlicer's profiles. Borrow from
        // the vendor's printers with the same nozzle, then the same model, then any of the vendor's.
        fun borrowed(printer: CatalogPrinter): List<String> {
            if (processes.common.isNotEmpty()) return emptyList()
            if (processes.conditional.isNotEmpty()) return processes.conditional
            val sameNozzle = printers.filter { it.nozzle == printer.nozzle }.flatMap { processes.byPrinter[it.name].orEmpty() }
            if (sameNozzle.isNotEmpty()) return sameNozzle.distinct()
            val sameModel = printers.filter { it.model == printer.model }.flatMap { processes.byPrinter[it.name].orEmpty() }
            if (sameModel.isNotEmpty()) return sameModel.distinct()
            return processes.byPrinter.values.flatten().distinct()
        }

        val withLists = printers.map { printer ->
            val ownProcesses = processes.byPrinter[printer.name].orEmpty()
            printer.copy(
                processes = ownProcesses.ifEmpty { borrowed(printer) }.sorted(),
                filaments = filaments.byPrinter[printer.name].orEmpty().sorted(),
                defaultProcess = printer.defaultProcess?.takeIf { it in ownPresets },
            )
        }
        return CatalogVendor(
            name = vendor,
            presets = ownPresets,
            printers = withLists.sortedBy { it.name.lowercase() },
            commonProcesses = processes.common.sorted(),
            commonFilaments = (if (vendor == Catalog.LIBRARY_VENDOR) filaments.common + filaments.conditional else filaments.common).sorted(),
        )
    }

    private class Compatibility(
        val byPrinter: Map<String, List<String>>,
        val common: List<String>,
        val conditional: List<String>,
    )

    private fun compatibility(vendor: String, entries: List<VendorIndex.Entry>, resolver: PresetResolver, printers: Set<String>): Compatibility {
        val byPrinter = HashMap<String, MutableList<String>>()
        val common = ArrayList<String>()
        val conditional = ArrayList<String>()
        for (entry in entries) {
            val raw = rawPreset(vendor, entry) ?: continue
            if (raw.string("instantiation") != "true") continue
            val flat = resolver.resolve(vendor, entry.name)
            val compatible = flat.strings("compatible_printers").filter { it.isNotEmpty() }
            when {
                compatible.isNotEmpty() -> compatible.filter { it in printers }.forEach { byPrinter.getOrPut(it) { ArrayList() }.add(entry.name) }
                flat.string("compatible_printers_condition").isNullOrEmpty() -> common.add(entry.name)
                else -> conditional.add(entry.name)
            }
        }
        return Compatibility(byPrinter, common, conditional)
    }

    private fun rawPreset(vendor: String, entry: VendorIndex.Entry): JsonObject? =
        source.read("$vendor/${entry.subPath}")?.let { PresetResolver.json.parseToJsonElement(it) as? JsonObject }
}

/** A vendor's index file, e.g. resources/profiles/Creality.json. */
@Serializable
internal data class VendorIndex(
    val name: String = "",
    val version: String = "",
    @SerialName("machine_model_list") val machineModelList: List<Entry> = emptyList(),
    @SerialName("machine_list") val machineList: List<Entry> = emptyList(),
    @SerialName("process_list") val processList: List<Entry> = emptyList(),
    @SerialName("filament_list") val filamentList: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(val name: String, @SerialName("sub_path") val subPath: String)

    fun presetPaths(): Map<String, String> =
        (machineList + processList + filamentList).associate { it.name to it.subPath }
}

/** Command line entry point used by the app build: `CatalogTool <profiles dir> <catalog.json> <orca version>`. */
object CatalogTool {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "usage: CatalogTool <profiles dir> <output catalog.json> <orca version>" }
        val catalog = CatalogBuilder(File(args[0])).build(args[2])
        File(args[1]).apply { parentFile?.mkdirs() }.writeText(catalog.toJson())
        val printers = catalog.vendors.sumOf { it.printers.size }
        println("Catalog: ${catalog.printerVendors.size} vendors, $printers printers -> ${args[1]}")
    }
}
