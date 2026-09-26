package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.appapi.docs.DocResolver
import io.github.vrcxandroid.bridge.appapi.docs.LocalFileDoc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class DocResolverAndIcsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var platform: FakeAppApiPlatform
    private lateinit var resolver: DocResolver
    private lateinit var mirrorDir: File

    @Before
    fun setUp() {
        platform = FakeAppApiPlatform(tmp.newFolder("root"))
        mirrorDir = File(platform.cacheDir, "mirror")
        resolver = DocResolver(platform, mirrorDir, maxFiles = 2, maxBytes = 1_000_000)
    }

    @Test
    fun servedFilesUseTheirOwnUrl() {
        val f = File(platform.servedDir, "a.png").apply { writeBytes(byteArrayOf(1)) }
        val doc = resolver.resolve("{dir}/a.png")!!
        assertEquals(f.absolutePath, doc.key)
        assertEquals("{dir}/a.png", resolver.displayPath(doc, materialize = true))
        assertFalse(mirrorDir.exists())
        assertEquals(f.absolutePath, resolver.resolve(f.absolutePath)!!.key)
        assertFalse(resolver.resolve("C:\\Users\\x\\a.png")?.exists() == true)
        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("content://other/x"))
    }

    @Test
    fun absolutePathsOutsideTheServedRootsResolveToNothing() {
        val database = File(platform.root, "files/VRCX/VRCX.sqlite3").apply { parentFile!!.mkdirs(); writeText("saved logins") }
        assertNull(resolver.resolve(database.absolutePath))
        assertNull(resolver.resolve(File(platform.servedDir, "../files/VRCX/VRCX.sqlite3").path))
        val api = AppApi(platform)
        val base64 = runBlocking { api.call("GetFileBase64", JsonArray(listOf(JsonPrimitive(database.absolutePath)))) }
        assertEquals(kotlinx.serialization.json.JsonNull, base64)
        // content URIs the platform refuses (no grant) resolve to nothing either
        assertNull(resolver.resolve("content://io.github.vrcxandroid.fileprovider/cache/x.png"))
    }

    @Test
    fun contentDocumentsAreShownThroughAMirrorThatMapsBack() {
        File(platform.contentDir, "shot.png").writeBytes(Vectors.fixture("vrcx_json.png"))
        val doc = resolver.resolve("content://test/shot.png")!!
        val token = resolver.displayPath(doc, materialize = false)
        assertTrue(token, token.startsWith("{cache}/mirror/") && token.endsWith(".png"))
        assertEquals("content://test/shot.png", resolver.resolve(token)!!.key)
        val mirror = File(mirrorDir, token.substringAfterLast('/'))
        assertFalse(mirror.exists())
        resolver.displayPath(doc, materialize = true)
        assertArrayEquals(Vectors.fixture("vrcx_json.png"), mirror.readBytes())
        // a new resolver (process restart) still maps the materialized mirror back through its sidecar
        val fresh = DocResolver(platform, mirrorDir)
        assertEquals("content://test/shot.png", fresh.resolve(token)!!.key)
    }

    @Test
    fun editsThroughTheMirrorReachTheOriginal() {
        val original = File(platform.contentDir, "shot.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")) }
        val api = AppApi(platform)
        val token = api.docs.displayPath(api.docs.resolve("content://test/shot.png")!!, materialize = true)
        val result = runBlocking { api.call("DeleteScreenshotMetadata", JsonArray(listOf(JsonPrimitive(token)))) }
        assertEquals("true", result.jsonPrimitive.content)
        assertTrue(original.length() < Vectors.fixture("vrcx_json.png").size)
        // the next display refreshes the stale copy
        val extra = runBlocking { api.call("GetExtraScreenshotData", JsonArray(listOf(JsonPrimitive(token), JsonPrimitive(true)))) }.jsonPrimitive.content
        assertTrue(extra, extra.contains("\"fileSizeBytes\": \"${original.length()}\""))
        assertArrayEquals(original.readBytes(), File(platform.cacheDir, "screenshot-mirror/" + token.substringAfterLast('/')).readBytes())
    }

    @Test
    fun mirrorIsTrimmed() {
        val outside = tmp.newFolder("outside")
        val tokens = (1..4).map { i ->
            val f = File(outside, "s$i.png").apply { writeBytes(ByteArray(10) { i.toByte() }) }
            Thread.sleep(5)
            resolver.displayPath(LocalFileDoc(f), materialize = true)
        }
        val left = mirrorDir.listFiles { f -> f.name.endsWith(".png") }!!.size
        assertEquals(2, left)
        assertTrue(File(mirrorDir, tokens.last().substringAfterLast('/')).isFile)
        // evicted tokens still resolve to their source
        assertEquals(File(outside, "s1.png").absolutePath, resolver.resolve(tokens.first())!!.key)
    }

    @Test
    fun calendarValidationAndEventParsing() {
        assertTrue(Ics.isValid("BEGIN:VCALENDAR\nEND:VCALENDAR"))
        assertTrue(Ics.isValid("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"))
        assertFalse(Ics.isValid(" BEGIN:VCALENDAR\nEND:VCALENDAR"))
        assertFalse(Ics.isValid("BEGIN:VCALENDAR\nEND:VEVENT"))
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Big\\, fun\r\n  meetup\r\nDESCRIPTION:line1\\nline2\r\n" +
            "LOCATION:VRChat\r\nDTSTART;TZID=Europe/Berlin:20250601T200000\r\nDTEND;VALUE=DATE:20250602\r\nEND:VEVENT\r\nEND:VCALENDAR"
        val e = Ics.parseFirstEvent(ics, ZoneId.of("UTC"))!!
        assertEquals("Big, fun meetup", e.title)
        assertEquals("line1\nline2", e.description)
        assertEquals("VRChat", e.location)
        assertEquals(1748800800000L, e.beginMillis)
        assertEquals(1748822400000L, e.endMillis)
        assertFalse(e.allDay)
        assertNull(Ics.parseFirstEvent("BEGIN:VCALENDAR\nEND:VCALENDAR"))
    }
}
