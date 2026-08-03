package com.vrcx.android.ui.screen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarSearchSourceTest {

    @Test
    fun `sources expose only accurately named supported providers`() {
        // Guards against accidentally re-introducing the misleading "VRCHAT" name
        // that implied a public avatar search VRChat does not actually offer.
        assertEquals(
            listOf(AvatarSearchSource.MY_AVATARS, AvatarSearchSource.REMOTE),
            AvatarSearchSource.entries,
        )
        assertEquals("My Avatars", AvatarSearchSource.MY_AVATARS.label)
        assertTrue(AvatarSearchSource.MY_AVATARS.hint.contains("your", ignoreCase = true))
    }
}
