package com.vrcx.android.data.api

import android.content.Context
import android.content.SharedPreferences
import com.vrcx.android.data.security.SecureSecretsStore
import com.vrcx.android.data.security.hasUsableAuthCookie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns encrypted persistence and the one-way SharedPreferences migration. */
internal class CookiePersistence(context: Context, private val secureSecretsStore: SecureSecretsStore) {
    private val legacyPreferences: SharedPreferences by lazy(LazyThreadSafetyMode.NONE) {
        context.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val mutableStorageStatus = MutableStateFlow(CookieStorageStatus.READY)
    private var initialized = false
    private var lastPersisted: Map<String, String>? = null

    val storageStatus: StateFlow<CookieStorageStatus> = mutableStorageStatus.asStateFlow()

    fun ensureInitialized(cookies: CookieMemoryStore) {
        if (initialized) return
        initialized = true

        val storedCookies = try {
            secureSecretsStore.readCookiesByHost()
        } catch (_: Exception) {
            mutableStorageStatus.value = CookieStorageStatus.UNREADABLE
            return
        }
        if (storedCookies.isReadable) {
            cookies.replace(storedCookies.cookiesByHost)
            lastPersisted = cookies.serialize()
            migrateLegacyPreferences(cookies)
        } else {
            // An unreadable record is not an empty jar. Legacy data remains until
            // an authenticated replacement deliberately supersedes that record.
            mutableStorageStatus.value = CookieStorageStatus.UNREADABLE
        }
    }

    fun persist(cookies: CookieMemoryStore): Boolean {
        val serializedCookies = cookies.serialize()
        if (serializedCookies == lastPersisted && storageStatus.value == CookieStorageStatus.READY) {
            return true
        }
        val persisted = try {
            secureSecretsStore.replaceCookiesByHost(serializedCookies)
        } catch (_: Exception) {
            // Cookie persistence runs inside OkHttp request/response callbacks;
            // a failed encrypted write must not turn a healthy HTTP call into a failure.
            false
        }
        // Remember only committed writes so the next identical response retries
        // after a transient Keystore or filesystem failure.
        if (persisted) {
            lastPersisted = serializedCookies
            mutableStorageStatus.value = CookieStorageStatus.READY
            clearLegacyPreferences()
        } else if (storageStatus.value != CookieStorageStatus.UNREADABLE) {
            mutableStorageStatus.value = CookieStorageStatus.WRITE_FAILED
        }
        return persisted
    }

    fun commitAuthenticatedSession(cookies: CookieMemoryStore): Boolean {
        val serializedCookies = cookies.serialize()
        val hasUsableAuth = hasUsableAuthCookie(serializedCookies, System.currentTimeMillis())
        val persisted = if (hasUsableAuth) {
            try {
                secureSecretsStore.establishAuthenticatedCookies(serializedCookies)
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (persisted) {
            lastPersisted = serializedCookies
            mutableStorageStatus.value = CookieStorageStatus.READY
            clearLegacyPreferences()
        } else {
            mutableStorageStatus.value = CookieStorageStatus.WRITE_FAILED
        }
        return persisted
    }

    fun preventReloadAfterSecretsDeleted() {
        initialized = true
    }

    fun markSecretsDeleted() {
        lastPersisted = emptyMap()
        mutableStorageStatus.value = CookieStorageStatus.READY
    }

    fun clearLegacyPreferences(): Boolean {
        if (legacyPreferences.all.isEmpty()) return true
        val cleared = legacyPreferences.edit().clear().commit() && legacyPreferences.all.isEmpty()
        if (!cleared) mutableStorageStatus.value = CookieStorageStatus.LEGACY_CLEANUP_FAILED
        return cleared
    }

    private fun migrateLegacyPreferences(cookies: CookieMemoryStore) {
        if (!cookies.isEmpty()) {
            clearLegacyPreferences()
            return
        }

        val legacyCookies = legacyPreferences.all.mapNotNull { (host, value) ->
            val serialized = value as? String ?: return@mapNotNull null
            serialized.takeUnless(String::isBlank)?.let { host to it }
        }.toMap()
        if (legacyCookies.isEmpty()) return

        cookies.replace(legacyCookies)
        persist(cookies)
    }

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "vrcx_cookies"
    }
}
