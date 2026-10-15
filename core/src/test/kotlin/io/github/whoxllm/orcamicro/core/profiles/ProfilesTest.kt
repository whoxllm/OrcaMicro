package io.github.whoxllm.orcamicro.core.profiles

import io.github.whoxllm.orcamicro.core.settings.BedShape
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Resolver behaviour on a tiny hand-made profile tree. */
class PresetResolverTest {
    @TempDir
    lateinit var dir: File

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun setUp(): Catalog {
        write(
            "Acme.json",
            """{"name":"Acme","machine_list":[{"name":"acme_common","sub_path":"machine/common.json"},
                {"name":"Acme One 0.4 nozzle","sub_path":"machine/one.json"}],
                "process_list":[{"name":"acme_process","sub_path":"process/base.json"},
                {"name":"0.20mm Acme","sub_path":"process/020.json"},{"name":"0.30mm Draft","sub_path":"process/030.json"}],
                "filament_list":[{"name":"Acme PLA","sub_path":"filament/pla.json"}]}""",
        )
        write("Acme/machine/common.json", """{"type":"machine","name":"acme_common","instantiation":"false","printable_height":"200","nozzle_diameter":["0.4"]}""")
        write(
            "Acme/machine/one.json",
            """{"type":"machine","name":"Acme One 0.4 nozzle","inherits":"acme_common","instantiation":"true",
                "printer_model":"Acme One","printable_area":["0x0","200x0","200x200","0x200"],
                "default_print_profile":"0.20mm Acme","default_filament_profile":["Generic PLA"]}""",
        )
        write("Acme/process/base.json", """{"type":"process","name":"acme_process","instantiation":"false","layer_height":"0.2","wall_loops":"2"}""")
        write("Acme/process/020.json", """{"type":"process","name":"0.20mm Acme","inherits":"acme_process","instantiation":"true","compatible_printers":["Acme One 0.4 nozzle"]}""")
        write("Acme/process/030.json", """{"type":"process","name":"0.30mm Draft","inherits":"acme_process","instantiation":"true","layer_height":"0.3"}""")
        write("Acme/filament/pla.json", """{"type":"filament","name":"Acme PLA","inherits":"fdm_filament_pla","instantiation":"true","compatible_printers":["Acme One 0.4 nozzle"],"nozzle_temperature":["205"]}""")
        write(
            "OrcaFilamentLibrary.json",
            """{"name":"OrcaFilamentLibrary","filament_list":[{"name":"fdm_filament_pla","sub_path":"filament/base.json"},
                {"name":"Generic PLA","sub_path":"filament/generic.json"}]}""",
        )
        write("OrcaFilamentLibrary/filament/base.json", """{"type":"filament","name":"fdm_filament_pla","instantiation":"false","filament_id":"GFL99","nozzle_temperature":["220"],"filament_type":["PLA"]}""")
        write("OrcaFilamentLibrary/filament/generic.json", """{"type":"filament","name":"Generic PLA","inherits":"fdm_filament_pla","instantiation":"true","compatible_printers":[]}""")
        return CatalogBuilder(dir).build("test")
    }

    @Test
    fun `catalog lists printers with their compatible presets`() {
        val catalog = setUp()
        val printer = assertNotNull(catalog.printer("Acme", "Acme One 0.4 nozzle"))
        assertEquals("Acme One", printer.model)
        assertEquals("0.4", printer.nozzle)
        assertEquals("0.20mm Acme", printer.defaultProcess)
        assertEquals(PresetRef(Catalog.LIBRARY_VENDOR, "Generic PLA"), printer.defaultFilament)
        assertEquals(listOf("0.20mm Acme", "0.30mm Draft"), catalog.processesFor("Acme", printer))
        assertEquals(
            listOf(PresetRef("Acme", "Acme PLA"), PresetRef(Catalog.LIBRARY_VENDOR, "Generic PLA")),
            catalog.filamentsFor("Acme", printer),
        )
        assertEquals(listOf("Acme"), catalog.printerVendors.map { it.name })
    }

    @Test
    fun `resolving merges the inheritance chain across vendors`() {
        val catalog = setUp()
        val resolver = PresetResolver(DirectoryProfileSource(dir), catalog)
        val pla = resolver.resolve("Acme", "Acme PLA")
        assertEquals(listOf("205"), pla.strings("nozzle_temperature"))
        assertEquals("GFL99", pla.string("filament_id"))
        assertEquals("User", pla.string("from"))
        assertEquals("Acme PLA", pla.string("name"))
        assertFalse("inherits" in pla)
        assertFalse("instantiation" in pla)

        val machine = resolver.resolve("Acme", "Acme One 0.4 nozzle")
        assertEquals("200", machine.string("printable_height"))
        assertEquals(200f, BedShape.of(machine)?.width)
    }

    @Test
    fun `missing parents are reported`() {
        val catalog = setUp()
        write("Acme/process/030.json", """{"type":"process","name":"0.30mm Draft","inherits":"nope","instantiation":"true"}""")
        val resolver = PresetResolver(DirectoryProfileSource(dir), catalog)
        assertFailsWith<PresetNotFoundException> { resolver.resolve("Acme", "0.30mm Draft") }
    }
}

/** Checks against the real OrcaSlicer profiles when -PorcaResourcesDir is given (CI always does). */
class OrcaProfilesTest {
    private val profiles: File? = System.getProperty("orca.resources")?.let { File(it, "profiles") }?.takeIf { it.isDirectory }

    private val catalog: Catalog by lazy { CatalogBuilder(profiles!!).build("test") }

    @Test
    fun `every printer and its default presets resolve`() {
        assumeTrue(profiles != null, "OrcaSlicer profiles not available")
        val resolver = PresetResolver(DirectoryProfileSource(profiles!!), catalog)
        var printers = 0
        for (vendor in catalog.printerVendors) {
            for (printer in vendor.printers) {
                val machine = resolver.resolve(vendor.name, printer.name)
                assertEquals("machine", machine.string("type"), printer.name)
                assertNotNull(BedShape.of(machine), "${printer.name} has no printable_area")
                val process = assertNotNull(catalog.defaultProcessFor(vendor.name, printer), "${printer.name} has no default process")
                assertEquals("process", resolver.resolve(vendor.name, process).string("type"), process)
                printer.defaultFilament?.let { resolver.resolve(it.vendor, it.name) }
                assertTrue(catalog.processesFor(vendor.name, printer).isNotEmpty(), "${printer.name} has no process presets")
                printers++
            }
        }
        assertTrue(printers > 500, "expected hundreds of printers, found $printers")
    }

    @Test
    fun `common printers have the expected presets`() {
        assumeTrue(profiles != null, "OrcaSlicer profiles not available")
        val ender = assertNotNull(catalog.printer("Creality", "Creality Ender-3 V3 SE 0.4 nozzle"))
        assertContains(catalog.processesFor("Creality", ender), "0.20mm Standard @Creality Ender3V3SE 0.4")
        assertContains(catalog.filamentsFor("Creality", ender), PresetRef(Catalog.LIBRARY_VENDOR, "Generic PLA @System"))
        val marlin = assertNotNull(catalog.printer("Custom", "MyMarlin 0.4 nozzle"))
        assertEquals("0.20mm Standard @MyMarlin", catalog.defaultProcessFor("Custom", marlin))
        // Creality's default for this 0.2 mm nozzle has 0.20 mm layers, which OrcaSlicer rejects.
        val small = assertNotNull(catalog.printer("Creality", "Creality Ender-3 0.2 nozzle"))
        assertEquals("0.12mm Fine @Creality Ender3 0.2", catalog.defaultProcessFor("Creality", small))
        assertTrue(catalog.vendor("BBL")!!.printers.isEmpty(), "Bambu printers are left out")
        assertTrue(catalog.toJson().length < 4_000_000, "catalog should stay small enough to load quickly")
    }
}
