package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.network.*
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable fun PrinterControlPanel(state:UiState,vm:MainViewModel,modifier:Modifier=Modifier) {
    val snapshot=state.telemetry
    val octo=state.connection.backend==PrinterBackend.OCTOPRINT
    val enabled=snapshot?.ready==true&&!state.deviceBusy&&state.deviceError==null
    var step by remember { mutableFloatStateOf(1f) };var confirm by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text((if(octo)"OctoPrint" else "Klipper")+if(snapshot?.ready==true)tr(" · подключён") else tr(" · нет данных"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Button(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick=vm::requestEmergencyStop,enabled=state.connection.apiUrl.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("STOP")}
        }
        state.deviceError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(state.deviceBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(snapshot==null)Text(if(state.connection.apiUrl.isBlank())tr("Добавьте принтер во вкладке «Подключения».") else tr("Ожидание телеметрии Moonraker. При недоступном Klipper элементы управления отключены."),style=MaterialTheme.typography.bodyMedium)
        snapshot?.let { s->
            if(s.message.isNotBlank()&&!s.ready)Text(s.message,style=MaterialTheme.typography.bodySmall)
            Text(tr("Состояние: {0}" ,when(s.printState){"printing"->tr("Печать");"paused"->tr("Пауза");"complete"->tr("Завершено");"error"->tr("Ошибка");"cancelled"->tr("Отменено");else->tr("Ожидание")}),style=MaterialTheme.typography.bodyMedium)
            if(s.filename.isNotBlank())Text(s.filename,style=MaterialTheme.typography.bodySmall)
            if(s.printing){LinearProgressIndicator(progress={s.progress},modifier=Modifier.fillMaxWidth());Text("${(s.progress*100).roundToInt()}%")}
            if(s.printing)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={vm.printerCommand { it.pause() }},enabled=enabled&&s.printState=="printing",modifier=Modifier.weight(1f)){Text(tr("Пауза"))}
                OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={vm.printerCommand { it.resume() }},enabled=enabled&&s.printState=="paused",modifier=Modifier.weight(1f)){Text(tr("Продолжить"),maxLines=1,style=MaterialTheme.typography.labelMedium,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
                OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={confirm="cancel"},enabled=enabled&&s.printing,modifier=Modifier.weight(1f)){Text(tr("Отмена"))}
            }
            PrintTimes(s,state.telemetryAt,state.telemetryIntervalSeconds,includePaused=!octo)
            Text(tr("Температуры"),style=MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(1,5,10).forEach{m->FilterChip(state.temperatureWindowMinutes==m,{vm.setTemperatureWindow(m)},label={Text(tr("{0} мин" ,m))})}
            }
            TemperatureChart(state.temperatureHistory,state.temperatureWindowMinutes)
            state.temperatureHistoryError?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            if(s.heaters.isEmpty())Text(tr("В Klipper не найдены датчики температуры"),style=MaterialTheme.typography.bodySmall)
            s.heaters.forEach { heater->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("${heaterDisplayName(heater.name)} · ${"%.1f".format(heater.temperature)} °C"+(heater.target?.let { " → ${it.roundToInt()} °C" } ?: ""),style=MaterialTheme.typography.titleSmall)
                        val currentTarget=heater.target
                        val maximum=heater.max
                        if(currentTarget!=null) {
                            var target by remember(state.activePrinterId,heater.name,currentTarget) { mutableStateOf(currentTarget.roundToInt().toString()) }
                            val value=target.replace(',','.').toFloatOrNull()
                            val valid=value!=null&&value.isFinite()&&(value==0f || (maximum!=null&&value in maxOf(0f,heater.min)..maximum))
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(target,{target=it},label={Text(tr("Цель, °C"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,isError=!valid,modifier=Modifier.weight(1f))
                                Button(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={value?.let { temp->vm.printerCommand { it.setHeater(heater.name,temp) } }},enabled=enabled&&valid){Text(tr("Задать"))}
                            }
                            Text(heater.max?.let { (if(octo)tr("Лимит приложения: ") else tr("Предел Klipper: "))+tr("{0}…{1} °C; 0 — выключить" ,heater.min.roundToInt(),it.roundToInt()) } ?: tr("Предел не получен из Klipper. Доступно выключение (0)."),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={vm.printerCommand { it.heatersOff() }},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text(tr("Выключить все нагреватели"))}
            HorizontalDivider()
            Text(tr("Перемещение каретки"),style=MaterialTheme.typography.titleMedium)
            if(s.positionKnown)Text(tr("X {0}  Y {1}  Z {2} мм · Home: {3}" ,s.position[0].roundToInt(),s.position[1].roundToInt(),"%.1f".format(s.position[2]),s.homedAxes.ifBlank { tr("не выполнен") }),style=MaterialTheme.typography.bodySmall)
            if(!s.positionKnown)Text(tr("OctoPrint не сообщает координаты и Home через REST API. Проверьте положение осей перед перемещением."),style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(.1f,1f,10f,50f).forEach { amount->FilterChip(selected=step==amount,onClick={step=amount},label={Text(tr("{0} мм" ,amount))}) } }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("X","Y","Z").forEach { axis->
                Column(Modifier.weight(1f)) {
                    Button(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={vm.printerCommand { it.jog(axis,step) }},enabled=enabled&&!s.printing&&(octo||axis.lowercase() in s.homedAxes),modifier=Modifier.fillMaxWidth()){Text("${axis} +")}
                    OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={vm.printerCommand { it.jog(axis,-step) }},enabled=enabled&&!s.printing&&(octo||axis.lowercase() in s.homedAxes),modifier=Modifier.fillMaxWidth()){Text("${axis} −")}
                }
            } }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={confirm="home"},enabled=enabled&&!s.printing,modifier=Modifier.weight(1f)){Text("Home XYZ")}
                OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={confirm="motors"},enabled=enabled&&!s.printing,modifier=Modifier.weight(1f)){Text(tr("Откл. моторы"))}
            }
            val currentFan=s.fan
            if(currentFan!=null) {
                var fan by remember(state.activePrinterId,currentFan) { mutableFloatStateOf(currentFan*100) }
                Text(tr("Обдув детали: {0}%" ,fan.roundToInt()),style=MaterialTheme.typography.titleSmall)
                Slider(fan,{fan=it},valueRange=0f..100f,enabled=enabled)
                OutlinedButton(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={vm.printerCommand { it.setFan(fan.roundToInt()) }},enabled=enabled){Text(tr("Применить обдув"))}
            }
        }
    }
    confirm?.let { action->AlertDialog(onDismissRequest={confirm=null},title={Text(when(action){"home"->tr("Выполнить Home XYZ?");"motors"->tr("Отключить двигатели?");else->tr("Отменить печать?")})},text={Text(when(action){"home"->tr("Каретка и стол начнут движение. Убедитесь, что им ничего не мешает.");"motors"->tr("Оси потеряют удержание; для следующих перемещений потребуется Home.");else->tr("Текущая печать будет отменена.")})},confirmButton={Button(shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),onClick={confirm=null;vm.printerCommand { when(action){"home"->it.home();"motors"->it.motorsOff();else->it.cancelPrint()} }}){Text(tr("Выполнить"))}},dismissButton={TextButton(onClick={confirm=null}){Text(tr("Назад"))}}) }
}

@Composable private fun PrintTimes(snapshot:KlipperSnapshot,receivedAt:Long?,intervalSeconds:Int,includePaused:Boolean) {
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle){lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED){while(true){now=System.currentTimeMillis();kotlinx.coroutines.delay(1000)}}}
    val age=receivedAt?.let{((now-it).coerceAtLeast(0)/1000.0)} ?: 0.0
    val delta=if(snapshot.printState=="printing"&&age<=intervalSeconds*2+3)age else 0.0
    val elapsed=snapshot.printSeconds+delta
    val eta=if(snapshot.printState=="printing")snapshot.remainingSeconds?.let{(now+((it-delta).coerceAtLeast(0.0)*1000)).toLong()} else null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr("Время печати"),style=MaterialTheme.typography.titleMedium)
            Row {Text(tr("Начало"),Modifier.weight(1f));Text((if(snapshot.startEstimated&&snapshot.startedAtMillis!=null)"≈ " else "")+printerTime(snapshot.startedAtMillis),style=MaterialTheme.typography.bodyMedium)}
            Row {Text(tr("Идёт печать"),Modifier.weight(1f));Text(printerDuration(elapsed),style=MaterialTheme.typography.bodyMedium)}
            Row {Text(tr("С паузами"),Modifier.weight(1f));Text(printerDuration(if(includePaused)snapshot.totalSeconds+delta else null),style=MaterialTheme.typography.bodyMedium)}
            Row {Text(tr("Завершение ≈"),Modifier.weight(1f));Text(if(snapshot.printState=="paused")tr("После возобновления") else printerTime(eta),style=MaterialTheme.typography.bodyMedium)}
            if(snapshot.printState=="printing"&&eta==null)Text(tr("Оценка появится после начала печати и получения данных о прогрессе."),style=MaterialTheme.typography.bodySmall)
        }
    }
}
fun heaterDisplayName(name:String)=when(name){"extruder","tool0"->tr("Сопло");"heater_bed","bed"->tr("Стол");"chamber"->tr("Камера");else->name.removePrefix("heater_generic ").removePrefix("temperature_sensor ").removePrefix("temperature_fan ")}

@Composable private fun TemperatureChart(history:List<TemperatureSeries>,minutes:Int) {
    val colors=listOf(MaterialTheme.colorScheme.primary,Color(0xFFEF8354),Color(0xFF778BEB),Color(0xFF2FA77A),Color(0xFFC878BA))
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val now=System.currentTimeMillis();val window=minutes*60000L
    val shown=history.filterNot { it.name in hidden }
    val maxValue=maxOf(50f,ceil((shown.flatMap { (it.temperatures+it.targets).takeLast(minutes*120) }.filterNotNull().maxOrNull() ?: 50f)/25f)*25f)
    val grid=MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row { Text("${maxValue.roundToInt()} °C",style=MaterialTheme.typography.labelSmall);Spacer(Modifier.weight(1f));Text(tr("Цель — пунктир"),style=MaterialTheme.typography.labelSmall) }
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                for(i in 0..4)drawLine(grid,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1f)
                for(i in 0..(if(minutes==1)4 else 5)) {val x=size.width*i/(if(minutes==1)4 else 5);drawLine(grid,Offset(x,0f),Offset(x,size.height),1f)}
                history.forEachIndexed { index,series->if(series.name !in hidden) {
                    fun line(values:List<Float?>,dashed:Boolean) {
                        val path=Path();var connected=false;var previous=0L
                        values.forEachIndexed { i,temp->
                            val timestamp=series.timestampsMillis.getOrNull(i) ?: (series.receivedAtMillis-(values.size-1-i)*1000)
                            if(temp==null||timestamp<now-window||timestamp>now)connected=false else {
                                val x=size.width*((timestamp-(now-window)).toDouble()/window).toFloat()
                                val y=size.height*(1-(temp/maxValue).coerceIn(0f,1f))
                                if(connected&&timestamp-previous<=10000)path.lineTo(x,y)else path.moveTo(x,y);connected=true;previous=timestamp
                            }
                        }
                        drawPath(path,colors[index%colors.size],style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(),pathEffect=if(dashed)PathEffect.dashPathEffect(floatArrayOf(10f,7f))else null))
                    }
                    line(series.temperatures,false);line(series.targets,true)
                } }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                val divisions=if(minutes==1)4 else 5
                for(i in 0..divisions){val value=minutes*(1.0-i.toDouble()/divisions);Text(if(i==divisions)tr("0 мин") else "−"+if(minutes==1)"%.2f".format(value).trimEnd('0').trimEnd('.',',') else value.roundToInt().toString(),style=MaterialTheme.typography.labelSmall)}
            }
            if(history.isEmpty())Text(tr("Ожидание истории температур"),style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { history.forEachIndexed { index,series->
                FilterChip(selected=series.name !in hidden,onClick={hidden=if(series.name in hidden)hidden-series.name else hidden+series.name},label={Text("● "+heaterDisplayName(series.name),color=colors[index%colors.size])})
            } }
        }
    }
}
