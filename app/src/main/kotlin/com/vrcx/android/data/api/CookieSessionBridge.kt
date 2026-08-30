package com.vrcx.android.data.api

import okhttp3.Cookie
import okhttp3.HttpUrl

/** Session boundary shared by both OkHttp cookie interceptors. */
internal class CookieSessionBridge(
    private val lock: Any,
    private val generation: CookieGenerationCounter,
    private val cookies: CookieMemoryStore,
    private val persistence: CookiePersistence,
) {
    fun captureGeneration(): Long = synchronized(lock) { generation.current() }

    fun isGenerationCurrent(expectedGeneration: Long): Boolean = synchronized(lock) {
        generation.isCurrent(expectedGeneration)
    }

    fun saveIfCurrent(expectedGeneration: Long, url: HttpUrl, responseCookies: List<Cookie>): Boolean =
        synchronized(lock) {
            if (!generation.isCurrent(expectedGeneration)) return@synchronized false
            persistence.ensureInitialized(cookies)
            cookies.update(url, responseCookies)
            persistence.persist(cookies)
            true
        }
}

internal class CookieGenerationCounter {
    private var value = 0L

    fun current(): Long = value

    fun isCurrent(expected: Long): Boolean = expected == value

    fun advance() {
        value++
    }
}
