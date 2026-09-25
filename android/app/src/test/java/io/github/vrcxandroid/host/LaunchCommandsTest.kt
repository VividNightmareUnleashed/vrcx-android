package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchCommandsTest {
    @Test
    fun stripsTheVrcxPrefixLikeElectron() {
        assertEquals("world/wrld_1234", LaunchCommands.fromUri("vrcx://world/wrld_1234"))
        assertEquals("user/usr_abc", LaunchCommands.fromUri("  vrcx://user/usr_abc  "))
        assertEquals("avatar/avtr_x", LaunchCommands.fromUri("VRCX://avatar/avtr_x"))
    }

    @Test
    fun acceptsTheOpaqueForm() {
        assertEquals("group/grp_1", LaunchCommands.fromUri("vrcx:group/grp_1"))
    }

    @Test
    fun keepsInstanceSyntaxAndPercentEncodingUntouched() {
        assertEquals(
            "world/wrld_1:12345~private(usr_2)~region(eu)",
            LaunchCommands.fromUri("vrcx://world/wrld_1:12345~private(usr_2)~region(eu)"),
        )
        assertEquals("addavatardb/https%3A%2F%2Fexample.com", LaunchCommands.fromUri("vrcx://addavatardb/https%3A%2F%2Fexample.com"))
        assertEquals("addavatardb/https://example.com/api", LaunchCommands.fromUri("vrcx://addavatardb/https://example.com/api"))
    }

    @Test
    fun dropsTrailingSlashes() {
        assertEquals("user/usr_abc", LaunchCommands.fromUri("vrcx://user/usr_abc/"))
        assertEquals("user/usr_abc", LaunchCommands.fromUri("vrcx://user/usr_abc//"))
    }

    @Test
    fun rejectsOtherSchemesAndEmptyCommands() {
        assertNull(LaunchCommands.fromUri(null))
        assertNull(LaunchCommands.fromUri(""))
        assertNull(LaunchCommands.fromUri("https://vrchat.com/home/world/wrld_1"))
        assertNull(LaunchCommands.fromUri("vrcx://"))
        assertNull(LaunchCommands.fromUri("vrcx:///"))
        assertNull(LaunchCommands.fromUri("vrcxx://world/x"))
    }

    @Test
    fun sharedTextBecomesASearchCommand() {
        assertEquals("search/https://vrchat.com/home/user/usr_1", LaunchCommands.fromSharedText(" https://vrchat.com/home/user/usr_1\n"))
        assertNull(LaunchCommands.fromSharedText("   "))
        assertNull(LaunchCommands.fromSharedText(null))
        val long = "x".repeat(LaunchCommands.MAX_SHARED_TEXT + 100)
        assertEquals("search/" + "x".repeat(LaunchCommands.MAX_SHARED_TEXT), LaunchCommands.fromSharedText(long))
    }

    @Test
    fun intentRoutingUsesTheActionToPickTheSource() {
        assertEquals("world/wrld_1", LaunchCommands.fromIntent("android.intent.action.VIEW", "vrcx://world/wrld_1", null))
        assertEquals("search/usr_1", LaunchCommands.fromIntent("android.intent.action.SEND", null, "usr_1"))
        assertNull(LaunchCommands.fromIntent("android.intent.action.MAIN", null, null))
        assertNull(LaunchCommands.fromIntent("android.intent.action.VIEW", "https://example.com", null))
    }

    @Test
    fun crashCommandsUseTheCefStrings() {
        assertEquals("crash/Browser crashed.", LaunchCommands.crash(didCrash = true))
        assertEquals("crash/Browser was killed.", LaunchCommands.crash(didCrash = false))
    }

    @Test
    fun aColdStartCommandIsReturnedOnceByGetLaunchCommand() {
        val inbox = LaunchCommandInbox()
        val emitted = ArrayList<String>()
        inbox.deliver("world/wrld_1", pageConnected = false) { emitted += it }
        assertEquals(emptyList<String>(), emitted)
        assertEquals("world/wrld_1", inbox.take())
        assertEquals("", inbox.take())
    }

    @Test
    fun aRunningPageGetsTheEventAndNothingStaysPending() {
        val inbox = LaunchCommandInbox()
        val emitted = ArrayList<String>()
        inbox.deliver("user/usr_1", pageConnected = true) { emitted += it }
        assertEquals(listOf("user/usr_1"), emitted)
        assertEquals("", inbox.take())
    }

    @Test
    fun theLastPendingCommandWins() {
        val inbox = LaunchCommandInbox()
        inbox.setPending(LaunchCommands.crash(didCrash = true))
        inbox.deliver("group/grp_1", pageConnected = false) {}
        assertEquals("group/grp_1", inbox.take())
    }
}
