package dev.excalidraw.spenbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the resident bridge after unlock. Disabled while the keep-running switch is off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BootStart.shouldOpen(BootStart.isEnabled(context), intent?.action)) return
        val service = Intent(context, BridgeService::class.java)
        runCatching { context.startForegroundService(service) }
            .onFailure { error ->
                Log.i(TAG, "Bridge service was not started after restart: ${error.javaClass.simpleName}")
            }
    }

    private companion object {
        const val TAG = "SpenBridge"
    }
}
