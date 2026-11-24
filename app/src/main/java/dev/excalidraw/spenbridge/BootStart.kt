package dev.excalidraw.spenbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal object BootStart {
    const val EXTRA_OPENED_FROM_REBOOT = "opened-from-reboot"

    /** Opens only for the post-unlock boot broadcast, and only when the user opted in. */
    fun shouldOpen(enabled: Boolean, action: String?): Boolean =
        enabled && action == Intent.ACTION_BOOT_COMPLETED

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(BridgeState.KEY_OPEN_AFTER_REBOOT, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(BridgeState.KEY_OPEN_AFTER_REBOOT, enabled).apply()
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(BridgeState.PREFS, Context.MODE_PRIVATE)
}
