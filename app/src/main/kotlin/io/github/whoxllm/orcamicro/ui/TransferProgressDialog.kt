package io.github.whoxllm.orcamicro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.i18n.tr

/** One progress surface across tabs, including metadata lookup and model validation. */
@Composable
fun TransferProgressDialog(state: UiState) {
    val upload = state.uploadPercent != null
    val percent = (if(upload) state.uploadPercent else state.libraryPercent)?.coerceIn(0,100)
    val waitingForPrinter=upload && percent!=null && percent>=99
    AlertDialog(onDismissRequest={}, title={Text(if(upload)tr("Загрузка G-code")else tr("Загрузка модели"))}, text={
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(upload) {
                if(waitingForPrinter) tr("Принтер обрабатывает файл…") else tr("Отправка файла на принтер…")
            } else state.libraryProgress ?: tr("Обработка модели"))
            if(percent==null || waitingForPrinter) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress={percent/100f},modifier=Modifier.fillMaxWidth())
            if(percent!=null && !waitingForPrinter) Text("$percent%",style=MaterialTheme.typography.labelLarge)
        }
    },confirmButton={})
}
