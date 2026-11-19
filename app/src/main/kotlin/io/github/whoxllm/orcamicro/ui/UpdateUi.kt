package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.whoxllm.orcamicro.BuildConfig
import io.github.whoxllm.orcamicro.update.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UpdateUi(app:UiState,onInstall:()->Unit) {
    val context=LocalContext.current;val updater=remember { AppUpdater.get(context) }
    val state by updater.state.collectAsState()
    val lifecycle=LocalLifecycleOwner.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if(updater.shouldAskNotifications()){updater.notificationAsked();permission.launch(Manifest.permission.POST_NOTIFICATIONS)}
        updater.check()
    }
    LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while(true){if(updater.state.value.stage in setOf(UpdateStage.DOWNLOADING,UpdateStage.VERIFYING))updater.refreshDownload();delay(1500)}
    } }
    val busy=app.slice is SliceState.Running||app.uploadPercent!=null||app.libraryBusy||app.deviceBusy||app.stopChallenge!=null||app.printDialog
    LaunchedEffect(state.stage,state.autoInstall,busy) {
        if(state.stage==UpdateStage.READY&&state.autoInstall&&!busy){updater.consumeAutoInstall();onInstall()}
    }
    if(state.showDialog&&!busy)UpdateDialog(state,updater,onInstall)
}

@Composable
fun UpdateCard() {
    val context=LocalContext.current;val updater=remember { AppUpdater.get(context) };val state by updater.state.collectAsState();val scope=rememberCoroutineScope()
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionVersion++ }
    OutlinedCard(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(tr("Обновления приложения"),style=MaterialTheme.typography.titleMedium)
            Text(tr("Установлено: {0}" ,BuildConfig.VERSION_NAME))
            state.release?.let { Text(tr("Доступно: {0}" ,it.version),color=MaterialTheme.colorScheme.primary) }
            if(state.checking)LinearProgressIndicator(Modifier.fillMaxWidth())
            state.status?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={scope.launch{updater.check(force=true,show=true)}},enabled=!state.checking,shape=MaterialTheme.shapes.small){Text(tr("Проверить"))}
                if(state.release!=null)Button(onClick=updater::show,shape=MaterialTheme.shapes.small){Text(if(state.stage==UpdateStage.READY)tr("Установить") else tr("Обновление"))}
            }
            Row {
                Text(tr("Проверять новые версии автоматически"),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(state.autoCheck,updater::setAutoCheck)
            }
            val canNotify=remember(permissionVersion){updater.notificationsAllowed()}
            if(!canNotify)TextButton(onClick={updater.notificationAsked();permission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text(tr("Разрешить уведомления"))}
            Text(tr("Изменения показываются перед загрузкой. APK скачивается в фоне и проверяется; установку подтверждает Android."),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdateDialog(state:UpdateState,updater:AppUpdater,onInstall:()->Unit) {
    val release=state.release ?: return
    val active=state.stage in setOf(UpdateStage.DOWNLOADING,UpdateStage.VERIFYING)
    AlertDialog(onDismissRequest=updater::dismiss,title={Text("OrcaMicro ${release.version}")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(tr("Что нового"),style=MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(release.notes.ifBlank { tr("Автор не добавил описание изменений.") },style=MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text(tr("ARM64 · {0} МБ · текущая {1}" ,release.bytes/(1024*1024),BuildConfig.VERSION_NAME),style=MaterialTheme.typography.labelMedium)
            when(state.stage) {
                UpdateStage.DOWNLOADING -> { state.progress?.let { LinearProgressIndicator(progress={it/100f},modifier=Modifier.fillMaxWidth());Text(tr("Скачивание: {0}%" ,it)) } ?: LinearProgressIndicator(Modifier.fillMaxWidth());Text(tr("Загрузка продолжится при закрытии приложения или временной потере сети."),style=MaterialTheme.typography.bodySmall) }
                UpdateStage.VERIFYING -> {LinearProgressIndicator(Modifier.fillMaxWidth());Text(tr("Проверка APK и подписи…"))}
                UpdateStage.READY -> Text(tr("APK проверен. Android попросит подтвердить установку."),color=MaterialTheme.colorScheme.primary)
                else -> Text(tr("После загрузки откроется системный установщик Android. Профили и настройки сохранятся."),style=MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(active)TextButton(onClick=updater::cancelDownload){Text(tr("Отменить загрузку"))}
        }
    },confirmButton={
        if(state.stage==UpdateStage.READY)Button(onClick={updater.consumeAutoInstall();onInstall()},shape=MaterialTheme.shapes.small){Text(tr("Установить обновление"))}
        else if(!active)Button(onClick=updater::download,shape=MaterialTheme.shapes.small){Text(if(state.stage==UpdateStage.FAILED)tr("Скачать заново") else tr("Скачать апдейт"))}
    },dismissButton={TextButton(onClick=updater::dismiss){Text(if(active)tr("В фоне") else tr("Позже"))}})
}
