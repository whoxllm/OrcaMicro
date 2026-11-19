package io.github.whoxllm.orcamicro

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.network.AppRelease
import io.github.whoxllm.orcamicro.update.AppUpdater
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdaterCacheTest {
    @Test fun publishedDateSurvivesRestartAndBaselineBelongsOnlyToTheInstalledVersion() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
        val previous=prefs.all
        val candidate=AppRelease("0.0.1","Fixture","","","fixture.apk",1,"a".repeat(64),2000)
        fun create():AppUpdater=AppUpdater::class.java.getDeclaredConstructor(Context::class.java).apply{isAccessible=true}.newInstance(context)
        try {
            prefs.edit().clear().putString("installed_release_version",BuildConfig.VERSION_NAME).putLong("installed_release_published_at",1000)
                .putString("latest",Json.encodeToString(AppRelease.serializer(),candidate)).commit()
            assertEquals(candidate,create().state.value.release)
            prefs.edit().putString("installed_release_version","0.0.0").commit()
            assertNull(create().state.value.release)
            val same=candidate.copy(version=BuildConfig.VERSION_NAME)
            prefs.edit().putString("installed_release_version",BuildConfig.VERSION_NAME).putString("latest",Json.encodeToString(AppRelease.serializer(),same)).commit()
            assertNull(create().state.value.release)
        } finally {
            val editor=prefs.edit().clear()
            previous.forEach { (key,value)->when(value) {
                is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Long->editor.putLong(key,value)
                is Int->editor.putInt(key,value);is Float->editor.putFloat(key,value)
                is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
    }
}
