package com.vrcx.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
            val hasAuth = prefs.all.values.any { (it as? String)?.contains("auth=") == true }
            if (hasAuth) {
                WebSocketForegroundService.start(context)
            }
        }
    }
}
