package com.vrcx.android.ui.screen.moderation

import org.junit.Assert.assertEquals
import org.junit.Test

class ModerationScreenTest {

    @Test
    fun `moderationTabIndex matches each known type`() {
        MODERATION_TABS.forEachIndexed { index, tab ->
            assertEquals(index, moderationTabIndex(tab.type))
        }
    }

    @Test
    fun `moderationTabIndex falls back to 0 for unknown types`() {
        assertEquals(0, moderationTabIndex("unknown"))
        assertEquals(0, moderationTabIndex(""))
    }
}
