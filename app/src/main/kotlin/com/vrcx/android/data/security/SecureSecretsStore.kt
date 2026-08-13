package com.vrcx.android.data.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.vrcx.android.data.api.StoredCookieCodec
import com.vrcx.android.data.api.isVrchatCookieHost
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedCredentials(
    val username: String = "",
    val password: String = "",
)

@Serializable
private data class SecureSecretsState(
    val savedCredentials: SavedCredentials? = null,
    val cookiesByHost: Map<String, String> = emptyMap(),
)

/** Reads and writes the secrets blob. Split out so the primary/backup ladder can run on plain files. */
internal interface SecretsFileCodec {
    fun read(file: File): String
    fun write(file: File, text: String)
}

@Singleton
class SecureSecretsStore internal constructor(
    context: Context,
    private val json: Json,
    private val fileCodec: SecretsFileCodec,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(context, json, EncryptedFileCodec(context))

    private val appContext = context.applicationContext
    private val lock = Any()
    private val secretsFile = File(appContext.filesDir, SECRETS_FILE_NAME)
    private val backupFile = File(appContext.filesDir, "$SECRETS_FILE_NAME.backup")
    private var reportedUnreadable = false

    fun getSavedCredentials(): SavedCredentials? = synchronized(lock) {
        readStateOrEmpty().savedCredentials
    }

    fun saveSavedCredentials(username: String, password: String) {
        synchronized(lock) {
            updateState { it.copy(savedCredentials = SavedCredentials(username, password)) }
        }
    }

    fun clearSavedCredentials() {
        synchronized(lock) {
            updateState { it.copy(savedCredentials = null) }
        }
    }

    /**
     * Drops every stored secret. Unlike the per-field mutators this needs no read,
     * so an explicit sign-out still lands on a record we could not decrypt.
     */
    fun clearAll() {
        synchronized(lock) {
            writeState(SecureSecretsState())
        }
    }

    fun getCookiesByHost(): Map<String, String> = synchronized(lock) {
        readStateOrEmpty().cookiesByHost
    }

    /** @return false when a record exists that we couldn't read, so nothing was written. */
    fun replaceCookiesByHost(cookiesByHost: Map<String, String>): Boolean = synchronized(lock) {
        updateState { it.copy(cookiesByHost = cookiesByHost.toMap()) }
    }

    fun hasAuthCookie(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        hasUsableAuthCookie(readStateOrEmpty().cookiesByHost, now)
    }

    private fun updateState(transform: (SecureSecretsState) -> SecureSecretsState): Boolean {
        // A record we couldn't read is not an empty record. Writing a transform of
        // "nothing stored" over it would destroy an intact username, password and
        // cookie set over what may well be a transient decryption failure.
        val current = readState() ?: return false
        writeState(transform(current))
        return true
    }

    /**
     * The stored state, or null when a secrets file exists that could not be read
     * — a corrupt file, a master key invalidated behind our back, or a detected
     * tamper. A *missing* file still means "nothing stored yet".
     */
    private fun readState(): SecureSecretsState? {
        if (!secretsFile.exists() && backupFile.exists()) {
            backupFile.renameTo(secretsFile)
        }
        if (!secretsFile.exists()) {
            return SecureSecretsState()
        }

        readStateFromPrimary()?.let { state ->
            if (backupFile.exists()) backupFile.delete()
            return state
        }

        if (backupFile.exists()) {
            secretsFile.delete()
            if (backupFile.renameTo(secretsFile)) {
                readStateFromPrimary()?.let { return it }
            }
        }

        if (!reportedUnreadable) {
            reportedUnreadable = true
            Log.w(TAG, "Stored secrets exist but could not be read; leaving the record untouched")
        }
        return null
    }

    private fun readStateOrEmpty(): SecureSecretsState = readState() ?: SecureSecretsState()

    private fun readStateFromPrimary(): SecureSecretsState? = runCatching {
            val text = fileCodec.read(secretsFile)
            if (text.isBlank()) {
                SecureSecretsState()
            } else {
                json.decodeFromString<SecureSecretsState>(text)
            }
        }.getOrNull()

    private fun writeState(state: SecureSecretsState) {
        if (state.savedCredentials == null && state.cookiesByHost.isEmpty()) {
            secretsFile.delete()
            backupFile.delete()
            return
        }

        backupFile.delete()
        if (secretsFile.exists() && !secretsFile.renameTo(backupFile)) {
            error("Unable to preserve the existing encrypted secrets")
        }

        try {
            fileCodec.write(secretsFile, json.encodeToString(SecureSecretsState.serializer(), state))
            backupFile.delete()
        } catch (failure: Exception) {
            secretsFile.delete()
            backupFile.renameTo(secretsFile)
            throw failure
        }
    }

    companion object {
        const val SECRETS_FILE_NAME = "vrcx_secure_secrets.json"
        private const val TAG = "SecureSecretsStore"
    }
}

private class EncryptedFileCodec(context: Context) : SecretsFileCodec {
    private val appContext = context.applicationContext
    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    override fun read(file: File): String =
        encryptedFile(file).openFileInput().bufferedReader().use { reader -> reader.readText() }

    override fun write(file: File, text: String) {
        encryptedFile(file).openFileOutput().bufferedWriter().use { writer ->
            writer.write(text)
            writer.flush()
        }
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    @Suppress("DEPRECATION")
    private fun encryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }
}

internal fun hasUsableAuthCookie(
    cookiesByHost: Map<String, String>,
    nowMillis: Long,
): Boolean = cookiesByHost.any { (host, encodedCookies) ->
    isVrchatCookieHost(host) && encodedCookies.split("|").any { encodedCookie ->
        StoredCookieCodec.deserialize(encodedCookie)?.let { cookie ->
            cookie.name == "auth" && cookie.expiresAt >= nowMillis
        } == true
    }
}
