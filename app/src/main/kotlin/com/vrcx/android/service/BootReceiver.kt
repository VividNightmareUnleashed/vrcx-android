package com.vrcx.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Defer all auth/preference reads to BootReconnectWorker.doWork() so the
        // broadcast main thread is never blocked on disk I/O during boot. The
        // worker checks SecureSecretsStore + backgroundServiceEnabled before
        // doing anything, so an unconditional enqueue is safe — it short-circuits
        // when the user isn't logged in or has the background service disabled.
        BootReconnectWorker.enqueue(context)
    }
}
