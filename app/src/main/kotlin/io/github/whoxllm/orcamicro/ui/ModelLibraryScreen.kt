package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.R
import io.github.whoxllm.orcamicro.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Composable
fun ModelLibraryScreen(state:UiState,vm:MainViewModel) {
    LaunchedEffect(Unit) { vm.cleanModelCache();if(!state.catalogLoaded)vm.searchModels() }
    val snackbar=remember { SnackbarHostState() };MessageEffect(state.message,snackbar,vm::consumeMessage)
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openModel) }
    var query by rememberSaveable { mutableStateOf(state.catalogQuery) }
    var tagText by rememberSaveable { mutableStateOf(state.catalogTags.joinToString(", ")) }
    var online by rememberSaveable { mutableStateOf(true) }
    var showTags by rememberSaveable { mutableStateOf(state.catalogTags.isNotEmpty()) }
    var details by remember { mutableStateOf<LibraryModel?>(null) }
    var editTags by remember { mutableStateOf<LibraryModel?>(null) };var newTags by remember { mutableStateOf("") }
    val cacheSize=android.text.format.Formatter.formatShortFileSize(LocalContext.current,state.modelCacheBytes)
    val tags=ModelTags.parse(tagText)
    val local=state.libraryModels.filter { ModelTags.matches(it,query.takeUnless { q->q.contains("://") }.orEmpty(),tags) }
    val remote=state.catalogModels.filter { m->state.catalogTags.all { tag->m.tags.any { it.equals(tag,true) } } }
    fun search(source:ModelSource=state.catalogSource) {
        if(query.contains("://"))vm.openModelLink(query)
        else vm.searchModels(source,query,tags)
    }
    Scaffold(topBar={DesktopHeader(state,vm) {
        IconButton(onClick={pick.launch(arrayOf("*/*"))}){OrcaIcon(OrcaAction.Import,tr("Открыть скачанный файл"))}
    }},snackbarHost={SnackbarHost(snackbar)}) { padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) {
                Text(tr("Каталог моделей"),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={showTags=!showTags}){Text(if(showTags)tr("Теги ▴") else tr("Теги"))}
            } }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(online,{online=true},label={Text(tr("В интернете"))})
                FilterChip(!online,{online=false},label={Text(tr("Мои модели · {0}" ,state.libraryModels.size))})
            } }
            item { OutlinedTextField(query,{query=it},label={Text(tr("Поиск или ссылка на модель"))},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={if(online||query.contains("://"))search()}),trailingIcon={if(online||query.contains("://"))TextButton(onClick={search()},enabled=!state.libraryBusy){Text(if(query.contains("://"))tr("Открыть") else tr("Найти"))}}) }
            if(showTags)item { OutlinedTextField(tagText,{tagText=it},label={Text(tr("Теги через запятую"))},placeholder={Text("phone, holder")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
            if(online)item { LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(listOf(ModelSource.PRINTABLES,ModelSource.MAKERWORLD,ModelSource.THINGIVERSE,ModelSource.CULTS)) { source->FilterChip(state.catalogSource==source,{search(source)},label={Text(source.title)}) } } }
            if(online) {
                if(state.catalogLoading)item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.catalogError?.let { error->item { OutlinedCard { Column(Modifier.padding(12.dp)) {
                    Text(error,color=MaterialTheme.colorScheme.error)
                    Row { TextButton(onClick={search()}){Text(tr("Повторить"))};TextButton(onClick=vm::openAppSettings){Text(tr("Настройки API"))} }
                } } } }
                item { Text(tr("{0} · найдено {1}" ,state.catalogSource.title,state.catalogTotal)+(if(state.catalogTags.isNotEmpty())tr(" · теги: {0}" ,state.catalogTags.joinToString { "#${it}" }) else ""),style=MaterialTheme.typography.labelMedium) }
                if(!state.catalogLoading&&state.catalogError==null&&remote.isEmpty())item { Text(tr("Ничего не найдено. Измените запрос, теги или площадку.")) }
                items(remote,key={it.id}) { model->LibraryModelCard(model,state.libraryBusy,onPlate={vm.prepareCatalogModel(model,false)},onQuickPrint={vm.prepareCatalogModel(model,true)},onDetails={details=model}) }
                if(state.catalogNext!=null)item { OutlinedButton(onClick={vm.searchModels(more=true)},enabled=!state.catalogLoading,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth()){Text(tr("Загрузить ещё"))} }
            } else {
                item { Text(tr("Кэш: {0} · {1}" ,cacheSize,if(state.modelCacheDays==0)tr("без срока") else tr("{0} дн." ,state.modelCacheDays)),style=MaterialTheme.typography.bodySmall) }
                val commonTags=state.libraryModels.flatMap { it.tags }.distinct().sorted().take(20)
                if(commonTags.isNotEmpty())item { LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(commonTags) { tag->FilterChip(tag in tags,{tagText=(if(tag in tags)tags-tag else tags+tag).joinToString(", ")},label={Text("#${tag}")}) } } }
                if(local.isEmpty())item { Text(if(state.libraryModels.isEmpty())tr("Откройте модель из каталога или добавьте свой файл.") else tr("Нет моделей с таким запросом и тегами.")) }
                items(local,key={it.id}) { model->Column {
                    LibraryModelCard(model,state.libraryBusy,onPlate={vm.openLibraryModel(model.id)},onQuickPrint={vm.openLibraryModel(model.id,true)},onDetails={details=model})
                    Row { TextButton(onClick={editTags=model;newTags=model.tags.joinToString(", ")}){Text(tr("Теги"))};Spacer(Modifier.weight(1f));TextButton(onClick={vm.deleteLibraryCard(model.id)},enabled=!state.libraryBusy){Text(tr("Удалить"))} }
                } }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    details?.let { model->AlertDialog(onDismissRequest={details=null},title={Text(model.title)},text={Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        ModelThumbnail(model.imageUrl,Modifier.fillMaxWidth().height(170.dp))
        ModelAttribution(model)
        if(model.tags.isNotEmpty())Text(model.tags.joinToString(" "){"#${it}"},style=MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(model.url,style=MaterialTheme.typography.bodySmall) }
    }},confirmButton={TextButton(onClick={details=null;vm.prepareCatalogModel(model,true)}){Text("Quick Print")}},dismissButton={TextButton(onClick={details=null}){Text(tr("Закрыть"))}}) }
    editTags?.let { model->AlertDialog(onDismissRequest={editTags=null},title={Text(tr("Теги модели"))},text={OutlinedTextField(newTags,{newTags=it},label={Text(tr("Через запятую"))},modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={vm.setModelTags(model.id,newTags);editTags=null}){Text(tr("Сохранить"))}},dismissButton={TextButton(onClick={editTags=null}){Text(tr("Отмена"))}}) }

}

@Composable
private fun LibraryModelCard(model:LibraryModel,busy:Boolean,onPlate:()->Unit,onQuickPrint:()->Unit,onDetails:()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ModelThumbnail(model.imageUrl,Modifier.size(104.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(model.title,style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                    ModelAttribution(model,compact=true)
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onPlate,enabled=!busy,shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(8.dp),modifier=Modifier.weight(1f)){Text(tr("На стол"),maxLines=1)}
                Button(onClick=onQuickPrint,enabled=!busy,shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(8.dp),modifier=Modifier.weight(1f)){Text("Quick Print",maxLines=1)}
            }
            TextButton(onClick=onDetails,contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.heightIn(min=32.dp)){Text(tr("Подробнее")+(if(model.localFile!=null)tr(" · в кэше") else ""),style=MaterialTheme.typography.labelMedium)}
        }
    }
}

@Composable
fun ModelAttribution(model:LibraryModel,compact:Boolean=false) {
    val style=if(compact)MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Text(model.source?.title ?: tr("Файл с телефона"),style=style,color=MaterialTheme.colorScheme.primary)
    Text(tr("Автор: {0}" ,model.author ?: tr("не указан")),style=style,maxLines=if(compact)1 else Int.MAX_VALUE,overflow=TextOverflow.Ellipsis)
    Text(when(model.paid){true->tr("Платная{0}" ,model.priceLabel?.let { " · ${it}" }.orEmpty());false->tr("Бесплатная");null->tr("Цена не указана API")},style=style)
    Text(tr("Лицензия: {0}" ,model.license ?: tr("не указана")),style=style,maxLines=if(compact)2 else Int.MAX_VALUE,overflow=TextOverflow.Ellipsis)
}

private val thumbnails=object:LruCache<String,Bitmap>(4*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount }
@Composable
fun ModelThumbnail(url:String?,modifier:Modifier=Modifier) {
    var bitmap by remember(url) { mutableStateOf(url?.let(thumbnails::get)) }
    LaunchedEffect(url) {
        if(bitmap!=null)return@LaunchedEffect
        val decoded=withContext(Dispatchers.IO) { runCatching {
            val uri=URI(url ?: return@withContext null)
            if(uri.scheme!="https"||uri.userInfo!=null)return@withContext null
            val http=(uri.toURL().openConnection() as HttpURLConnection).apply { connectTimeout=7000;readTimeout=7000;instanceFollowRedirects=false;setRequestProperty("User-Agent","OrcaSlicer-Android/0.6.0") }
            try {
                if(http.responseCode !in 200..299 || http.contentLengthLong>3*1024*1024)return@withContext null
                val bytes=http.inputStream.use { it.readNBytes(3*1024*1024+1) };if(bytes.size>3*1024*1024)return@withContext null
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                if(bounds.outWidth<=0||bounds.outHeight<=0)return@withContext null
                val options=BitmapFactory.Options().apply { var n=1;while(maxOf(bounds.outWidth,bounds.outHeight)/n>640)n*=2;inSampleSize=n }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            }finally{http.disconnect()}
        }.getOrNull() }
        bitmap=decoded; if(decoded!=null&&url!=null)thumbnails.put(url,decoded)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
            ?: Image(painterResource(R.drawable.orca_logo),tr("Фото модели недоступно"),Modifier.size(38.dp))
    }
}

@Composable
fun CatalogApiDialog(initial:CatalogCredentials,onSave:(CatalogCredentials)->Unit,onDismiss:()->Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(tr("API площадок"))},text={Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(tr("Поиск и карточки работают внутри приложения. Ключи шифруются Android Keystore и сохраняются только на этом телефоне."),style=MaterialTheme.typography.bodySmall)
        Text("Printables",style=MaterialTheme.typography.titleSmall)
        Text(tr("Поиск и открытые STL без ключа. Для ограниченных файлов можно добавить свою сессию."),style=MaterialTheme.typography.bodySmall)
        SecretField(tr("Session cookie (необязательно)"),value.printablesCookie){value=value.copy(printablesCookie=it)}
        Text("MakerWorld",style=MaterialTheme.typography.titleSmall)
        Text(tr("Поиск без ключа. Для скачивания защищённых 3MF — ваш access token Bambu."),style=MaterialTheme.typography.bodySmall)
        SecretField("Bambu access token",value.makerWorldToken){value=value.copy(makerWorldToken=it)}
        Text("Thingiverse",style=MaterialTheme.typography.titleSmall)
        Text(tr("Ваш App Token из настроек приложения Thingiverse Developers."),style=MaterialTheme.typography.bodySmall)
        SecretField("Thingiverse App Token",value.thingiverseToken){value=value.copy(thingiverseToken=it)}
        Text("Cults3D",style=MaterialTheme.typography.titleSmall)
        OutlinedTextField(value.cultsUser,{value=value.copy(cultsUser=it)},label={Text(tr("Ник Cults"))},singleLine=true,modifier=Modifier.fillMaxWidth())
        SecretField("Cults API key",value.cultsKey){value=value.copy(cultsKey=it)}
        Text(tr("Cults API выдаёт карточки, цены и лицензии, но не файлы. К карточке можно прикрепить свой файл."),style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={onSave(value)}){Text(tr("Сохранить"))}},dismissButton={TextButton(onClick=onDismiss){Text(tr("Отмена"))}})
}
@Composable private fun SecretField(label:String,value:String,onChange:(String)->Unit) { OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth()) }

@Composable
fun CatalogFilesDialog(state:UiState,vm:MainViewModel) {
    val model=state.catalogFileModel ?: return
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attachCatalogFile) }
    AlertDialog(onDismissRequest={if(!state.libraryBusy)vm.dismissCatalogFiles()},title={Text(model.title,maxLines=2)},text={
        LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { ModelAttribution(model) }
            state.catalogNotice?.let { notice->item { Text(notice,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            if(state.catalogFiles.isNotEmpty())item { Text(tr("Выберите файл для {0}" ,if(state.catalogFileSetup)"Quick Print" else tr("стола")),style=MaterialTheme.typography.titleSmall) }
            items(state.catalogFiles.distinctBy { Triple(it.kind,it.id,it.url) },key={it.kind+it.id+it.url.orEmpty()}) { file->OutlinedButton(onClick={vm.chooseCatalogFile(file)},enabled=!state.libraryBusy,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth()){Text(file.name)} }
            if(state.catalogFiles.isEmpty()&&state.catalogNotice==null)item { Text(tr("API не вернул STL/3MF. Можно прикрепить свой файл.")) }
            item { TextButton(onClick={pick.launch(arrayOf("*/*"))},enabled=!state.libraryBusy){Text(tr("Прикрепить свой STL / ZIP / 3MF"))} }
        }
    },confirmButton={TextButton(onClick=vm::dismissCatalogFiles,enabled=!state.libraryBusy){Text(tr("Закрыть"))}})
}
