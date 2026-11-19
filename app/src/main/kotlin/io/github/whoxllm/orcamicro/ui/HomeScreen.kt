package io.github.whoxllm.orcamicro.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.R
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.data.RecentProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(state:UiState, viewModel:MainViewModel) {
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::openModel) }
    val snackbar=remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<RecentProject?>(null) }
    MessageEffect(state.message,snackbar,viewModel::consumeMessage)
    Scaffold(
        topBar={ TopAppBar(title={ Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.orca_logo),null,Modifier.size(30.dp))
            Text("OrcaMicro",style=MaterialTheme.typography.titleLarge)
        } },actions={ IconButton(onClick={viewModel.navigate(Screen.ABOUT)}) { OrcaIcon(OrcaAction.Settings,tr("О программе и настройки")) } }) },
        snackbarHost={SnackbarHost(snackbar)},
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max=700.dp).fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    Text(tr("Начнём печать"),style=MaterialTheme.typography.headlineSmall)
                    Text(tr("Откройте модель или вернитесь к проекту"),Modifier.padding(top=4.dp,bottom=16.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Button(onClick=viewModel::newProject,enabled=!state.libraryBusy,modifier=Modifier.weight(1f).heightIn(min=56.dp),shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(10.dp)) {
                            OrcaIcon(OrcaAction.Add,null);Text(tr("Новый проект"),Modifier.padding(start=8.dp))
                        }
                        OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!state.libraryBusy,modifier=Modifier.weight(1f).heightIn(min=56.dp),shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(10.dp)) {
                            OrcaIcon(OrcaAction.Library,null);Text(tr("Открыть модель"),Modifier.padding(start=8.dp))
                        }
                    }
                }
                if(state.model!=null) item {
                    Card(onClick={viewModel.navigate(Screen.PLATE)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.PlayArrow,null)
                            Column(Modifier.weight(1f)) {
                                Text(tr("Продолжить работу"),style=MaterialTheme.typography.titleMedium)
                                Text(state.model.name,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.KeyboardArrowRight,null)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={viewModel.navigate(Screen.LIBRARY)},modifier=Modifier.weight(1f)) { Text(tr("Модели")) }
                        OutlinedButton(onClick={viewModel.navigate(Screen.DEVICE)},modifier=Modifier.weight(1f)) { Text(tr("Принтер")) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(tr("Последние проекты"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        if(state.projectSaving) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                    }
                    Text(if(state.projectSaving)tr("Сохранение проекта…") else tr("Модель и параметры сохраняются автоматически"),Modifier.padding(top=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(state.recentProjects.isEmpty()) item {
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        OrcaIcon(OrcaAction.Library,null)
                        Text(tr("Здесь появятся ваши проекты"),style=MaterialTheme.typography.titleMedium)
                        Text(tr("Добавьте STL или 3MF на стол. Его можно будет открыть вместе с положением модели и настройками печати."),style=MaterialTheme.typography.bodyMedium)
                    } }
                }
                items(state.recentProjects,key={it.document.id}) { project ->
                    RecentProjectCard(project,onOpen={viewModel.openRecentProject(project.document.id)},onDelete={deleting=project})
                }
            }
        }
    }
    deleting?.let { project -> AlertDialog(onDismissRequest={deleting=null},title={Text(tr("Удалить проект?"))},text={Text(tr("Будут удалены сохранённая модель и настройки проекта «{0}». Исходный файл останется на месте.",project.document.name))},confirmButton={TextButton(onClick={deleting=null;viewModel.deleteRecentProject(project.document.id)}){Text(tr("Удалить"))}},dismissButton={TextButton(onClick={deleting=null}){Text(tr("Отмена"))}}) }
}

@Composable
private fun RecentProjectCard(project:RecentProject,onOpen:()->Unit,onDelete:()->Unit) {
    val doc=project.document
    var image by remember(doc.id,doc.modifiedAt) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(doc.id,doc.modifiedAt) {
        image=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(project.thumbnail.path)?.asImageBitmap() }
    }
    Card(onClick=onOpen,modifier=Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.padding(10.dp).size(84.dp).background(MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.shapes.small),contentAlignment=Alignment.Center) {
                image?.let { Image(it,null,Modifier.fillMaxSize().padding(4.dp),contentScale=ContentScale.Fit) }
                    ?: OrcaIcon(OrcaAction.PlateAdd,null)
            }
            Column(Modifier.weight(1f).padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(doc.name,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(doc.printerName,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(doc.filamentName,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(remember(doc.modifiedAt) { SimpleDateFormat("d MMM · HH:mm",Locale.getDefault()).format(Date(doc.modifiedAt)) },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick=onDelete) { OrcaIcon(OrcaAction.Delete,tr("Удалить проект: {0}",doc.name)) }
        }
    }
}
