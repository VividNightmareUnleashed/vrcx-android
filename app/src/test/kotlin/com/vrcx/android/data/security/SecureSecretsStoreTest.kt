package com.vrcx.android.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.data.api.StoredCookieCodec
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.Cookie
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
    private lateinit var store: SecureSecretsStore

    @Before
    fun setUp() {
        secretsFile.delete()
        backupFile.delete()
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
    fun `an unreadable primary falls back to the backup`() {
        store.saveSavedCredentials("user", "secret")
        secretsFile.copyTo(backupFile, overwrite = true)
        secretsFile.writeText("not a secrets file")

        assertEquals("secret", store.getSavedCredentials()?.password)
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
        assertEquals("not a secrets file", secretsFile.readText())

        secretsFile.writeText(intact)
        assertEquals("user", store.getSavedCredentials()?.username)
    }

    @Test
    fun `clearing everything works even on a record that cannot be read`() {
        store.saveSavedCredentials("user", "secret")
        secretsFile.writeText("not a secrets file")

        store.clearAll()

        // Signing out is a promise the account is off the device, so it cannot
        // depend on our being able to decrypt what is already there.
        assertFalse(secretsFile.exists())
        assertFalse(backupFile.exists())
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

    private fun authCookie(expiresAt: Long = 2_000_000_000_000L): Cookie =
        cookie("auth", "authcookie_test", "api.vrchat.cloud", expiresAt)

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

        override fun read(file: File): String = file.readText()

        override fun write(file: File, text: String) {
            if (failWrites) throw IOException("write failed")
            file.writeText(text)
        }
    }
}
