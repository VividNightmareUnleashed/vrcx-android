package com.vrcx.android.data.api

import android.content.Context
import android.content.SharedPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

@Singleton
class CookieJarImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val secureSecretsStore: SecureSecretsStore,
) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
    private val lock = Any()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    // Last state written to the encrypted store, so we can skip the expensive
    // encrypt/write/fsync when a Set-Cookie doesn't actually change anything.
    private var lastPersisted: Map<String, String>? = null

    init {
        synchronized(lock) {
            replaceStoreLocked(secureSecretsStore.getCookiesByHost())
            lastPersisted = serializeStore()
            migrateLegacyPrefsIfNeededLocked()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val host = url.host
            val existing = cookieStore.getOrPut(host) { mutableListOf() }
            for (cookie in cookies) {
                existing.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (!isExpired(cookie)) {
                    existing.add(cookie)
                }
            }
            persistToPrefsLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return synchronized(lock) {
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

    fun clearAll() {
        synchronized(lock) {
            cookieStore.clear()
            persistToPrefsLocked()
            prefs.edit().clear().apply()
        }
    }

    /** Serialized copy of the jar, for a caller that has to clear it but may need it back. */
    fun snapshot(): Map<String, String> = synchronized(lock) { serializeStore() }

    fun restore(snapshot: Map<String, String>) {
        synchronized(lock) {
            replaceStoreLocked(snapshot)
            persistToPrefsLocked()
        }
    }

    fun getAuthCookie(): String? {
        return synchronized(lock) {
            cookieStore.entries
                .filter { (host, _) -> isVrchatCookieHost(host) }
                .flatMap { (_, cookies) -> cookies }
                .firstOrNull { it.name == "auth" && !isExpired(it) }
                ?.value
        }
    }

    private fun isExpired(cookie: Cookie): Boolean {
        return cookie.expiresAt < System.currentTimeMillis()
    }

    private fun serializeStore(): Map<String, String> =
        cookieStore.mapValues { (_, cookies) ->
            cookies.joinToString("|") { StoredCookieCodec.serialize(it) }
        }

    private fun persistToPrefsLocked() {
        val serializedCookies = serializeStore()
        if (serializedCookies == lastPersisted) return
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
        if (persisted) lastPersisted = serializedCookies
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
            prefs.edit().clear().apply()
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
        persistToPrefsLocked()
        prefs.edit().clear().apply()
    }

}
