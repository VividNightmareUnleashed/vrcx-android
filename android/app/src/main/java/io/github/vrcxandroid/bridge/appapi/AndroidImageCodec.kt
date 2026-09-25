package io.github.vrcxandroid.bridge.appapi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import io.github.vrcxandroid.bridge.DotNetException
import java.io.ByteArrayOutputStream

/** [ImageCodec] on android.graphics. Sizes follow [ImageGeometry]; output is always PNG. */
class AndroidImageCodec : ImageCodec {
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun resizeToFitLimits(
        bytes: ByteArray,
        matchingDimensions: Boolean,
        maxWidth: Int,
        maxHeight: Int,
        maxSize: Long,
    ): ByteArray {
        val (srcW, srcH) = bounds(bytes)
        val (w, h) = ImageGeometry.fitLimits(srcW, srcH, maxWidth, maxHeight)
        var image = decodeAtSize(bytes, srcW, srcH, w, h)
        if (matchingDimensions && image.width != image.height) {
            val side = maxOf(image.width, image.height)
            val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            Canvas(square).drawBitmap(image, ((side - image.width) / 2).toFloat(), ((side - image.height) / 2).toFloat(), null)
            image.recycle()
            image = square
        }

        var data = encodePng(image)
        var encodedCurrent = true
        var i = 0
        while (i < 250 && data.size > maxSize) {
            if (!encodedCurrent) {
                data = encodePng(image)
                encodedCurrent = true
            }
            if (data.size < maxSize) break
            val (nw, nh) = ImageGeometry.shrinkStep(image.width, image.height)
            val smaller = scale(image, nw, nh)
            if (smaller !== image) image.recycle()
            image = smaller
            encodedCurrent = false
            i++
        }
        image.recycle()
        if (data.size > maxSize) throw DotNetException("Exception", "Failed to get image into target filesize.")
        return data
    }

    override fun resizePrint(bytes: ByteArray): ByteArray {
        val (srcW, srcH) = bounds(bytes)
        val layout = ImageGeometry.printLayout(srcW, srcH)
        val sample = sampleSizeFor(maxOf(srcW, srcH), minOf(srcW, srcH), ImageGeometry.PRINT_PICTURE_WIDTH, ImageGeometry.PRINT_PICTURE_HEIGHT)
        var image = decode(bytes, sample)
        if (layout.rotate) {
            // ImageSharp Rotate270 = 270° clockwise
            val m = Matrix().apply { postRotate(270f) }
            val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height, m, true)
            if (rotated !== image) image.recycle()
            image = rotated
        }
        layout.letterbox?.let { lb ->
            val canvasBitmap = Bitmap.createBitmap(ImageGeometry.PRINT_PICTURE_WIDTH, ImageGeometry.PRINT_PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            val scaled = scale(image, lb.width, lb.height)
            canvas.drawBitmap(scaled, lb.x.toFloat(), lb.y.toFloat(), null)
            if (scaled !== image) scaled.recycle()
            image.recycle()
            image = canvasBitmap
        }
        val picture = scale(image, layout.width, layout.height)
        if (picture !== image) image.recycle()
        val print = Bitmap.createBitmap(ImageGeometry.PRINT_WIDTH, ImageGeometry.PRINT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(print)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(picture, layout.x.toFloat(), layout.y.toFloat(), null)
        picture.recycle()
        return encodePng(print).also { print.recycle() }
    }

    override fun cropPrint(bytes: ByteArray): ByteArray? {
        val (srcW, srcH) = bounds(bytes)
        if (srcW != ImageGeometry.PRINT_WIDTH || srcH != ImageGeometry.PRINT_HEIGHT) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw unknownFormat()
        // Copy the unpremultiplied pixels as they are (a Canvas cannot draw non-premultiplied bitmaps).
        val w = ImageGeometry.PRINT_PICTURE_WIDTH
        val h = ImageGeometry.PRINT_PICTURE_HEIGHT
        val pixels = IntArray(w * h)
        full.getPixels(pixels, 0, w, ImageGeometry.PRINT_BORDER_X, ImageGeometry.PRINT_BORDER_Y, w, h)
        full.recycle()
        val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        crop.isPremultiplied = false
        crop.setPixels(pixels, 0, w, 0, 0, w, h)
        return encodePng(crop).also { crop.recycle() }
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw unknownFormat()
        return options.outWidth to options.outHeight
    }

    private fun decode(bytes: ByteArray, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw unknownFormat()
    }

    /** Decodes with the largest power-of-two subsampling that stays at or above the target, then scales exactly. */
    private fun decodeAtSize(bytes: ByteArray, srcW: Int, srcH: Int, w: Int, h: Int): Bitmap {
        val decoded = decode(bytes, sampleSizeFor(srcW, srcH, w, h))
        val scaled = scale(decoded, w, h)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun sampleSizeFor(srcW: Int, srcH: Int, w: Int, h: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= w * 2 && srcH / (sample * 2) >= h * 2) sample *= 2
        return sample
    }

    private fun scale(source: Bitmap, w: Int, h: Int): Bitmap {
        if (source.width == w && source.height == h) return source
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply { setScale(w / source.width.toFloat(), h / source.height.toFloat()) }
        Canvas(out).drawBitmap(source, m, filterPaint)
        return out
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw DotNetException("Exception", "Failed to encode PNG")
        return out.toByteArray()
    }

    private fun unknownFormat() = DotNetException("UnknownImageFormatException", "Image cannot be loaded.")
}
