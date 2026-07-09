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
            cookies.joinToString("|") { serializeCookie(it) }
        }
        secureSecretsStore.replaceCookiesByHost(serializedCookies)
    }

    private fun loadFromSecureStoreLocked() {
        secureSecretsStore.getCookiesByHost().forEach { (host, value) ->
            if (value.isNotEmpty()) {
                val cookies = value.split("|").mapNotNull { deserializeCookie(it) }
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
            val cookies = value.split("|").mapNotNull { deserializeCookie(it) }
            if (cookies.isNotEmpty()) {
                cookieStore[host] = cookies.toMutableList()
            }
        }
        persistToPrefsLocked()
        prefs.edit().clear().apply()
    }

    private fun serializeCookie(cookie: Cookie): String {
        return buildString {
            append(cookie.name).append("=").append(cookie.value)
            append("; domain=").append(cookie.domain)
            append("; path=").append(cookie.path)
            append("; expires=").append(cookie.expiresAt)
            if (cookie.secure) append("; secure")
            if (cookie.httpOnly) append("; httponly")
        }
    }

    private fun deserializeCookie(serialized: String): Cookie? {
        return try {
            val parts = serialized.split("; ")
            val nameValue = parts[0].split("=", limit = 2)
            val builder = Cookie.Builder()
                .name(nameValue[0])
                .value(nameValue.getOrElse(1) { "" })

            for (part in parts.drop(1)) {
                val kv = part.split("=", limit = 2)
                when (kv[0].lowercase()) {
                    "domain" -> builder.domain(kv[1])
                    "path" -> builder.path(kv[1])
                    "expires" -> builder.expiresAt(kv[1].toLongOrNull() ?: 0L)
                    "secure" -> builder.secure()
                    "httponly" -> builder.httpOnly()
                }
            }
            builder.build()
        } catch (_: Exception) {
            null
        }
    }
}
