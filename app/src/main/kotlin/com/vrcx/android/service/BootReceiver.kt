package com.vrcx.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vrcx.android.data.preferences.VrcxPreferences
import com.vrcx.android.data.preferences.dataStore
import com.vrcx.android.data.security.SecureSecretsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var secureSecretsStore: SecureSecretsStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
            val hasLegacyAuth = prefs.all.values.any { (it as? String)?.contains("auth=") == true }
            val hasAuth = secureSecretsStore.hasAuthCookie() || hasLegacyAuth
            if (!hasAuth) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                return
            }

            val bgEnabled = runBlocking {
                context.dataStore.data.first()[VrcxPreferences.BACKGROUND_SERVICE_ENABLED] ?: true
            }
            if (bgEnabled) {
                WebSocketForegroundService.start(context)
            }
        }
    }
}
