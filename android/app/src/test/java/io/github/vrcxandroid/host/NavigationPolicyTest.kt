package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun sameOriginNavigationsAreAllowed() {
        assertEquals(NavigationAction.Allow, NavigationPolicy.decide("https://appassets.androidplatform.net/assets/web/index.html"))
        assertEquals(NavigationAction.Allow, NavigationPolicy.decide("https://appassets.androidplatform.net/assets/web/index.html#/feed"))
        assertEquals(NavigationAction.Allow, NavigationPolicy.decide("https://APPASSETS.androidplatform.net:443/assets/web/"))
    }

    @Test
    fun vrcxLinksBecomeLaunchCommands() {
        assertEquals(NavigationAction.LaunchCommand("user/usr_1"), NavigationPolicy.decide("vrcx://user/usr_1"))
        assertEquals(NavigationAction.Block, NavigationPolicy.decide("vrcx://"))
    }

    @Test
    fun otherWebOriginsOpenExternally() {
        assertEquals(NavigationAction.External("https://vrchat.com/home"), NavigationPolicy.decide("https://vrchat.com/home"))
        assertEquals(NavigationAction.External("http://example.com/x"), NavigationPolicy.decide("http://example.com/x"))
        // Same host over http or another port is a different origin.
        assertEquals(
            NavigationAction.External("http://appassets.androidplatform.net/assets/web/index.html"),
            NavigationPolicy.decide("http://appassets.androidplatform.net/assets/web/index.html"),
        )
        assertEquals(
            NavigationAction.External("https://appassets.androidplatform.net:8443/x"),
            NavigationPolicy.decide("https://appassets.androidplatform.net:8443/x"),
        )
    }

    @Test
    fun everythingElseIsBlocked() {
        listOf(
            null, "", "javascript:alert(1)", "data:text/html,hi", "file:///sdcard/x.html", "intent://scan/#Intent;end",
            "blob:https://appassets.androidplatform.net/1234", "about:blank", "vrchat://launch?id=x", "https://",
            "https://exa mple.com/",
        ).forEach { assertEquals("url=$it", NavigationAction.Block, NavigationPolicy.decide(it)) }
    }

    @Test
    fun sameOriginHelper() {
        assertTrue(NavigationPolicy.isSameOrigin("https://appassets.androidplatform.net/local/x.png"))
        assertFalse(NavigationPolicy.isSameOrigin("https://appassets.androidplatform.net.evil.com/"))
        assertFalse(NavigationPolicy.isSameOrigin("not a url"))
    }
}
