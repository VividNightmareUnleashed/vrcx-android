package com.vrcx.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TextFormatTest {
    @Test
    fun `byte counts render one way everywhere they are shown`() {
        assertEquals("0 B", formatByteCount(0))
        assertEquals("512 B", formatByteCount(512))
        assertEquals("2.0 KB", formatByteCount(2048))
        assertEquals("1.4 MB", formatByteCount(1_500_000))
        assertEquals("1.0 GB", formatByteCount(1024L * 1024 * 1024))
    }

    @Test
    fun `the decimal separator does not follow the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.4 MB", formatByteCount(1_500_000))
        } finally {
            Locale.setDefault(original)
        }
    }
}
