package com.vrcx.android.data.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfilePicCacheManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var manager: ProfilePicCacheManager

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        cacheDir = File(context.filesDir, "profile_pic_cache")
        cacheDir.deleteRecursively()
        manager = ProfilePicCacheManager(context, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `filenames stay byte-identical to the lowercase hex digest`() {
        val url = "https://api.vrchat.cloud/api/1/file/file_abcdef/1/file"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, manager.urlToFilename(url))
    }

    @Test
    fun `a cached image is served back with the exact response bytes`() {
        val bytes = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val url = server.url("/avatar.png").toString()

        runBlocking { manager.cacheImage(url) }

        val cached = manager.getCachedFile(url)
        assertNotNull(cached)
        assertArrayEquals(bytes, cached!!.readBytes())
    }

    @Test
    fun `a failed download leaves neither a cache entry nor a temp file`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/avatar.png").toString()

        runBlocking { manager.cacheImage(url) }

        assertNull(manager.getCachedFile(url))
        assertEquals(emptyList<String>(), cacheDir.list()?.toList())
    }

    @Test
    fun `a successful download leaves no temp file behind`() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        val url = server.url("/avatar.png").toString()

        runBlocking { manager.cacheImage(url) }

        assertEquals(listOf(manager.urlToFilename(url)), cacheDir.list()?.toList())
    }
}
