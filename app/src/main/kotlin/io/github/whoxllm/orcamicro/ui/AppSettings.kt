package io.github.whoxllm.orcamicro.ui

import android.text.format.Formatter
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.network.ModelCache

@Composable
fun AppSettingsContent(state: UiState, vm: MainViewModel) {
    var api by remember { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(LocalContext.current,state.modelCacheBytes)
    TextButton(onClick=vm::closeAppSettings) { Text(tr("‹ О программе")) }
    Text(tr("Настройки приложения"),style=MaterialTheme.typography.headlineSmall)
    LanguageSettingsCard(state,vm)
    SettingsGroup(tr("Графика · Dev menu")) { GraphicsSettings(state,vm) }
    SettingsGroup(tr("Каталоги и кэш моделей")) {
        OutlinedButton(onClick={api=true},modifier=Modifier.fillMaxWidth()) { Text(tr("Настройки API")) }
        Text(tr("Срок после последнего открытия. Карточки и модель на столе сохраняются. Очистка при запуске и ежедневно в фоне."),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            ModelCache.durations.forEach { days -> FilterChip(state.modelCacheDays==days,{vm.setCacheDays(days)},label={Text(if(days==0)tr("Без срока")else tr("{0} дн.",days))}) }
        }
        OutlinedButton(onClick={vm.cleanModelCache(all=true)},enabled=!state.libraryBusy,modifier=Modifier.fillMaxWidth()) { Text(tr("Очистить кэш · {0}",size)) }
    }
    SettingsGroup(tr("Телеметрия принтера")) {
        Text(tr("Автообновление"),style=MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(1,5,10,30).forEach { seconds -> FilterChip(state.telemetryIntervalSeconds==seconds,{vm.setTelemetryInterval(seconds)},label={Text(tr("{0} с",seconds))}) }
        }
        Text(tr("Окно графика температур"),style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(1,5,10).forEach { minutes -> FilterChip(state.temperatureWindowMinutes==minutes,{vm.setTemperatureWindow(minutes)},label={Text(tr("{0} мин",minutes))}) }
        }
    }
    UpdateCard()
    state.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
    if(api) CatalogApiDialog(state.catalogCredentials,onSave={vm.saveCatalogCredentials(it);api=false},onDismiss={api=false})
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable{expanded=!expanded}.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Text(if(expanded) "▴" else "▾")
        }
        androidx.compose.animation.AnimatedVisibility(
            visible=expanded,
            modifier=Modifier.fillMaxWidth(),
            enter=expandVertically(animationSpec=tween(220),expandFrom=Alignment.Top)+fadeIn(tween(180)),
            exit=shrinkVertically(animationSpec=tween(180),shrinkTowards=Alignment.Top)+fadeOut(tween(120)),
        ) {
            Column(Modifier.padding(horizontal=12.dp).padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
        }
    }
}
