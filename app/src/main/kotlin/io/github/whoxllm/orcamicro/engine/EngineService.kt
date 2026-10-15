package io.github.whoxllm.orcamicro.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.whoxllm.orcamicro.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.thread

/**
 * Runs the native engine in its own process (android:process=":engine"). The app binds to it,
 * sends one slice request and receives progress and the result as messages. Cancelling kills
 * this process, which is the only reliable way to stop a native slice midway.
 */
class EngineService : Service() {
    private val ipcThread = HandlerThread("engine-ipc").apply { start() }
    private val messenger = Messenger(Handler(ipcThread.looper) { message -> handle(message); true })
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService(): promote to foreground right away so a long slice
        // keeps running while the user is in another app.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    private fun handle(message: Message) {
        when (message.what) {
            MSG_SLICE -> {
                val request = message.data.getString(KEY_JSON) ?: return
                val replyTo = message.replyTo ?: return
                if (busy) {
                    reply(replyTo, MSG_RESULT, errorJson("busy", "Another slice is already running"))
                    return
                }
                busy = true
                startSlice(request, replyTo)
            }
            MSG_CANCEL -> Process.killProcess(Process.myPid())
        }
    }

    private fun startSlice(request: String, replyTo: Messenger) {
        val worker = thread(name = "orcamicro-slice") {
            val result = try {
                if (!NativeEngine.isAvailable) {
                    errorJson("engine", "The slicing engine is missing from this build (${NativeEngine.unavailableReason})")
                } else {
                    NativeEngine.slice(request)
                }
            } catch (t: Throwable) {
                errorJson("engine", "${t.javaClass.simpleName}: ${t.message}")
            }
            reply(replyTo, MSG_RESULT, result)
            busy = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        thread(name = "orcamicro-progress") {
            while (worker.isAlive) {
                if (NativeEngine.isAvailable) reply(replyTo, MSG_PROGRESS, NativeEngine.progress())
                Thread.sleep(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun reply(to: Messenger, what: Int, json: String) {
        try {
            to.send(Message.obtain(null, what).apply { data = Bundle().apply { putString(KEY_JSON, json) } })
        } catch (e: RemoteException) {
            // The app went away; nothing to report to.
        }
    }

    private fun errorJson(stage: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("stage", stage)
        put("error", message)
    }.toString()

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_slicing), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_slicing))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    override fun onDestroy() {
        ipcThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val MSG_SLICE = 1
        const val MSG_CANCEL = 2
        const val MSG_PROGRESS = 3
        const val MSG_RESULT = 4
        const val KEY_JSON = "json"

        private const val CHANNEL_ID = "slicing"
        private const val NOTIFICATION_ID = 1
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}
