package com.vrcx.android.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class VrcxTabRowTest {
    @Test
    fun `a wallpaper shows through the tab strip`() {
        val base = Color(0xFF102030)

        assertEquals(0.88f, tabStripContainerColor(base, isWallpaperActive = true).alpha, 0.002f)
    }

    @Test
    fun `without a wallpaper the strip stays exactly the theme colour`() {
        val base = Color(0xFF102030)

        assertEquals(base, tabStripContainerColor(base, isWallpaperActive = false))
    }
}
