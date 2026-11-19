package io.github.whoxllm.orcamicro.update

import android.app.job.*
import android.content.*
import kotlinx.coroutines.*

class UpdateCheckService:JobService() {
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        work=CoroutineScope(Dispatchers.IO+SupervisorJob()).launch { try{AppUpdater.get(this@UpdateCheckService).check()}finally{jobFinished(params,false)} };return true
    }
    override fun onStopJob(params:JobParameters):Boolean {work?.cancel();return true}
    companion object {
        fun schedule(context:Context,enabled:Boolean=true) {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(!enabled){scheduler.cancel(7600);return}
            if(scheduler.getPendingJob(7600)==null)scheduler.schedule(JobInfo.Builder(7600,ComponentName(context,UpdateCheckService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(24*60*60*1000L).setPersisted(true).build())
        }
    }
}
class UpdateDownloadReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)return
        val id=intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID,-1);if(id<=0)return
        val result=goAsync()
        CoroutineScope(Dispatchers.IO).launch {try{AppUpdater.get(context).refreshDownload(id)}finally{result.finish()}}
    }
}
