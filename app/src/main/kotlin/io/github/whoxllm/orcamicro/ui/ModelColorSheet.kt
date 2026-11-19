package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable fun ModelColorSheet(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf("%06X".format(state.modelRgb())) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Цвет пластика и модели"), style = MaterialTheme.typography.titleLarge)
            Text(tr("Цвет сохранится в текущих параметрах пластика. Для отдельного пресета выберите «Сохранить как»."), style = MaterialTheme.typography.bodySmall)
            for (row in listOf(listOf("F4F4F4", "252525", "E6B83C", "E5484D", "F08A35"), listOf("009688", "2678D4", "8A62CF", "D5A6C8", "9EADB5"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (c in row) Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong(16))).clickable { hex = c; vm.setModelColor(c) })
                }
            }
            OutlinedTextField(hex, { hex = it.removePrefix("#").take(6) }, label = { Text("HEX · RRGGBB") }, leadingIcon = { Text("#") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setModelColor(hex); onDismiss() }, enabled = hex.matches(Regex("[0-9a-fA-F]{6}")), modifier = Modifier.fillMaxWidth()) { Text(tr("Применить цвет")) }
        }
    }
}
