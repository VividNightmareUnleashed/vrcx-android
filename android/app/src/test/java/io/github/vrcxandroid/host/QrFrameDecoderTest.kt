package io.github.vrcxandroid.host

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrFrameDecoderTest {
    private val payload = "vrcxc://pair?h=192.168.1.20&p=49460&fp=0123456789abcdef&c=ABCDE12345"

    /** A camera-like luma plane: the code drawn at [offsetX], [offsetY] on a grey background, rows [rowStride] wide. */
    private fun frame(
        width: Int,
        height: Int,
        rowStride: Int = width,
        offsetX: Int = 40,
        offsetY: Int = 30,
        inverted: Boolean = false,
    ): ByteArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 240, 240)
        val dark = if (inverted) 235.toByte() else 20.toByte()
        val light = if (inverted) 20.toByte() else 235.toByte()
        val luma = ByteArray(rowStride * (height - 1) + width) { 128.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val mx = x - offsetX
                val my = y - offsetY
                if (mx in 0 until matrix.width && my in 0 until matrix.height) {
                    luma[y * rowStride + x] = if (matrix[mx, my]) dark else light
                }
            }
        }
        return luma
    }

    @Test
    fun `finds the code in a frame`() {
        assertEquals(payload, QrFrameDecoder.decode(frame(320, 300), 320, 300, 320))
    }

    @Test
    fun `honours a row stride wider than the frame`() {
        assertEquals(payload, QrFrameDecoder.decode(frame(320, 300, rowStride = 384), 320, 300, 384))
    }

    @Test
    fun `finds a light-on-dark code`() {
        assertEquals(payload, QrFrameDecoder.decode(frame(320, 300, inverted = true), 320, 300, 320))
    }

    @Test
    fun `returns null without a code`() {
        assertNull(QrFrameDecoder.decode(ByteArray(320 * 300) { 128.toByte() }, 320, 300, 320))
    }

    @Test
    fun `returns null for a buffer smaller than the frame`() {
        assertNull(QrFrameDecoder.decode(ByteArray(100), 320, 300, 320))
        assertNull(QrFrameDecoder.decode(ByteArray(320 * 300), 320, 300, 300))
    }
}
