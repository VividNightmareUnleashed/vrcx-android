package com.vrcx.android.ui.screen.profile

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
}
