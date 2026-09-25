package io.github.vrcxandroid.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Default-network changes → `network-changed` `{available}` events (docs/ARCHITECTURE.md §4.4 item 5). The shim then
 * force-closes the VRChat pipeline socket so the frontend reconnects on the new network. Callback driven, no polling.
 */
object NetworkMonitor {
    private const val TAG = "VRCXNetwork"
    private val tracker = NetworkChangeTracker<Network>()
    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (tracker.onAvailable(network)) emit(true)
                }

                override fun onLost(network: Network) {
                    if (tracker.onLost(network)) emit(false)
                }
            })
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "could not register the network callback", e)
        }
    }

    private fun emit(available: Boolean) {
        Log.i(TAG, "default network changed, available=$available")
        VrcxHost.emit("network-changed", buildJsonObject { put("available", available) })
    }
}
