package io.github.whoxllm.orcamicro.update

import io.github.whoxllm.orcamicro.core.i18n.tr
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.whoxllm.orcamicro.BuildConfig
import io.github.whoxllm.orcamicro.MainActivity
import io.github.whoxllm.orcamicro.R
import io.github.whoxllm.orcamicro.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, READY, FAILED }
data class UpdateState(
    val release:AppRelease?=null,val checking:Boolean=false,val stage:UpdateStage=UpdateStage.IDLE,
    val progress:Int?=null,val error:String?=null,val status:String?=null,val showDialog:Boolean=false,
    val autoCheck:Boolean=true,val autoInstall:Boolean=false,
)

/** Small bootstrap: persistent OS download, verified APK, then the Android installer. */
class AppUpdater private constructor(private val context:Context) {
    private val prefs=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    private val downloads=context.getSystemService(DownloadManager::class.java)
    private val checkLock=Mutex();private val verifyLock=Mutex()
    private val _state=MutableStateFlow(UpdateState(autoCheck=prefs.getBoolean("auto_check",true)))
    val state=_state.asStateFlow()
    init {
        val pending=readRelease("download_release")?.takeIf { isNewer(it) }
        if(pending!=null&&downloadId()>0)_state.update { it.copy(release=pending,stage=UpdateStage.DOWNLOADING) }
        else {
            if(downloadId()>0)clearDownload(remove=true)
            readRelease("latest")?.takeIf { isNewer(it) }?.let { release->_state.update { it.copy(release=release,showDialog=prefs.getString("dismissed",null)!=release.version) } }
        }
    }
    private fun installedPublishedAt():Long? = if(prefs.getString("installed_release_version",null)==BuildConfig.VERSION_NAME)
        prefs.getLong("installed_release_published_at",0).takeIf { it>0 } else null
    private fun isNewer(release:AppRelease)=release.newerThan(BuildConfig.VERSION_NAME,installedPublishedAt())
    private fun readRelease(key:String):AppRelease? { return runCatching { Json.decodeFromString(AppRelease.serializer(),prefs.getString(key,null) ?: return null) }.getOrNull() }
    private fun downloadId()=prefs.getLong("download_id",-1)
    private fun apkFile(release:AppRelease)=File(context.getExternalFilesDir(null) ?: error(tr("Хранилище обновлений недоступно")),"updates/${release.apkName}")
    fun notificationsAllowed()=ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED&&context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun shouldAskNotifications()=!notificationsAllowed()&&!prefs.getBoolean("notification_asked",false)
    fun notificationAsked(){prefs.edit().putBoolean("notification_asked",true).apply()}
    fun setAutoCheck(enabled:Boolean) {
        prefs.edit().putBoolean("auto_check",enabled).apply();_state.update { it.copy(autoCheck=enabled) }
        UpdateCheckService.schedule(context,enabled)
    }
    suspend fun check(force:Boolean=false,show:Boolean=false) {
        if(!force&&!_state.value.autoCheck)return
        if(!force&&System.currentTimeMillis()-prefs.getLong("last_check",0)<6*60*60*1000L)return
        if(!checkLock.tryLock())return
        _state.update { it.copy(checking=true,error=null,status=null) }
        try {
            val result=withContext(Dispatchers.IO) { AppReleases.check(BuildConfig.VERSION_NAME,installedPublishedAt()) }
            val release=result.update
            val saved=prefs.edit().putLong("last_check",System.currentTimeMillis()).putString("latest",release?.let { Json.encodeToString(AppRelease.serializer(),it) })
            result.installedPublishedAtMillis?.let { saved.putString("installed_release_version",BuildConfig.VERSION_NAME).putLong("installed_release_published_at",it) }
            saved.apply()
            _state.update { s->s.copy(checking=false,release=if(s.stage in setOf(UpdateStage.DOWNLOADING,UpdateStage.VERIFYING,UpdateStage.READY))s.release else release,
                showDialog=s.showDialog||(release!=null&&(show||prefs.getString("dismissed",null)!=release.version)),status=if(release==null)tr("Установлена актуальная версия {0}" ,BuildConfig.VERSION_NAME) else tr("Доступна версия {0}" ,release.version)) }
            if(release!=null)notifyAvailable(release)
        }catch(e:kotlinx.coroutines.CancellationException){_state.update { it.copy(checking=false) };throw e}
        catch(e:Exception){_state.update { it.copy(checking=false,error=if(show)e.message ?: tr("Не удалось проверить обновления") else it.error) }}
        finally{checkLock.unlock()}
    }
    fun show(){_state.update { it.copy(showDialog=true) }}
    fun dismiss(){_state.value.release?.let { prefs.edit().putString("dismissed",it.version).apply() };_state.update { it.copy(showDialog=false,autoInstall=false) };prefs.edit().putBoolean("auto_install",false).apply()}
    fun download() {
        val release=_state.value.release ?: return
        if(_state.value.stage in setOf(UpdateStage.DOWNLOADING,UpdateStage.VERIFYING))return
        try {
            require(isNewer(release)) { tr("Обновление уже установлено") }
            require(release.apkName==AppReleases.apkName(release.version)&&release.tag.removePrefix("v")==release.version&&ReleaseVersion.parts(release.tag)!=null&&release.apkUrl=="https://github.com/${AppReleases.REPOSITORY}/releases/download/${release.tag}/${release.apkName}") { tr("Неверный источник обновления") }
            clearDownload(remove=true)
            val file=apkFile(release);file.parentFile?.mkdirs();file.delete()
            val id=downloads.enqueue(DownloadManager.Request(Uri.parse(release.apkUrl)).setTitle("OrcaMicro ${release.version}").setDescription(tr("Загрузка обновления ARM64")).setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverRoaming(false).setDestinationUri(Uri.fromFile(file)))
            kotlin.check(prefs.edit().putLong("download_id",id).putString("download_release",Json.encodeToString(AppRelease.serializer(),release)).putBoolean("auto_install",true).commit())
            _state.update { it.copy(stage=UpdateStage.DOWNLOADING,progress=0,error=null,showDialog=true,autoInstall=false) }
        }catch(e:Exception){_state.update { it.copy(stage=UpdateStage.FAILED,error=e.message ?: tr("Не удалось начать загрузку")) }}
    }
    fun cancelDownload() { clearDownload(remove=true);_state.update { it.copy(stage=UpdateStage.IDLE,progress=null,error=null,autoInstall=false) } }
    private fun clearDownload(remove:Boolean) {
        val id=downloadId();if(remove&&id>0)runCatching { downloads.remove(id) }
        prefs.edit().remove("download_id").remove("download_release").remove("verified_id").remove("auto_install").apply()
    }
    suspend fun refreshDownload(expectedId:Long?=null) {
        val id=downloadId();if(id<=0||(expectedId!=null&&id!=expectedId)||!verifyLock.tryLock())return
        try {
            val release=readRelease("download_release") ?: return
            if(!isNewer(release)){clearDownload(remove=true);_state.update { it.copy(stage=UpdateStage.IDLE,release=null,autoInstall=false) };return}
            val status=withContext(Dispatchers.IO) {
                downloads.query(DownloadManager.Query().setFilterById(id)).use { c->
                    if(c==null||!c.moveToFirst())return@withContext null
                    Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                }
            }
            if(status==null||status.first==DownloadManager.STATUS_FAILED)error(tr("Загрузка обновления прервана. Проверьте сеть и свободное место, затем повторите."))
            if(status.second>AppReleases.MAX_APK_BYTES){downloads.remove(id);error(tr("Обновление превышает допустимый размер"))}
            if(status.first==DownloadManager.STATUS_SUCCESSFUL) {
                if(prefs.getLong("verified_id",-1)!=id) {
                    _state.update { it.copy(stage=UpdateStage.VERIFYING,release=release,progress=100,error=null) }
                    withContext(Dispatchers.IO) { verifyApk(context,apkFile(release),release) }
                    if(id!=downloadId())return
                    prefs.edit().putLong("verified_id",id).apply()
                    _state.update { it.copy(stage=UpdateStage.READY,release=release,progress=100,error=null,autoInstall=prefs.getBoolean("auto_install",false)) }
                    notifyReady(release)
                } else _state.update { it.copy(stage=UpdateStage.READY,release=release,progress=100,autoInstall=prefs.getBoolean("auto_install",false)) }
            }else _state.update { it.copy(stage=UpdateStage.DOWNLOADING,release=release,progress=if(status.third>0)(status.second*100/status.third).toInt().coerceIn(0,100) else null) }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){if(id==downloadId()){prefs.edit().putBoolean("auto_install",false).apply();_state.update { it.copy(stage=UpdateStage.FAILED,error=e.message ?: tr("Не удалось проверить APK"),autoInstall=false) }}}
        finally{verifyLock.unlock()}
    }
    fun consumeAutoInstall(){prefs.edit().putBoolean("auto_install",false).apply();_state.update { it.copy(autoInstall=false) }}
    suspend fun installerIntent():Intent=withContext(Dispatchers.IO) {
        val s=_state.value;val release=s.release ?: error(tr("Нет обновления"))
        require(s.stage==UpdateStage.READY) { tr("Дождитесь проверки APK") }
        val file=apkFile(release);verifyApk(context,file,release)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        @Suppress("DEPRECATION")
        Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
    }
    fun reportError(message:String){_state.update { it.copy(error=message,showDialog=true,autoInstall=false) }}
    private fun notifyAvailable(release:AppRelease) {
        if(!notificationsAllowed()||prefs.getString("notified",null)==release.version)return
        notify(7601,tr("Доступна OrcaSlicer {0}" ,release.version),tr("Посмотреть изменения и скачать обновление"))
        prefs.edit().putString("notified",release.version).apply()
    }
    private fun notifyReady(release:AppRelease) { if(notificationsAllowed())notify(7602,tr("OrcaMicro {0} готова к установке" ,release.version),tr("Нажмите, чтобы установить проверенное обновление")) }
    private fun notify(id:Int,title:String,text:String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app_updates",tr("Обновления OrcaSlicer"),NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).setAction(ACTION_UPDATES).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(id,NotificationCompat.Builder(context,"app_updates").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text).setContentIntent(pending).setAutoCancel(true).build()) }
    }
    companion object {
        const val ACTION_UPDATES="io.github.whoxllm.orcamicro.SHOW_UPDATES"
        @Volatile private var instance:AppUpdater?=null
        fun get(context:Context):AppUpdater=instance ?: synchronized(this){instance ?: AppUpdater(context.applicationContext).also { instance=it }}
        fun verifyApk(context:Context,file:File,release:AppRelease) {
            AppReleases.verifyDownload(file,release)
            val pm=context.packageManager
            val apk=pm.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES) ?: error(tr("Файл обновления не является APK"))
            require(apk.packageName==context.packageName) { tr("APK принадлежит другому приложению") }
            val current=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
            require(apk.longVersionCode>current.longVersionCode&&apk.versionName==release.version) { tr("APK не является более новой версией приложения") }
            fun signatures(info:android.content.pm.PackageInfo)=info.signingInfo?.apkContentsSigners?.map { signature->MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toList() }?.toSet().orEmpty()
            val installed=signatures(current);require(installed.isNotEmpty()&&signatures(apk)==installed) { tr("Подпись APK отличается от установленного приложения. Установка заблокирована.") }
            ZipFile(file).use { require(it.getEntry("lib/arm64-v8a/liborcamicro_engine.so")!=null) { tr("В обновлении отсутствует ARM64-движок") } }
        }
    }
}
