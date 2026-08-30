package com.vrcx.android.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import com.vrcx.android.data.api.StoredCookieCodec
import com.vrcx.android.data.api.isVrchatCookieHost
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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

@Singleton
class SecureSecretsStore internal constructor(
    context: Context,
    private val json: Json,
    private val fileCodec: SecretsFileCodec,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(context, json, PlatformSecretsFileCodec(context))

    private val appContext = context.applicationContext
    private val lock = Any()
    private val secretsFile = File(appContext.filesDir, SECRETS_FILE_NAME)
    private val backupFile = File(appContext.filesDir, "$SECRETS_FILE_NAME.backup")
    private var reportedUnreadable = false
    private var credentialWriteGeneration = 0L

    fun getSavedCredentials(): SavedCredentials? = synchronized(lock) {
        readStateOrEmpty().savedCredentials
    }

    internal fun readSavedCredentials(): SavedCredentialsRead = synchronized(lock) {
        val state = readState()
        SavedCredentialsRead(
            credentials = state?.savedCredentials,
            isReadable = state != null,
        )
    }

    /** @return false when an unreadable record was preserved instead of overwritten. */
    fun saveSavedCredentials(username: String, password: String): Boolean = synchronized(lock) {
        updateState { it.copy(savedCredentials = SavedCredentials(username, password)) }
    }

    internal fun credentialWriteToken(): CredentialWriteToken = synchronized(lock) {
        CredentialWriteToken(credentialWriteGeneration)
    }

    internal fun saveSavedCredentialsIfCurrent(
        token: CredentialWriteToken,
        username: String,
        password: String,
    ): CredentialMutationResult = synchronized(lock) {
        mutateCredentialsIfCurrent(token) {
            updateState { it.copy(savedCredentials = SavedCredentials(username, password)) }
        }
    }

    /** @return false when an unreadable record was preserved instead of overwritten. */
    fun clearSavedCredentials(): Boolean = synchronized(lock) {
        updateState { it.copy(savedCredentials = null) }
    }

    internal fun clearSavedCredentialsIfCurrent(token: CredentialWriteToken): CredentialMutationResult =
        synchronized(lock) {
            mutateCredentialsIfCurrent(token) {
                updateState { it.copy(savedCredentials = null) }
            }
        }

    /**
     * Drops every stored secret. Unlike the per-field mutators this needs no read,
     * so an explicit sign-out still lands on a record we could not decrypt.
     */
    fun clearAll(): Boolean = synchronized(lock) {
        credentialWriteGeneration++
        deleteSecretsFiles()
    }

    internal fun readCookiesByHost(): CookiesRead = synchronized(lock) {
        val state = readState()
        CookiesRead(
            cookiesByHost = state?.cookiesByHost.orEmpty(),
            isReadable = state != null,
        )
    }

    /** @return false when a record exists that we couldn't read, so nothing was written. */
    fun replaceCookiesByHost(cookiesByHost: Map<String, String>): Boolean = synchronized(lock) {
        updateState { it.copy(cookiesByHost = cookiesByHost.toMap()) }
    }

    /**
     * Commits the cookies of a server-authenticated replacement session. A readable
     * record keeps its saved credentials; an unreadable old record is deliberately
     * replaced so it can never revive the previous account after this succeeds.
     */
    fun establishAuthenticatedCookies(cookiesByHost: Map<String, String>) {
        synchronized(lock) {
            val current = readState()
            val authenticatedState = current?.copy(cookiesByHost = cookiesByHost.toMap())
                ?: SecureSecretsState(cookiesByHost = cookiesByHost.toMap())
            writeState(authenticatedState)
        }
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

    private inline fun mutateCredentialsIfCurrent(
        token: CredentialWriteToken,
        mutation: () -> Boolean,
    ): CredentialMutationResult {
        if (token.generation != credentialWriteGeneration) return CredentialMutationResult.STALE
        return if (mutation()) CredentialMutationResult.APPLIED else CredentialMutationResult.FAILED
    }

    /**
     * The stored state, or null when a secrets file exists that could not be read
     * — a corrupt file, a master key invalidated behind our back, or a detected
     * tamper. A *missing* file still means "nothing stored yet".
     */
    private fun readState(): SecureSecretsState? {
        if (!fileCodec.exists(secretsFile) && backupFile.exists()) {
            if (!backupFile.renameTo(secretsFile)) {
                reportUnreadableSecrets()
                return null
            }
        }
        if (!fileCodec.exists(secretsFile)) {
            reportedUnreadable = false
            return SecureSecretsState()
        }

        readStateFromPrimary()?.let { state ->
            if (backupFile.exists() && (!backupFile.delete() || backupFile.exists())) {
                reportUnreadableSecrets()
                return null
            }
            reportedUnreadable = false
            return state
        }

        // Once a primary exists, a manual backup is ambiguous: it may be the
        // rollback for an interrupted legacy write, or a stale generation whose
        // cleanup failed after a successful write. Never revive it over a primary.
        reportUnreadableSecrets()
        return null
    }

    private fun readStateOrEmpty(): SecureSecretsState = readState() ?: SecureSecretsState()

    private fun readStateFromPrimary(): SecureSecretsState? {
        val contents = try {
            fileCodec.read(secretsFile)
        } catch (_: Exception) {
            return null
        }
        val state = try {
            if (contents.text.isBlank()) {
                SecureSecretsState()
            } else {
                json.decodeFromString<SecureSecretsState>(contents.text)
            }
        } catch (_: Exception) {
            return null
        }

        if (contents.requiresMigration) {
            try {
                writeState(state)
                Log.i(TAG, "Migrated stored secrets to the platform Keystore format")
            } catch (failure: Exception) {
                // Reading old credentials remains useful even when the atomic
                // rewrite cannot land. The legacy ciphertext is still intact.
                Log.w(TAG, "Could not migrate stored secrets; retaining the legacy record", failure)
            }
        }
        return state
    }

    private fun writeState(state: SecureSecretsState) {
        if (state.savedCredentials == null && state.cookiesByHost.isEmpty()) {
            if (!deleteSecretsFiles()) throw IOException("Unable to delete stored secrets")
            return
        }

        if (backupFile.exists() && (!backupFile.delete() || backupFile.exists())) {
            throw IOException("Unable to remove the legacy secrets backup")
        }
        // AtomicFile owns rollback for the primary. Removing the obsolete
        // pre-AtomicFile backup first ensures a successful write has exactly
        // one committed generation and a failed write still restores primary.
        fileCodec.write(secretsFile, json.encodeToString(SecureSecretsState.serializer(), state))
        reportedUnreadable = false
    }

    private fun deleteSecretsFiles(): Boolean {
        // Attempt every artifact even if an earlier deletion fails. Logout must
        // leave as little recoverable material as the filesystem permits.
        val primaryDeleted = fileCodec.delete(secretsFile)
        val backupDeleted = (!backupFile.exists() || backupFile.delete()) && !backupFile.exists()
        if (primaryDeleted && backupDeleted) reportedUnreadable = false
        return primaryDeleted && backupDeleted
    }

    private fun reportUnreadableSecrets() {
        if (reportedUnreadable) return
        reportedUnreadable = true
        Log.w(TAG, "Stored secrets exist but could not be read; leaving the record untouched")
    }

    companion object {
        const val SECRETS_FILE_NAME = "vrcx_secure_secrets.json"
        private const val TAG = "SecureSecretsStore"
    }
}

private class PlatformSecretsFileCodec(context: Context) : SecretsFileCodec {
    private val appContext = context.applicationContext
    private val legacyReader by lazy { LegacyEncryptedFileReader(appContext) }

    override fun exists(file: File): Boolean = file.exists() || File("${file.path}.bak").exists()

    override fun read(file: File): SecretsFileContents {
        val encryptedBytes = AtomicFile(file).readBounded(MAX_SECRETS_FILE_BYTES)
        return if (VersionedSecretsEnvelope.hasHeader(encryptedBytes)) {
            val cleartext = VersionedSecretsEnvelope.decrypt(encryptedBytes, getOrCreateKey())
            try {
                SecretsFileContents(cleartext.toString(StandardCharsets.UTF_8))
            } finally {
                cleartext.fill(0)
            }
        } else {
            SecretsFileContents(legacyReader.read(file), requiresMigration = true)
        }
    }

    override fun write(file: File, text: String) {
        val atomicBackup = File("${file.path}.bak")
        val pendingWrite = File("${file.path}.new")
        // AtomicFile supports an old .bak protocol and does not report rename
        // failures. Its read path has already tried to restore a .bak; if that
        // artifact remains, preserve it and fail before a new primary can land.
        if (atomicBackup.exists()) {
            throw IOException("Unable to resolve the atomic secrets backup")
        }
        deleteBeforeCommit(pendingWrite)

        val cleartext = text.toByteArray(StandardCharsets.UTF_8)
        val encrypted = try {
            VersionedSecretsEnvelope.encrypt(cleartext, getOrCreateKey())
        } finally {
            cleartext.fill(0)
        }

        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write(encrypted)
            atomicFile.finishWrite(stream)
            output = null
            verifyAtomicSecretsCommit(file)
        } catch (failure: Exception) {
            output?.let(atomicFile::failWrite)
            throw failure
        } finally {
            encrypted.fill(0)
        }
    }

    private fun deleteBeforeCommit(file: File) {
        if (deleteAndVerify(file)) return
        throw IOException("Unable to remove stale secrets recovery file: ${file.name}")
    }

    override fun delete(file: File): Boolean {
        AtomicFile(file).delete()
        val primaryDeleted = deleteAndVerify(file)
        val backupDeleted = deleteAndVerify(File("${file.path}.bak"))
        val pendingWriteDeleted = deleteAndVerify(File("${file.path}.new"))
        return primaryDeleted && backupDeleted && pendingWriteDeleted
    }

    private fun deleteAndVerify(file: File): Boolean = (!file.exists() || file.delete()) && !file.exists()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vrcx_secure_secrets_aes_gcm_v1"
        const val KEY_SIZE_BITS = 256
        const val MAX_SECRETS_FILE_BYTES = 1024 * 1024
    }
}

internal fun verifyAtomicSecretsCommit(primary: File) {
    val backup = File("${primary.path}.bak")
    val pendingWrite = File("${primary.path}.new")
    if (!primary.exists() || backup.exists() || pendingWrite.exists()) {
        throw IOException("Atomic secrets write did not leave one committed generation")
    }
}

/** Compatibility reader only. Every successful read is immediately rewritten by the platform codec. */
@Suppress("DEPRECATION")
private class LegacyEncryptedFileReader(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey by lazy {
        androidx.security.crypto.MasterKey.Builder(appContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    fun read(file: File): String = encryptedFile(file).openFileInput().bufferedReader().use { reader ->
        reader.readText()
    }

    private fun encryptedFile(file: File): androidx.security.crypto.EncryptedFile =
        androidx.security.crypto.EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
}

internal object VersionedSecretsEnvelope {
    private val magic = "VRCXSECR".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 10
    private const val GCM_TAG_BITS = TAG_BYTES * 8
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun hasHeader(bytes: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { index -> bytes[index] == magic[index] }

    fun encrypt(cleartext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        check(cipher.iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        val header = magic + byteArrayOf(VERSION.toByte(), IV_BYTES.toByte())
        cipher.updateAAD(header)
        return header + cipher.iv + cipher.doFinal(cleartext)
    }

    fun decrypt(envelope: ByteArray, key: SecretKey): ByteArray {
        if (!hasHeader(envelope) || envelope.size < HEADER_BYTES + IV_BYTES + TAG_BYTES) {
            throw IOException("Invalid secrets envelope")
        }
        val version = envelope[magic.size].toInt() and 0xff
        if (version != VERSION) throw IOException("Unsupported secrets envelope version: $version")

        val ivLength = envelope[magic.size + 1].toInt() and 0xff
        if (ivLength != IV_BYTES || envelope.size < HEADER_BYTES + ivLength + TAG_BYTES) {
            throw IOException("Invalid secrets envelope IV")
        }

        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val iv = envelope.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength)
        val ciphertext = envelope.copyOfRange(HEADER_BYTES + ivLength, envelope.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(header)
            doFinal(ciphertext)
        }
    }
}

private fun AtomicFile.readBounded(maxBytes: Int): ByteArray = openRead().use { input ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw IOException("Secrets file exceeds $maxBytes bytes")
        output.write(buffer, 0, read)
    }
    output.toByteArray()
}

internal fun hasUsableAuthCookie(cookiesByHost: Map<String, String>, nowMillis: Long): Boolean =
    cookiesByHost.any { (host, encodedCookies) ->
        isVrchatCookieHost(host) && encodedCookies.split("|").any { encodedCookie ->
            StoredCookieCodec.deserialize(encodedCookie)?.let { cookie ->
                cookie.name == "auth" && cookie.expiresAt >= nowMillis
            } == true
        }
    }
