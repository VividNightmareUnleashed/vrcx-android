package com.vrcx.android.ui.screen.gallery

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryViewModelTest {

    @Test
    fun `bounded upload reader accepts files at the size limit`() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        val result = readUploadBytesBounded(bytes.inputStream(), maxBytes = 4)

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `bounded upload reader stops after one byte over the size limit`() {
        val input = CountingInputStream(totalBytes = 100)

        val result = readUploadBytesBounded(input, maxBytes = 4)

        assertNull(result)
        assertEquals(5, input.bytesRead)
    }

    private class CountingInputStream(
        private val totalBytes: Int,
    ) : InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int {
            if (bytesRead >= totalBytes) return -1
            bytesRead += 1
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= totalBytes) return -1
            val count = minOf(length, totalBytes - bytesRead)
            buffer.fill(0, offset, offset + count)
            bytesRead += count
            return count
        }
    }
}
