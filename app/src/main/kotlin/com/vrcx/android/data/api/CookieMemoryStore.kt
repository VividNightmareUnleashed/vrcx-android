package com.vrcx.android.data.api

import okhttp3.Cookie
import okhttp3.HttpUrl

/** Owns only the process-local cookie representation and its serialization. */
internal class CookieMemoryStore {
    private val cookiesByHost = mutableMapOf<String, MutableList<Cookie>>()

    fun update(url: HttpUrl, responseCookies: List<Cookie>) {
        val existing = cookiesByHost.getOrPut(url.host) { mutableListOf() }
        for (cookie in responseCookies) {
            existing.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (!isExpired(cookie)) existing.add(cookie)
        }
        if (existing.isEmpty()) cookiesByHost.remove(url.host)
    }

    fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = cookiesByHost[url.host].orEmpty()
        // A cookie that misses this URL can still belong to another request. In
        // particular, a clock jump must not make a read evict a durable session.
        return cookies.filter { !isExpired(it) && it.matches(url) }
    }

    fun serialize(): Map<String, String> = cookiesByHost.mapValues { (_, cookies) ->
        cookies.joinToString("|") { StoredCookieCodec.serialize(it) }
    }

    fun replace(serializedByHost: Map<String, String>) {
        cookiesByHost.clear()
        serializedByHost.forEach { (host, value) ->
            val cookies = value
                .takeIf(String::isNotEmpty)
                ?.split("|")
                ?.mapNotNull(StoredCookieCodec::deserialize)
                .orEmpty()
            if (cookies.isNotEmpty()) cookiesByHost[host] = cookies.toMutableList()
        }
    }

    fun clear() {
        cookiesByHost.clear()
    }

    fun isEmpty(): Boolean = cookiesByHost.isEmpty()

    fun accountBoundSnapshot(): AccountBoundCookies = AccountBoundCookies(
        cookiesByHost.mapValues { (_, cookies) -> cookies.toList() },
    )

    fun authCookie(): String? = cookiesByHost.asSequence()
        .filter { (host, _) -> isVrchatCookieHost(host) }
        .flatMap { (_, cookies) -> cookies.asSequence() }
        .firstOrNull { it.name == "auth" && !isExpired(it) }
        ?.value

    private fun isExpired(cookie: Cookie): Boolean = cookie.expiresAt < System.currentTimeMillis()
}
