package com.vrcx.android.ui.screen.tools

import com.vrcx.android.ui.navigation.VrcxRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolsScreenTest {

    @Test
    fun `resolveOpenByIdRoute maps each prefix to the correct VrcxRoute`() {
        assertEquals(VrcxRoutes.userDetail("usr_abc"), resolveOpenByIdRoute("usr_abc"))
        assertEquals(VrcxRoutes.worldDetail("wrld_xyz"), resolveOpenByIdRoute("wrld_xyz"))
        assertEquals(VrcxRoutes.avatarDetail("avtr_123"), resolveOpenByIdRoute("avtr_123"))
        assertEquals(VrcxRoutes.groupDetail("grp_qux"), resolveOpenByIdRoute("grp_qux"))
    }

    @Test
    fun `resolveOpenByIdRoute trims surrounding whitespace`() {
        assertEquals(VrcxRoutes.userDetail("usr_abc"), resolveOpenByIdRoute("  usr_abc  "))
    }

    @Test
    fun `resolveOpenByIdRoute returns null for unknown or empty input`() {
        assertNull(resolveOpenByIdRoute(""))
        assertNull(resolveOpenByIdRoute("nope"))
        assertNull(resolveOpenByIdRoute("user_abc"))
        assertNull(resolveOpenByIdRoute("usr"))
    }
}
