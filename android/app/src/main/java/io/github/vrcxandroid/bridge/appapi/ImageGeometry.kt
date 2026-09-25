package io.github.vrcxandroid.bridge.appapi

/** Image operations of upstream Dotnet/AppApi/Common/ImageSaving.cs; implemented with android.graphics by [AndroidImageCodec]. */
interface ImageCodec {
    /** `ResizeImageToFitLimits(bytes, matchingDimensions, maxWidth, maxHeight, maxSize)`: always returns a PNG. */
    fun resizeToFitLimits(
        bytes: ByteArray,
        matchingDimensions: Boolean,
        maxWidth: Int = 2000,
        maxHeight: Int = 2000,
        maxSize: Long = 10_000_000,
    ): ByteArray

    /** `ResizePrintImage(bytes)`: the picture on a white 2048x1440 print canvas, as PNG. */
    fun resizePrint(bytes: ByteArray): ByteArray

    /** `CropPrint`: the 1920x1080 picture of a 2048x1440 print as PNG, or null when the image is not 2048x1440. */
    fun cropPrint(bytes: ByteArray): ByteArray?
}

/**
 * The size arithmetic of the upstream image helpers, kept separate so it can be checked against vectors produced by
 * the .NET code. `Math.Round` in .NET rounds half to even, like [Math.rint].
 */
object ImageGeometry {
    const val PRINT_WIDTH = 2048
    const val PRINT_HEIGHT = 1440
    const val PRINT_PICTURE_WIDTH = 1920
    const val PRINT_PICTURE_HEIGHT = 1080
    const val PRINT_BORDER_X = 64
    const val PRINT_BORDER_Y = 69

    fun netRound(value: Double): Int = Math.rint(value).toInt()

    /** Width cap first (height rounded), then height cap (width rounded). */
    fun fitLimits(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        var w = width
        var h = height
        if (w > maxWidth) {
            val factor = w / maxWidth.toDouble()
            h = netRound(h / factor)
            w = maxWidth
        }
        if (h > maxHeight) {
            val factor = h / maxHeight.toDouble()
            w = netRound(w / factor)
            h = maxHeight
        }
        return w to h
    }

    /** One step of the file-size loop: the longer side shrinks by 25 px, the other keeps the aspect ratio. */
    fun shrinkStep(width: Int, height: Int): Pair<Int, Int> = if (width > height) {
        val newWidth = width - 25
        newWidth to netRound(height / (width / newWidth.toDouble()))
    } else {
        val newHeight = height - 25
        netRound(width / (height / newHeight.toDouble())) to newHeight
    }

    /** Where the picture ends up on the print canvas. */
    data class PrintLayout(
        /** Rotate by 270° clockwise first (portrait input). */
        val rotate: Boolean,
        /** Letterboxing into a white 1920x1080 canvas: the scaled picture's size and offset, or null. */
        val letterbox: Letterbox?,
        /** Size of the (letterboxed) picture after the 1920x1080 caps. */
        val width: Int,
        val height: Int,
        /** Offset on the 2048x1440 canvas. */
        val x: Int,
        val y: Int,
    )

    data class Letterbox(val width: Int, val height: Int, val x: Int, val y: Int)

    fun printLayout(sourceWidth: Int, sourceHeight: Int): PrintLayout {
        val rotate = sourceHeight > sourceWidth
        var w = if (rotate) sourceHeight else sourceWidth
        var h = if (rotate) sourceWidth else sourceHeight
        var letterbox: Letterbox? = null
        if (w < PRINT_PICTURE_WIDTH || h < PRINT_PICTURE_HEIGHT) {
            val expected = PRINT_PICTURE_WIDTH / PRINT_PICTURE_HEIGHT.toDouble()
            val aspect = w.toDouble() / h
            letterbox = if (aspect > expected) {
                val sh = (PRINT_PICTURE_WIDTH / aspect).toInt()
                Letterbox(PRINT_PICTURE_WIDTH, sh, 0, (PRINT_PICTURE_HEIGHT - sh) / 2)
            } else {
                val sw = (PRINT_PICTURE_HEIGHT * aspect).toInt()
                Letterbox(sw, PRINT_PICTURE_HEIGHT, (PRINT_PICTURE_WIDTH - sw) / 2, 0)
            }
            w = PRINT_PICTURE_WIDTH
            h = PRINT_PICTURE_HEIGHT
        }
        val (fw, fh) = fitLimits(w, h, PRINT_PICTURE_WIDTH, PRINT_PICTURE_HEIGHT)
        return PrintLayout(rotate, letterbox, fw, fh, (PRINT_WIDTH - fw) / 2, PRINT_BORDER_Y)
    }

    /** Bounding box of the source picture on the print canvas (what the .NET vectors measure). */
    fun printPictureBox(layout: PrintLayout): IntArray {
        val lb = layout.letterbox ?: return intArrayOf(layout.x, layout.y, layout.width, layout.height)
        // the letterbox canvas is exactly 1920x1080, so the caps do not rescale it
        return intArrayOf(layout.x + lb.x, layout.y + lb.y, lb.width, lb.height)
    }
}
