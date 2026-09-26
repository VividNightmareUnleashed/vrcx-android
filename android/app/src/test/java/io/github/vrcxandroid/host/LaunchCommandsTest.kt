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
    fun externalNavigationCommandsAreDeliveredAsTheyAre() {
        for (c in listOf("world/wrld_1:123~private(usr_2)", "avatar/avtr_1", "user/usr_1", "group/grp_1", "search/https://vrchat.com/home/user/usr_1")) {
            assertEquals(c, ExternalRoute.Navigate(c), LaunchCommands.routeExternal(c))
        }
        // shared text is a search command and stays a navigation
        val shared = LaunchCommands.fromIntent("android.intent.action.SEND", null, "vrcx://switchavatar/avtr_1")!!
        assertEquals(ExternalRoute.Navigate(shared), LaunchCommands.routeExternal(shared))
    }

    @Test
    fun externalStateChangingCommandsNeedConfirmation() {
        for (c in listOf(
            "switchavatar/avtr_1",
            "addavatardb/https://evil.example/api",
            "local-favorite-world/Group/wrld_1",
            "local-favorite-avatar/Group/avtr_1",
            "import/avatar/avtr_1,avtr_2",
        )) {
            assertEquals(c, ExternalRoute.Confirm(c), LaunchCommands.routeExternal(c))
        }
        val fromBrowser = LaunchCommands.fromIntent("android.intent.action.VIEW", "vrcx://switchavatar/avtr_1", null)!!
        assertEquals(ExternalRoute.Confirm("switchavatar/avtr_1"), LaunchCommands.routeExternal(fromBrowser))
    }

    @Test
    fun crashAndUnknownExternalCommandsAreDropped() {
        assertEquals(ExternalRoute.Drop, LaunchCommands.routeExternal(LaunchCommands.crash(didCrash = true)))
        assertEquals(ExternalRoute.Drop, LaunchCommands.routeExternal(LaunchCommands.fromUri("vrcx://crash/Browser crashed.")!!))
        assertEquals(ExternalRoute.Drop, LaunchCommands.routeExternal("somethingelse/x"))
        assertEquals(ExternalRoute.Drop, LaunchCommands.routeExternal("SwitchAvatar/avtr_1"))
        assertEquals(ExternalRoute.Drop, LaunchCommands.routeExternal("worldx/wrld_1"))
        assertEquals(ExternalRoute.Drop, LaunchCommands.routeExternal(""))
    }

    @Test
    fun loggedNamesCarryNoPayload() {
        assertEquals("crash", LaunchCommands.loggableName("crash/Browser crashed."))
        assertEquals("a??b", LaunchCommands.loggableName("a:\nb/secret"))
        assertEquals(32, LaunchCommands.loggableName("x".repeat(100)).length)
    }

    @Test
    fun externalCommandsUseTheirOwnSlot() {
        val launch = LaunchCommandInbox()
        val external = LaunchCommandInbox()
        launch.setPending(LaunchCommands.crash(didCrash = false))
        external.deliver("switchavatar/avtr_1", pageConnected = false) {}
        assertEquals("crash/Browser was killed.", launch.take())
        assertEquals("switchavatar/avtr_1", external.take())
        assertEquals("", external.take())
    }

    @Test
    fun theLastPendingCommandWins() {
        val inbox = LaunchCommandInbox()
        inbox.setPending(LaunchCommands.crash(didCrash = true))
        inbox.deliver("group/grp_1", pageConnected = false) {}
        assertEquals("group/grp_1", inbox.take())
    }
}
