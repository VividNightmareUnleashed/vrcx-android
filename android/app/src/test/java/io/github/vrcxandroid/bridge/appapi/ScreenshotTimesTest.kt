package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.appapi.docs.LocalFileDoc
import io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotTimes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The creation time `GetLastScreenshot` and `creationDate` use: upstream keeps it through its in-place metadata edits,
 * so the port must not let its own rewrites turn an old screenshot into the newest one.
 */
class ScreenshotTimesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private lateinit var platform: FakeAppApiPlatform
    private lateinit var api: AppApi

    @Before
    fun setUp() {
        platform = FakeAppApiPlatform(tmp.newFolder("root"))
        api = AppApi(platform) { zone }
    }

    private fun call(method: String, vararg args: Any?): JsonElement = runBlocking {
        api.call(method, JsonArray(args.map { if (it == null) JsonNull else if (it is Boolean) JsonPrimitive(it) else JsonPrimitive(it.toString()) }))
    }

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000, zone).toInstant().toEpochMilli()

    /** Sets the times a rewrite on Android storage would leave: modification (and Linux "creation") = [at]. */
    private fun touch(file: File, at: Long) {
        file.setLastModified(at)
        try {
            Files.getFileAttributeView(file.toPath(), BasicFileAttributeView::class.java).setTimes(null, null, FileTime.fromMillis(at))
        } catch (e: Exception) {
            // file systems without creation times
        }
    }

    @Test
    fun captureTimeComesFromBothVrchatFileNameFormats() {
        assertEquals(millis(2023, 2, 8, 12, 31, 35, 104), ScreenshotTimes.captureTime("VRChat_2023-02-08_12-31-35.104_1920x1080.png", zone))
        assertEquals(millis(2021, 6, 1, 20, 15, 30, 123), ScreenshotTimes.captureTime("VRChat_1920x1080_2021-06-01_20-15-30.123.png", zone))
        assertEquals(millis(2021, 6, 1, 20, 15, 30, 0), ScreenshotTimes.captureTime("VRChat_1920x1080_2021-06-01_20-15-30.png", zone))
        assertEquals(millis(2024, 12, 31, 23, 59, 59, 900), ScreenshotTimes.captureTime("VRChat_2024-12-31_23-59-59.9_3840x2160.png", zone))
        // local time of the machine that took it
        assertEquals(
            ScreenshotTimes.captureTime("VRChat_2023-02-08_12-31-35.104_1920x1080.png", ZoneId.of("UTC"))!! - 3_600_000,
            ScreenshotTimes.captureTime("VRChat_2023-02-08_12-31-35.104_1920x1080.png", zone),
        )
    }

    @Test
    fun otherNamesHaveNoCaptureTime() {
        assertNull(ScreenshotTimes.captureTime("print.png", zone))
        assertNull(ScreenshotTimes.captureTime("Screenshot_2023-02-08_12-31-35.104.png", zone))
        assertNull(ScreenshotTimes.captureTime("VRChat_1920x1080.png", zone))
        assertNull(ScreenshotTimes.captureTime("VRChat_2023-13-08_12-31-35.104_1920x1080.png", zone))
        assertNull(ScreenshotTimes.captureTime("VRChat_2023-02-30_12-31-35.104_1920x1080.png", zone))
        assertNull(ScreenshotTimes.captureTime("VRChat_12023-02-08_12-31-35.104_1920x1080.png", zone))
    }

    @Test
    fun otherNamesFallBackToTheStorageCreationTime() {
        val file = tmp.newFile("print.png").apply { writeBytes(byteArrayOf(1)) }
        touch(file, 1_500_000_000_000)
        assertEquals(LocalFileDoc(file).creationTime(), ScreenshotTimes.creationTime(LocalFileDoc(file), zone))
    }

    @Test
    fun localRewritesKeepTheCreationTimeWhereTheFileSystemHasOne() {
        val file = tmp.newFile("shot.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")) }
        val created = FileTime.fromMillis(1_000_000_000_000)
        Files.getFileAttributeView(file.toPath(), BasicFileAttributeView::class.java).setTimes(null, null, created)
        assumeTrue("the file system keeps creation times", LocalFileDoc(file).creationTime() == created.toMillis())
        LocalFileDoc(file).writeBytes(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(created.toMillis(), LocalFileDoc(file).creationTime())
        assertEquals(false, File(file.parentFile, "shot.png.temp").exists())
    }

    @Test
    fun deletingMetadataFromAnOlderShotKeepsTheNewestShotLast() {
        val photos = tmp.newFolder("photos")
        val month = File(photos, "2025-09").apply { mkdirs() }
        val older = File(month, "VRChat_2025-09-01_10-00-00.000_1920x1080.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")) }
        val newer = File(month, "VRChat_2025-09-02_10-00-00.000_1920x1080.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")) }
        touch(older, millis(2025, 9, 1, 10, 0, 0, 5))
        touch(newer, millis(2025, 9, 2, 10, 0, 0, 5))
        platform.photos = FakeAppApiPlatform.FakePhotosLibrary(photos)
        assertEquals(newer.absolutePath, api.docs.resolve(call("GetLastScreenshot").jsonPrimitive.content)!!.key)

        assertEquals("true", call("DeleteScreenshotMetadata", older.absolutePath).jsonPrimitive.content)
        // what the rewrite leaves on Android storage: the only time stamp SAF and local files expose is now "now"
        touch(older, System.currentTimeMillis())
        assertEquals(newer.absolutePath, api.docs.resolve(call("GetLastScreenshot").jsonPrimitive.content)!!.key)
        // and the displayed creation date is still when the shot was taken
        val extra = Json.parseToJsonElement(call("GetExtraScreenshotData", older.absolutePath, false).jsonPrimitive.content).jsonObject
        assertEquals("2025-09-01 10:00:00", extra["creationDate"]!!.jsonPrimitive.content)

        // the same after "Delete all screenshot metadata", which rewrites every shot in walk order
        newer.writeBytes(Vectors.fixture("vrcx_json.png"))
        call("DeleteAllScreenshotMetadata")
        touch(newer, System.currentTimeMillis() - 1000)
        touch(older, System.currentTimeMillis())
        assertEquals(newer.absolutePath, api.docs.resolve(call("GetLastScreenshot").jsonPrimitive.content)!!.key)
    }
}
