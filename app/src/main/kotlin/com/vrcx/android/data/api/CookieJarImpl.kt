package com.vrcx.android.data.api

import android.content.Context
import android.content.SharedPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class CookieJarImpl(
    context: Context,
    private val secureSecretsStore: SecureSecretsStore,
) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
    private val lock = Any()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    init {
        synchronized(lock) {
            loadFromSecureStoreLocked()
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
            val host = url.host
            val cookies = cookieStore[host] ?: return@synchronized emptyList()
            val validCookies = cookies.filter { !isExpired(it) && it.matches(url) }
            if (validCookies.size != cookies.size) {
                cookieStore[host] = validCookies.toMutableList()
                persistToPrefsLocked()
            }
            validCookies
        }
    }

    fun clearAll() {
        synchronized(lock) {
            cookieStore.clear()
            secureSecretsStore.replaceCookiesByHost(emptyMap())
            prefs.edit().clear().apply()
        }
    }

    fun getAuthCookie(): String? {
        return synchronized(lock) {
            cookieStore.values.flatten().firstOrNull { it.name == "auth" }?.value
        }
    }

    private fun isExpired(cookie: Cookie): Boolean {
        return cookie.expiresAt < System.currentTimeMillis()
    }

    private fun persistToPrefsLocked() {
        val serializedCookies = cookieStore.mapValues { (_, cookies) ->
            cookies.joinToString("|") { StoredCookieCodec.serialize(it) }
        }
        secureSecretsStore.replaceCookiesByHost(serializedCookies)
    }

    private fun loadFromSecureStoreLocked() {
        secureSecretsStore.getCookiesByHost().forEach { (host, value) ->
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
