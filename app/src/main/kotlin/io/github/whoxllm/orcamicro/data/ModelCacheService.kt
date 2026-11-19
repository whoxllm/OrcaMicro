package io.github.whoxllm.orcamicro.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.whoxllm.orcamicro.core.network.ModelCache
import java.io.File

/** Daily cache expiry also runs while the application is closed. */
class ModelCacheService:JobService() {
    override fun onStartJob(params:JobParameters):Boolean {
        Thread {
            try {
                val days=SettingsStore(this).load().modelCacheDays.takeIf { it in ModelCache.durations } ?: 7
                ModelCache.clean(File(filesDir,"library"),days)
            } finally { jobFinished(params,false) }
        }.start()
        return true
    }
    override fun onStopJob(params:JobParameters)=true
    companion object {
        fun schedule(context:Context) {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(scheduler.getPendingJob(7501)==null)scheduler.schedule(JobInfo.Builder(7501,ComponentName(context,ModelCacheService::class.java)).setPeriodic(24*60*60*1000L).setPersisted(true).build())
        }
    }
}
