package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.errorText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ImageCache (GetImage/PopulateImageHosts) and the print/sticker/emoji methods against a local HTTP server. */
class ImageCacheAndUgcTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var platform: FakeAppApiPlatform
    private lateinit var api: AppApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        platform = FakeAppApiPlatform(tmp.newFolder("root"))
        api = AppApi(platform)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(method: String, vararg args: Any?): JsonElement = runBlocking {
        api.call(method, JsonArray(args.map { if (it == null) JsonNull else JsonPrimitive(it.toString()) }))
    }

    private fun rejection(method: String, vararg args: Any?): String = try {
        call(method, *args)
        fail("$method should reject")
        ""
    } catch (t: Throwable) {
        errorText(t)
    }

    private fun allowServer() {
        call("PopulateImageHosts", "[\"${server.url("/")}\", null, \"\", \"not a url\", \"/relative\"]")
    }

    private fun png(bytes: ByteArray = Vectors.fixture("nometa.png")) = MockResponse().setBody(Buffer().write(bytes))

    @Test
    fun populateImageHostsAddsHostsAndSkipsInvalidEntries() {
        val before = api.imageCache.imageHosts
        assertEquals(ImageCache.DEFAULT_HOSTS, before)
        allowServer()
        assertEquals(before + server.hostName, api.imageCache.imageHosts)
        allowServer()
        assertEquals(before.size + 1, api.imageCache.imageHosts.size)
        assertTrue(rejection("PopulateImageHosts", null).startsWith("ArgumentNullException"))
        assertTrue(rejection("PopulateImageHosts", "{").startsWith("JsonException"))
    }

    @Test
    fun getImageDownloadsOnceWithTheVrcxUserAgent() {
        allowServer()
        server.enqueue(png())
        val url = server.url("/file/file_abc/3/file").toString()
        val path = call("GetImage", url, "file_abc", "3").jsonPrimitive.content
        val expected = File(platform.cacheDir, "ImageCache/file_abc/3.png")
        assertEquals(expected.absolutePath, path)
        assertArrayEquals(Vectors.fixture("nometa.png"), expected.readBytes())
        assertEquals("VRCX 2026.09.16", server.takeRequest().getHeader("User-Agent"))
        // cached: no second request
        assertEquals(path, call("GetImage", url, "file_abc", "3").jsonPrimitive.content)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getImageReturnsEmptyOnFailures() {
        assertEquals("", call("GetImage", server.url("/x").toString(), "file_x", "1").jsonPrimitive.content)
        assertEquals(0, server.requestCount)
        allowServer()
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals("", call("GetImage", server.url("/x").toString(), "file_x", "1").jsonPrimitive.content)
        assertFalse(File(platform.cacheDir, "ImageCache/file_x/1.png").exists())
        assertEquals("", call("GetImage", server.url("/x").toString(), "..", "1").jsonPrimitive.content)
        assertEquals("", call("GetImage", server.url("/x").toString(), "file_x", "../../evil").jsonPrimitive.content)
        assertEquals("", call("GetImage", "not a url", "file_y", "1").jsonPrimitive.content)
    }

    @Test
    fun imageCacheIsTrimmedToTheNewestThousand() {
        val root = File(platform.cacheDir, "ImageCache")
        for (i in 0 until ImageCache.MAX_ENTRIES) {
            File(root, "file_$i").apply { mkdirs(); setLastModified(1_000_000_000_000L + i * 1000L) }
        }
        allowServer()
        server.enqueue(png())
        call("GetImage", server.url("/n").toString(), "file_new", "1")
        val left = root.listFiles()!!.map { it.name }.toSet()
        assertEquals(ImageCache.KEEP_ENTRIES, left.size)
        assertTrue("file_new" in left)
        assertTrue("file_${ImageCache.MAX_ENTRIES - 1}" in left)
        assertFalse("file_0" in left)
    }

    @Test
    fun imageRequestsOnlyCarryCookiesForTheApiHost() {
        val cookie = Cookie.Builder().name("auth").value("secret").domain("api.vrchat.cloud").build()
        val base = object : CookieJar {
            var saved = 0
            override fun loadForRequest(url: HttpUrl) = listOf(cookie)
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                saved++
            }
        }
        val jar = ImageCache.ApiHostOnlyCookieJar(base)
        assertEquals(listOf(cookie), jar.loadForRequest("https://api.vrchat.cloud/api/1/image/file_x/1/256".toHttpUrl()))
        assertEquals(emptyList<Cookie>(), jar.loadForRequest("https://files.vrchat.cloud/x.png".toHttpUrl()))
        jar.saveFromResponse("https://api.vrchat.cloud/".toHttpUrl(), listOf(cookie))
        assertEquals(0, base.saved)
        platform.httpClient = OkHttpClient.Builder().cookieJar(base).build()
        allowServer()
        server.enqueue(png())
        call("GetImage", server.url("/c").toString(), "file_c", "1")
        assertEquals(null, server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun savePrintToFileWritesOnceAndReturnsNullOtherwise() {
        allowServer()
        server.enqueue(png())
        val url = server.url("/print").toString()
        val path = call("SavePrintToFile", url, "", "2025:09", "Alice?_2025-09-01_10-11-12.123_prnt_x.png").jsonPrimitive.content
        val file = File(platform.root, "ugc/default/Prints/202509/Alice_2025-09-01_10-11-12.123_prnt_x.png")
        assertEquals(file.absolutePath, path)
        assertArrayEquals(Vectors.fixture("nometa.png"), file.readBytes())
        // exists → null, without downloading
        assertEquals(JsonNull, call("SavePrintToFile", url, "", "2025:09", "Alice?_2025-09-01_10-11-12.123_prnt_x.png"))
        assertEquals(1, server.requestCount)
        // download failure → null
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(JsonNull, call("SaveStickerToFile", server.url("/s").toString(), "", "2025-09", "s.png"))
        // host not allowed → null
        assertEquals(JsonNull, call("SaveEmojiToFile", "https://example.com/e.png", "", "2025-09", "e.png"))
        // SAF tree argument goes to the storage as is and is walked by TreeUgc
        File(platform.root, "ugc/mine/emoji").mkdirs()
        server.enqueue(png())
        val saved = File(platform.root, "ugc/mine/emoji/2025-09/e.png")
        assertEquals(saved.absolutePath, call("SaveEmojiToFile", server.url("/e").toString(), "tree:mine", "2025-09", "e.png").jsonPrimitive.content)
        assertEquals("tree:mine", (platform.ugc as FakeAppApiPlatform.FakeUgcStorage).calls.last())
        assertTrue(saved.isFile)
        // same name in the tree (any case) → null without a download
        assertEquals(JsonNull, call("SaveEmojiToFile", server.url("/e").toString(), "tree:mine", "2025-09", "E.png"))
        // a tree that is no longer granted falls back to the default folder
        server.enqueue(png())
        call("SaveEmojiToFile", server.url("/e").toString(), "tree:revoked", "2025-09", "e.png")
        assertTrue(File(platform.root, "ugc/default/Emoji/2025-09/e.png").isFile)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun saveRejectsWhenTheFolderCannotBeCreated() {
        (platform.ugc as FakeAppApiPlatform.FakeUgcStorage).failFolder = java.io.IOException("Access to the path is denied.")
        assertEquals("IOException: Access to the path is denied.", rejection("SaveEmojiToFile", "https://files.vrchat.cloud/e.png", "", "2025-09", "e.png"))
    }

    @Test
    fun cropPrintImageCopiesTheTextChunksIntoTheCroppedFile() {
        // the header claims 2048x1440; the fake codec "crops" it to nometa.png
        val source = Vectors.withSize(Vectors.fixture("multi_itxt.png"), 2048, 1440)
        var decoded = 0
        platform.images = object : ImageCodec by platform.images {
            override fun cropPrint(bytes: ByteArray): ByteArray? {
                decoded++
                return if (bytes.contentEquals(source)) Vectors.fixture("nometa.png") else null
            }
        }
        val print = File(platform.servedDir, "print.png").apply { writeBytes(source) }
        assertTrue(call("CropPrintImage", "{dir}/print.png").jsonPrimitive.boolean)
        // only the iTXt chunks of the source are copied, so the result matches the .NET vector for multi_itxt.png
        val v = Vectors.root["copyITxt"]!! as kotlinx.serialization.json.JsonObject
        assertEquals(v["sha256"]!!.jsonPrimitive.content, Vectors.sha256(print.readBytes()))
        assertEquals(1, decoded)
        // not a 2048x1440 print → false, file untouched, and the header alone decides (no decode)
        val other = File(platform.servedDir, "other.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")) }
        assertFalse(call("CropPrintImage", "{dir}/other.png").jsonPrimitive.boolean)
        assertArrayEquals(Vectors.fixture("vrcx_json.png"), other.readBytes())
        assertEquals(1, decoded)
        // not a PNG at all → left to the decoder, which refuses it
        File(platform.servedDir, "other.jpg.png").writeBytes(Vectors.fixture("notpng.png"))
        assertFalse(call("CropPrintImage", "{dir}/other.jpg.png").jsonPrimitive.boolean)
        assertEquals(2, decoded)
        assertTrue(rejection("CropPrintImage", "{dir}/missing.png").startsWith("FileNotFoundException: Could not find file"))
    }

    @Test
    fun cropAllPrintsCropsEveryPrintBelowTheUgcRoot() {
        var crops = 0
        platform.images = object : ImageCodec by platform.images {
            override fun cropPrint(bytes: ByteArray): ByteArray? {
                crops++
                return Vectors.fixture("nometa.png")
            }
        }
        val dir = File(platform.root, "ugc/default/Prints/2025-09").apply { mkdirs() }
        File(dir, "a.png").writeBytes(Vectors.withSize(Vectors.fixture("multi_itxt.png"), 2048, 1440))
        File(dir, "b.png").writeBytes(Vectors.withSize(Vectors.fixture("vrcx_json.png"), 2048, 1440))
        // already cropped: skipped from the header
        File(dir, "c.png").writeBytes(Vectors.withSize(Vectors.fixture("vrcx_json.png"), 1920, 1080))
        File(dir, "notes.txt").writeText("x")
        assertEquals(JsonNull, call("CropAllPrints", ""))
        assertEquals(2, crops)
    }
}
