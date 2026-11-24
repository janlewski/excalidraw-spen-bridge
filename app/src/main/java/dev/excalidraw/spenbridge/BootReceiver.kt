package dev.excalidraw.spenbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** One-shot after reboot. The receiver is disabled until the user opts in, so a normal boot does not start the process. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!BootStart.shouldOpen(BootStart.isEnabled(context), intent?.action)) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BootStart.EXTRA_OPENED_FROM_REBOOT, true)
        runCatching { context.startActivity(launch) }
            .onFailure { error ->
                Log.i(TAG, "Open after restart was blocked: ${error.javaClass.simpleName}")
            }
    }

    private companion object {
        const val TAG = "SpenBridge"
    }
}
