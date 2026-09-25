package io.github.vrcxandroid.bridge.appapi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vrcxandroid.bridge.appapi.docs.ContentDoc
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Saving prints/stickers/emoji to MediaStore `Pictures/VRCX/<type>/<month>/` and the screenshot/print methods working
 * on the resulting content URIs, through the real [AndroidAppApiPlatform].
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreAppApiTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<Uri>()
    private val month = "2099-" + (System.nanoTime() % 90 + 10)
    private val platform by lazy { AndroidAppApiPlatform(context) }
    private val api by lazy { AppApi(platform) }

    @After
    fun cleanUp() {
        for (uri in created) context.contentResolver.delete(uri, null, null)
    }

    private fun png(w: Int, h: Int, color: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun call(method: String, vararg args: Any?): JsonElement = runBlocking {
        api.call(method, JsonArray(args.map { if (it == null) JsonNull else if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))
    }

    private fun save(type: String, name: String, bytes: ByteArray): Uri {
        val folder = platform.ugc.folder("", type, month)
        assertFalse(folder.exists(name))
        val uri = Uri.parse(folder.create(name, bytes))
        created += uri
        assertTrue(folder.exists(name))
        return uri
    }

    @Test
    fun savesIntoPicturesVrcxAndListsTheFile() {
        val bytes = png(16, 16, Color.RED)
        val uri = save("Stickers", "vrcx_test_sticker.png", bytes)
        assertEquals("media", uri.authority)
        val doc = ContentDoc(context, uri)
        assertTrue(doc.exists())
        assertEquals("vrcx_test_sticker.png", doc.name)
        assertEquals(bytes.size.toLong(), doc.length())
        assertArrayEquals(bytes, doc.readBytes())
        val listed = platform.ugc.listPngs("", "Stickers").map { it.key }
        assertTrue(listed.toString(), uri.toString() in listed)
        // an imported Windows path falls back to the default location as well
        assertTrue(platform.ugc.folder("C:\\Users\\x\\Pictures\\VRChat", "Stickers", month).exists("vrcx_test_sticker.png"))
        // the base64 of the content document is available to the gallery upload
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), call("GetFileBase64", uri.toString()).jsonPrimitive.content)
    }

    @Test
    fun cropPrintImageRewritesTheMediaStoreItem() {
        val print = ScreenshotParser.writeVrcxMetadata("{\"application\":\"VRCX\"}", png(2048, 1440, Color.BLUE))!!
        val uri = save("Prints", "vrcx_test_print.png", print)
        assertEquals("true", call("CropPrintImage", uri.toString()).jsonPrimitive.content)
        val cropped = ContentDoc(context, uri).readBytes()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(cropped, 0, cropped.size, o)
        assertEquals(1920, o.outWidth)
        assertEquals(1080, o.outHeight)
        // the metadata chunk survived; a second crop is refused (no longer 2048x1440)
        assertEquals(listOf("{\"application\":\"VRCX\"}"), ScreenshotParser.readTextMetadata(ContentDoc(context, uri)))
        assertEquals("false", call("CropPrintImage", uri.toString()).jsonPrimitive.content)
    }

    @Test
    fun cropAllPrintsWalksTheDefaultPrintsFolder() {
        val uri = save("Prints", "vrcx_test_all.png", png(2048, 1440, Color.GREEN))
        assertEquals(JsonNull, call("CropAllPrints", ""))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes = ContentDoc(context, uri).readBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        assertEquals(1920, o.outWidth)
    }

    @Test
    fun screenshotMethodsWorkOnContentDocuments() {
        val json = "{\"application\":\"VRCX\",\"version\":1,\"author\":{\"id\":\"usr_a\",\"displayName\":\"A\"}," +
            "\"world\":{\"name\":\"W\",\"id\":\"wrld_b\",\"instanceId\":\"wrld_b:1\"},\"players\":[{\"id\":\"usr_c\",\"displayName\":\"C\"}]}"
        val shot = ScreenshotParser.writeVrcxMetadata(json, png(64, 36, Color.YELLOW))!!
        val uri = save("Emoji", "VRChat_2099-01-01_00-00-00.000_64x36.png", shot)

        val metadata = Json.parseToJsonElement(call("GetScreenshotMetadata", uri.toString()).jsonPrimitive.content).jsonObject
        assertEquals(uri.toString(), metadata["sourceFile"]!!.jsonPrimitive.content)
        assertEquals("VRCX", metadata["application"]!!.jsonPrimitive.content)

        val extra = Json.parseToJsonElement(call("GetExtraScreenshotData", uri.toString(), true).jsonPrimitive.content).jsonObject
        assertEquals("64x36", extra["fileResolution"]!!.jsonPrimitive.content)
        assertEquals("VRChat_2099-01-01_00-00-00.000_64x36", extra["fileName"]!!.jsonPrimitive.content)
        assertEquals(shot.size.toString(), extra["fileSizeBytes"]!!.jsonPrimitive.content)
        val shown = extra["filePath"]!!.jsonPrimitive.content
        // the displayed path is a mirror copy in the cache that maps back to the MediaStore item
        assertTrue(shown, shown.contains("screenshot-mirror/"))
        assertEquals(uri.toString(), api.docs.resolve(shown)!!.key)

        assertEquals("true", call("DeleteScreenshotMetadata", shown).jsonPrimitive.content)
        val after = Json.parseToJsonElement(call("GetScreenshotMetadata", uri.toString()).jsonPrimitive.content).jsonObject
        assertEquals(ScreenshotParser.NO_METADATA, after["error"]!!.jsonPrimitive.content)
    }
}
