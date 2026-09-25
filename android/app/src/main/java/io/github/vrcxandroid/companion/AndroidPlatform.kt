package io.github.vrcxandroid.companion

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "VRCXCompanion"

/**
 * [SecureStore] on EncryptedSharedPreferences (AES-256 keys wrapped by the Android Keystore). If the encrypted file
 * cannot be opened (for example a keyset that no longer matches the Keystore), it is recreated once; if that fails as
 * well, pairings live in memory for this process rather than being written in plain text.
 */
class EncryptedPrefsSecureStore(context: Context) : SecureStore {
    private val appContext = context.applicationContext
    private val fallback = InMemorySecureStore()
    private val prefs: SharedPreferences? by lazy { open() }

    private fun create(): SharedPreferences {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun open(): SharedPreferences? = try {
        create()
    } catch (e: Exception) {
        Log.w(TAG, "encrypted companion store unreadable, recreating it", e)
        try {
            appContext.deleteSharedPreferences(FILE_NAME)
            create()
        } catch (e2: Exception) {
            Log.e(TAG, "encrypted companion store unavailable; pairings are kept in memory only", e2)
            null
        }
    }

    override fun get(key: String): String? {
        val p = prefs ?: return fallback.get(key)
        return try {
            p.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $key", e)
            null
        }
    }

    override fun put(key: String, value: String?) {
        val p = prefs ?: return fallback.put(key, value)
        val editor = p.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        if (!editor.commit()) Log.w(TAG, "cannot write $key")
    }

    companion object {
        const val FILE_NAME = "vrcx_companion_secure"
    }
}

/** WifiManager.MulticastLock, held only while a discovery runs (needs CHANGE_WIFI_MULTICAST_STATE). */
class AndroidMulticastLock(context: Context) : MulticastLockHandle {
    private val appContext = context.applicationContext
    private val lock: WifiManager.MulticastLock? by lazy {
        try {
            (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("vrcx-companion-discovery")
                ?.apply { setReferenceCounted(true) }
        } catch (e: Exception) {
            Log.w(TAG, "no multicast lock", e)
            null
        }
    }

    override fun acquire(): Boolean {
        val l = lock ?: return false
        return try {
            l.acquire()
            true
        } catch (e: Exception) {
            // SecurityException without CHANGE_WIFI_MULTICAST_STATE: discovery still works on most devices.
            Log.w(TAG, "cannot acquire multicast lock: ${e.message}")
            false
        }
    }

    override fun release() {
        try {
            lock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "cannot release multicast lock: ${e.message}")
        }
    }
}

/**
 * Calls [onChange] when the default network changes (another network becomes the default, or the default is lost),
 * so the client reconnects at once instead of waiting for its backoff or the 20 s read timeout. The callback for the
 * network that is already the default when registering is ignored. Needs ACCESS_NETWORK_STATE.
 */
class AndroidNetworkMonitor(context: Context, private val onChange: () -> Unit) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var registered = false
    private var initialized = false
    private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val notify = synchronized(lock) {
                val first = !initialized
                initialized = true
                val changed = network != current
                current = network
                !first && changed
            }
            if (notify) onChange()
        }

        override fun onLost(network: Network) {
            val notify = synchronized(lock) {
                initialized = true
                if (network == current) {
                    current = null
                    true
                } else {
                    false
                }
            }
            if (notify) onChange()
        }
    }

    fun start() {
        synchronized(lock) {
            if (registered) return
            registered = true
        }
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // SecurityException without ACCESS_NETWORK_STATE: reconnects then rely on backoff and timeouts.
            Log.w(TAG, "cannot watch the default network: ${e.message}")
        }
    }
}
