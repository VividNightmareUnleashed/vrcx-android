package com.vrcx.android.data.util

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UploadBytesTest {

    @Test
    fun `upload byte limit matches decimal API contract`() {
        assertEquals(10_000_000, MAX_UPLOAD_SIZE_BYTES)
    }

    @Test
    fun `bounded read distinguishes unreadable, too large and success`() {
        assertSame(UploadBytesResult.Unreadable, readUploadBytesBounded(null, maxBytes = 4))
        assertSame(
            UploadBytesResult.TooLarge,
            readUploadBytesBounded(byteArrayOf(1, 2, 3, 4, 5).inputStream(), maxBytes = 4),
        )
        val success = readUploadBytesBounded(byteArrayOf(1, 2, 3, 4).inputStream(), maxBytes = 4)
            as UploadBytesResult.Success
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), success.bytes)
    }

    @Test
    fun `bounded read stops one byte over the limit without buffering the rest`() {
        val input = CountingInputStream(totalBytes = 100)

        val result = readUploadBytesBounded(input, maxBytes = 4)

        assertSame(UploadBytesResult.TooLarge, result)
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
