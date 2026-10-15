package io.github.whoxllm.orcamicro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.profiles.Catalog
import io.github.whoxllm.orcamicro.core.profiles.first
import io.github.whoxllm.orcamicro.core.settings.formatNumber

private enum class Picker { VENDOR, PRINTER, PROCESS, FILAMENT }

private data class PickItem(val key: String, val title: String, val subtitle: String?, val selected: Boolean)

@Composable
fun PresetsScreen(state: UiState, viewModel: MainViewModel) {
    val catalog = state.catalog ?: return
    val selection = state.selection ?: return
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var browsingVendor by rememberSaveable { mutableStateOf(selection.vendor) }
    BackHandler(enabled = picker != null) { picker = if (picker == Picker.PRINTER) Picker.VENDOR else null }

    when (picker) {
        null -> PresetsOverview(state, onPick = { picker = it }, viewModel = viewModel)
        Picker.VENDOR -> SearchList(
            title = "Printer brand",
            items = catalog.printerVendors.map { v ->
                PickItem(v.name, displayVendor(v.name), "${v.printers.size} printers", v.name == selection.vendor)
            },
            onBack = { picker = null },
            onPick = { browsingVendor = it; picker = Picker.PRINTER },
            showChevron = true,
        )
        Picker.PRINTER -> SearchList(
            title = displayVendor(browsingVendor),
            items = catalog.vendor(browsingVendor)?.printers.orEmpty().map { p ->
                PickItem(p.name, p.name, "${p.nozzle} mm nozzle", p.name == selection.printer && browsingVendor == selection.vendor)
            },
            onBack = { picker = Picker.VENDOR },
            onPick = { viewModel.selectPrinter(browsingVendor, it); picker = null },
        )
        Picker.PROCESS -> {
            val printer = catalog.printer(selection.vendor, selection.printer)
            SearchList(
                title = "Process",
                items = printer?.let { catalog.processesFor(selection.vendor, it) }.orEmpty().map { name ->
                    PickItem(name, name, null, name == selection.process)
                },
                onBack = { picker = null },
                onPick = { viewModel.selectProcess(it); picker = null },
            )
        }
        Picker.FILAMENT -> {
            val printer = catalog.printer(selection.vendor, selection.printer)
            val filaments = printer?.let { catalog.filamentsFor(selection.vendor, it) }.orEmpty()
            SearchList(
                title = "Filament",
                items = filaments.map { ref ->
                    PickItem("${ref.vendor}\u0000${ref.name}", ref.name, displayVendor(ref.vendor), ref == selection.filament)
                },
                onBack = { picker = null },
                onPick = { key ->
                    filaments.firstOrNull { "${it.vendor}\u0000${it.name}" == key }?.let { viewModel.selectFilament(it) }
                    picker = null
                },
            )
        }
    }
}

private fun displayVendor(vendor: String) = when (vendor) {
    Catalog.LIBRARY_VENDOR -> "OrcaSlicer generic"
    "Custom" -> "Generic / custom printer"
    else -> vendor
}

@Composable
private fun PresetsOverview(state: UiState, onPick: (Picker) -> Unit, viewModel: MainViewModel) {
    val selection = state.selection ?: return
    val machine = state.inputs?.machine
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer and presets") },
                navigationIcon = {
                    IconButton(onClick = viewModel::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PresetCard("Printer", selection.printer, displayVendor(selection.vendor)) { onPick(Picker.VENDOR) }
            PresetCard("Process", selection.process, "Layer height, walls, infill, supports") { onPick(Picker.PROCESS) }
            PresetCard("Filament", selection.filament.name, displayVendor(selection.filament.vendor)) { onPick(Picker.FILAMENT) }
            val bed = state.bed
            if (bed != null && machine != null) {
                Text(
                    "Bed ${formatNumber(bed.width.toDouble())} × ${formatNumber(bed.depth.toDouble())} mm, " +
                        "height ${formatNumber(state.printableHeight.toDouble())} mm, " +
                        "nozzle ${machine.first("nozzle_diameter") ?: "?"} mm",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Your printer not listed? Pick \"Generic / custom printer\" and set the bed size and start G-code under Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.navigate(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Adjust printer and print settings")
            }
            Button(onClick = viewModel::back, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun PresetCard(label: String, value: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            overlineContent = { Text(label) },
            headlineContent = { Text(value) },
            supportingContent = { Text(detail) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        )
    }
}

@Composable
private fun SearchList(
    title: String,
    items: List<PickItem>,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    showChevron: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val shown = if (words.isEmpty()) items else items.filter { item ->
        val text = (item.title + " " + (item.subtitle ?: "")).lowercase()
        words.all { it in text }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.key }) { item ->
                    val subtitle = item.subtitle
                    val supporting: (@Composable () -> Unit)? = if (subtitle != null) {
                        { Text(subtitle) }
                    } else {
                        null
                    }
                    val trailing: (@Composable () -> Unit)? = if (item.selected) {
                        { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary) }
                    } else if (showChevron) {
                        { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                    } else {
                        null
                    }
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = supporting,
                        trailingContent = trailing,
                        modifier = Modifier.clickable { onPick(item.key) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
