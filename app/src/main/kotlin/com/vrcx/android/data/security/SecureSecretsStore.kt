package com.vrcx.android.data.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
        readState().cookiesByHost.values.any { it.contains("auth=") }
    }

    private fun updateState(transform: (SecureSecretsState) -> SecureSecretsState) {
        writeState(transform(readState()))
    }

    private fun readState(): SecureSecretsState {
        if (!secretsFile.exists()) {
            return SecureSecretsState()
        }

        return runCatching {
            encryptedFile().openFileInput().bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.isBlank()) {
                    SecureSecretsState()
                } else {
                    json.decodeFromString<SecureSecretsState>(text)
                }
            }
        }.getOrElse {
            SecureSecretsState()
        }
    }

    private fun writeState(state: SecureSecretsState) {
        if (state.savedCredentials == null && state.cookiesByHost.isEmpty()) {
            if (secretsFile.exists()) {
                secretsFile.delete()
            }
            return
        }

        if (secretsFile.exists()) {
            secretsFile.delete()
        }

        encryptedFile().openFileOutput().bufferedWriter().use { writer ->
            writer.write(json.encodeToString(SecureSecretsState.serializer(), state))
        }
    }

    @Suppress("DEPRECATION")
    private fun encryptedFile(): EncryptedFile {
        return EncryptedFile.Builder(
            appContext,
            secretsFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    companion object {
        const val SECRETS_FILE_NAME = "vrcx_secure_secrets.json"
    }
}
