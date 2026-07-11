package com.vrcx.android.ui.screen.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenshotUploadReadTest {
    @Test
    fun `bounded read distinguishes unreadable too large and success`() {
        assertSame(ScreenshotUploadReadResult.Unreadable, readUploadBytesBounded(null, maxBytes = 4))
        assertSame(
            ScreenshotUploadReadResult.TooLarge,
            readUploadBytesBounded(byteArrayOf(1, 2, 3, 4, 5).inputStream(), maxBytes = 4),
        )
        val success = readUploadBytesBounded(byteArrayOf(1, 2, 3, 4).inputStream(), maxBytes = 4)
            as ScreenshotUploadReadResult.Success
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), success.bytes)
    }
}
