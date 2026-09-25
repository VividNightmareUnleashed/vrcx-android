package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.bridge.errorText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Return shapes of every AppApiElectron method, through the bridge entry point. */
class AppApiContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var platform: FakeAppApiPlatform
    private lateinit var api: AppApi

    @Before
    fun setUp() {
        platform = FakeAppApiPlatform(tmp.newFolder("root"))
        api = AppApi(platform)
    }

    private fun arg(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun call(method: String, vararg args: Any?): JsonElement = runBlocking { api.call(method, JsonArray(args.map(::arg))) }

    private fun rejection(method: String, vararg args: Any?): String = try {
        call(method, *args)
        fail("$method should reject")
        ""
    } catch (t: Throwable) {
        errorText(t)
    }

    private fun assertBool(expected: Boolean, value: JsonElement) {
        val p = value as JsonPrimitive
        assertFalse("strict boolean, not a string", p.isString)
        assertEquals(expected, p.boolean)
    }

    @Test
    fun pcOnlyMethodsReturnTheSafeValues() {
        for (m in listOf("GetVRChatRegistryKey", "GetVRChatRegistryKeyString", "GetVRChatRegistry", "GetVRChatModerations")) {
            assertEquals(m, JsonNull, call(m, "LOGGING_ENABLED"))
        }
        assertBool(true, call("HasVRChatRegistryFolder"))
        assertBool(false, call("SetVRChatRegistryKey", "VRC_GROUP_ORDER_usr_x", "[]", 3))
        assertBool(false, call("SetVRChatUserModeration", "usr_a", "usr_b", 4))
        assertEquals(0, call("GetVRChatUserModeration", "usr_a", "usr_b").jsonPrimitive.int)
        assertEquals(0, call("QuitGame").jsonPrimitive.int)
        assertEquals(0, call("CheckUpdateProgress").jsonPrimitive.int)
        assertEquals(0, call("GetZoom").jsonPrimitive.int)
        assertBool(false, call("CheckForUpdateExe"))
        assertBool(false, call("TryOpenInstanceInVrc", "vrchat://launch?id=wrld_x"))
        for (m in listOf("OpenVrcxAppDataFolder", "OpenVrcAppDataFolder", "OpenVrcScreenshotsFolder", "OpenCrashVrcCrashDumps")) {
            assertBool(false, call(m))
        }
        for (m in listOf(
            "ReadConfigFileSafe", "ReadConfigFile", "ReadVrcRegJsonFile", "OpenFileSelectorDialog", "OpenFolderSelectorDialog",
            "GetVRChatAppDataLocation", "GetVRChatCacheLocation", "GetVRChatScreenshotsLocation",
        )) {
            assertEquals(m, "", call(m).jsonPrimitive.content)
        }
        assertEquals("", call("AddScreenshotMetadata", "C:\\x\\VRChat_1.png", "{}", "wrld_x", false).jsonPrimitive.content)
        for (m in listOf(
            "SetVR", "ExecuteVrOverlayFunction", "XSNotification", "OVRTNotification", "SendIpc", "IPCAnnounceStart", "SetUserAgent",
            "ShowDevTools", "SetStartup", "SetAppLauncherSettings", "OpenShortcutFolder", "SetZoom", "CancelUpdate", "WriteConfigFile",
            "DeleteVRChatRegistryFolder", "DoFunny", "Init",
        )) {
            assertEquals(m, JsonNull, call(m))
        }
    }

    @Test
    fun registryJsonAndUpdaterReject() {
        assertEquals("PlatformNotSupportedException: VRChat registry is not available on Android", rejection("GetVRChatRegistryJson"))
        assertEquals("PlatformNotSupportedException: VRChat registry is not available on Android", rejection("SetVRChatRegistry", "{}"))
        assertEquals("PlatformNotSupportedException: Updater not supported on Android", rejection("DownloadUpdate", "https://x", "", 1))
    }

    @Test
    fun unknownMethodRejectsLikeTheElectronProxy() {
        assertEquals("MissingMethodException: Method Nope does not exist on class AppApiElectron", rejection("Nope"))
    }

    @Test
    fun versionIsExactlyTheUpstreamString() {
        assertEquals("VRCX 2026.09.16", call("GetVersion").jsonPrimitive.content)
        platform.vrcxVersion = "2026.09.16-abcdef1"
        assertEquals("VRCX Nightly 2026.09.16-abcdef1", call("GetVersion").jsonPrimitive.content)
    }

    @Test
    fun colourBulkIsAnArrayOfPairs() {
        val result = call("GetColourBulk", JsonArray(listOf(JsonPrimitive("usr_c1644b5b-3ca4-45b4-97c6-a2a0de70d469"), JsonPrimitive("a"))))
        val pairs = result.jsonArray
        assertEquals(2, pairs.size)
        assertEquals("usr_c1644b5b-3ca4-45b4-97c6-a2a0de70d469", pairs[0].jsonArray[0].jsonPrimitive.content)
        assertEquals(22041, pairs[0].jsonArray[1].jsonPrimitive.int)
        assertEquals(47552, pairs[1].jsonArray[1].jsonPrimitive.int)
        assertEquals(22041, call("GetColourFromUserID", "usr_c1644b5b-3ca4-45b4-97c6-a2a0de70d469").jsonPrimitive.int)
        assertTrue(rejection("GetColourFromUserID", null).startsWith("ArgumentNullException"))
    }

    @Test
    fun hashingMethodsTakeBase64() {
        assertEquals("XUFAKrxLKna5cZ2REBfFkg==", call("MD5File", "aGVsbG8=").jsonPrimitive.content)
        assertEquals("5", call("FileLength", "aGVsbG8=").jsonPrimitive.content)
        assertEquals("cnMBNwAACAAAAAAgB/gCrzJNzwJ91KMKkyxEHzZaJehrFz3vpLjliUglNHG4G3LP", call("SignFile", "aGVsbG8=").jsonPrimitive.content)
        assertTrue(rejection("MD5File", "***").startsWith("FormatException"))
    }

    @Test
    fun gameStateIsBooleanAndCheckGameRunningEmits() {
        assertBool(false, call("IsGameRunning"))
        platform.gameState.isGameRunning = true
        platform.gameState.isSteamVRRunning = true
        platform.gameState.vrcClosedGracefully = false
        assertBool(true, call("IsGameRunning"))
        assertBool(true, call("IsSteamVRRunning"))
        assertBool(false, call("VrcClosedGracefully"))
        assertEquals(JsonNull, call("CheckGameRunning"))
        val (event, data) = platform.events.single()
        assertEquals("game-state", event)
        data as JsonObject
        assertEquals(true, data["isGameRunning"]!!.jsonPrimitive.boolean)
        assertEquals(true, data["isSteamVRRunning"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun startGameFiresTheLaunchIntentOnlyWhenHandled() {
        val args = "vrchat://launch?ref=vrcx.app&id=wrld_x:1~private(usr_y) --no-vr"
        assertBool(false, call("StartGame", args))
        assertTrue(platform.viewedUris.isEmpty())
        assertEquals(true, platform.lastViewRequiredResolvable)
        platform.handledUriPrefixes += "vrchat://launch"
        assertBool(true, call("StartGame", args))
        assertEquals("vrchat://launch?ref=vrcx.app&id=wrld_x:1~private(usr_y)", platform.viewedUris.single().first)
        assertBool(true, call("StartGameFromPath", "C:\\VRChat", args))
        assertBool(false, call("StartGame", "--profile=1"))
        assertBool(false, call("StartGame", null))
    }

    @Test
    fun launchCommandIsReturnedOnce() {
        platform.launchCommand = "world/wrld_x"
        assertEquals("world/wrld_x", call("GetLaunchCommand").jsonPrimitive.content)
        assertEquals("", call("GetLaunchCommand").jsonPrimitive.content)
    }

    @Test
    fun shellMethodsGoThroughTheHost() {
        call("ChangeTheme", 2)
        assertEquals(2, platform.theme)
        call("SetTrayIconNotification", true)
        assertEquals(true, platform.trayNotify)
        call("FocusWindow")
        assertEquals(0, platform.attentionRequests)
        platform.isInForeground = false
        call("FlashWindow")
        call("FocusWindow")
        assertEquals(2, platform.attentionRequests)
        call("RestartApplication", false)
        assertEquals(1, platform.restarts)
        platform.clipboard = "usr_x"
        assertEquals("usr_x", call("GetClipboard").jsonPrimitive.content)
        assertEquals("de-DE", call("CurrentCulture").jsonPrimitive.content)
        platform.formatTag = ""
        assertEquals("en-US", call("CurrentCulture").jsonPrimitive.content)
        assertEquals("de-DE", call("CurrentLanguage").jsonPrimitive.content)
    }

    @Test
    fun desktopNotificationUsesALocalIconOnly() {
        val icon = File(platform.cacheDir, "ImageCache/file_x/1.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        call("DesktopNotification", "Bold", "text", icon.absolutePath)
        call("DesktopNotification", "Bold")
        call("DesktopNotification", "Bold", "t", "C:\\Users\\x\\icon.png")
        assertEquals(Triple("Bold", "text", icon.absolutePath), platform.notifications[0])
        assertEquals(Triple("Bold", "", null), platform.notifications[1])
        assertEquals(null, platform.notifications[2].third)
    }

    @Test
    fun openLinkOnlyOpensAbsoluteHttpUrls() {
        call("OpenLink", "https://vrchat.com/home")
        call("OpenLink", "  HTTP://Example.com/a b ")
        call("OpenLink", "javascript:alert(1)")
        call("OpenLink", "/relative")
        call("OpenLink", "")
        call("OpenLink", null)
        assertEquals(listOf("https://vrchat.com/home", "http://example.com/a%20b"), platform.openedUrls)
    }

    @Test
    fun discordProfileValidatesAndFallsBackToTheWeb() {
        assertEquals("Exception: Invalid user ID", rejection("OpenDiscordProfile", "not-a-number"))
        assertEquals("Exception: Invalid user ID", rejection("OpenDiscordProfile", null))
        call("OpenDiscordProfile", "123456789012345678")
        assertEquals(listOf("https://discord.com/users/123456789012345678"), platform.openedUrls)
        assertEquals(false, platform.lastViewRequiredResolvable)
        platform.handledUriPrefixes += "discord://"
        call("OpenDiscordProfile", "42")
        assertEquals("discord://-/users/42", platform.viewedUris.single().first)
    }

    @Test
    fun calendarFilesAreValidatedWrittenAndOpened() {
        assertEquals("Exception: Invalid calendar file", rejection("OpenCalendarFile", "hello"))
        assertEquals("Exception: Invalid calendar file", rejection("OpenCalendarFile", null))
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Meetup\r\nDTSTART:20250101T120000Z\r\nDTEND:20250101T130000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals(JsonNull, call("OpenCalendarFile", ics))
        val (file, event) = platform.calendarOpened!!
        assertEquals(File(platform.cacheDir, "event.ics"), file)
        assertEquals(ics, file.readText())
        assertEquals("Meetup", event!!.title)
        assertEquals(1735732800000L, event.beginMillis)
    }

    @Test
    fun customCssAndScriptComeFromExternalFiles() {
        assertEquals("", call("CustomCss").jsonPrimitive.content)
        File(platform.externalFilesDir, "custom.css").writeText("\uFEFFbody{color:red}")
        File(platform.externalFilesDir, "custom.js").writeText("console.log(1)")
        assertEquals("body{color:red}", call("CustomCss").jsonPrimitive.content)
        assertEquals("console.log(1)", call("CustomScript").jsonPrimitive.content)
        platform.externalFilesDir = null
        assertEquals("", call("CustomScript").jsonPrimitive.content)
    }

    @Test
    fun resizeImageToFitLimitsRoundTripsBase64ThroughTheCodec() {
        var seen: Boolean? = null
        platform.images = object : ImageCodec by platform.images {
            override fun resizeToFitLimits(bytes: ByteArray, matchingDimensions: Boolean, maxWidth: Int, maxHeight: Int, maxSize: Long): ByteArray {
                seen = matchingDimensions
                assertEquals(2000, maxWidth)
                assertEquals(10_000_000L, maxSize)
                return bytes.reversedArray()
            }
        }
        assertEquals("b2xsZWg=", call("ResizeImageToFitLimits", "aGVsbG8=").jsonPrimitive.content)
        assertEquals(false, seen)
    }

    @Test
    fun localFilesCanBeReadCopiedAndShown() {
        val file = File(platform.servedDir, "shot.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")) }
        assertEquals(java.util.Base64.getEncoder().encodeToString(file.readBytes()), call("GetFileBase64", "{dir}/shot.png").jsonPrimitive.content)
        call("CopyImageToClipboard", "{dir}/shot.png")
        call("CopyImageToClipboard", "{dir}/missing.png")
        assertEquals(listOf(file.absolutePath), platform.clipboardImages)
        call("OpenFolderAndSelectItem", "{dir}/shot.png", false)
        call("OpenFolderAndSelectItem", "C:\\VRChat\\Cache-WindowsPlayer\\x", true)
        assertEquals(listOf(file.absolutePath), platform.viewedDocs)
    }

    @Test
    fun photosAndUgcFoldersComeFromTheLibraries() {
        assertEquals("", call("GetVRChatPhotosLocation").jsonPrimitive.content)
        assertBool(false, call("OpenVrcPhotosFolder"))
        val photos = tmp.newFolder("photos")
        platform.photos = FakeAppApiPlatform.FakePhotosLibrary(photos)
        assertEquals(photos.absolutePath, call("GetVRChatPhotosLocation").jsonPrimitive.content)
        assertBool(true, call("OpenVrcPhotosFolder"))
        assertBool(false, call("OpenUGCPhotosFolder", "tree:none"))
        assertBool(false, call("OpenUGCPhotosFolder"))
        File(platform.root, "ugc/default").mkdirs()
        assertBool(true, call("OpenUGCPhotosFolder"))
    }

    @Test
    fun lastScreenshotSkipsUgcFoldersAndIsNewestFirst() {
        val photos = tmp.newFolder("photos")
        val month = File(photos, "2025-09").apply { mkdirs() }
        val old = File(month, "VRChat_old.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")); setLastModified(1_000_000_000_000) }
        val new = File(month, "VRChat_new.png").apply { writeBytes(Vectors.fixture("vrcx_json.png")); setLastModified(1_700_000_000_000) }
        File(photos, "Prints/2025-09").apply { mkdirs() }.let {
            File(it, "print.png").apply { writeBytes(Vectors.fixture("nometa.png")); setLastModified(1_800_000_000_000) }
        }
        platform.photos = FakeAppApiPlatform.FakePhotosLibrary(photos)
        val last = call("GetLastScreenshot").jsonPrimitive.content
        // outside the served roots: shown through a mirror copy that maps back to the original
        assertTrue(last, last.startsWith("{cache}/screenshot-mirror/"))
        assertEquals(new.absolutePath, api.docs.resolve(last)!!.key)
        assertNotNull(old)
        // displaying it materializes the mirror copy
        val extra = call("GetExtraScreenshotData", last, true).jsonPrimitive.content
        assertTrue(extra, extra.contains("\"fileName\": \"VRChat_new\""))
        // siblings sort like Windows Directory.GetFiles: VRChat_new < VRChat_old
        assertFalse(extra, extra.contains("previousFilePath"))
        assertTrue(extra, extra.contains("\"nextFilePath\": \"{cache}/screenshot-mirror/"))
        assertTrue(File(platform.cacheDir, "screenshot-mirror/" + last.substringAfterLast('/')).isFile)
    }
}
