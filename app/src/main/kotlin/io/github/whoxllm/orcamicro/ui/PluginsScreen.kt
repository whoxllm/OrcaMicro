package io.github.whoxllm.orcamicro.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.slicing.PluginPermission
import io.github.whoxllm.orcamicro.data.InstalledPlugin
import io.github.whoxllm.orcamicro.data.PluginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(repository: PluginRepository, close: () -> Unit) {
    val installed by repository.plugins.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var capability by rememberSaveable { mutableStateOf<String?>(null) }
    var trust by rememberSaveable { mutableStateOf(false) }
    var promptId by rememberSaveable { mutableStateOf<String?>(null) }
    var requested by rememberSaveable { mutableStateOf(emptyArray<String>()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun act(action: suspend () -> Unit) {
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.localizedMessage ?: tr("Не удалось изменить плагин") }
        }
    }
    LaunchedEffect(repository) {
        try { repository.edit { refreshChanged() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.localizedMessage ?: tr("Не удалось загрузить плагины") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act { repository.importFile(uri) }
    }
    fun back() {
        if (capability != null) capability = null else if (selected != null) selected = null else close()
    }
    BackHandler { back() }
    val detail = installed.firstOrNull { it.state.id == selected }
    val cap = detail?.state?.inspected?.capabilities?.firstOrNull { it.name == capability && it.configUi != null }
    Scaffold(topBar = {
        TopAppBar(title = { Text(cap?.name ?: detail?.name ?: tr("Плагины")) }, navigationIcon = {
            TextButton(onClick = ::back) { Text(tr("Назад")) }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail != null && cap != null -> PluginSettingsPage(detail, cap, repository) { error = it.localizedMessage }
                detail != null -> PluginDetail(detail, openSettings = { capability = it },
                    master = { act { repository.edit { setPermissionsEnabled(detail.state.id, it) } } },
                    permission = { name, on -> act { repository.edit { setPermission(detail.state.id, name, on) } } },
                    inspect = { act { repository.edit { inspect(detail.state.id) } } },
                    delete = { act { repository.edit { delete(detail.state.id) }; selected = null } })
                else -> PluginList(installed, open = { selected = it }, reorder = { ids -> act { repository.edit { reorder(ids) } } },
                    toggle = { plugin, on ->
                        act {
                            if (!on) repository.edit { turnOff(plugin.state.id) }
                            else {
                                val ask = repository.edit { turnOn(plugin.state.id) }
                                if (ask.isNotEmpty()) { requested = ask.toTypedArray(); promptId = plugin.state.id }
                            }
                        }
                    }, onImport = {
                        act {
                            if (repository.edit { needsTrustWarning() }) trust = true
                            else picker.launch(arrayOf("*/*"))
                        }
                    })
            }
        }
    }
    if (trust) AlertDialog(onDismissRequest = { trust = false }, title = { Text(tr("Импорт плагина")) },
        text = { Text(pluginTrustText()) },
        confirmButton = { TextButton(onClick = { trust = false; act {
            repository.edit { acknowledgeTrustWarning() }; picker.launch(arrayOf("*/*"))
        } }) { Text(tr("Выбрать файл")) } },
        dismissButton = { TextButton(onClick = { trust = false }) { Text(tr("Отмена")) } })
    promptId?.let { id ->
        val plugin = installed.firstOrNull { it.state.id == id }
        if (plugin != null) AlertDialog(onDismissRequest = { promptId = null },
            title = { Text(tr("Разрешить плагину {0}?", plugin.name)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                requested.forEach { Text(permissionLabel(it)); Text(permissionDetail(it), style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = { promptId = null; act { repository.edit { answer(id, requested.toSet(), true) } } }) { Text(tr("Да")) } },
            dismissButton = { TextButton(onClick = { promptId = null; act { repository.edit { answer(id, requested.toSet(), false) } } }) { Text(tr("Нет, оставить выключенным")) } })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(tr("Ошибка плагина")) },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text(tr("Закрыть")) } }) }
}

@Composable
private fun PluginList(plugins: List<InstalledPlugin>, open: (String) -> Unit, reorder: (List<String>) -> Unit,
                       toggle: (InstalledPlugin, Boolean) -> Unit, onImport: () -> Unit) {
    val scroll = rememberLazyListState()
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val installedIds = plugins.map { it.state.id }
    var order by remember(installedIds) { mutableStateOf(installedIds) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableFloatStateOf(0f) }
    fun moveOverFinger() {
        val from = order.indexOf(dragging)
        val target = scroll.layoutInfo.visibleItemsInfo.firstOrNull { finger >= it.offset && finger < it.offset + it.size }
        val to = order.indexOf(target?.key)
        if (from >= 0 && to >= 0 && from != to) order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    LaunchedEffect(dragging) {
        if (dragging != null) while (true) {
            withFrameNanos { }
            val info = scroll.layoutInfo

            val delta = when {
                finger < info.viewportStartOffset + edge -> -10f
                finger > info.viewportEndOffset - edge -> 10f
                else -> 0f
            }
            if (delta != 0f && scroll.scrollBy(delta) != 0f) moveOverFinger()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(pluginTrustText(), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                OrcaIcon(OrcaAction.Import, null); Spacer(Modifier.width(8.dp)); Text(tr("Импортировать плагин"))
            }
        }
        if (plugins.isEmpty()) item { Text(tr("Плагинов пока нет. Выберите файл .py для импорта.")) }
        items(order, key = { it }) { id ->
            val item = plugins.firstOrNull { it.state.id == id } ?: return@items
            val state = item.state
            val label = tr("Изменить порядок")
            val up = tr("Переместить выше")
            val down = tr("Переместить ниже")
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp, 64.dp).semantics {
                        contentDescription = label
                        customActions = listOf(-1 to up, 1 to down).mapNotNull { (step, text) ->
                            val from = order.indexOf(id)
                            if (from + step !in order.indices) null else CustomAccessibilityAction(text) {
                                reorder(order.toMutableList().apply { add(from + step, removeAt(from)) }); true
                            }
                        }
                    }.pointerInput(id, installedIds) {
                        detectDragGestures(onDragStart = {
                            val row = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return@detectDragGestures
                            finger = row.offset + row.size / 2f
                            dragging = id
                        }, onDragEnd = { dragging = null; reorder(order) },
                            onDragCancel = { dragging = null; order = installedIds }) { change, delta ->
                            change.consume(); finger += delta.y; moveOverFinger()
                        }
                    }, contentAlignment = Alignment.Center) { Text("≡", style = MaterialTheme.typography.titleLarge) }
                    Column(Modifier.weight(1f).clickable { open(id) }.padding(vertical = 10.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(item.version, item.author).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                        if (item.description.isNotBlank()) Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (state.builtIn) Text(tr("Встроенный"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if ((state.inspected?.requestedPermissions.orEmpty() - state.permissions.effective()).isNotEmpty())
                            Text(tr("● Запрашивает разрешения"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        InspectStatus(item)
                    }
                    Switch(state.enabled, { toggle(item, it) }, enabled = state.enabled || state.inspected?.ok == true,
                        modifier = Modifier.semantics { contentDescription = item.name })
                }
            }
        }
    }
}

@Composable
private fun InspectStatus(plugin: InstalledPlugin) {
    val inspected = plugin.state.inspected
    if (inspected == null) Text(tr("Ожидает проверки"), style = MaterialTheme.typography.bodySmall)
    else if (!inspected.ok) Text(inspected.error ?: tr("Не удалось проверить плагин"), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun PluginDetail(plugin: InstalledPlugin, openSettings: (String) -> Unit, master: (Boolean) -> Unit,
                         permission: (String, Boolean) -> Unit, inspect: () -> Unit, delete: () -> Unit) {
    val state = plugin.state
    var confirmDelete by rememberSaveable(state.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(listOf(plugin.version, plugin.author).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
        Text(plugin.description)
        InspectStatus(plugin)
        Text(tr("Возможности плагина"), style = MaterialTheme.typography.titleLarge)
        state.inspected?.capabilities.orEmpty().forEach { cap ->
            Text(cap.name, style = MaterialTheme.typography.titleMedium)
            Text(if (cap.type == "slicing-pipeline") cap.type else tr("{0} · пока недоступно на телефоне", cap.type))
            if (cap.configUi != null) OutlinedButton(onClick = { openSettings(cap.name) }) {
                OrcaIcon(OrcaAction.Settings, null); Spacer(Modifier.width(8.dp)); Text(tr("Настройки"))
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Разрешения"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Switch(state.permissions.enabled, master, modifier = Modifier.semantics { contentDescription = tr("Разрешения") })
        }
        PluginPermission.ALL.forEach { name ->
            val label = permissionLabel(name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp).alpha(if (state.permissions.enabled) 1f else 0.55f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(permissionDetail(name), style = MaterialTheme.typography.bodySmall)
                    if (name in state.inspected?.requestedPermissions.orEmpty()) Text(tr("Запрошено"), color = MaterialTheme.colorScheme.primary)
                }
                Switch(name in state.permissions.allowed, { permission(name, it) }, enabled = state.permissions.enabled,
                    modifier = Modifier.semantics { contentDescription = label })
            }
        }
        OutlinedButton(onClick = inspect) { Text(tr("Проверить снова")) }
        if (!state.builtIn) TextButton(onClick = { confirmDelete = true }) { Text(tr("Удалить плагин"), color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Удалить плагин {0}?", plugin.name)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text(tr("Удалить")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Отмена")) } })
}
