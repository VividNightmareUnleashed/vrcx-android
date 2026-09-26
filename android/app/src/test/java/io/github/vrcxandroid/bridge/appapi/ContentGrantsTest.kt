package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.appapi.docs.ContentGrants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentGrantsTest {
    @Test
    fun theAppsOwnProvidersAreNeverOpenedForThePage() {
        val pkg = "io.github.vrcxandroid"
        assertTrue(ContentGrants.isOwnAuthority("io.github.vrcxandroid.fileprovider", pkg))
        assertTrue(ContentGrants.isOwnAuthority("IO.GITHUB.VRCXANDROID.fileprovider", pkg))
        assertTrue(ContentGrants.isOwnAuthority(pkg, pkg))
        assertFalse(ContentGrants.isOwnAuthority("io.github.vrcxandroidx.provider", pkg))
        assertFalse(ContentGrants.isOwnAuthority("com.android.externalstorage.documents", pkg))
        assertFalse(ContentGrants.isOwnAuthority("media", pkg))
    }

    @Test
    fun photosFolderNamesFallBackToTheLastPathPart() {
        assertEquals("VRChat", DocumentTreeNames.fallbackName("primary:Pictures/VRChat"))
        assertEquals("VRChat", DocumentTreeNames.fallbackName("primary:Pictures/VRChat/"))
        assertEquals("Pictures", DocumentTreeNames.fallbackName("primary:Pictures"))
        assertEquals("primary:", DocumentTreeNames.fallbackName("primary:"))
        assertEquals("1234", DocumentTreeNames.fallbackName("1234"))
    }
}
