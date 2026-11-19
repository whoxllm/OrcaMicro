package io.github.whoxllm.orcamicro

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.i18n.*
import io.github.whoxllm.orcamicro.core.settings.OrcaOptions
import io.github.whoxllm.orcamicro.data.SettingsStore
import io.github.whoxllm.orcamicro.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParameterLanguageTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val store=SettingsStore(instrumentation.targetContext)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean){val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};assertTrue(condition())}

    @Test fun parameterLanguagePersistsAndNeverChangesTheInterfaceLanguage() {
        val before=store.load()
        val option=OrcaOptions.all.first{it.key=="layer_height"}
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                val interfaceEnglish=AppText.english
                main{vm.setParameterLanguage(ParameterLanguage.RUSSIAN)}
                assertEquals(interfaceEnglish,AppText.english);assertEquals(option.labelRu,option.displayLabel)
                await{store.load().parameterLanguage==ParameterLanguage.RUSSIAN}
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                lateinit var vm:MainViewModel;scenario.onActivity{vm=ViewModelProvider(it)[MainViewModel::class.java]};await{vm.state.value.ready}
                assertEquals(ParameterLanguage.RUSSIAN,vm.state.value.parameterLanguage);assertEquals(option.labelRu,option.displayLabel)
                val interfaceEnglish=AppText.english
                main{vm.setParameterLanguage(ParameterLanguage.ENGLISH)}
                assertEquals(interfaceEnglish,AppText.english);assertEquals(option.label,option.displayLabel)
                main{vm.setParameterLanguage(ParameterLanguage.INTERFACE)}
                assertEquals(if(interfaceEnglish)option.label else option.labelRu,option.displayLabel)
                await{store.load().parameterLanguage==ParameterLanguage.INTERFACE}
            }
        } finally {store.save(before);AppText.parameterLanguage=before.parameterLanguage}
    }
}
