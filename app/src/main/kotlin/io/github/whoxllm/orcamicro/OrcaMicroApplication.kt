package io.github.whoxllm.orcamicro

import android.app.Application
import android.content.res.Configuration
import io.github.whoxllm.orcamicro.core.i18n.AppText

class OrcaMicroApplication:Application() {
    override fun onCreate(){super.onCreate();syncLanguage(resources.configuration)}
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);syncLanguage(newConfig)}
    private fun syncLanguage(config:Configuration){AppText.english=config.locales[0].language!="ru"}
}
