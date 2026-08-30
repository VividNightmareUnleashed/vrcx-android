package com.vrcx.android.data.api

import android.content.Context
import android.content.SharedPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.security.hasUsableAuthCookie
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

enum class CookieStorageStatus {
    READY,
    UNREADABLE,
    WRITE_FAILED,
    LEGACY_CLEANUP_FAILED,
}

@Singleton
class CookieJarImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val secureSecretsStore: SecureSecretsStore,
) : CookieJar {

    private val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.NONE) {
        context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
    }
    private val lock = Any()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    // Last state written to the encrypted store, so we can skip the expensive
    // encrypt/write/fsync when a Set-Cookie doesn't actually change anything.
    private var lastPersisted: Map<String, String>? = null
    private var initialized = false
    private var sessionGeneration = 0L
    private val _storageStatus = MutableStateFlow(CookieStorageStatus.READY)

    val storageStatus: StateFlow<CookieStorageStatus> = _storageStatus.asStateFlow()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            ensureInitializedLocked()
            updateCookiesLocked(url, cookies)
            persistToEncryptedStoreLocked()
        }
    }

    /** Generation captured before an OkHttp call enters the cookie bridge. */
    internal fun sessionGeneration(): Long = synchronized(lock) { sessionGeneration }

    /** Prevents a queued call from crossing a login, logout, or cookie restore boundary. */
    internal fun isSessionGenerationCurrent(expectedGeneration: Long): Boolean = synchronized(lock) {
        expectedGeneration == sessionGeneration
    }

    /**
     * Accepts response cookies only while they still belong to the session that sent the request.
     * The network interceptor removes the headers afterward, so OkHttp's bridge cannot write them
     * a second time without this generation check.
     */
    internal fun saveFromResponseIfCurrent(expectedGeneration: Long, url: HttpUrl, cookies: List<Cookie>): Boolean =
        synchronized(lock) {
            if (expectedGeneration != sessionGeneration) return@synchronized false
            ensureInitializedLocked()
            updateCookiesLocked(url, cookies)
            persistToEncryptedStoreLocked()
            true
        }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return synchronized(lock) {
            ensureInitializedLocked()
            val cookies = cookieStore[url.host] ?: return@synchronized emptyList()
            // Filter, never evict. A cookie that doesn't match *this* URL is still
            // owed to other requests, and making an expiry-driven eviction durable
            // from a read path means a device whose clock jumped forward destroys
            // the stored session on the next request — correcting the clock would
            // not bring it back. The store shrinks on the write path instead, when
            // the server itself replaces or expires a cookie.
            cookies.filter { !isExpired(it) && it.matches(url) }
        }
    }

    fun clearAll(): Boolean = synchronized(lock) {
        ensureInitializedLocked()
        sessionGeneration++
        cookieStore.clear()
        val persisted = persistToEncryptedStoreLocked()
        persisted && clearLegacyPrefsLocked()
    }

    /** Serialized copy of the jar, for a caller that has to clear it but may need it back. */
    fun snapshot(): Map<String, String> = synchronized(lock) {
        ensureInitializedLocked()
        serializeStore()
    }

    /** Immutable request credentials that cannot drift to a later signed-in account. */
    internal fun snapshotForAccountBoundRequest(): AccountBoundCookies = synchronized(lock) {
        ensureInitializedLocked()
        AccountBoundCookies(
            cookieStore.mapValues { (_, cookies) -> cookies.toList() },
        )
    }

    fun restore(snapshot: Map<String, String>): Boolean = synchronized(lock) {
        ensureInitializedLocked()
        sessionGeneration++
        replaceStoreLocked(snapshot)
        persistToEncryptedStoreLocked() && clearLegacyPrefsLocked()
    }

    /** Clears legacy cookie preferences only after the encrypted secrets were verifiably deleted. */
    fun completeLogoutAfterSecretsDeleted(): Boolean = synchronized(lock) {
        // The durable store is already gone. Loading here could migrate a stale
        // legacy cookie back into the file that logout just deleted.
        initialized = true
        sessionGeneration++
        cookieStore.clear()
        lastPersisted = emptyMap()
        _storageStatus.value = CookieStorageStatus.READY
        clearLegacyPrefsLocked()
    }

    /**
     * Makes the current in-memory jar the durable session after the server has
     * authenticated it. This is the only path allowed to replace an unreadable
     * old secrets blob, preventing that previous account from reviving later.
     */
    fun commitAuthenticatedSession(): Boolean = synchronized(lock) {
        ensureInitializedLocked()
        val serializedCookies = serializeStore()
        if (!hasUsableAuthCookie(serializedCookies, System.currentTimeMillis())) {
            _storageStatus.value = CookieStorageStatus.WRITE_FAILED
            return@synchronized false
        }

        val persisted = try {
            secureSecretsStore.establishAuthenticatedCookies(serializedCookies)
            true
        } catch (_: Exception) {
            false
        }
        if (!persisted) {
            _storageStatus.value = CookieStorageStatus.WRITE_FAILED
            return@synchronized false
        }

        lastPersisted = serializedCookies
        _storageStatus.value = CookieStorageStatus.READY
        clearLegacyPrefsLocked()
        true
    }

    fun getAuthCookie(): String? = synchronized(lock) {
        ensureInitializedLocked()
        cookieStore.entries
            .filter { (host, _) -> isVrchatCookieHost(host) }
            .flatMap { (_, cookies) -> cookies }
            .firstOrNull { it.name == "auth" && !isExpired(it) }
            ?.value
    }

    private fun ensureInitializedLocked() {
        if (initialized) return
        initialized = true

        val storedCookies = try {
            secureSecretsStore.readCookiesByHost()
        } catch (_: Exception) {
            _storageStatus.value = CookieStorageStatus.UNREADABLE
            return
        }
        if (storedCookies.isReadable) {
            replaceStoreLocked(storedCookies.cookiesByHost)
            lastPersisted = serializeStore()
            migrateLegacyPrefsIfNeededLocked()
        } else {
            // An unreadable record is not an empty jar. Keep the process jar
            // empty and the legacy preferences untouched until a fully
            // authenticated replacement session explicitly supersedes it.
            _storageStatus.value = CookieStorageStatus.UNREADABLE
        }
    }

    private fun isExpired(cookie: Cookie): Boolean = cookie.expiresAt < System.currentTimeMillis()

    private fun updateCookiesLocked(url: HttpUrl, cookies: List<Cookie>) {
        val existing = cookieStore.getOrPut(url.host) { mutableListOf() }
        for (cookie in cookies) {
            existing.removeAll {
                it.name == cookie.name && it.domain == cookie.domain &&
                    it.path == cookie.path
            }
            if (!isExpired(cookie)) existing.add(cookie)
        }
        if (existing.isEmpty()) cookieStore.remove(url.host)
    }

    private fun serializeStore(): Map<String, String> = cookieStore.mapValues { (_, cookies) ->
        cookies.joinToString("|") { StoredCookieCodec.serialize(it) }
    }

    private fun persistToEncryptedStoreLocked(): Boolean {
        val serializedCookies = serializeStore()
        if (serializedCookies == lastPersisted &&
            storageStatus.value == CookieStorageStatus.READY
        ) {
            return true
        }
        val persisted = try {
            secureSecretsStore.replaceCookiesByHost(serializedCookies)
        } catch (_: Exception) {
            // OkHttp calls the jar on the request and response paths, so a failed
            // encrypted write must not surface as a failed HTTP call.
            false
        }
        // Only a write that landed may be remembered — otherwise the short-circuit
        // above would swallow every later retry and the session would never reach
        // disk, while the running process still looks perfectly healthy.
        if (persisted) {
            lastPersisted = serializedCookies
            _storageStatus.value = CookieStorageStatus.READY
            clearLegacyPrefsLocked()
        } else if (storageStatus.value != CookieStorageStatus.UNREADABLE) {
            _storageStatus.value = CookieStorageStatus.WRITE_FAILED
        }
        return persisted
    }

    private fun replaceStoreLocked(cookiesByHost: Map<String, String>) {
        cookieStore.clear()
        cookiesByHost.forEach { (host, value) ->
            if (value.isNotEmpty()) {
                val cookies = value.split("|").mapNotNull { StoredCookieCodec.deserialize(it) }
                if (cookies.isNotEmpty()) {
                    cookieStore[host] = cookies.toMutableList()
                }
            }
        }
    }

    private fun migrateLegacyPrefsIfNeededLocked() {
        if (cookieStore.isNotEmpty()) {
            clearLegacyPrefsLocked()
            return
        }

        val legacyCookies = prefs.all.mapNotNull { (host, value) ->
            val serialized = value as? String ?: return@mapNotNull null
            if (serialized.isBlank()) {
                null
            } else {
                host to serialized
            }
        }.toMap()

        if (legacyCookies.isEmpty()) {
            return
        }

        legacyCookies.forEach { (host, value) ->
            val cookies = value.split("|").mapNotNull { StoredCookieCodec.deserialize(it) }
            if (cookies.isNotEmpty()) {
                cookieStore[host] = cookies.toMutableList()
            }
        }
        persistToEncryptedStoreLocked()
    }

    private fun clearLegacyPrefsLocked(): Boolean {
        if (prefs.all.isEmpty()) return true
        val cleared = prefs.edit().clear().commit() && prefs.all.isEmpty()
        if (!cleared) _storageStatus.value = CookieStorageStatus.LEGACY_CLEANUP_FAILED
        return cleared
    }
}
