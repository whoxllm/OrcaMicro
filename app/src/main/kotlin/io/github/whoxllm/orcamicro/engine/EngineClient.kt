package io.github.whoxllm.orcamicro.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.content.ContextCompat
import io.github.whoxllm.orcamicro.core.slicing.EngineProgress
import io.github.whoxllm.orcamicro.core.slicing.EngineResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface EngineEvent {
    data class Progress(val progress: EngineProgress) : EngineEvent
    data class Finished(val response: EngineResponse) : EngineEvent
    /** The engine process died without answering, most likely a native crash. */
    data object Crashed : EngineEvent
}

/** Talks to [EngineService]. Cancelling the flow before it finishes stops the slice. */
class EngineClient(private val context: Context) {
    fun slice(requestJson: String): Flow<EngineEvent> = callbackFlow {
        var service: Messenger? = null
        var finished = false

        val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
            val json = message.data.getString(EngineService.KEY_JSON)
            when (message.what) {
                EngineService.MSG_PROGRESS -> json?.let { trySend(EngineEvent.Progress(EngineProgress.parse(it))) }
                EngineService.MSG_RESULT -> {
                    finished = true
                    trySend(EngineEvent.Finished(json?.let { EngineResponse.parse(it) } ?: EngineResponse(ok = false, error = "Empty engine reply")))
                    close()
                }
            }
            true
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val messenger = Messenger(binder)
                service = messenger
                val message = Message.obtain(null, EngineService.MSG_SLICE).apply {
                    data = Bundle().apply { putString(EngineService.KEY_JSON, requestJson) }
                    replyTo = incoming
                }
                try {
                    messenger.send(message)
                } catch (e: RemoteException) {
                    trySend(EngineEvent.Crashed)
                    close()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!finished) {
                    trySend(EngineEvent.Crashed)
                    close()
                }
            }
        }

        val intent = Intent(context, EngineService::class.java)
        ContextCompat.startForegroundService(context, intent)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            trySend(EngineEvent.Crashed)
            close()
        }

        awaitClose {
            if (!finished) {
                try {
                    service?.send(Message.obtain(null, EngineService.MSG_CANCEL))
                } catch (e: RemoteException) {
                    // Already gone.
                }
            }
            if (bound) context.unbindService(connection)
        }
    }
}
