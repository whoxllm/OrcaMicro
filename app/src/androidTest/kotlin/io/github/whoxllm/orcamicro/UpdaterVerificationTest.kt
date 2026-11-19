package io.github.whoxllm.orcamicro

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.whoxllm.orcamicro.core.network.AppRelease
import io.github.whoxllm.orcamicro.update.AppUpdater
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdaterVerificationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun release(file:File):AppRelease {
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{stream->val b=ByteArray(64*1024);while(true){val n=stream.read(b);if(n<0)break;digest.update(b,0,n)}}
        val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        return AppRelease(BuildConfig.VERSION_NAME,"Fixture","https://github.com/whoxllm/OrcaMicro/releases","https://github.com/whoxllm/OrcaMicro/fixture.apk","fixture.apk",file.length(),hash)
    }
    @Test fun correctlyHashedApksStillRequireSamePackageAndNewerVersion() {
        val own=File(context.applicationInfo.sourceDir)
        try{AppUpdater.verifyApk(context,own,release(own));fail("The installed version cannot be offered as an upgrade")}catch(e:IllegalArgumentException){assertTrue(listOf("более новой","newer version").any{e.message.orEmpty().contains(it)})}
        val other=File(instrumentation.context.applicationInfo.sourceDir)
        try{AppUpdater.verifyApk(context,other,release(other));fail("A correctly signed test APK must not replace another package")}catch(e:IllegalArgumentException){assertTrue(listOf("другому приложению","different application").any{e.message.orEmpty().contains(it)})}
    }
    @Test fun installerFileProviderExposesOnlyTheUpdateDirectory() {
        val update=File(context.getExternalFilesDir(null),"updates/provider-test.apk").apply{parentFile!!.mkdirs();writeText("fixture")}
        try {
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",update)
            assertEquals("content",uri.scheme);assertEquals("${context.packageName}.files",uri.authority)
            context.contentResolver.openInputStream(uri).use{assertEquals("fixture",it!!.reader().readText())}
            for(file in listOf(File(context.filesDir,"state.json"),File(context.getExternalFilesDir(null),"private-profile.json"))) {
                try{FileProvider.getUriForFile(context,"${context.packageName}.files",file);fail("Private file was exposed")}catch(_:IllegalArgumentException){}
            }
        }finally{update.delete()}
    }
}
