package io.github.whoxllm.orcamicro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.i18n.tr

@Composable fun UploadSplitButton(state:UiState,vm:MainViewModel,modifier:Modifier=Modifier) {
    var menu by remember{mutableStateOf(false)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){it?.let(vm::exportGcode)}
    val ready=(state.lastResult!=null||state.externalGcode!=null)&&state.uploadPercent==null
    Box(modifier){
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)) {
            Button(onClick={vm.showPrintDialog(true)},enabled=ready,modifier=Modifier.weight(1f),shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(horizontal=6.dp,vertical=10.dp)){Text(tr("Загрузить"),maxLines=1)}
            Spacer(Modifier.width(1.dp))
            Button(onClick={menu=true},enabled=ready,modifier=Modifier.width(48.dp),shape=RoundedCornerShape(0.dp),contentPadding=PaddingValues(0.dp)){Icon(Icons.Default.KeyboardArrowUp,tr("Действия с G-code"))}
        }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
            DropdownMenuItem(text={Text(tr("Отправить на принтер"))},onClick={menu=false;vm.showPrintDialog(true)})
            DropdownMenuItem(text={Text(tr("Сохранить G-code локально"))},onClick={menu=false;save.launch(state.externalGcode?.name ?: ((state.lastResult?.modelName?.substringBeforeLast('.') ?: "model")+".gcode"))})
        }
    }
}
