package com.vrcx.android.data.api

import android.content.Context
import com.vrcx.android.data.security.SecureSecretsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
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
class CookieJarImpl private constructor(private val runtime: CookieJarRuntime) : CookieJar by runtime {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        secureSecretsStore: SecureSecretsStore,
    ) : this(CookieJarRuntime(context, secureSecretsStore))

    val storageStatus: StateFlow<CookieStorageStatus> = runtime.storageStatus

    /** Generation captured before an OkHttp call enters the cookie bridge. */
    internal fun sessionGeneration(): Long = runtime.requestBridge.captureGeneration()

    /** Prevents a queued call from crossing a login, logout, or cookie restore boundary. */
    internal fun isSessionGenerationCurrent(expectedGeneration: Long): Boolean =
        runtime.requestBridge.isGenerationCurrent(expectedGeneration)

    /** Accepts response cookies only while they belong to the session that sent the request. */
    internal fun saveFromResponseIfCurrent(expectedGeneration: Long, url: HttpUrl, cookies: List<Cookie>): Boolean =
        runtime.requestBridge.saveIfCurrent(expectedGeneration, url, cookies)

    fun clearAll(): Boolean = runtime.clearAll()

    /** Serialized copy of the jar, for a caller that has to clear it but may need it back. */
    fun snapshot(): Map<String, String> = runtime.snapshot()

    /** Immutable request credentials that cannot drift to a later signed-in account. */
    internal fun snapshotForAccountBoundRequest(): AccountBoundCookies = runtime.snapshotForAccountBoundRequest()

    fun restore(snapshot: Map<String, String>): Boolean = runtime.restore(snapshot)

    /** Clears legacy cookie preferences only after the encrypted secrets were verifiably deleted. */
    fun completeLogoutAfterSecretsDeleted(): Boolean = runtime.completeLogoutAfterSecretsDeleted()

    /** Makes the current in-memory jar durable after the server has authenticated it. */
    fun commitAuthenticatedSession(): Boolean = runtime.commitAuthenticatedSession()

    fun getAuthCookie(): String? = runtime.getAuthCookie()
}
