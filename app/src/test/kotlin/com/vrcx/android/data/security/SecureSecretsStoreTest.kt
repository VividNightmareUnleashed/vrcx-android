package com.vrcx.android.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.api.StoredCookieCodec
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureSecretsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val codec = PlainFileCodec()
    private val secretsFile = File(context.filesDir, SecureSecretsStore.SECRETS_FILE_NAME)
    private val backupFile = File(context.filesDir, "${SecureSecretsStore.SECRETS_FILE_NAME}.backup")
    private val atomicBackupFile = File(context.filesDir, "${SecureSecretsStore.SECRETS_FILE_NAME}.bak")
    private val atomicNewFile = File(context.filesDir, "${SecureSecretsStore.SECRETS_FILE_NAME}.new")
    private lateinit var store: SecureSecretsStore

    @Before
    fun setUp() {
        secretsFile.delete()
        backupFile.deleteRecursively()
        atomicBackupFile.delete()
        atomicNewFile.delete()
        store = SecureSecretsStore(context, Json { ignoreUnknownKeys = true }, codec)
    }

    @Test
    fun `a record left only in the backup is promoted back to the primary`() {
        store.saveSavedCredentials("user", "secret")
        // A crash between the rename and the rewrite leaves exactly this shape.
        assertTrue(secretsFile.renameTo(backupFile))

        assertEquals("user", store.getSavedCredentials()?.username)
        assertTrue(secretsFile.exists())
    }

    @Test
    fun `an unreadable primary is not rolled back to an ambiguous backup`() {
        store.saveSavedCredentials("user", "secret")
        secretsFile.copyTo(backupFile, overwrite = true)
        secretsFile.writeText("not a secrets file")

        assertFalse(store.readSavedCredentials().isReadable)
        assertTrue(secretsFile.exists())
        assertTrue(backupFile.exists())
    }

    @Test
    fun `an interrupted atomic transaction restores its committed backup`() {
        store.saveSavedCredentials("user", "secret")
        assertTrue(secretsFile.renameTo(atomicBackupFile))

        assertEquals("user", store.getSavedCredentials()?.username)

        assertTrue(secretsFile.exists())
        assertFalse(atomicBackupFile.exists())
    }

    @Test
    fun `a record that cannot be read is left alone instead of being written over`() {
        store.saveSavedCredentials("user", "secret")
        val intact = secretsFile.readText()
        secretsFile.writeText("not a secrets file")

        // Fail closed for readers…
        assertNull(store.getSavedCredentials())
        assertFalse(store.hasAuthCookie())
        // …but a mutator must not turn "I couldn't read it" into "there was
        // nothing there", which would destroy an intact record for good.
        assertFalse(store.replaceCookiesByHost(mapOf("api.vrchat.cloud" to "auth=token")))
        assertFalse(store.saveSavedCredentials("other", "replacement"))
        assertEquals("not a secrets file", secretsFile.readText())

        secretsFile.writeText(intact)
        assertEquals("user", store.getSavedCredentials()?.username)
    }

    @Test
    fun `clearing everything works even on a record that cannot be read`() {
        store.saveSavedCredentials("user", "secret")
        secretsFile.writeText("not a secrets file")

        assertTrue(store.clearAll())

        // Signing out is a promise the account is off the device, so it cannot
        // depend on our being able to decrypt what is already there.
        assertFalse(secretsFile.exists())
        assertFalse(backupFile.exists())
    }

    @Test
    fun `a credential write captured before logout cannot recreate the deleted secret`() {
        val staleToken = store.credentialWriteToken()
        store.saveSavedCredentials("user", "secret")

        assertTrue(store.clearAll())
        assertEquals(
            CredentialMutationResult.STALE,
            store.saveSavedCredentialsIfCurrent(staleToken, "user", "secret"),
        )
        assertNull(store.getSavedCredentials())
    }

    @Test
    fun `clearing everything verifies every atomic and legacy artifact is gone`() {
        store.saveSavedCredentials("user", "secret")
        atomicBackupFile.writeText("atomic backup")
        atomicNewFile.writeText("atomic new")
        backupFile.writeText("legacy backup")

        assertTrue(store.clearAll())

        assertFalse(secretsFile.exists())
        assertFalse(atomicBackupFile.exists())
        assertFalse(atomicNewFile.exists())
        assertFalse(backupFile.exists())
    }

    @Test
    fun `clearing everything reports a durable deletion failure`() {
        store.saveSavedCredentials("user", "secret")
        codec.failDeletes = true

        assertFalse(store.clearAll())
        assertTrue(secretsFile.exists())
    }

    @Test
    fun `a write that fails part way puts the previous record back`() {
        store.saveSavedCredentials("user", "secret")
        codec.failWrites = true

        assertThrows(IOException::class.java) { store.saveSavedCredentials("other", "replacement") }

        codec.failWrites = false
        assertEquals("user", store.getSavedCredentials()?.username)
    }

    @Test
    fun `a readable primary fails closed when its stale backup cannot be removed`() {
        store.saveSavedCredentials("user", "secret")
        assertTrue(backupFile.mkdir())
        val blocker = File(backupFile, "still-present")
        blocker.writeText("old generation")

        assertFalse(store.readSavedCredentials().isReadable)
        assertTrue(secretsFile.exists())

        blocker.delete()
        backupFile.delete()
        assertEquals("user", store.getSavedCredentials()?.username)
    }

    @Test
    fun `an undeletable legacy backup blocks a new commit before primary changes`() {
        store.saveSavedCredentials("user", "secret")
        val writesBeforeAttempt = codec.writeCount
        assertTrue(backupFile.mkdir())
        val blocker = File(backupFile, "still-present")
        blocker.writeText("old generation")

        assertThrows(IOException::class.java) {
            store.establishAuthenticatedCookies(
                mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie(value = "replacement"))),
            )
        }

        assertEquals(writesBeforeAttempt, codec.writeCount)
        blocker.delete()
        backupFile.delete()
        assertEquals("user", store.getSavedCredentials()?.username)
    }

    @Test
    fun `an atomic backup left after finish is not reported as committed`() {
        secretsFile.writeText("new generation")
        atomicBackupFile.writeText("old generation")

        assertThrows(IOException::class.java) {
            verifyAtomicSecretsCommit(secretsFile)
        }

        atomicBackupFile.delete()
        verifyAtomicSecretsCommit(secretsFile)
    }

    @Test
    fun `an authenticated replacement supersedes an unreadable old record`() {
        store.saveSavedCredentials("old-user", "old-secret")
        store.replaceCookiesByHost(
            mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie(value = "old-session"))),
        )
        secretsFile.writeText("unreadable old record")
        val replacement = mapOf(
            "api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie(value = "new-session")),
        )

        store.establishAuthenticatedCookies(replacement)

        assertNull(store.getSavedCredentials())
        assertEquals(CookiesRead(replacement, isReadable = true), store.readCookiesByHost())
    }

    @Test
    fun `an authenticated replacement preserves credentials from a readable record`() {
        store.saveSavedCredentials("user", "secret")
        val replacement = mapOf(
            "api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie(value = "new-session")),
        )

        store.establishAuthenticatedCookies(replacement)

        assertEquals(SavedCredentials("user", "secret"), store.getSavedCredentials())
        assertEquals(CookiesRead(replacement, isReadable = true), store.readCookiesByHost())
    }

    @Test
    fun `credentials and cookies survive each other's writes`() {
        store.saveSavedCredentials("user", "secret")
        store.replaceCookiesByHost(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(authCookie())))

        assertEquals("user", store.getSavedCredentials()?.username)
        assertTrue(store.hasAuthCookie())

        store.clearSavedCredentials()

        assertNull(store.getSavedCredentials())
        assertTrue(store.hasAuthCookie())
    }

    @Test
    fun `a readable legacy record is atomically migrated on first read`() {
        secretsFile.writeText(
            """{"savedCredentials":{"username":"user","password":"secret"}}""",
        )
        codec.legacyFormat = true

        assertEquals("secret", store.getSavedCredentials()?.password)

        assertFalse(codec.legacyFormat)
        assertEquals(1, codec.writeCount)
    }

    @Test
    fun `a failed legacy migration leaves readable credentials intact for a retry`() {
        val legacyRecord = """{"savedCredentials":{"username":"user","password":"secret"}}"""
        secretsFile.writeText(legacyRecord)
        codec.legacyFormat = true
        codec.failWrites = true

        assertEquals("user", store.getSavedCredentials()?.username)
        assertEquals(legacyRecord, secretsFile.readText())
        assertTrue(codec.legacyFormat)

        codec.failWrites = false
        assertEquals("secret", store.getSavedCredentials()?.password)
        assertFalse(codec.legacyFormat)
    }

    @Test
    fun `the versioned AES GCM envelope round trips and detects tampering`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cleartext = "credential material".toByteArray()
        val envelope = VersionedSecretsEnvelope.encrypt(cleartext, key)

        assertTrue(VersionedSecretsEnvelope.hasHeader(envelope))
        assertFalse(envelope.toString(Charsets.UTF_8).contains("credential material"))
        assertArrayEquals(cleartext, VersionedSecretsEnvelope.decrypt(envelope, key))

        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            VersionedSecretsEnvelope.decrypt(envelope, key)
        }
    }

    @Test
    fun `boot eligibility requires an exact unexpired auth cookie from a vrchat host`() {
        val now = 1_900_000_000_000L
        val expiredAuth = authCookie(expiresAt = now - 1)
        val misleadingName = cookie("notauth", "auth=inside-value", "api.vrchat.cloud", now + 10_000)
        val validAuth = authCookie(expiresAt = now + 10_000)
        val foreignHost = cookie("auth", "token", "images.example.com", now + 10_000)

        assertFalse(hasUsableAuthCookie(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(expiredAuth)), now))
        assertFalse(hasUsableAuthCookie(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(misleadingName)), now))
        assertFalse(hasUsableAuthCookie(mapOf("images.example.com" to StoredCookieCodec.serialize(foreignHost)), now))
        assertTrue(hasUsableAuthCookie(mapOf("api.vrchat.cloud" to StoredCookieCodec.serialize(validAuth)), now))
    }

    private fun authCookie(value: String = "authcookie_test", expiresAt: Long = 2_000_000_000_000L): Cookie =
        cookie("auth", value, "api.vrchat.cloud", expiresAt)

    private fun cookie(name: String, value: String, domain: String, expiresAt: Long): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path("/")
        .expiresAt(expiresAt)
        .build()

    /** Runs the primary/backup ladder on plain files, so the state machine is testable without the keystore. */
    private class PlainFileCodec : SecretsFileCodec {
        var failWrites = false
        var failDeletes = false
        var legacyFormat = false
        var writeCount = 0

        override fun exists(file: File): Boolean = file.exists() || File("${file.path}.bak").exists()

        override fun read(file: File): SecretsFileContents {
            val atomicBackup = File("${file.path}.bak")
            if (!file.exists() && atomicBackup.exists()) {
                check(atomicBackup.renameTo(file))
            }
            return SecretsFileContents(file.readText(), requiresMigration = legacyFormat)
        }

        override fun write(file: File, text: String) {
            if (failWrites) throw IOException("write failed")
            file.writeText(text)
            legacyFormat = false
            writeCount++
        }

        override fun delete(file: File): Boolean {
            if (failDeletes) return false
            file.delete()
            File("${file.path}.bak").delete()
            File("${file.path}.new").delete()
            return !file.exists() &&
                !File("${file.path}.bak").exists() &&
                !File("${file.path}.new").exists()
        }
    }
}
