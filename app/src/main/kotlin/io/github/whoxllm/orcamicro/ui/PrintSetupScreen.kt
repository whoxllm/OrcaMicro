package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.i18n.ptr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.profiles.*
import io.github.whoxllm.orcamicro.render.PlateRenderer
import io.github.whoxllm.orcamicro.render.SceneView
import kotlin.math.max

val FILAMENT_REMINDER get() = tr("(Удостоверьтесь что данный филамент загружен в принтер)")

data class PickerItem(val id:String,val title:String,val section:String="")

@Composable
fun ChoiceField(label:String,value:String,items:List<PickerItem>,enabled:Boolean=true,onSelect:(String)->Unit) {
    var show by remember { mutableStateOf(false) };var query by remember { mutableStateOf("") }
    OutlinedCard(onClick={ show=true },enabled=enabled,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) { Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary);Text(value.ifBlank { tr("Выбрать…") },style=MaterialTheme.typography.bodyMedium) }
    }
    if(show) AlertDialog(onDismissRequest={show=false},title={Text(label)},text={
        Column {
            OutlinedTextField(query,{query=it},label={Text(tr("Поиск"))},singleLine=true,modifier=Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max=380.dp)) {
                items(items.filter { it.title.contains(query,true) },key={it.id}) { item->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(item.id);show=false;query="" }.padding(vertical=12.dp)) {
                        Text(item.title);if(item.section.isNotBlank())Text(item.section,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    };HorizontalDivider()
                }
            }
        }
    },confirmButton={TextButton(onClick={show=false}){Text(tr("Закрыть"))}})
}

@Composable
fun PrintSetupScreen(state:UiState,viewModel:MainViewModel) {
    val selection=state.selection ?: return;val catalog=state.catalog ?: return
    val stock=catalog.printer(selection.vendor,selection.printer)
    val ownMachine=state.userPresets.filter { it.type==PresetType.MACHINE }
    val ownProcess=state.userPresets.filter { it.type==PresetType.PROCESS }
    val ownFilament=state.userPresets.filter { it.type==PresetType.FILAMENT }
    val snackbar=remember { SnackbarHostState() };MessageEffect(state.message,snackbar,viewModel::consumeMessage)
    val validFields=remember { mutableStateMapOf<String,Boolean>() }
    var ownOnly by remember { mutableStateOf(ownFilament.isNotEmpty()) }
    var preview by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    val ready=state.model?.fits==true&&validFields.values.all{it}&&state.slice !is SliceState.Running
    Scaffold(topBar={DesktopHeader(state,viewModel)},snackbarHost={SnackbarHost(snackbar)},bottomBar={
        Surface(tonalElevation=3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(state.lastResult!=null)Text(tr("G-code подготовлен · {0} КБ" ,state.lastResult.gcode.length()/1024),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                Button(shape=MaterialTheme.shapes.small,onClick={if(state.lastResult==null)viewModel.quickSlice() else viewModel.showPrintDialog(true)},enabled=ready,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)) {
                    Text(if(state.lastResult==null)tr("Нарезать для печати") else tr("Отправить на печать"))
                }
            }
        }
    }) { padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Quick Print",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                TextButton(onClick={viewModel.navigate(Screen.LIBRARY)}){Text(tr("К моделям"))}
            }
            Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ModelThumbnail(state.activeModelCard?.imageUrl,Modifier.size(100.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(state.activeModelCard?.title ?: state.model?.name ?: tr("Сначала откройте модель"),style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                        state.activeModelCard?.let { Text(it.author ?: it.source?.title ?: tr("Файл с телефона"),style=MaterialTheme.typography.bodySmall) }
                        TextButton(onClick={preview=!preview},contentPadding=PaddingValues(0.dp)) { Text(if(preview)tr("Скрыть 3D-просмотр") else tr("3D-просмотр на столе"),style=MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if(preview) {
                val renderer=remember { PlateRenderer() };val framed=remember { arrayOfNulls<io.github.whoxllm.orcamicro.core.settings.BedShape>(1) }
                SceneView(renderer,Modifier.fillMaxWidth().height(220.dp),onModelDrag=viewModel::moveModelBy,onModelDragEnd=viewModel::finishModelMove) { r->
                    val bed=state.bed
                    if(bed!=null&&framed[0]!=bed){r.camera.frame(bed.centerX,bed.centerY,0f,max(bed.width,bed.depth)*.6f);framed[0]=bed}
                    r.setBed(bed,state.printableHeight);r.setRenderOptions(state.renderOptions);r.setModel(state.model?.placed,state.model?.fits?:true)
                    r.modelColor=state.modelRgb();r.offsetX=state.model?.offsetX?:0f;r.offsetY=state.model?.offsetY?:0f
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr("Двойной тап и перенос"),style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
                    TextButton(onClick=viewModel::editQuickPrintPlacement){OrcaIcon(OrcaAction.Move,null,Modifier.size(20.dp));Text(tr("Изменить"),Modifier.padding(start=4.dp))}
                }
            } else TextButton(onClick=viewModel::editQuickPrintPlacement){Text(tr("Разместить модель на столе"))}
            if(state.model?.fits==false)Text(tr("Модель не помещается на выбранном принтере. Измените положение или масштаб на столе."),color=MaterialTheme.colorScheme.error)
            QuickPrintSection(tr("Филамент")) {
                PresetDraftNotice(state,viewModel,PresetType.FILAMENT)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr("Мои пресеты филамента"),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    Switch(ownOnly,{ownOnly=it},enabled=ownFilament.isNotEmpty())
                }
                if(ownFilament.isEmpty())Text(tr("Импортируйте свой конфиг во вкладке профилей."),style=MaterialTheme.typography.bodySmall)
                ChoiceField(tr("Пластик"),state.inputs?.filament?.string("name").orEmpty() + state.presetSuffix(PresetType.FILAMENT),ownFilament.map { PickerItem(it.id,it.name,tr("Мои профили")) }+
                    if(ownOnly)emptyList() else stock?.let { catalog.filamentsFor(selection.vendor,it).map { ref->PickerItem("stock:${ref.vendor}:${ref.name}",ref.name,tr("Штатные · {0}" ,ref.vendor)) } }.orEmpty()) { id->
                    ownFilament.firstOrNull { it.id==id }?.let(viewModel::selectUserPreset) ?: stock?.let { p->catalog.filamentsFor(selection.vendor,p).firstOrNull { "stock:${it.vendor}:${it.name}"==id }?.let(viewModel::selectFilament) }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Canvas(Modifier.size(26.dp)) { drawCircle(Color(0xFF000000 or state.modelRgb().toLong())) }
                    TextButton(onClick={showColor=true}){Text(tr("Цвет модели"))}
                }
                Text(FILAMENT_REMINDER,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            }
            QuickPrintSection(tr("Принтер")) {
                PresetDraftNotice(state,viewModel,PresetType.MACHINE)
                if(state.printers.isNotEmpty())ChoiceField(tr("Принтер в сети"),state.printers.firstOrNull { it.id==state.activePrinterId }?.name.orEmpty(),state.printers.map { PickerItem(it.id,it.name,it.connection.apiUrl) },onSelect=viewModel::selectSavedPrinter)
                else OutlinedButton(shape=MaterialTheme.shapes.small,onClick={viewModel.navigate(Screen.DEVICE)}){Text(tr("Добавить подключение"))}
                ChoiceField(tr("Профиль принтера"),state.inputs?.machine?.string("name").orEmpty() + state.presetSuffix(PresetType.MACHINE),
                    ownMachine.map { PickerItem(it.id,it.name,tr("Мои профили")) }+PickerItem("stock",selection.printer,tr("Штатный профиль"))) { id->
                    if(id=="stock")viewModel.selectPrinter(selection.vendor,selection.printer)else ownMachine.firstOrNull{it.id==id}?.let(viewModel::selectUserPreset)
                }
                TextButton(onClick={viewModel.openPreset(PresetType.MACHINE)}){Text(tr("Выбрать другой профиль принтера"))}
            }
            QuickPrintSection(tr("Параметры печати")) {
                PresetDraftNotice(state,viewModel,PresetType.PROCESS)
                ChoiceField(tr("Процесс печати"),state.inputs?.process?.string("name").orEmpty() + state.presetSuffix(PresetType.PROCESS),ownProcess.map { PickerItem(it.id,it.name,tr("Мои профили")) }+
                    stock?.let { catalog.processesFor(selection.vendor,it).map { name->PickerItem("stock:${name}",name,tr("Штатные")) } }.orEmpty()) { id->
                    ownProcess.firstOrNull { it.id==id }?.let(viewModel::selectUserPreset) ?: viewModel.selectProcess(id.removePrefix("stock:"))
                }
                QuickParameter(ptr("Высота слоя, мм"),"layer_height",state,viewModel,.01f..(state.inputs?.machine?.first("nozzle_diameter")?.toFloatOrNull() ?: .4f),onValid={validFields["layer"]=it})
                QuickParameter(ptr("Заполнение, %"),"sparse_infill_density",state,viewModel,0f..100f,suffix="%",onValid={validFields["infill"]=it})
                QuickParameter(ptr("Количество стенок"),"wall_loops",state,viewModel,1f..50f,integer=true,onValid={validFields["walls"]=it})
                OutlinedButton(shape=MaterialTheme.shapes.small,onClick={viewModel.openSettings()}){Text(tr("Все параметры печати"))}
            }
            if(state.lastResult!=null)OutlinedButton(shape=MaterialTheme.shapes.small,onClick={viewModel.navigate(Screen.PREVIEW)},modifier=Modifier.fillMaxWidth()){Text(tr("Проверить слои G-code"))}
        }
    }
    if(showColor)ModelColorSheet(state,viewModel){showColor=false}
}

@Composable private fun QuickPrintSection(title:String,content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable private fun QuickParameter(label:String,key:String,state:UiState,vm:MainViewModel,range:ClosedFloatingPointRange<Float>,suffix:String="",integer:Boolean=false,onValid:(Boolean)->Unit) {
    val stored=state.inputs?.process?.first(key).orEmpty().removeSuffix("%")
    var text by remember(state.selection?.process,state.edits.importedProcess?.string("name"),stored) { mutableStateOf(stored) }
    val value=text.replace(',','.').toFloatOrNull()
    val valid=value!=null&&value.isFinite()&&value in range&&(!integer||value%1f==0f)
    SideEffect { onValid(valid) }
    OutlinedTextField(text,{text=it;if(it.replace(',','.').toFloatOrNull()?.let { n->n.isFinite()&&n in range&&(!integer||n%1f==0f) }==true)vm.setEdit(PresetType.PROCESS,key,it.replace(',','.')+suffix)},label={Text(label)},isError=!valid,supportingText={if(!valid)Text(tr("Допустимо: {0}–{1}" ,range.start,range.endInclusive))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
}

@Composable
fun PrintSendDialog(state:UiState,vm:MainViewModel) {
    var confirmed by remember(state.lastResult,state.externalGcode,state.activePrinterId) { mutableStateOf(false) }
    val ready=(state.lastResult!=null||state.externalGcode!=null)&&state.connection.apiUrl.isNotBlank()&&state.uploadPercent==null&&!state.deviceBusy
    androidx.compose.ui.window.Dialog(onDismissRequest={vm.showPrintDialog(false)},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max=440.dp).heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.9f).dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(tr("Отправка на печать"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick={vm.showPrintDialog(false)}){Text(tr("Закрыть"))}}
                Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(state.externalGcode?.name ?: state.lastResult?.modelName.orEmpty(),style=MaterialTheme.typography.titleMedium,maxLines=2)
                    if(state.externalGcode==null&&state.model!=null) {
                        val renderer=remember{PlateRenderer()}
                        SceneView(renderer,Modifier.fillMaxWidth().height(160.dp)){r->
                            state.bed?.let{bed->r.camera.frame(bed.centerX,bed.centerY,0f,kotlin.math.max(bed.width,bed.depth)*.6f);r.setBed(bed,state.printableHeight)}
                            r.setModel(state.model.placed,state.model.fits);r.modelColor=state.modelRgb();r.offsetX=state.model.offsetX;r.offsetY=state.model.offsetY
                        }
                    }else if(state.externalGcode!=null)Text(tr("Внешний G-code: параметры заданы в файле; 3D-модель недоступна."),style=MaterialTheme.typography.bodySmall)
                    Text(state.printers.firstOrNull{it.id==state.activePrinterId}?.name ?: tr("Подключение не настроено"),style=MaterialTheme.typography.titleSmall)
                    if(state.externalGcode==null){
                        Text(tr("Сопло: {0} мм",state.inputs?.machine?.first("nozzle_diameter").orEmpty()))
                        Text(tr("Пластик: {0}",state.inputs?.filament?.string("name").orEmpty()))
                        Text(tr("Процесс: {0}",state.inputs?.process?.string("name").orEmpty()))
                    }
                    Text(FILAMENT_REMINDER,style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(confirmed,{confirmed=it});Text(tr("Филамент загружен, стол свободен"),style=MaterialTheme.typography.bodyMedium)}
                    Text(tr("«Загрузить» сохраняет файл на принтере. «Напечатать» загрузит его и начнёт печать."),style=MaterialTheme.typography.bodySmall)
                    state.uploadedFile?.let{Text(tr("На принтере: {0}",it),style=MaterialTheme.typography.labelMedium)}
                    if(state.connection.apiUrl.isBlank())TextButton(onClick={vm.showPrintDialog(false);vm.navigate(Screen.DEVICE)}){Text(tr("Настроить принтер"))}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=vm::uploadLastGcode,enabled=ready,modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.small){Text(tr("Загрузить"))}
                    Button(onClick=vm::uploadAndPrintGcode,enabled=confirmed&&ready,modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.small){Text(tr("Напечатать"))}
                }
            }
        }
    }
}
