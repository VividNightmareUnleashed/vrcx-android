package io.github.vrcxandroid.host

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Finds a QR code in one camera frame's luma (Y) plane with ZXing (pure; used by [QrScanActivity]). */
object QrFrameDecoder {
    private val HINTS = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    /**
     * [luma] holds [height] rows of [rowStride] bytes, one byte per pixel; the last row may stop after [width] bytes.
     * Tries the frame as it is, then inverted (light modules on a dark background). Null when no QR code is found.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
    } catch (e: ReaderException) {
        null
    }
}
