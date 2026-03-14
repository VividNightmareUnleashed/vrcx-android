package com.vrcx.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.preferences.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
            val hasAuth = prefs.all.values.any { (it as? String)?.contains("auth=") == true }
            if (!hasAuth) return

            val bgEnabled = runBlocking {
                context.dataStore.data.first()[VrcxPreferences.BACKGROUND_SERVICE_ENABLED] ?: true
            }
            if (bgEnabled) {
                WebSocketForegroundService.start(context)
            }
        }
    }
}
