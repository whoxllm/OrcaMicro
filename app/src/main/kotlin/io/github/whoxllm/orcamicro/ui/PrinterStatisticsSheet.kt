package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.network.*
import io.github.whoxllm.orcamicro.core.profiles.PresetType
import io.github.whoxllm.orcamicro.core.settings.OrcaOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun printerDuration(seconds:Double?):String {
    if(seconds==null||!seconds.isFinite()||seconds<0)return "—"
    val n=seconds.toLong();return "%d:%02d:%02d".format(Locale.ROOT,n/3600,n/60%60,n%60)
}
fun printerTime(millis:Long?):String=millis?.let {DateTimeFormatter.ofPattern("d MMM, HH:mm",Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))} ?: "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PrinterStatisticsSheet(state:UiState,vm:MainViewModel,onDismiss:()->Unit) {
    val results=state.printerStatistics
    val available=results.mapNotNull{it.statistics}
    val total=PrinterStatistics.combine(available)
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(tr("Статистика печатей"),style=MaterialTheme.typography.headlineSmall)
            ChoiceField(tr("Показать"),state.printers.firstOrNull{it.id==state.statisticsScopeId}?.name ?: tr("Все принтеры"),listOf(PickerItem("all",tr("Все принтеры")))+state.printers.map{PickerItem(it.id,it.name,it.connection.backend.name)}) { vm.loadPrinterStatistics(it.takeUnless{it=="all"}) }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(tr("Доступны: {0} из {1}" ,available.size,results.size),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={vm.loadPrinterStatistics(state.statisticsScopeId)},enabled=!state.statisticsLoading){Text(tr("Обновить"))}
            }
            if(state.statisticsLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedVisibility(visible=!state.statisticsLoading&&available.isNotEmpty()) {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    StatisticsDonut(total)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StatisticValue(tr("Время печати"),printerDuration(total.printSeconds),Modifier.weight(1f))
                        StatisticValue(tr("Всего с паузами"),printerDuration(total.totalSeconds),Modifier.weight(1f))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StatisticValue(tr("Самая долгая"),printerDuration(total.longestSeconds),Modifier.weight(1f))
                        StatisticValue(tr("Филамент"),total.filamentMm?.let{tr("%.1f м").format(it/1000)} ?: "—",Modifier.weight(1f))
                    }
                    if(total.limited)Text(tr("Показаны последние 10 000 записей на принтер. Старые записи не включены."),style=MaterialTheme.typography.bodySmall)
                }
            }
            if(!state.statisticsLoading&&results.isEmpty())Text(tr("Сначала добавьте подключение к принтеру"))
            results.forEach { result->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(result.printer.name,style=MaterialTheme.typography.titleMedium)
                        result.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                        result.statistics?.let { s->
                            Text(tr("Удачных: {0} · Неудачных: {1} · Отменено: {2}" ,s.completed,s.failed,s.cancelled),style=MaterialTheme.typography.bodySmall)
                            Text(tr("Печать: {0} · С паузами: {1}" ,printerDuration(s.printSeconds),printerDuration(s.totalSeconds)),style=MaterialTheme.typography.bodySmall)
                            if(s.active>0)Text(tr("Сейчас печатаются: {0}" ,s.active),style=MaterialTheme.typography.bodySmall)
                            s.note?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                            if(s.recent.isNotEmpty()) {
                                var expanded by remember(result.printer.id){mutableStateOf(false)}
                                TextButton(onClick={expanded=!expanded}){Text(if(expanded)tr("Скрыть историю") else tr("Последние печати"))}
                                AnimatedVisibility(expanded) {
                                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){s.recent.forEach{job->
                                        Text(job.filename,style=MaterialTheme.typography.bodyMedium,maxLines=2)
                                        Text("${printStatusLabel(job.status)} · ${printerTime(job.startedAt)} · ${printerDuration(job.totalSeconds)}",style=MaterialTheme.typography.bodySmall)
                                    }}
                                }
                            }
                        }
                    }
                }
            }
            Text(tr("Moonraker: история сохранённых заданий, включая печати из других клиентов. Недоступные принтеры отмечены отдельно."),style=MaterialTheme.typography.bodySmall)
            state.statisticsAt?.let{Text(tr("Обновлено: {0}" ,printerTime(it)),style=MaterialTheme.typography.labelSmall)}
        }
    }
}
@Composable private fun StatisticValue(label:String,value:String,modifier:Modifier) {
    OutlinedCard(modifier){Column(Modifier.padding(12.dp)){Text(value,style=MaterialTheme.typography.titleMedium);Text(label,style=MaterialTheme.typography.labelSmall)}}
}
@Composable private fun StatisticsDonut(stats:PrinterStatistics) {
    var reveal by remember {mutableStateOf(false)}
    LaunchedEffect(stats){reveal=false;withFrameNanos{};reveal=true}
    val progress by animateFloatAsState(if(reveal&&stats.finished>0)1f else 0f,tween(800),label="Statistics chart")
    val colors=listOf(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.error,Color(0xFFE3A943),MaterialTheme.colorScheme.outline)
    val counts=listOf(stats.completed,stats.failed,stats.cancelled,stats.other)
    val grid=MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(144.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize().semantics{contentDescription=tr("Статистика: {0} удачных, {1} неудачных, {2} отменено" ,stats.completed,stats.failed,stats.cancelled)}) {
                val stroke=18.dp.toPx();val pad=stroke/2;val circle=Size(size.width-stroke,size.height-stroke)
                drawArc(grid,0f,360f,false,topLeft=androidx.compose.ui.geometry.Offset(pad,pad),size=circle,style=Stroke(stroke))
                var start=-90f
                counts.forEachIndexed{i,n->val sweep=if(stats.finished>0)(n*360.0/stats.finished).toFloat()*progress else 0f;drawArc(colors[i],start,sweep,false,topLeft=androidx.compose.ui.geometry.Offset(pad,pad),size=circle,style=Stroke(stroke));start+=sweep}
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally){Text(stats.successRate?.let{"%.1f%%".format(it)} ?: "—",style=MaterialTheme.typography.headlineSmall);Text(tr("Успешно"),style=MaterialTheme.typography.labelSmall)}
        }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){listOf(tr("Удачные"),tr("Неудачные"),tr("Отменённые"),tr("Другие")).forEachIndexed{i,label->Text("● ${label}: ${counts[i]}",color=colors[i],style=MaterialTheme.typography.bodyMedium)}}
    }
}
fun printStatusLabel(status:String):String=when(status){"printing","in_progress"->tr("Печать");"paused"->tr("Пауза");"completed","complete"->tr("Завершено");"error","klippy_shutdown","klippy_disconnect","interrupted"->tr("Ошибка");"cancelled"->tr("Отменено");else->tr("Ожидание")}

@Composable fun PrinterConfigurationDialog(state:UiState,vm:MainViewModel) {
    val preview=state.configurationPreview ?: return
    val printer=state.printers.firstOrNull{it.id==preview.printerId}
    var name by remember(preview){mutableStateOf((printer?.name ?: tr("Принтер"))+tr(" — считано"))}
    val duplicate=state.userPresets.any{it.type==PresetType.MACHINE&&it.name==name.trim()}
    AlertDialog(onDismissRequest=vm::dismissPrinterConfiguration,title={Text(tr("Параметры с принтера"))},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(preview.config.source,style=MaterialTheme.typography.labelMedium)
            OutlinedTextField(name,{name=it},label={Text(tr("Имя нового профиля"))},isError=name.isBlank()||duplicate,singleLine=true)
            if(duplicate)Text(tr("Такое имя уже существует"),color=MaterialTheme.colorScheme.error)
            preview.config.values.forEach{(key,value)->Text("${OrcaOptions.all.firstOrNull{it.key==key}?.displayLabel ?: key}: ${OrcaOptions.display(value)}",style=MaterialTheme.typography.bodySmall)}
            preview.config.notes.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            Text(tr("После сохранения профиль будет выбран и привязан к этому подключению."),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={Button(onClick={vm.savePrinterConfiguration(name)},enabled=name.isNotBlank()&&!duplicate){Text(tr("Сохранить профиль"))}},dismissButton={TextButton(onClick=vm::dismissPrinterConfiguration){Text(tr("Отмена"))}})
}
