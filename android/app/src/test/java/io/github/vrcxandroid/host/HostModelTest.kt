package io.github.vrcxandroid.host

import io.github.vrcxandroid.host.TtsVoiceRetry.Action
import io.github.vrcxandroid.host.TtsVoiceRetry.Engine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostModelTest {
    @Test
    fun webViewMajorVersion() {
        assertEquals(120, WebViewVersion.major("120.0.6099.230"))
        assertEquals(133, WebViewVersion.major(" 133.0.6943.49 "))
        assertNull(WebViewVersion.major("unknown"))
        assertNull(WebViewVersion.major(""))
        assertNull(WebViewVersion.major(null))
    }

    @Test
    fun webViewGateNeedsVersionAndBothFeatures() {
        assertTrue(WebViewVersion.isSupported("120.0.1", hasDocumentStartScript = true, hasWebMessageListener = true))
        assertFalse(WebViewVersion.isSupported("119.9.9", hasDocumentStartScript = true, hasWebMessageListener = true))
        assertFalse(WebViewVersion.isSupported("130.0", hasDocumentStartScript = false, hasWebMessageListener = true))
        assertFalse(WebViewVersion.isSupported("130.0", hasDocumentStartScript = true, hasWebMessageListener = false))
        assertFalse(WebViewVersion.isSupported(null, hasDocumentStartScript = true, hasWebMessageListener = true))
    }

    @Test
    fun electronArchNames() {
        assertEquals("arm64", BridgeConfig.archFor("arm64-v8a"))
        assertEquals("x64", BridgeConfig.archFor("x86_64"))
        assertEquals("arm", BridgeConfig.archFor("armeabi-v7a"))
        assertEquals("ia32", BridgeConfig.archFor("x86"))
        assertEquals("riscv64", BridgeConfig.archFor("riscv64"))
        assertEquals("arm64", BridgeConfig.archFor(null))
    }

    @Test
    fun bridgeConfigPrelude() {
        val json = BridgeConfig.json("2026.09.16", "0.1.0", "x86_64", 35, debug = true)
        assertEquals("x64", json["arch"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(35), json["sdkInt"])
        val prelude = BridgeConfig.prelude(json)
        assertTrue(prelude.startsWith("window.__vrcxBridgeConfig={"))
        assertTrue(prelude.endsWith(";\n"))
        assertTrue(prelude.contains("\"vrcxVersion\":\"2026.09.16\""))
        assertTrue(prelude.contains("\"origin\":\"https://appassets.androidplatform.net\""))
    }

    @Test
    fun themeColoursMatchShellDesign() {
        assertEquals(ThemeColors(0xFFFAFAFA.toInt(), light = true), ThemeColors.forMode(0))
        assertEquals(ThemeColors(0xFF171717.toInt(), light = false), ThemeColors.forMode(1))
        assertEquals(ThemeColors(0xFF0A0A0A.toInt(), light = false), ThemeColors.forMode(2))
        assertNull(ThemeColors.forMode(3))
        assertNull(ThemeColors.forMode(-1))
    }

    @Test
    fun startupThemeFollowsTheSystemWhenThePageDid() {
        // Nothing stored: follow the system.
        assertEquals(ThemeColors.DARK, ThemeColors.startupMode(null, null, systemNightNow = true))
        assertEquals(ThemeColors.LIGHT, ThemeColors.startupMode(null, null, systemNightNow = false))
        // Stored light while the system was light: probably "system" mode, so follow the system now.
        assertEquals(ThemeColors.DARK, ThemeColors.startupMode(ThemeColors.LIGHT, false, systemNightNow = true))
        // Stored dark while the system was light: an explicit choice wins.
        assertEquals(ThemeColors.DARK, ThemeColors.startupMode(ThemeColors.DARK, false, systemNightNow = false))
        assertEquals(ThemeColors.LIGHT, ThemeColors.startupMode(ThemeColors.LIGHT, true, systemNightNow = true))
        // Midnight is always explicit.
        assertEquals(ThemeColors.MIDNIGHT, ThemeColors.startupMode(ThemeColors.MIDNIGHT, true, systemNightNow = false))
        // Garbage in storage: follow the system.
        assertEquals(ThemeColors.LIGHT, ThemeColors.startupMode(7, true, systemNightNow = false))
    }

    @Test
    fun insetsAreCssPixels() {
        val p = InsetsPayload.fromPixels(top = 63, right = 0, bottom = 126, left = 5, imeBottom = 882, density = 2.625f)
        assertEquals(24f, p.top)
        assertEquals(0f, p.right)
        assertEquals(48f, p.bottom)
        assertEquals(1.9f, p.left)
        assertEquals(336f, p.imeBottom)
        val json = p.toJson()
        assertEquals(48f, json["bottom"]!!.jsonPrimitive.content.toFloat())
        assertEquals(336f, json["imeBottom"]!!.jsonPrimitive.content.toFloat())
        assertEquals(336f, json["ime"]!!.jsonPrimitive.content.toFloat())
        assertEquals(0f, InsetsPayload.fromPixels(-3, 0, 0, 0, 0, 0f).top)
    }

    @Test
    fun networkTrackerIgnoresTheBaselineAndRepeats() {
        val t = NetworkChangeTracker<String>()
        assertFalse(t.onAvailable("wifi")) // baseline right after registration
        assertFalse(t.onAvailable("wifi")) // same network again
        assertTrue(t.onAvailable("cell")) // switched networks
        assertFalse(t.onLost("wifi")) // an old network going away changes nothing
        assertTrue(t.onLost("cell")) // lost the current one
        assertTrue(t.onAvailable("cell")) // back after a loss
    }

    @Test
    fun networkTrackerWithNoNetworkAtStart() {
        val t = NetworkChangeTracker<String>()
        assertFalse(t.onAvailable("wifi"))
        assertTrue(t.onLost("wifi"))
        assertTrue(t.onAvailable("wifi"))
    }

    private fun v(name: String, lang: String, local: Boolean = true) = VoiceInfo(name, lang, isDefault = false, localService = local)

    @Test
    fun voiceOrderIsStableAndPromotesTheFirstEnglishVoicePerLanguage() {
        val voices = listOf(
            v("ja-jp-x-htm-local", "ja-JP"),
            v("en-us-x-tpf-network", "en-US", local = false),
            v("en-gb-x-gba-local", "en-GB"),
            v("en-us-x-iob-local", "en-US"),
            v("de-de-x-deb-local", "de-DE"),
            v("en-us-x-aaa-local", "en-US"),
        )
        val ordered = TtsVoiceOrder.order(voices.shuffled()).map { it.name }
        assertEquals(
            listOf(
                "en-gb-x-gba-local", // first en-GB
                "en-us-x-aaa-local", // first en-US (local before network, then by name)
                "de-de-x-deb-local",
                "en-us-x-iob-local",
                "en-us-x-tpf-network",
                "ja-jp-x-htm-local",
            ),
            ordered,
        )
        assertEquals(ordered, TtsVoiceOrder.order(voices.reversed()).map { it.name })
    }

    @Test
    fun uiIndexAddressesTheSameVoiceInBothLists() {
        val ordered = TtsVoiceOrder.order(
            listOf(v("fr-a", "fr-FR"), v("en-b", "en-US"), v("en-c", "en-AU"), v("en-d", "en-US"), v("es-e", "es-ES")),
        )
        // stores/settings/notifications.js updateTTSVoices on the LINUX path.
        val seen = HashSet<String>()
        val ui = ordered.filter { seen.add(it.lang) }.filter { it.lang.startsWith("en") }
        ui.forEachIndexed { i, voice -> assertEquals(voice, ordered[i]) }
        // Promoting again is a no-op (the shim applies the same step).
        assertEquals(ordered, TtsVoiceOrder.promoteFirstEnglishPerLang(ordered))
    }

    @Test
    fun voiceJsonHasTheWebSpeechShape() {
        val json = VoiceInfo("en-us-x-iob-local", "en-US", isDefault = true, localService = true).toJson()
        assertEquals(setOf("name", "lang", "voiceURI", "default", "localService"), json.keys)
        assertEquals("en-us-x-iob-local", json["voiceURI"]!!.jsonPrimitive.content)
    }

    @Test
    fun fileNamesAreSanitized() {
        assertEquals("VRChat_2026-09-25_12-00-00.png", FileNames.sanitize("VRChat_2026-09-25_12-00-00.png", "x"))
        assertEquals("a_b_c_.png", FileNames.sanitize("a/b\\c:.png", "x"))
        assertEquals("secret", FileNames.sanitize("..secret", "x"))
        assertEquals("image.png", FileNames.sanitize("  ", "image.png"))
        assertEquals("image.png", FileNames.sanitize(null, "image.png"))
        assertEquals(120, FileNames.sanitize("y".repeat(300), "x").length)
    }

    @Test
    fun sanitizedNamesSurviveUseAsAUrlPath() {
        // The copy's absolute path is used as <img src>: '#' would cut the path, '%41' would decode to 'A'.
        assertEquals("shot_2.png", FileNames.sanitize("shot#2.png", "x"))
        assertEquals("a_41.png", FileNames.sanitize("a%41.png", "x"))
        assertEquals("q_x=1.png", FileNames.sanitize("q?x=1.png", "x"))
        for (name in listOf("a#b%c?d.png", "#%?", "100% #1 ?.png")) {
            val cleaned = FileNames.sanitize(name, "image.png")
            assertFalse(cleaned, cleaned.any { it in "#%?" })
        }
        assertEquals("my shot (1).png", FileNames.sanitize("my shot (1).png", "x"))
    }

    // ---- TTS voice re-listing ----

    @Test
    fun voicesAreListedAgainWhileEmpty() {
        assertEquals(Action.NONE, TtsVoiceRetry.onPageRequest(haveVoices = true, engine = Engine.READY, msSinceInitFailure = null))
        assertEquals(Action.NONE, TtsVoiceRetry.onPageRequest(haveVoices = true, engine = Engine.NONE, msSinceInitFailure = null))
        // The engine is up but reported nothing: ask it again instead of never returning voices.
        assertEquals(Action.RELIST, TtsVoiceRetry.onPageRequest(haveVoices = false, engine = Engine.READY, msSinceInitFailure = null))
        assertEquals(Action.NONE, TtsVoiceRetry.onPageRequest(haveVoices = false, engine = Engine.INITIALIZING, msSinceInitFailure = null))
        assertEquals(Action.BIND, TtsVoiceRetry.onPageRequest(haveVoices = false, engine = Engine.NONE, msSinceInitFailure = null))
        // A failed init is not retried on every request, but it is retried.
        assertEquals(Action.NONE, TtsVoiceRetry.onPageRequest(haveVoices = false, engine = Engine.NONE, msSinceInitFailure = 5_000))
        assertEquals(Action.BIND, TtsVoiceRetry.onPageRequest(haveVoices = false, engine = Engine.NONE, msSinceInitFailure = TtsVoiceRetry.INIT_RETRY_MS))
    }

    @Test
    fun delayedRelistsEndWellBeforeTheIdleShutdown() {
        assertTrue(TtsVoiceRetry.RELIST_DELAYS_MS.isNotEmpty())
        assertEquals(TtsVoiceRetry.RELIST_DELAYS_MS.sorted(), TtsVoiceRetry.RELIST_DELAYS_MS)
        assertTrue(TtsVoiceRetry.RELIST_DELAYS_MS.last() < 120_000L)
    }

    // ---- notification permission ----

    private val fresh = NotificationPermissionPolicy.Record()

    private fun afterDenial(record: NotificationPermissionPolicy.Record, before: Boolean, after: Boolean) =
        NotificationPermissionPolicy.afterRequest(record, granted = false, rationaleBefore = before, rationaleAfter = after)

    private fun state(record: NotificationPermissionPolicy.Record, granted: Boolean = false, enabled: Boolean = true) =
        NotificationPermissionPolicy.state(granted, enabled, record)

    @Test
    fun notificationPermissionStaysDefaultWhileThePromptCanComeBack() {
        assertEquals("default", state(fresh))
        // First denial: Android shows the rationale flag and will prompt again.
        val once = afterDenial(fresh, before = false, after = true)
        assertEquals("default", state(once))
        // Dismissed prompt: no rationale either side, the prompt still comes back.
        val dismissed = afterDenial(fresh, before = false, after = false)
        assertEquals("default", state(dismissed))
        // Dismissed after one denial: the rationale flag stays.
        assertEquals("default", state(afterDenial(once, before = true, after = true)))
    }

    @Test
    fun notificationPermissionIsDeniedOnceTheSystemStopsPrompting() {
        val once = afterDenial(fresh, before = false, after = true)
        assertEquals("denied", state(afterDenial(once, before = true, after = false)))
        // Two answers in a row without any rationale: the prompt is not being shown.
        val silentTwice = afterDenial(afterDenial(fresh, before = false, after = false), before = false, after = false)
        assertEquals("denied", state(silentTwice))
    }

    @Test
    fun notificationPermissionGrantResetsTheRecord() {
        val blocked = NotificationPermissionPolicy.Record(blocked = true)
        assertEquals("granted", state(blocked, granted = true))
        assertEquals("denied", state(fresh, granted = true, enabled = false))
        assertEquals(fresh, NotificationPermissionPolicy.afterRequest(blocked, granted = true, rationaleBefore = false, rationaleAfter = false))
    }

    // ---- bridge gate ----

    @Test
    fun bridgeGatePassesHoldsInOrderAndBlocks() {
        val gate = BridgeGate<Int>()
        val out = ArrayList<Int>()
        gate.submit(1) { out += it }
        assertTrue(gate.hold())
        gate.submit(2) { out += it }
        gate.submit(3) { out += it }
        assertEquals(listOf(1), out)
        assertEquals(2, gate.heldCount)
        gate.release { out += it }
        gate.submit(4) { out += it }
        assertEquals(listOf(1, 2, 3, 4), out)

        assertTrue(gate.hold())
        gate.submit(5) { out += it }
        gate.block()
        gate.release { out += it }
        gate.submit(6) { out += it }
        assertFalse(gate.hold())
        assertEquals(listOf(1, 2, 3, 4), out)
        assertEquals(0, gate.heldCount)
    }
}
