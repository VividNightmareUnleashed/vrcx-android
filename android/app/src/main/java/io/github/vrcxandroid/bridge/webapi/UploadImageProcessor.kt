package io.github.vrcxandroid.bridge.webapi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Image preparation for the upload variants (upstream `Dotnet/AppApi/Common/ImageSaving.cs`).
 * Every result is PNG bytes.
 */
interface UploadImageProcessor {
    /** `ResizeImageToFitLimits(bytes, matchingDimensions)`: ≤ 2000×2000, optionally squared, ≤ 10 000 000 bytes. */
    fun resizeToFitLimits(image: ByteArray, matchingDimensions: Boolean): ByteArray

    /** `CropPrint` (when [cropWhiteBorder] and the image is exactly 2048×1440) followed by `ResizePrintImage`. */
    fun preparePrint(image: ByteArray, cropWhiteBorder: Boolean): ByteArray
}

/**
 * [UploadImageProcessor] on `android.graphics`. Sizes, offsets and the size-reduction loop follow upstream exactly
 * (including .NET's banker's rounding and integer truncation); resampling is bilinear instead of ImageSharp's bicubic.
 * Large sources are decoded with a power-of-two sample size that keeps them at least as large as the target, so a
 * 50-megapixel photo does not need 200 MB of heap.
 */
class BitmapUploadImageProcessor : UploadImageProcessor {
    override fun resizeToFitLimits(image: ByteArray, matchingDimensions: Boolean): ByteArray {
        var bitmap = decode(image, MAX_SIDE, MAX_SIDE)
        try {
            if (bitmap.width > MAX_SIDE) {
                val factor = bitmap.width / MAX_SIDE.toDouble()
                bitmap = scale(bitmap, MAX_SIDE, round(bitmap.height / factor))
            }
            if (bitmap.height > MAX_SIDE) {
                val factor = bitmap.height / MAX_SIDE.toDouble()
                bitmap = scale(bitmap, round(bitmap.width / factor), MAX_SIDE)
            }
            if (matchingDimensions && bitmap.width != bitmap.height) {
                val side = maxOf(bitmap.width, bitmap.height)
                val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888) // transparent
                Canvas(square).drawBitmap(bitmap, ((side - bitmap.width) / 2).toFloat(), ((side - bitmap.height) / 2).toFloat(), PAINT)
                bitmap.recycle()
                bitmap = square
            }
            var png = encode(bitmap)
            var i = 0
            while (i < 250 && png.size > MAX_BYTES) {
                if (i > 0) {
                    png = encode(bitmap)
                    if (png.size < MAX_BYTES) break
                }
                val (w, h) = if (bitmap.width > bitmap.height) {
                    val newWidth = bitmap.width - 25
                    newWidth to round(bitmap.height / (bitmap.width / newWidth.toDouble()))
                } else {
                    val newHeight = bitmap.height - 25
                    round(bitmap.width / (bitmap.height / newHeight.toDouble())) to newHeight
                }
                bitmap = scale(bitmap, w, h)
                i++
            }
            if (png.size > MAX_BYTES) throw IllegalStateException("Failed to get image into target filesize.")
            return png
        } finally {
            bitmap.recycle()
        }
    }

    override fun preparePrint(image: ByteArray, cropWhiteBorder: Boolean): ByteArray {
        var bitmap = decode(image, PRINT_WIDTH, PRINT_HEIGHT)
        try {
            if (cropWhiteBorder && bitmap.width == PRINT_CANVAS_WIDTH && bitmap.height == PRINT_CANVAS_HEIGHT) {
                bitmap = replace(bitmap, Bitmap.createBitmap(bitmap, PRINT_X, PRINT_Y, PRINT_WIDTH, PRINT_HEIGHT))
            }
            if (bitmap.height > bitmap.width) {
                val matrix = Matrix().apply { postRotate(270f) }
                bitmap = replace(bitmap, Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true))
            }
            if (bitmap.width < PRINT_WIDTH || bitmap.height < PRINT_HEIGHT) {
                val expected = PRINT_WIDTH.toDouble() / PRINT_HEIGHT
                val aspect = bitmap.width.toDouble() / bitmap.height
                val width: Int
                val height: Int
                val x: Int
                val y: Int
                if (aspect > expected) {
                    width = PRINT_WIDTH
                    height = maxOf(1, (width / aspect).toInt())
                    x = 0
                    y = (PRINT_HEIGHT - height) / 2
                } else {
                    height = PRINT_HEIGHT
                    width = maxOf(1, (height * aspect).toInt())
                    x = (PRINT_WIDTH - width) / 2
                    y = 0
                }
                val target = Bitmap.createBitmap(PRINT_WIDTH, PRINT_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(target)
                canvas.drawColor(Color.WHITE)
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), PAINT)
                if (scaled !== bitmap) scaled.recycle()
                bitmap = replace(bitmap, target)
            }
            if (bitmap.width > PRINT_WIDTH) {
                val factor = bitmap.width / PRINT_WIDTH.toDouble()
                bitmap = scale(bitmap, PRINT_WIDTH, round(bitmap.height / factor))
            }
            if (bitmap.height > PRINT_HEIGHT) {
                val factor = bitmap.height / PRINT_HEIGHT.toDouble()
                bitmap = scale(bitmap, round(bitmap.width / factor), PRINT_HEIGHT)
            }
            val canvasBitmap = Bitmap.createBitmap(PRINT_CANVAS_WIDTH, PRINT_CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, ((PRINT_CANVAS_WIDTH - bitmap.width) / 2).toFloat(), PRINT_Y.toFloat(), PAINT)
            try {
                return encode(canvasBitmap)
            } finally {
                canvasBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(bytes: ByteArray, minWidth: Int, minHeight: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Image cannot be decoded.")
        // Keep both sides at least as large as needed for either orientation, so the exact resize still happens.
        val need = maxOf(minWidth, minHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= need && bounds.outHeight / (sample * 2) >= need) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Image cannot be decoded.")
        return if (decoded.config == Bitmap.Config.ARGB_8888) decoded else replace(decoded, decoded.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun scale(bitmap: Bitmap, width: Int, height: Int): Bitmap =
        replace(bitmap, Bitmap.createScaledBitmap(bitmap, maxOf(1, width), maxOf(1, height), true))

    private fun replace(old: Bitmap, new: Bitmap): Bitmap {
        if (new !== old) old.recycle()
        return new
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** .NET `Math.Round` (to even). */
    private fun round(value: Double): Int = Math.rint(value).toInt()

    private companion object {
        const val MAX_SIDE = 2000
        const val MAX_BYTES = 10_000_000
        const val PRINT_WIDTH = 1920
        const val PRINT_HEIGHT = 1080
        const val PRINT_CANVAS_WIDTH = 2048
        const val PRINT_CANVAS_HEIGHT = 1440
        const val PRINT_X = 64
        const val PRINT_Y = 69
        val PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
