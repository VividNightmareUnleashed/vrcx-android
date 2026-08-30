package com.vrcx.android.data.security

import android.content.Context
import android.util.Log
import com.vrcx.android.data.api.StoredCookieCodec
import com.vrcx.android.data.api.isVrchatCookieHost
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedCredentials(val username: String = "", val password: String = "")

@Serializable
private data class SecureSecretsState(
    val savedCredentials: SavedCredentials? = null,
    val cookiesByHost: Map<String, String> = emptyMap(),
)

internal data class SecretsFileContents(val text: String, val requiresMigration: Boolean = false)

/** Owns the durable file transaction so callers never expose a partially written secrets blob. */
internal interface SecretsFileCodec {
    fun exists(file: File): Boolean = file.exists()
    fun read(file: File): SecretsFileContents
    fun write(file: File, text: String)
    fun delete(file: File): Boolean = (!file.exists() || file.delete()) && !file.exists()
}

internal data class SavedCredentialsRead(val credentials: SavedCredentials?, val isReadable: Boolean)

internal data class CookiesRead(val cookiesByHost: Map<String, String>, val isReadable: Boolean)

internal data class CredentialWriteToken(val generation: Long)

internal enum class CredentialMutationResult {
    APPLIED,
    FAILED,
    STALE,
}

/** Public credential operations implemented by [SecureSecretsStore]. */
interface SavedCredentialSecrets {
    fun getSavedCredentials(): SavedCredentials?
    fun saveSavedCredentials(username: String, password: String): Boolean
    fun clearSavedCredentials(): Boolean
}

/** Public cookie operations implemented by [SecureSecretsStore]. */
interface CookieSecrets {
    fun replaceCookiesByHost(cookiesByHost: Map<String, String>): Boolean
    fun establishAuthenticatedCookies(cookiesByHost: Map<String, String>)
    fun hasAuthCookie(): Boolean
}

@Singleton
class SecureSecretsStore private constructor(private val owner: SecureSecretsOwner) :
    SavedCredentialSecrets by owner.credentials,
    CookieSecrets by owner.cookies {

    internal constructor(
        context: Context,
        json: Json,
        fileCodec: SecretsFileCodec,
    ) : this(SecureSecretsOwner(context, json, fileCodec))

    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(context, json, PlatformSecretsFileCodec(context))

    internal fun readSavedCredentials(): SavedCredentialsRead = synchronized(owner.lock) {
        val state = owner.states.read()
        SavedCredentialsRead(
            credentials = state?.savedCredentials,
            isReadable = state != null,
        )
    }

    internal fun credentialWriteToken(): CredentialWriteToken = synchronized(owner.lock) {
        CredentialWriteToken(owner.credentialWriteGeneration)
    }

    internal fun saveSavedCredentialsIfCurrent(
        token: CredentialWriteToken,
        username: String,
        password: String,
    ): CredentialMutationResult = synchronized(owner.lock) {
        mutateCredentialsIfCurrent(token) {
            owner.states.update { state ->
                state.copy(savedCredentials = SavedCredentials(username, password))
            }
        }
    }

    internal fun clearSavedCredentialsIfCurrent(token: CredentialWriteToken): CredentialMutationResult =
        synchronized(owner.lock) {
            mutateCredentialsIfCurrent(token) {
                owner.states.update { state -> state.copy(savedCredentials = null) }
            }
        }

    /**
     * Drops every stored secret without first reading it, so explicit sign-out
     * also lands when an existing record cannot be decrypted.
     */
    fun clearAll(): Boolean = synchronized(owner.lock) {
        owner.credentialWriteGeneration++
        owner.states.clear()
    }

    internal fun readCookiesByHost(): CookiesRead = synchronized(owner.lock) {
        val state = owner.states.read()
        CookiesRead(
            cookiesByHost = state?.cookiesByHost.orEmpty(),
            isReadable = state != null,
        )
    }

    private inline fun mutateCredentialsIfCurrent(
        token: CredentialWriteToken,
        mutation: () -> Boolean,
    ): CredentialMutationResult = when {
        token.generation != owner.credentialWriteGeneration -> CredentialMutationResult.STALE
        mutation() -> CredentialMutationResult.APPLIED
        else -> CredentialMutationResult.FAILED
    }

    companion object {
        const val SECRETS_FILE_NAME = "vrcx_secure_secrets.json"
    }
}

private class SecureSecretsOwner(context: Context, json: Json, fileCodec: SecretsFileCodec) {
    val lock = Any()
    val states = SecureSecretsStates(
        SecretsStateFile(
            primary = File(context.applicationContext.filesDir, SecureSecretsStore.SECRETS_FILE_NAME),
            legacyBackup = File(
                context.applicationContext.filesDir,
                "${SecureSecretsStore.SECRETS_FILE_NAME}.backup",
            ),
            json = json,
            codec = fileCodec,
        ),
    )
    val credentials: SavedCredentialSecrets = StoredCredentials(lock, states)
    val cookies: CookieSecrets = StoredCookies(lock, states)
    var credentialWriteGeneration = 0L
}

private class StoredCredentials(private val lock: Any, private val states: SecureSecretsStates) :
    SavedCredentialSecrets {
    override fun getSavedCredentials(): SavedCredentials? = synchronized(lock) {
        states.readOrEmpty().savedCredentials
    }

    /** @return false when an unreadable record was preserved instead of overwritten. */
    override fun saveSavedCredentials(username: String, password: String): Boolean = synchronized(lock) {
        states.update { state -> state.copy(savedCredentials = SavedCredentials(username, password)) }
    }

    /** @return false when an unreadable record was preserved instead of overwritten. */
    override fun clearSavedCredentials(): Boolean = synchronized(lock) {
        states.update { state -> state.copy(savedCredentials = null) }
    }
}

private class StoredCookies(private val lock: Any, private val states: SecureSecretsStates) : CookieSecrets {
    /** @return false when an unreadable record was preserved instead of overwritten. */
    override fun replaceCookiesByHost(cookiesByHost: Map<String, String>): Boolean = synchronized(lock) {
        states.update { state -> state.copy(cookiesByHost = cookiesByHost.toMap()) }
    }

    override fun establishAuthenticatedCookies(cookiesByHost: Map<String, String>) {
        synchronized(lock) {
            states.establishAuthenticatedCookies(cookiesByHost)
        }
    }

    override fun hasAuthCookie(): Boolean = synchronized(lock) {
        hasUsableAuthCookie(states.readOrEmpty().cookiesByHost, System.currentTimeMillis())
    }
}

private class SecureSecretsStates(private val file: SecretsStateFile) {
    fun read(): SecureSecretsState? = file.read()

    fun readOrEmpty(): SecureSecretsState = read() ?: SecureSecretsState()

    fun update(transform: (SecureSecretsState) -> SecureSecretsState): Boolean {
        // An unreadable record is not an empty record. Preserving it avoids
        // destroying valid secrets during a transient key or filesystem failure.
        val current = read()
        val updated = current?.let(transform)
        if (updated != null) file.write(updated)
        return updated != null
    }

    fun establishAuthenticatedCookies(cookiesByHost: Map<String, String>) {
        val current = read()
        val authenticatedState = current?.copy(cookiesByHost = cookiesByHost.toMap())
            ?: SecureSecretsState(cookiesByHost = cookiesByHost.toMap())
        file.write(authenticatedState)
    }

    fun clear(): Boolean = file.delete()
}

private class SecretsStateFile(
    private val primary: File,
    private val legacyBackup: File,
    private val json: Json,
    private val codec: SecretsFileCodec,
) {
    private var reportedUnreadable = false

    /** Null means an existing record or transaction artifact could not be resolved safely. */
    fun read(): SecureSecretsState? {
        val primaryReady = restoreLegacyPrimaryIfNeeded()
        val state = when {
            !primaryReady -> null

            !codec.exists(primary) -> SecureSecretsState()

            else -> readPrimary()?.takeIf {
                !legacyBackup.exists() || (legacyBackup.delete() && !legacyBackup.exists())
            }
        }
        if (state == null) {
            reportUnreadableSecrets()
        } else {
            reportedUnreadable = false
        }
        return state
    }

    fun write(state: SecureSecretsState) {
        if (state.savedCredentials == null && state.cookiesByHost.isEmpty()) {
            if (!delete()) throw IOException("Unable to delete stored secrets")
        } else {
            if (legacyBackup.exists() && (!legacyBackup.delete() || legacyBackup.exists())) {
                throw IOException("Unable to remove the legacy secrets backup")
            }
            // AtomicFile owns rollback for the primary. Removing the obsolete
            // pre-AtomicFile backup first leaves exactly one committed generation.
            codec.write(primary, json.encodeToString(SecureSecretsState.serializer(), state))
            reportedUnreadable = false
        }
    }

    fun delete(): Boolean {
        // Attempt every artifact even if an earlier deletion fails. Logout must
        // leave as little recoverable material as the filesystem permits.
        val primaryDeleted = codec.delete(primary)
        val backupDeleted = (!legacyBackup.exists() || legacyBackup.delete()) && !legacyBackup.exists()
        if (primaryDeleted && backupDeleted) reportedUnreadable = false
        return primaryDeleted && backupDeleted
    }

    private fun restoreLegacyPrimaryIfNeeded(): Boolean = when {
        codec.exists(primary) || !legacyBackup.exists() -> true
        else -> legacyBackup.renameTo(primary)
    }

    private fun readPrimary(): SecureSecretsState? {
        val contents = readContents()
        val state = contents?.let(::decode)
        if (contents?.requiresMigration == true && state != null) migrate(state)
        return state
    }

    private fun readContents(): SecretsFileContents? = runCatching {
        codec.read(primary)
    }.getOrElse { failure ->
        if (failure !is Exception) throw failure
        null
    }

    private fun decode(contents: SecretsFileContents): SecureSecretsState? = runCatching {
        if (contents.text.isBlank()) {
            SecureSecretsState()
        } else {
            json.decodeFromString<SecureSecretsState>(contents.text)
        }
    }.getOrElse { failure ->
        if (failure !is Exception) throw failure
        null
    }

    private fun migrate(state: SecureSecretsState) {
        val failure = runCatching {
            write(state)
            Log.i(TAG, "Migrated stored secrets to the platform Keystore format")
        }.exceptionOrNull()
        if (failure != null) {
            if (failure !is Exception) throw failure
            logMigrationFailure(failure)
        }
    }

    private fun reportUnreadableSecrets() {
        if (!reportedUnreadable) {
            reportedUnreadable = true
            Log.w(TAG, "Stored secrets exist but could not be read; leaving the record untouched")
        }
    }

    private companion object {
        const val TAG = "SecureSecretsStore"

        fun logMigrationFailure(failure: Exception) {
            // The readable legacy ciphertext remains intact for a later retry.
            Log.w(TAG, "Could not migrate stored secrets; retaining the legacy record", failure)
        }
    }
}

internal fun verifyAtomicSecretsCommit(primary: File) {
    val backup = File("${primary.path}.bak")
    val pendingWrite = File("${primary.path}.new")
    if (!primary.exists() || backup.exists() || pendingWrite.exists()) {
        throw IOException("Atomic secrets write did not leave one committed generation")
    }
}

internal fun hasUsableAuthCookie(cookiesByHost: Map<String, String>, nowMillis: Long): Boolean =
    cookiesByHost.any { (host, encodedCookies) ->
        isVrchatCookieHost(host) && encodedCookies.split("|").any { encodedCookie ->
            StoredCookieCodec.deserialize(encodedCookie)?.let { cookie ->
                cookie.name == "auth" && cookie.expiresAt >= nowMillis
            } == true
        }
    }
