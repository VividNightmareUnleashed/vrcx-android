package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.appapi.docs.LocalFileDoc
import io.github.vrcxandroid.bridge.appapi.png.PngHelper
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

/** The screenshot metadata port (PNGFile/PNGChunk/ScreenshotHelper) against the .NET vectors. */
class ScreenshotMetadataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of(Vectors.root["timeZone"]!!.jsonPrimitive.content)
    private lateinit var platform: FakeAppApiPlatform
    private lateinit var api: AppApi

    @Before
    fun setUp() {
        platform = FakeAppApiPlatform(tmp.newFolder("root"))
        Vectors.copyFixtures(platform.servedDir)
        api = AppApi(platform) { zone }
    }

    private fun call(method: String, vararg args: Any?): JsonElement = runBlocking {
        api.call(method, JsonArray(args.map { if (it == null) JsonNull else if (it is Boolean) JsonPrimitive(it) else if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))
    }

    private val shots get() = Vectors.root["screenshots"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun getScreenshotMetadataMatchesDotNetText() {
        for (s in shots) {
            val name = s["file"]!!.jsonPrimitive.content
            val expected = s["metadata"]!!.jsonPrimitive.content
            assertEquals(name, expected, call("GetScreenshotMetadata", "{dir}/$name").jsonPrimitive.content)
        }
    }

    @Test
    fun getExtraScreenshotDataMatchesDotNetText() {
        for (s in shots) {
            val name = s["file"]!!.jsonPrimitive.content
            val result = call("GetExtraScreenshotData", "{dir}/$name", true)
            val expected = s["extra"]
            if (expected == null || expected is JsonNull) {
                assertEquals(name, JsonNull, result)
                continue
            }
            val text = result.jsonPrimitive.content
            val creation = Regex("\n  \"creationDate\": \"(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\",")
            val match = creation.find(text)
            assertEquals("$name has a creationDate", true, match != null)
            assertEquals(name, expected.jsonPrimitive.content, text.replace(creation, ""))
        }
    }

    @Test
    fun readTextMetadataMatchesDotNet() {
        for (s in shots) {
            val name = s["file"]!!.jsonPrimitive.content
            val expected = s["texts"]?.jsonArray?.map { it.jsonPrimitive.content } ?: continue
            assertEquals(name, expected, ScreenshotParser.readTextMetadata(LocalFileDoc(File(platform.servedDir, name))))
        }
    }

    @Test
    fun deleteScreenshotMetadataProducesTheSameBytes() {
        for (s in shots) {
            val name = s["file"]!!.jsonPrimitive.content
            val result = call("DeleteScreenshotMetadata", "{dir}/$name")
            assertEquals(name, s["deleteResult"]!!.jsonPrimitive.content.toBoolean(), result.jsonPrimitive.content.toBoolean())
            val bytes = File(platform.servedDir, name).readBytes()
            assertEquals(name, s["afterDeleteLength"]!!.jsonPrimitive.int, bytes.size)
            assertEquals(name, s["afterDeleteSha256"]!!.jsonPrimitive.content, Vectors.sha256(bytes))
        }
    }

    @Test
    fun findScreenshotsBySearchMatchesDotNet() {
        platform.photos = FakeAppApiPlatform.FakePhotosLibrary(platform.servedDir)
        for (v in Vectors.root["search"]!!.jsonArray) {
            val o = v.jsonObject
            val json = call("FindScreenshotsBySearch", o["query"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.int).jsonPrimitive.content
            val names = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content.removePrefix("{dir}/") }
            assertEquals(o.toString(), o["results"]!!.jsonArray.map { it.jsonPrimitive.content }, names)
        }
        // served from the persisted index the second time
        val again = call("FindScreenshotsBySearch", "teacup", 0).jsonPrimitive.content
        assertEquals(3, Json.parseToJsonElement(again).jsonArray.size)
        assertEquals(true, File(platform.cacheDir, "screenshot-search-index.json").isFile)
    }

    @Test
    fun searchWithoutPhotosRootReturnsEmptyArray() {
        assertEquals("[]", call("FindScreenshotsBySearch", "x", 0).jsonPrimitive.content)
        assertEquals(JsonNull, call("GetLastScreenshot"))
        assertEquals("", call("GetVRChatPhotosLocation").jsonPrimitive.content)
    }

    @Test
    fun copyingITxtChunksMatchesPngFileWriteChunk() {
        val v = Vectors.root["copyITxt"]!!.jsonObject
        val out = PngHelper.copyITxtChunks(Vectors.fixture(v["source"]!!.jsonPrimitive.content), Vectors.fixture(v["target"]!!.jsonPrimitive.content))
        assertEquals(v["length"]!!.jsonPrimitive.int, out.size)
        assertEquals(v["sha256"]!!.jsonPrimitive.content, Vectors.sha256(out))
    }

    @Test
    fun writingVrcxMetadataMatchesDotNet() {
        val v = Vectors.root["writeVrcx"]!!.jsonObject
        val out = ScreenshotParser.writeVrcxMetadata("{\"application\":\"VRCX\"}", Vectors.fixture(v["target"]!!.jsonPrimitive.content))!!
        assertEquals(v["sha256"]!!.jsonPrimitive.content, Vectors.sha256(out))
    }

    @Test
    fun xmpDatesFollowDateTimeTryParse() {
        for (v in Vectors.root["xmpDates"]!!.jsonArray) {
            val o = v.jsonObject
            val input = o["input"]!!.jsonPrimitive.content
            val expected = (o["output"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            assertEquals(input, expected, NetDateTime.tryParse(input, zone)?.format())
        }
    }

    @Test
    fun jsonDatesFollowNewtonsoftRoundtripKind() {
        for (v in Vectors.root["jsonDates"]!!.jsonArray) {
            val o = v.jsonObject
            val input = o["input"]!!.jsonPrimitive.content
            assertEquals(input, o["output"]!!.jsonPrimitive.content, NetDateTime.parseJson(input, zone)?.format())
        }
    }

    @Test
    fun missingOrEmptyPathsBehaveLikeUpstream() {
        assertEquals(JsonNull, call("GetScreenshotMetadata", ""))
        assertEquals(
            "{\n  \"sourceFile\": \"C:\\\\Users\\\\x\\\\VRChat_1.png\",\n  \"error\": \"Screenshot contains no metadata.\"\n}",
            call("GetScreenshotMetadata", "C:\\Users\\x\\VRChat_1.png").jsonPrimitive.content,
        )
        assertEquals(JsonNull, call("GetExtraScreenshotData", "{dir}/missing.png", true))
        assertEquals(false, call("DeleteScreenshotMetadata", "{dir}/missing.png").jsonPrimitive.content.toBoolean())
        assertEquals(false, call("DeleteScreenshotMetadata", null).jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, call("GetFileBase64", "{dir}/missing.png"))
        assertNull(ScreenshotParser.getScreenshotMetadata(LocalFileDoc(File(platform.servedDir, "upper.PNG")), "x"))
    }

    @Test
    fun lfsFloatsParseLikeDotNet() {
        assertEquals(12.3f, ScreenshotParser.parseNetFloat("12.30"))
        assertEquals(1234.5f, ScreenshotParser.parseNetFloat("1,234.5"))
        assertEquals(0f, ScreenshotParser.parseNetFloat("Olivia."))
        assertEquals(0f, ScreenshotParser.parseNetFloat("1.5f"))
        assertEquals(-0.5f, ScreenshotParser.parseNetFloat(" -.5 "))
    }
}
