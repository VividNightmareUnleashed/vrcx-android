package com.vrcx.android.data.api

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class CookieJarImpl(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vrcx_cookies", Context.MODE_PRIVATE)
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    init {
        loadFromPrefs()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = cookieStore.getOrPut(host) { mutableListOf() }
        for (cookie in cookies) {
            existing.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (!isExpired(cookie)) {
                existing.add(cookie)
            }
        }
        persistToPrefs()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookies = cookieStore[host] ?: return emptyList()
        val validCookies = cookies.filter { !isExpired(it) && it.matches(url) }
        if (validCookies.size != cookies.size) {
            cookieStore[host] = validCookies.toMutableList()
            persistToPrefs()
        }
        return validCookies
    }

    fun clearAll() {
        cookieStore.clear()
        prefs.edit().clear().apply()
    }

    fun getAuthCookie(): String? {
        return cookieStore.values.flatten().firstOrNull { it.name == "auth" }?.value
    }

    private fun isExpired(cookie: Cookie): Boolean {
        return cookie.expiresAt < System.currentTimeMillis()
    }

    private fun persistToPrefs() {
        val editor = prefs.edit()
        editor.clear()
        cookieStore.forEach { (host, cookies) ->
            val serialized = cookies.joinToString("|") { serializeCookie(it) }
            editor.putString(host, serialized)
        }
        editor.apply()
    }

    private fun loadFromPrefs() {
        prefs.all.forEach { (host, value) ->
            if (value is String && value.isNotEmpty()) {
                val cookies = value.split("|").mapNotNull { deserializeCookie(it) }
                if (cookies.isNotEmpty()) {
                    cookieStore[host] = cookies.toMutableList()
                }
            }
        }
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
