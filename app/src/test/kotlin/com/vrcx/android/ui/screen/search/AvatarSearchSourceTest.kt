package com.vrcx.android.ui.screen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarSearchSourceTest {

    @Test
    fun `MY_AVATARS replaces VRCHAT and is the default first entry`() {
        // Guards against accidentally re-introducing the misleading "VRCHAT" name
        // that implied a public avatar search VRChat does not actually offer.
        assertEquals(AvatarSearchSource.MY_AVATARS, AvatarSearchSource.entries.first())
        assertEquals(2, AvatarSearchSource.entries.size)
        assertEquals(AvatarSearchSource.REMOTE, AvatarSearchSource.entries[1])
    }

    @Test
    fun `every source carries a non-blank user-facing label and hint`() {
        AvatarSearchSource.entries.forEach { source ->
            assertTrue("label blank for $source", source.label.isNotBlank())
            assertTrue("hint blank for $source", source.hint.isNotBlank())
        }
    }

    @Test
    fun `MY_AVATARS label clarifies that results are owned avatars`() {
        assertEquals("My Avatars", AvatarSearchSource.MY_AVATARS.label)
        assertNotEquals("VRChat", AvatarSearchSource.MY_AVATARS.label)
        assertNotNull(AvatarSearchSource.MY_AVATARS.hint)
        assertTrue(AvatarSearchSource.MY_AVATARS.hint.contains("your", ignoreCase = true))
    }
}
