package com.vrcx.android.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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

internal class PlatformSecretsFileCodec(context: Context) : SecretsFileCodec {
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
        // failures. Preserve an unresolved artifact rather than overwrite it.
        ensureMissing(atomicBackup, "Unable to resolve the atomic secrets backup")
        deleteBeforeCommit(pendingWrite)

        val cleartext = text.toByteArray(StandardCharsets.UTF_8)
        val encrypted = try {
            VersionedSecretsEnvelope.encrypt(cleartext, getOrCreateKey())
        } finally {
            cleartext.fill(0)
        }
        commitEncrypted(file, encrypted)
    }

    override fun delete(file: File): Boolean {
        AtomicFile(file).delete()
        val primaryDeleted = deleteAndVerify(file)
        val backupDeleted = deleteAndVerify(File("${file.path}.bak"))
        val pendingWriteDeleted = deleteAndVerify(File("${file.path}.new"))
        return primaryDeleted && backupDeleted && pendingWriteDeleted
    }

    private fun commitEncrypted(file: File, encrypted: ByteArray) {
        val atomicFile = AtomicFile(file)
        var unfinishedOutput: FileOutputStream? = null
        try {
            val failure = runCatching {
                val stream = atomicFile.startWrite()
                unfinishedOutput = stream
                stream.write(encrypted)
                atomicFile.finishWrite(stream)
                unfinishedOutput = null
                verifyAtomicSecretsCommit(file)
            }.exceptionOrNull()
            if (failure != null) {
                if (failure is Exception) unfinishedOutput?.let(atomicFile::failWrite)
                throw failure
            }
        } finally {
            encrypted.fill(0)
        }
    }

    private fun deleteBeforeCommit(file: File) {
        if (!deleteAndVerify(file)) {
            throw IOException("Unable to remove stale secrets recovery file: ${file.name}")
        }
    }

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

private fun ensureMissing(file: File, message: String) {
    if (file.exists()) throw IOException(message)
}

private fun deleteAndVerify(file: File): Boolean = (!file.exists() || file.delete()) && !file.exists()

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
    private data class EnvelopeLayout(val ivLength: Int)

    private val magic = "VRCXSECR".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 10
    private const val BITS_PER_BYTE = 8
    private const val GCM_TAG_BITS = TAG_BYTES * BITS_PER_BYTE
    private const val UNSIGNED_BYTE_MASK = 0xFF
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
        val layout = parseLayout(envelope)
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val iv = envelope.copyOfRange(HEADER_BYTES, HEADER_BYTES + layout.ivLength)
        val ciphertext = envelope.copyOfRange(HEADER_BYTES + layout.ivLength, envelope.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(header)
            doFinal(ciphertext)
        }
    }

    private fun parseLayout(envelope: ByteArray): EnvelopeLayout {
        requireEnvelope(
            hasHeader(envelope) && envelope.size >= HEADER_BYTES + IV_BYTES + TAG_BYTES,
            "Invalid secrets envelope",
        )
        val version = envelope[magic.size].toInt() and UNSIGNED_BYTE_MASK
        requireEnvelope(version == VERSION, "Unsupported secrets envelope version: $version")
        val ivLength = envelope[magic.size + 1].toInt() and UNSIGNED_BYTE_MASK
        requireEnvelope(
            ivLength == IV_BYTES && envelope.size >= HEADER_BYTES + ivLength + TAG_BYTES,
            "Invalid secrets envelope IV",
        )
        return EnvelopeLayout(ivLength)
    }

    private fun requireEnvelope(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
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
