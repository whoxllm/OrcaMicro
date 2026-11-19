package io.github.whoxllm.orcamicro.cad

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import io.github.whoxllm.orcamicro.engine.NativeEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs OrcaSlicer's parametric CAD kernel and STEP converter in its own process
 * (android:process=":cad"), separate from both the UI and the :engine slicing process.
 * Cancelling a slice never kills an open CAD session, and an OCCT crash cannot take the app down.
 */
class CadService : Service() {
    private val workerThread = HandlerThread("orcamicro-cad").apply { start() }
    private val messenger = Messenger(Handler(workerThread.looper) { msg -> handle(msg); true })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(message: Message) {
        when (message.what) {
            MSG_COMMAND -> {
                val request = message.data.getString(KEY_JSON) ?: return
                val requestId = message.arg1
                val replyTo = message.replyTo ?: return
                val response = try {
                    if (!NativeEngine.isAvailable) {
                        errorJson("The CAD engine is missing from this build (${NativeEngine.unavailableReason})")
                    } else {
                        NativeEngine.cadCommand(request)
                    }
                } catch (t: Throwable) {
                    errorJson("${t.javaClass.simpleName}: ${t.message}")
                }
                try {
                    replyTo.send(Message.obtain(null, MSG_REPLY, requestId, 0).apply {
                        data = Bundle().apply { putString(KEY_JSON, response) }
                    })
                } catch (_: RemoteException) {
                    // Client disconnected.
                }
            }
            MSG_CANCEL -> Process.killProcess(Process.myPid())
        }
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    override fun onDestroy() {
        workerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val MSG_COMMAND = 1
        const val MSG_REPLY = 2
        const val MSG_CANCEL = 3
        const val KEY_JSON = "json"
    }
}
