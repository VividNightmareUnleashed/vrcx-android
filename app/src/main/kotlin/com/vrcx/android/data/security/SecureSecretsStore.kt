package com.vrcx.android.data.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.vrcx.android.data.api.StoredCookieCodec
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

@Singleton
class SecureSecretsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val secretsFile = File(appContext.filesDir, SECRETS_FILE_NAME)
    private val backupFile = File(appContext.filesDir, "$SECRETS_FILE_NAME.backup")
    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    fun getSavedCredentials(): SavedCredentials? = synchronized(lock) {
        readState().savedCredentials
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

    fun getCookiesByHost(): Map<String, String> = synchronized(lock) {
        readState().cookiesByHost
    }

    fun replaceCookiesByHost(cookiesByHost: Map<String, String>) {
        synchronized(lock) {
            updateState { it.copy(cookiesByHost = cookiesByHost.toMap()) }
        }
    }

    fun hasAuthCookie(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        hasUsableAuthCookie(readState().cookiesByHost, now)
    }

    private fun updateState(transform: (SecureSecretsState) -> SecureSecretsState) {
        writeState(transform(readState()))
    }

    private fun readState(): SecureSecretsState {
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
        return SecureSecretsState()
    }

    private fun readStateFromPrimary(): SecureSecretsState? = runCatching {
            encryptedFile(secretsFile).openFileInput().bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.isBlank()) {
                    SecureSecretsState()
                } else {
                    json.decodeFromString<SecureSecretsState>(text)
                }
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
            encryptedFile(secretsFile).openFileOutput().bufferedWriter().use { writer ->
                writer.write(json.encodeToString(SecureSecretsState.serializer(), state))
                writer.flush()
            }
            FileOutputStream(secretsFile, true).use { output -> output.fd.sync() }
            backupFile.delete()
        } catch (failure: Exception) {
            secretsFile.delete()
            backupFile.renameTo(secretsFile)
            throw failure
        }
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

    companion object {
        const val SECRETS_FILE_NAME = "vrcx_secure_secrets.json"
    }
}

internal fun hasUsableAuthCookie(
    cookiesByHost: Map<String, String>,
    nowMillis: Long,
): Boolean = cookiesByHost.values.any { encodedCookies ->
    encodedCookies.split("|").any { encodedCookie ->
        StoredCookieCodec.deserialize(encodedCookie)?.let { cookie ->
            cookie.name == "auth" && cookie.expiresAt >= nowMillis
        } == true
    }
}
