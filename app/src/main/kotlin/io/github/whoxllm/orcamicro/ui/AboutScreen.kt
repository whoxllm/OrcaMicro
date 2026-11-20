package io.github.whoxllm.orcamicro.ui

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.whoxllm.orcamicro.BuildConfig

private val thirdParty = listOf(
    "OrcaSlicer (libslic3r), AGPL-3.0, by SoftFever and contributors, based on Bambu Studio, PrusaSlicer and Slic3r",
    "Boost, Boost Software License 1.0",
    "oneTBB, Apache-2.0",
    "CGAL, GPL-3.0 / LGPL-3.0",
    "Eigen, MPL-2.0",
    "Open CASCADE Technology, LGPL-2.1 with exception",
    "OpenCV, Apache-2.0",
    "GMP and MPFR, LGPL-3.0",
    "NLopt, MIT / LGPL-2.1",
    "Draco, Apache-2.0",
    "libnoise, LGPL-2.1",
    "cereal, BSD-3-Clause",
    "expat, MIT",
    "libpng, PNG Reference Library License",
    "libjpeg-turbo, IJG / BSD-3-Clause",
    "FreeType, FreeType License",
    "Clipper and Clipper2, Boost Software License 1.0",
    "Qhull, Qhull License",
    "admesh, libigl, mcut, miniz, nlohmann/json and the other libraries bundled with OrcaSlicer",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization, AndroidX and Jetpack Compose, Apache-2.0",
)

@Composable
fun AboutScreen(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    fun open(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    androidx.activity.compose.BackHandler(enabled=state.appSettingsOpen) { viewModel.closeAppSettings() }
    Scaffold(topBar = { DesktopHeader(state, viewModel) {
        IconButton(onClick={if(state.appSettingsOpen)viewModel.closeAppSettings()else viewModel.openAppSettings()}) { Icon(Icons.Default.Settings,tr("Настройки приложения")) }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(state.appSettingsOpen) {
                AppSettingsContent(state,viewModel)
                return@Column
            }
            Text("OrcaMicro ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text(tr("Слайсер для Android на движке OrcaSlicer. Нарезка работает на телефоне без интернета; сеть нужна только каталогам моделей и принтеру. Неофициальный проект, не связан с командой OrcaSlicer."))
            Text(tr("Авторы"), style = MaterialTheme.typography.titleMedium)
            Text(tr("OrcaMicro — whoxllm и участники проекта"))
            Text(tr("Сенсорный интерфейс, пульт принтера, каталоги моделей и Quick Print — redfaced97"))
            Text(tr("Бета-тестер — redfaced97 и Ender 3 v3 SE (Klipper)"))
            Text(tr("Источники"), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { open("https://github.com/whoxllm/OrcaMicro") }) { Text(tr("Исходники OrcaMicro")) }
            Text(tr("Движок libslic3r, профили, основа русских подписей и подсказок — OrcaSlicer {0}, SoftFever и участники; на основе Bambu Studio, PrusaSlicer и Slic3r. Недостающие русские подписи и краткие пояснения дополнены в этой адаптации." ,BuildConfig.ORCA_VERSION))
            OutlinedButton(onClick = { open("https://github.com/OrcaSlicer/OrcaSlicer") }) { Text(tr("Исходники OrcaSlicer")) }
            Text(tr("Шрифт Inter — Rasmus Andersson и участники, SIL OFL 1.1. Иконки действий и иконка приложения — официальный репозиторий OrcaSlicer: resources/images/OrcaSlicer.svg. Акцент #009688 и серые тона — из настольного интерфейса OrcaSlicer. Android-представление иконки преобразовано из оригинального SVG."))
            OutlinedButton(onClick = { open("https://www.orcaslicer.com/") }) { Text(tr("Официальный сайт OrcaSlicer")) }
            Text(tr("Пульт, графики температур и отправка G-code — собственный HTTP-клиент по документации Moonraker и Klipper. Веб-интерфейс открывает установленный Mainsail или Fluidd. Каталоги моделей — нативный интерфейс и HTTP-клиенты JSON API Thingiverse, Printables, Cults3D и MakerWorld. Карточки показывают автора, источник, лицензию и доступную цену. Quick Print использует ваши профили и локальную нарезку. Образец компоновки — экран Prepare to Print в Bambu Handy; код этого экрана написан для Android-адаптации. API Cults не предоставляет файлы."))
            Text(tr("Возможности этой версии"), style = MaterialTheme.typography.titleMedium)
            Text(tr("STL, ZIP, геометрия и параметры проектов 3MF; один объект и материал, 751 параметр, свои профили, каталоги моделей и просмотр слоёв. Klipper / OctoPrint, статистика и два языка. Покраска, мультиматериальная нарезка и раздельные столы 3MF пока не поддерживаются. Совместимость 90% не измерена."))
            Text(tr("Лицензия AGPL-3.0. Исходники доступны по ссылке ниже и в разделе Source code у каждого релиза GitHub."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { open("https://github.com/whoxllm/OrcaMicro/releases") }) { Text(tr("Релизы и обновления")) }
            if (BuildConfig.SOURCE_URL.isNotBlank()) OutlinedButton(onClick = { open(BuildConfig.SOURCE_URL) }) { Text(tr("Исходники этой сборки")) }
            Text(tr("Библиотеки"), style = MaterialTheme.typography.titleMedium)
            for (line in thirdParty) Text("• ${line}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
