package com.vrcx.android.ui.screen.moderation

import org.junit.Assert.assertEquals
import org.junit.Test

class ModerationScreenTest {

    @Test
    fun `moderationTabIndex matches each known type`() {
        assertEquals(0, moderationTabIndex("block"))
        assertEquals(1, moderationTabIndex("mute"))
        assertEquals(2, moderationTabIndex("hideAvatar"))
        assertEquals(3, moderationTabIndex("showAvatar"))
        assertEquals(4, moderationTabIndex("interactOff"))
        assertEquals(5, moderationTabIndex("interactOn"))
    }

    @Test
    fun `moderationTabIndex falls back to 0 for unknown types`() {
        assertEquals(0, moderationTabIndex("unknown"))
        assertEquals(0, moderationTabIndex(""))
    }

    @Test
    fun `MODERATION_TYPES and TAB_LABELS stay aligned`() {
        assertEquals(MODERATION_TYPES.size, TAB_LABELS.size)
    }
}
