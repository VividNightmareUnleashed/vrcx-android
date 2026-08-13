package com.vrcx.android.ui.screen.profile

import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.friendStateOf
import com.vrcx.android.data.model.resolvedWorldId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserDetailPresentationTest {
    @Test
    fun `traveling location resolves destination world`() {
        assertEquals("wrld_destination", resolvedWorldId("traveling", "wrld_destination:123"))
    }

    @Test
    fun `non world locations are not navigable`() {
        assertNull(resolvedWorldId("private", null))
        assertNull(resolvedWorldId("traveling", "traveling"))
    }

    @Test
    fun `mutual friends read presence from the location, like every other list`() {
        assertEquals(FriendState.OFFLINE, friendStateOf(null))
        assertEquals(FriendState.OFFLINE, friendStateOf(""))
        assertEquals(FriendState.OFFLINE, friendStateOf("offline"))
        assertEquals(FriendState.ACTIVE, friendStateOf("private"))
        assertEquals(FriendState.ONLINE, friendStateOf("wrld_abc:123"))
    }
}
