package io.github.whoxllm.orcamicro

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.whoxllm.orcamicro.update.AppUpdater
import io.github.whoxllm.orcamicro.update.UpdateCheckService
import io.github.whoxllm.orcamicro.ui.SliceState
import kotlinx.coroutines.launch
import android.provider.Settings
import androidx.activity.viewModels
import io.github.whoxllm.orcamicro.ui.MainViewModel
import io.github.whoxllm.orcamicro.ui.OrcaMicroApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val installer=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val unknownSources=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(packageManager.canRequestPackageInstalls())installUpdate()
        else AppUpdater.get(this).reportError(tr("Разрешите установку обновлений из OrcaSlicer и нажмите «Установить обновление» ещё раз."))
    }
    private fun installUpdate() {
        val updater=AppUpdater.get(this)
        lifecycleScope.launch {
            try {
                val s=viewModel.state.value
                require(s.slice !is SliceState.Running&&s.uploadPercent==null&&!s.libraryBusy) { tr("Дождитесь завершения нарезки, загрузки или открытия модели") }
                if(!packageManager.canRequestPackageInstalls()) {
                    unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${packageName}")))
                } else {
                    val intent=updater.installerIntent()
                    val now=viewModel.state.value
                    require(now.slice !is SliceState.Running&&now.uploadPercent==null&&!now.libraryBusy) { tr("Дождитесь завершения текущей операции") }
                    installer.launch(intent)
                }
            }catch(e:Exception){updater.reportError(e.message ?: tr("Не удалось открыть установщик Android"))}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.whoxllm.orcamicro.core.i18n.AppText.english=resources.configuration.locales[0].language!="ru"
        enableEdgeToEdge()
        io.github.whoxllm.orcamicro.data.ModelCacheService.schedule(this)
        val updates=AppUpdater.get(this)
        UpdateCheckService.schedule(this,updates.state.value.autoCheck)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { OrcaMicroApp(viewModel,::installUpdate) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" and "Share" from file managers and other apps. */
    private fun handleIntent(intent: Intent?) {
        if(intent?.action==AppUpdater.ACTION_UPDATES) { AppUpdater.get(this).show();return }
        if(intent?.action==Intent.ACTION_VIEW && intent.data?.scheme in setOf("orcaslicer","prusaslicer","bambustudio","https")) {
            viewModel.openModelLink(intent.data.toString());return
        }
        if(intent?.action==Intent.ACTION_SEND && intent.type=="text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::openModelLink);return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri?.let { viewModel.openModel(it) }
    }
}
