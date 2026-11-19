package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.whoxllm.orcamicro.core.network.PrinterConnection
import java.net.URI
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun PrinterScreen(state: UiState, viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(if(state.connection.apiUrl.isBlank())2 else 0) }
    var backend by remember(state.connection) { mutableStateOf(state.connection.backend) }
    var statistics by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var editingId by remember(state.activePrinterId) { mutableStateOf(state.activePrinterId) }
    var name by remember(state.activePrinterId) { mutableStateOf(state.printers.firstOrNull { it.id==state.activePrinterId }?.name.orEmpty()) }
    var api by remember(state.connection) { mutableStateOf(state.connection.apiUrl) }
    var web by remember(state.connection) { mutableStateOf(state.connection.webUrl) }
    var apiKey by remember(state.connection) { mutableStateOf(state.connection.apiKey) }
    var machine by remember(state.activePrinterId) { mutableStateOf(state.printers.firstOrNull { it.id==state.activePrinterId }?.machineProfile) }
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, viewModel::consumeMessage)
    val pickGcode = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::uploadPickedGcode) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.connection,tab,refresh,lifecycle,state.telemetryIntervalSeconds) {
        if(tab==0 && state.connection.apiUrl.isNotBlank()) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(true) { val start=android.os.SystemClock.elapsedRealtime();viewModel.refreshTelemetry();kotlinx.coroutines.delay((state.telemetryIntervalSeconds*1000L-(android.os.SystemClock.elapsedRealtime()-start)).coerceAtLeast(1)) }
        }
    }
    if(statistics)PrinterStatisticsSheet(state,viewModel){statistics=false}
    if(state.configurationPreview!=null)PrinterConfigurationDialog(state,viewModel)
    Scaffold(topBar={DesktopHeader(state,viewModel) {
        IconButton(onClick={statistics=true;viewModel.loadPrinterStatistics(state.activePrinterId)}){OrcaIcon(OrcaAction.Statistics,tr("Статистика печатей"))}
        IconButton(onClick={tab=2}){OrcaIcon(OrcaAction.Settings,tr("Настроить подключение"))}
        IconButton(onClick={refresh++;viewModel.testPrinter()},enabled=!state.connecting){OrcaIcon(OrcaAction.Refresh,tr("Обновить принтер"))}
    }},snackbarHost={SnackbarHost(snackbar)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(state.printers.isNotEmpty())Box(Modifier.padding(horizontal=12.dp,vertical=6.dp)) {
                ChoiceField(tr("Активный принтер"),state.printers.firstOrNull { it.id==state.activePrinterId }?.name.orEmpty(),state.printers.map { PickerItem(it.id,it.name,it.connection.apiUrl) },enabled=!state.deviceBusy&&state.uploadPercent==null,onSelect=viewModel::selectSavedPrinter)
            }
            TabRow(tab) { listOf(tr("Пульт"),tr("Веб"),tr("Принтеры")).forEachIndexed { i,title->Tab(selected=tab==i,onClick={tab=i},text={Text(title,maxLines=1)}) } }
            when(tab) {
                0 -> PrinterControlPanel(state,viewModel,Modifier.weight(1f))
                1 -> if(state.connection.apiUrl.isNotBlank())key(state.connection,refresh) { EmbeddedKlipper(state.connection,Modifier.weight(1f).fillMaxWidth()) }
                    else Box(Modifier.weight(1f).padding(16.dp)){Text(tr("Добавьте подключение к Moonraker"))}
                else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row {
                        Text(if(editingId==null)tr("Новое подключение") else tr("Настройки подключения"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick={editingId=null;name="";api="";web="";apiKey="";machine=null}){Text(tr("+ Добавить"))}
                    }
                    OutlinedTextField(name,{name=it},label={Text(tr("Имя принтера"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                    ChoiceField(tr("Сервер печати"),if(backend==io.github.whoxllm.orcamicro.core.network.PrinterBackend.OCTOPRINT)"OctoPrint" else "Klipper / Moonraker",listOf(PickerItem("MOONRAKER","Klipper / Moonraker"),PickerItem("OCTOPRINT","OctoPrint"))){backend=io.github.whoxllm.orcamicro.core.network.PrinterBackend.valueOf(it)}
                    OutlinedTextField(api,{api=it},label={Text(tr("Адрес сервера"))},placeholder={Text("http://printer.local:7125")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(web,{web=it},label={Text("Mainsail / Fluidd")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey,{apiKey=it},label={Text(tr("API-ключ, если требуется"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    ChoiceField(tr("Связанный профиль принтера"),machine ?: tr("Выбрать вручную перед нарезкой"),listOf(PickerItem("none",tr("Выбрать вручную перед нарезкой")))+state.userPresets.filter { it.type==io.github.whoxllm.orcamicro.core.profiles.PresetType.MACHINE }.map { PickerItem(it.name,it.name,tr("Мои профили")) }) { machine=it.takeUnless { it=="none" } }
                    Button(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={if(viewModel.savePrinter(name,PrinterConnection(api,web,apiKey,backend),editingId,machine)){tab=0;viewModel.testPrinter()}},enabled=!state.deviceBusy,modifier=Modifier.fillMaxWidth()){Text(tr("Сохранить и подключить"))}
                    if(state.connection.apiUrl.isNotBlank())OutlinedButton(onClick=viewModel::readPrinterConfiguration,enabled=!state.configurationLoading,modifier=Modifier.fillMaxWidth()){Text(if(state.configurationLoading)tr("Чтение конфигурации…") else tr("Считать параметры принтера"))}
                    state.printerStatus?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                    if(editingId!=null)TextButton(onClick={editingId?.let(viewModel::deletePrinter)}){Text(tr("Удалить подключение"))}
                    Text(tr("Пульт и графики работают через Moonraker / Klipper. Для других прошивок можно использовать веб-интерфейс. Каждый принтер хранит свой адрес и ключ."),style=MaterialTheme.typography.bodySmall)
                }
            }
            state.uploadedFile?.let { Text(tr("Загружен: {0}" ,it),Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium) }
            Surface(tonalElevation=2.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={pickGcode.launch(arrayOf("*/*"))},enabled=state.connection.apiUrl.isNotBlank()&&state.uploadPercent==null,modifier=Modifier.weight(1f)){Text(tr("Файл G-code"),maxLines=1)}
                    UploadSplitButton(state,viewModel,Modifier.weight(1f))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedKlipper(connection: PrinterConnection, modifier: Modifier) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val url = connection.webUrl.ifBlank { connection.apiUrl }
    val result = remember(url) { runCatching {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, target: String?, favicon: Bitmap?) { loading = true; error = null }
                override fun onPageFinished(view: WebView?, target: String?) { loading = false }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, problem: WebResourceError?) {
                    if (request?.isForMainFrame == true) { loading = false; error = tr("Не удалось открыть Klipper: {0}" ,problem?.description) }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return true
                    if (target.scheme !in listOf("http", "https")) return true
                    val initial = URI(url)
                    if (target.host == initial.host && target.port == initial.port && target.scheme == initial.scheme) return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                    return true
                }
            }
            // The web application manages its own login; no Android JS bridge is exposed.
            loadUrl(url)
        }
    } }
    val browser = result.getOrNull()
    DisposableEffect(browser) { onDispose { browser?.stopLoading(); browser?.destroy() } }
    BackHandler(enabled = browser?.canGoBack() == true) { browser?.goBack() }
    Column(modifier) {
        if (loading && browser != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (browser == null) Text(tr("Системный WebView недоступен. Откройте адрес в браузере."), Modifier.padding(16.dp))
        error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (browser != null) AndroidView(factory = { browser }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
