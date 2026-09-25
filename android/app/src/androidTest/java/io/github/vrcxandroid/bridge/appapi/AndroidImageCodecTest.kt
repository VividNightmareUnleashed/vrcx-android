package io.github.vrcxandroid.bridge.appapi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vrcxandroid.bridge.appapi.png.PngHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Bitmap-based resize/crop against the geometry recorded from the upstream ImageSharp code. */
@RunWith(AndroidJUnit4::class)
class AndroidImageCodecTest {
    private val codec = AndroidImageCodec()

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        bitmap.recycle()
    }.toByteArray()

    private fun solid(w: Int, h: Int, color: Int): ByteArray =
        png(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun size(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun resizeToFitLimitsProducesTheUpstreamSizes() {
        // (w, h, matching) -> (outW, outH), from src/test/resources/appapi/vectors.json "resize"
        val cases = listOf(
            Triple(100, 50, false) to (100 to 50),
            Triple(2000, 2000, false) to (2000 to 2000),
            Triple(2001, 1000, false) to (2000 to 1000),
            Triple(4000, 3000, false) to (2000 to 1500),
            Triple(2500, 2600, false) to (1923 to 2000),
            Triple(1000, 3001, false) to (666 to 2000),
            Triple(2003, 2999, false) to (1336 to 2000),
            Triple(300, 100, true) to (300 to 300),
            Triple(100, 301, true) to (301 to 301),
            Triple(5000, 1200, true) to (2000 to 2000),
            Triple(1, 3000, false) to (1 to 2000),
        )
        for ((input, expected) in cases) {
            val out = codec.resizeToFitLimits(solid(input.first, input.second, Color.GREEN), input.third)
            assertEquals(input.toString(), expected, size(out))
        }
    }

    @Test
    fun matchingDimensionsCentresOnATransparentSquare() {
        val out = decode(codec.resizeToFitLimits(solid(300, 100, Color.RED), true))
        assertEquals(Color.TRANSPARENT, out.getPixel(150, 10))
        assertEquals(Color.RED, out.getPixel(150, 150))
        assertEquals(Color.TRANSPARENT, out.getPixel(150, 290))
    }

    @Test
    fun fileSizeLoopShrinksByTwentyFivePixelsUntilItFits() {
        val random = Random(1)
        val noise = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) setPixel(x, y, random.nextInt() or 0xFF000000.toInt())
        }
        val input = png(noise)
        val out = codec.resizeToFitLimits(input, false, maxSize = input.size / 2L)
        assertTrue(out.size <= input.size / 2)
        val (w, h) = size(out)
        // the loop only walks the shrinkStep sequence
        var step = 400 to 300
        val sequence = mutableListOf(step)
        repeat(20) {
            step = ImageGeometry.shrinkStep(step.first, step.second)
            sequence += step
        }
        assertTrue("$w x $h", (w to h) in sequence && w < 400)
    }

    @Test
    fun resizePrintPlacesThePictureLikeUpstream() {
        // (w, h) -> (x, y, innerW, innerH) of the picture on the 2048x1440 canvas, from vectors.json "resizePrint"
        val cases = listOf(
            (1920 to 1080) to listOf(64, 69, 1920, 1080),
            (1000 to 500) to listOf(64, 129, 1920, 960),
            (500 to 1000) to listOf(64, 129, 1920, 960),
            (800 to 600) to listOf(304, 69, 1440, 1080),
            (4000 to 1000) to listOf(64, 369, 1920, 480),
            (2000 to 3000) to listOf(214, 69, 1620, 1080),
            (1920 to 1200) to listOf(160, 69, 1728, 1080),
            (1919 to 1080) to listOf(64, 69, 1919, 1080),
            (2560 to 1080) to listOf(64, 69, 1920, 810),
        )
        for ((input, expected) in cases) {
            val out = decode(codec.resizePrint(solid(input.first, input.second, Color.RED)))
            assertEquals(2048, out.width)
            assertEquals(1440, out.height)
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            val row = IntArray(out.width)
            for (y in 0 until out.height) {
                out.getPixels(row, 0, out.width, 0, y, out.width, 1)
                for (x in 0 until out.width) {
                    val p = row[x]
                    if (Color.red(p) > 128 && Color.green(p) < 128 && Color.blue(p) < 128) {
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
            }
            assertEquals(input.toString(), expected, listOf(minX, minY, maxX - minX + 1, maxY - minY + 1))
            assertEquals(Color.WHITE, out.getPixel(10, 10))
            out.recycle()
        }
    }

    @Test
    fun portraitPrintsAreRotatedCounterClockwise() {
        // left half blue, right half red; Rotate270 (270° clockwise) puts the right half on top
        val src = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            for (y in 0 until 2000) for (x in 500 until 1000) setPixel(x, y, Color.RED)
        }
        val out = decode(codec.resizePrint(png(src)))
        // picture box is (64, 129, 1920, 960)
        assertEquals(Color.RED, out.getPixel(1000, 129 + 100))
        assertEquals(Color.BLUE, out.getPixel(1000, 129 + 860))
    }

    @Test
    fun cropPrintKeepsTheExactPixels() {
        val print = Bitmap.createBitmap(2048, 1440, Bitmap.Config.ARGB_8888)
        val row = IntArray(2048)
        for (y in 0 until 1440) {
            for (x in 0 until 2048) row[x] = Color.rgb(x and 0xFF, y and 0xFF, (x xor y) and 0xFF)
            print.setPixels(row, 0, 2048, 0, y, 2048, 1)
        }
        val out = decode(codec.cropPrint(png(print))!!)
        assertEquals(1920, out.width)
        assertEquals(1080, out.height)
        for ((x, y) in listOf(0 to 0, 1919 to 1079, 100 to 500, 1500 to 17, 777 to 1000)) {
            val sx = x + 64
            val sy = y + 69
            assertEquals("$x,$y", Color.rgb(sx and 0xFF, sy and 0xFF, (sx xor sy) and 0xFF), out.getPixel(x, y))
        }
        assertNull(codec.cropPrint(solid(2048, 1441, Color.WHITE)))
    }

    @Test
    fun croppedPrintsKeepTheirTextChunks() {
        val print = png(Bitmap.createBitmap(2048, 1440, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        val withText = io.github.vrcxandroid.bridge.appapi.screenshot.ScreenshotParser.writeVrcxMetadata("{\"note\":\"hi\"}", print)!!
        val cropped = PngHelper.copyITxtChunks(withText, codec.cropPrint(withText)!!)
        val texts = io.github.vrcxandroid.bridge.appapi.png.PngFile(io.github.vrcxandroid.bridge.appapi.png.MemorySeekableStream(cropped))
            .use { PngHelper.readTextChunk("Description", it) }
        assertEquals("{\"note\":\"hi\"}", texts)
        assertEquals(1920 to 1080, size(cropped))
    }
}
