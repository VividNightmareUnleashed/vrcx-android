package com.vrcx.android.ui.screen.profile

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDestructiveActionTest {

    @Test
    fun `every action has a non-blank verb and consequence string for the dialog`() {
        val all = listOf(
            UserDestructiveAction.Block,
            UserDestructiveAction.Mute,
            UserDestructiveAction.HideAvatar,
            UserDestructiveAction.ShowAvatar,
            UserDestructiveAction.Unfriend,
        )
        all.forEach { action ->
            assertTrue("verb blank for $action", action.verb.isNotBlank())
            assertTrue("consequence blank for $action", action.consequence.isNotBlank())
        }
    }

    @Test
    fun `verbs are distinct so the dialog title is unambiguous per action`() {
        val verbs = listOf(
            UserDestructiveAction.Block.verb,
            UserDestructiveAction.Mute.verb,
            UserDestructiveAction.HideAvatar.verb,
            UserDestructiveAction.ShowAvatar.verb,
            UserDestructiveAction.Unfriend.verb,
        )
        for (i in verbs.indices) {
            for (j in i + 1 until verbs.size) {
                assertNotEquals(verbs[i], verbs[j])
            }
        }
    }
}
