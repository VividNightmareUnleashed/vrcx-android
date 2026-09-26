package io.github.vrcxandroid.host

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Start on boot (AndroidHost.GetStartOnBoot/SetStartOnBoot, opt-in, default off). On BOOT_COMPLETED it loads the page
 * without an Activity and starts the foreground service, but only while both start on boot and background mode are
 * on ([VrcxHost.startFromBoot]). The component is disabled otherwise, so the app is not even started at boot.
 * BOOT_COMPLETED is a protected broadcast; any other action that reaches this exported receiver is ignored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            VrcxHost.startFromBoot()
        } catch (e: Exception) {
            Log.w(TAG, "start on boot failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "VRCXBoot"

        /** Whether the receiver should be enabled for these settings. */
        fun shouldBeEnabled(startOnBoot: Boolean, backgroundMode: Boolean): Boolean = startOnBoot && backgroundMode

        /** Enables or disables the receiver component to match the settings. Any thread. */
        fun sync(context: Context, startOnBoot: Boolean, backgroundMode: Boolean) {
            val state = if (shouldBeEnabled(startOnBoot, backgroundMode)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, BootReceiver::class.java), state, PackageManager.DONT_KILL_APP,
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not update the boot receiver (${e.javaClass.simpleName})")
            }
        }
    }
}
