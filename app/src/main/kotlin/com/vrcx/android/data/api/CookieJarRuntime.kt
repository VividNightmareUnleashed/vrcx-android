package com.vrcx.android.data.api

import android.content.Context
import com.vrcx.android.data.security.SecureSecretsStore
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Serializes every memory, persistence, and session-generation transition for the cookie jar. */
internal class CookieJarRuntime(context: Context, secureSecretsStore: SecureSecretsStore) : CookieJar {
    private val lock = Any()
    private val cookies = CookieMemoryStore()
    private val persistence = CookiePersistence(context, secureSecretsStore)
    private val generation = CookieGenerationCounter()

    val requestBridge = CookieSessionBridge(lock, generation, cookies, persistence)
    val storageStatus: StateFlow<CookieStorageStatus> = persistence.storageStatus

    override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) {
        synchronized(lock) {
            persistence.ensureInitialized(cookies)
            cookies.update(url, responseCookies)
            persistence.persist(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        cookies.loadForRequest(url)
    }

    fun clearAll(): Boolean = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        generation.advance()
        cookies.clear()
        persistence.persist(cookies) && persistence.clearLegacyPreferences()
    }

    fun snapshot(): Map<String, String> = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        cookies.serialize()
    }

    fun snapshotForAccountBoundRequest(): AccountBoundCookies = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        cookies.accountBoundSnapshot()
    }

    fun restore(snapshot: Map<String, String>): Boolean = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        generation.advance()
        cookies.replace(snapshot)
        persistence.persist(cookies) && persistence.clearLegacyPreferences()
    }

    fun completeLogoutAfterSecretsDeleted(): Boolean = synchronized(lock) {
        // Loading here could migrate a stale legacy cookie back into the file
        // that logout just deleted.
        persistence.preventReloadAfterSecretsDeleted()
        generation.advance()
        cookies.clear()
        persistence.markSecretsDeleted()
        persistence.clearLegacyPreferences()
    }

    fun commitAuthenticatedSession(): Boolean = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        persistence.commitAuthenticatedSession(cookies)
    }

    fun getAuthCookie(): String? = synchronized(lock) {
        persistence.ensureInitialized(cookies)
        cookies.authCookie()
    }
}
