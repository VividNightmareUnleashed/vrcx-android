package io.github.vrcxandroid.companion

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VRCXCompanion"

/**
 * [SecureStore] on EncryptedSharedPreferences (AES-256 keys wrapped by the Android Keystore).
 *
 * Opening the store needs the Keystore, which can fail for a while (for example right after boot). Such a failure is
 * reported as [SecureStoreException] and retried on the next call, so the caller neither sees an empty store nor
 * overwrites the stored pairings. Only when opening has kept failing for [RECREATE_AFTER_MS] is the keyset taken to no
 * longer match the Keystore: the file is recreated, and if that fails as well, pairings live in memory for this
 * process rather than being written in plain text.
 *
 * Values are decrypted with the keyset loaded when the store was opened, without the Keystore, so a value that cannot
 * be decrypted fails the same way on every read: it is corrupt and reads as absent.
 */
class EncryptedPrefsSecureStore(context: Context) : SecureStore {
    private val appContext = context.applicationContext
    private val fallback = InMemorySecureStore()
    private val lock = Any()

    // ---- guarded by lock ----
    private var prefs: SharedPreferences? = null
    private var memoryOnly = false
    private var firstFailureMs = 0L

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

    /**
     * The open store; null when only the in-memory fallback is left. Throws [SecureStoreException] while opening
     * fails.
     */
    private fun open(): SharedPreferences? = synchronized(lock) {
        prefs?.let { return it }
        if (memoryOnly) return null
        val now = SystemClock.elapsedRealtime()
        try {
            return create().also {
                prefs = it
                firstFailureMs = 0L
            }
        } catch (e: Exception) {
            if (firstFailureMs == 0L) firstFailureMs = now
            if (now - firstFailureMs < RECREATE_AFTER_MS) {
                Log.w(TAG, "encrypted companion store cannot be opened; retrying later", e.redacted())
                throw SecureStoreException("companion store unavailable", e)
            }
        }
        Log.w(TAG, "encrypted companion store unreadable for ${RECREATE_AFTER_MS / 1000} s, recreating it")
        try {
            appContext.deleteSharedPreferences(FILE_NAME)
            create().also { prefs = it }
        } catch (e: Exception) {
            Log.e(TAG, "encrypted companion store unavailable; pairings are kept in memory only", e.redacted())
            memoryOnly = true
            null
        }
    }

    override fun get(key: String): String? {
        val p = open() ?: return fallback.get(key)
        return try {
            p.getString(key, null)
        } catch (e: Exception) {
            Log.w(TAG, "stored $key cannot be decrypted; treating it as absent", e.redacted())
            null
        }
    }

    override fun put(key: String, value: String?) {
        val p = open() ?: return fallback.put(key, value)
        val editor = p.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        if (!editor.commit()) throw SecureStoreException("cannot write $key")
    }

    companion object {
        const val FILE_NAME = "vrcx_companion_secure"

        /** How long opening must keep failing before the file is taken as unrecoverable and recreated. */
        const val RECREATE_AFTER_MS = 60_000L
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
            Log.w(TAG, "no multicast lock", e.redacted())
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
            Log.w(TAG, "cannot acquire multicast lock: ${e.kind}")
            false
        }
    }

    override fun release() {
        try {
            lock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "cannot release multicast lock: ${e.kind}")
        }
    }
}

/**
 * Calls [onChange] when the default network changes (another network becomes the default, a network arrives while
 * there was none, or the default is lost), so the client reconnects at once instead of waiting for its backoff or the
 * 20 s read timeout. Only the callback for the network that was already the default when registering is ignored
 * ([DefaultNetworkTracker]). Calls [onWifiAvailable] whenever a Wi-Fi network becomes available, default or not: while
 * the app is hidden the client retries an unreachable PC only every few minutes, and this is what brings it back at
 * once when the phone gets home (PROTOCOL.md §5.10). Callbacks cost nothing while nothing changes. Needs
 * ACCESS_NETWORK_STATE.
 */
class AndroidNetworkMonitor(
    context: Context,
    private val onChange: () -> Unit,
    private val onWifiAvailable: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)

    @Volatile
    private var tracker: DefaultNetworkTracker<Network>? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (tracker?.onAvailable(network) == true) onChange()
        }

        override fun onLost(network: Network) {
            if (tracker?.onLost(network) == true) onChange()
        }
    }

    /** Also called for the Wi-Fi networks present at registration; the client ignores it while connected. */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onWifiAvailable()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val cm = try {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        } catch (e: Exception) {
            Log.w(TAG, "no connectivity service: ${e.kind}")
            return
        }
        try {
            // Read before registering: a change in between arrives as a callback for another network.
            tracker = DefaultNetworkTracker(cm.activeNetwork)
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // SecurityException without ACCESS_NETWORK_STATE: reconnects then rely on backoff and timeouts.
            Log.w(TAG, "cannot watch the default network: ${e.kind}")
        }
        try {
            // A LAN-only Wi-Fi counts too: the PC is on the local network, not on the internet.
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(wifi, wifiCallback)
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch Wi-Fi networks: ${e.kind}")
        }
    }
}
