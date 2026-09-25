package io.github.vrcxandroid.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vrcxandroid.bridge.webapi.BitmapUploadImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Sizes and layout of the upload image helpers (upstream ImageSaving.cs). */
@RunWith(AndroidJUnit4::class)
class UploadImageProcessorTest {
    private val images = BitmapUploadImageProcessor()

    private fun png(width: Int, height: Int, color: Int = Color.RED, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap {
        assertTrue("output is PNG", bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte())
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun smallImageKeepsItsSize() {
        val out = decode(images.resizeToFitLimits(png(640, 480, format = Bitmap.CompressFormat.JPEG), false))
        assertEquals(640, out.width)
        assertEquals(480, out.height)
    }

    @Test
    fun wideImageIsScaledToTwoThousandWide() {
        val out = decode(images.resizeToFitLimits(png(3000, 1000), false))
        assertEquals(2000, out.width)
        assertEquals(667, out.height) // round(1000 / 1.5)
    }

    @Test
    fun tallImageIsScaledToTwoThousandHigh() {
        val out = decode(images.resizeToFitLimits(png(1000, 4001), false))
        assertEquals(2000, out.height)
        assertEquals(500, out.width) // round(1000 / 2.0005) = round(499.875)
    }

    @Test
    fun matchingDimensionsCentresOnTransparentSquare() {
        val out = decode(images.resizeToFitLimits(png(300, 100), true))
        assertEquals(300, out.width)
        assertEquals(300, out.height)
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        assertEquals(Color.RED, out.getPixel(150, 150))
        assertEquals(0, Color.alpha(out.getPixel(150, 99)))
        assertEquals(Color.RED, out.getPixel(150, 100))
    }

    @Test
    fun pngOverTenMegabytesIsShrunkBy25PixelSteps() {
        // Opaque random noise barely compresses: 2000x1500 RGBA is ~12 MB as PNG.
        val random = java.util.Random(1)
        val pixels = IntArray(2000 * 1500) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        val bitmap = Bitmap.createBitmap(pixels, 2000, 1500, Bitmap.Config.ARGB_8888)
        val source = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        assertTrue("fixture must exceed the limit: ${source.size}", source.size > 10_000_000)
        val result = images.resizeToFitLimits(source, false)
        assertTrue(result.size <= 10_000_000)
        val out = decode(result)
        assertTrue(out.width < 2000)
        assertEquals(0, (2000 - out.width) % 25)
        // Aspect kept (each step rounds, so allow a pixel or two of drift).
        assertTrue(Math.abs(Math.rint(1500 / (2000.0 / out.width)) - out.height) <= 2)
    }

    @Test
    fun printIsPlacedOnWhite2048x1440Canvas() {
        val out = decode(images.preparePrint(png(1920, 1080), false))
        assertEquals(2048, out.width)
        assertEquals(1440, out.height)
        assertEquals(Color.WHITE, out.getPixel(10, 10))
        assertEquals(Color.RED, out.getPixel(64, 69))
        assertEquals(Color.RED, out.getPixel(64 + 1919, 69 + 1079))
        assertEquals(Color.WHITE, out.getPixel(64 + 1920, 69 + 1080))
    }

    @Test
    fun printCropRemovesAnExistingBorder() {
        // A 2048x1440 print: white border, blue picture area at (64, 69, 1920, 1080).
        val bitmap = Bitmap.createBitmap(2048, 1440, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        android.graphics.Canvas(bitmap).drawRect(64f, 69f, 64f + 1920f, 69f + 1080f, android.graphics.Paint().apply { color = Color.BLUE })
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = decode(images.preparePrint(bytes, true))
        assertEquals(2048, out.width)
        assertEquals(Color.BLUE, out.getPixel(64, 69))
        assertEquals(Color.WHITE, out.getPixel(63, 69))
    }

    @Test
    fun portraitPrintIsRotatedAndSmallPrintIsLetterboxed() {
        val portrait = decode(images.preparePrint(png(1080, 1920), false))
        assertEquals(2048, portrait.width)
        assertEquals(Color.RED, portrait.getPixel(1000, 600))

        // 400x100 is wider than 16:9: scaled to 1920x480 and centred vertically on white 1920x1080.
        val small = decode(images.preparePrint(png(400, 100), false))
        assertEquals(1440, small.height)
        assertEquals(Color.WHITE, small.getPixel(1000, 69 + 100))
        assertEquals(Color.RED, small.getPixel(1000, 69 + 540))
    }
}
