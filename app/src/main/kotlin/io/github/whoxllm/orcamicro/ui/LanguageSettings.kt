package io.github.whoxllm.orcamicro.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.core.i18n.tr
import io.github.whoxllm.orcamicro.core.i18n.ParameterLanguage

@Composable fun LanguageSettingsCard(state:UiState,vm:MainViewModel) {
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    val manager=context.getSystemService(LocaleManager::class.java)
    var selected by remember(configuration) { mutableStateOf(manager.applicationLocales.toLanguageTags()) }
    val system=tr("По языку системы")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr("Языки"),style=MaterialTheme.typography.titleMedium)
            ChoiceField(tr("Язык интерфейса"),when(selected){"ru"->"Русский";"en"->"English";else->system},listOf(PickerItem("system",system),PickerItem("ru","Русский"),PickerItem("en","English"))) {
                selected=if(it=="system")""else it
                manager.applicationLocales=if(it=="system")LocaleList.getEmptyLocaleList()else LocaleList.forLanguageTags(it)
            }
            ChoiceField(tr("Язык параметров"),when(state.parameterLanguage) {
                ParameterLanguage.INTERFACE -> tr("Как у интерфейса")
                ParameterLanguage.RUSSIAN -> "Русский"
                ParameterLanguage.ENGLISH -> "English"
            },listOf(PickerItem("INTERFACE",tr("Как у интерфейса")),PickerItem("RUSSIAN","Русский"),PickerItem("ENGLISH","English"))) {
                vm.setParameterLanguage(ParameterLanguage.valueOf(it))
            }
            Text(tr("Названия, описания, разделы и значения параметров печати могут использовать другой язык."),style=MaterialTheme.typography.bodySmall)

        }
    }
}
