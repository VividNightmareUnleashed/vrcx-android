package io.github.vrcxandroid.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

// Small pure helpers of the host package. No android.* imports: unit-tested on the JVM.

/** WebView startup gate (docs/ARCHITECTURE.md §6.3). */
object WebViewVersion {
    const val MIN_MAJOR = 120

    /** Major version from a WebView package versionName such as "120.0.6099.230"; null when unparsable. */
    fun major(versionName: String?): Int? {
        val digits = versionName?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        return digits.toIntOrNull()
    }

    fun isSupported(versionName: String?, hasDocumentStartScript: Boolean, hasWebMessageListener: Boolean): Boolean {
        val major = major(versionName) ?: return false
        return major >= MIN_MAJOR && hasDocumentStartScript && hasWebMessageListener
    }
}

/** Values the shim needs before the page runs, injected as `window.__vrcxBridgeConfig` (see [prelude]). */
object BridgeConfig {
    /** Electron's `process.arch` spelling for an Android ABI (`electron.getArch()`). */
    fun archFor(abi: String?): String = when (abi?.lowercase(Locale.ROOT)) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "x64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86" -> "ia32"
        null, "" -> "arm64"
        else -> abi
    }

    fun json(vrcxVersion: String, appVersion: String, abi: String?, sdkInt: Int, debug: Boolean): JsonObject = buildJsonObject {
        put("vrcxVersion", vrcxVersion)
        put("appVersion", appVersion)
        put("arch", archFor(abi))
        put("sdkInt", sdkInt)
        put("debug", debug)
        put("origin", HostUrls.ORIGIN)
    }

    /** The document-start script: a one-line JSON prelude followed by the shim source. */
    fun prelude(config: JsonObject): String = "window.__vrcxBridgeConfig=$config;\n"
}

/** Native colours for `AppApi.ChangeTheme(0|1|2)`. */
data class ThemeColors(val frame: Int, val light: Boolean) {
    companion object {
        const val LIGHT = 0
        const val DARK = 1
        const val MIDNIGHT = 2

        fun forMode(mode: Int): ThemeColors? = when (mode) {
            LIGHT -> ThemeColors(0xFFFAFAFA.toInt(), light = true)
            DARK -> ThemeColors(0xFF171717.toInt(), light = false)
            MIDNIGHT -> ThemeColors(0xFF0A0A0A.toInt(), light = false)
            else -> null
        }

        /**
         * Which mode to paint at cold start, before the page calls ChangeTheme again. [storedMode] was saved together
         * with [storedSystemNight] (the system night flag at that time). When the stored mode matched the system then,
         * the page most likely follows the system theme, so the current system flag wins; otherwise the page chose a
         * theme explicitly and the stored mode wins. Midnight is always explicit.
         */
        fun startupMode(storedMode: Int?, storedSystemNight: Boolean?, systemNightNow: Boolean): Int {
            val followSystem = if (systemNightNow) DARK else LIGHT
            if (storedMode == null || forMode(storedMode) == null) return followSystem
            if (storedMode == MIDNIGHT) return MIDNIGHT
            val matchedSystem = storedSystemNight != null && (storedMode == DARK) == storedSystemNight
            return if (matchedSystem) followSystem else storedMode
        }
    }
}

/**
 * The `insets` event payload, in CSS px (ARCHITECTURE.md §4.4 item 7). The keyboard height is sent as `imeBottom` and,
 * for the phone shell's preview bridge (web/src/platform/android/dev/mockBridge.js), also as `ime`.
 */
data class InsetsPayload(val top: Float, val right: Float, val bottom: Float, val left: Float, val imeBottom: Float) {
    fun toJson(): JsonObject = buildJsonObject {
        put("top", top)
        put("right", right)
        put("bottom", bottom)
        put("left", left)
        put("imeBottom", imeBottom)
        put("ime", imeBottom)
    }

    companion object {
        /** Device pixels → CSS px (1 CSS px = 1 dp at the page's initial-scale=1), rounded to 0.1. */
        fun fromPixels(top: Int, right: Int, bottom: Int, left: Int, imeBottom: Int, density: Float): InsetsPayload {
            val d = if (density > 0f) density else 1f
            fun css(px: Int) = ((px.coerceAtLeast(0) / d) * 10f).roundToInt() / 10f
            return InsetsPayload(css(top), css(right), css(bottom), css(left), css(imeBottom))
        }
    }
}

/**
 * Turns default-network callbacks into `network-changed` events. The first network seen after registration is the
 * baseline (no event); switching to a different network, or getting one back after a loss, emits `available:true`;
 * losing the current network emits `available:false`.
 */
class NetworkChangeTracker<N : Any> {
    private var current: N? = null
    private var seenAny = false

    /** Returns true when an `available:true` event should be emitted. */
    @Synchronized
    fun onAvailable(network: N): Boolean {
        val emit = seenAny && network != current
        current = network
        seenAny = true
        return emit
    }

    /** Returns true when an `available:false` event should be emitted. */
    @Synchronized
    fun onLost(network: N): Boolean {
        seenAny = true
        if (network != current) return false
        current = null
        return true
    }
}

/** A text-to-speech voice as the `speechSynthesis` polyfill exposes it. */
data class VoiceInfo(val name: String, val lang: String, val isDefault: Boolean, val localService: Boolean) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("lang", lang)
        put("voiceURI", name)
        put("default", isDefault)
        put("localService", localService)
    }
}

object TtsVoiceOrder {
    /**
     * Stable order for `speechSynthesis.getVoices()`. The settings UI stores a voice *index*, so the order must not
     * change between runs: sorted by language, local voices first, then name. Then the first voice of every English
     * language moves to the front, in that order, because on the LINUX path the UI lists only those voices but `speak()`
     * indexes the full list. The same reordering in the shim is then a
     * no-op.
     */
    fun order(voices: Collection<VoiceInfo>): List<VoiceInfo> {
        val sorted = voices
            .distinctBy { it.name }
            .sortedWith(compareBy<VoiceInfo>({ it.lang.lowercase(Locale.ROOT) }, { !it.localService }, { it.name }))
        return promoteFirstEnglishPerLang(sorted)
    }

    fun promoteFirstEnglishPerLang(voices: List<VoiceInfo>): List<VoiceInfo> {
        val seenLang = HashSet<String>()
        val firsts = ArrayList<VoiceInfo>()
        for (v in voices) {
            if (seenLang.add(v.lang) && v.lang.startsWith("en")) firsts += v
        }
        val promoted = firsts.toHashSet()
        return firsts + voices.filter { it !in promoted }
    }
}

/**
 * When [AndroidTtsController] lists the voices again while it has none. Some engines report no voices right after
 * init; without another look the page would never get any (the notification speech returns early on an empty list).
 */
object TtsVoiceRetry {
    enum class Engine { NONE, INITIALIZING, READY }
    enum class Action { NONE, BIND, RELIST }

    /** Re-lists after an init that found no voices, counted from the init. */
    val RELIST_DELAYS_MS = listOf(2_000L, 10_000L, 30_000L)

    /** After a failed init, the page's next request binds the engine again only after this long. */
    const val INIT_RETRY_MS = 60_000L

    /** What `AndroidHost.TtsGetVoices` does besides returning the cached list. */
    fun onPageRequest(haveVoices: Boolean, engine: Engine, msSinceInitFailure: Long?): Action = when {
        haveVoices -> Action.NONE
        engine == Engine.READY -> Action.RELIST
        engine == Engine.INITIALIZING -> Action.NONE
        msSinceInitFailure != null && msSinceInitFailure < INIT_RETRY_MS -> Action.NONE
        else -> Action.BIND
    }
}

/**
 * POST_NOTIFICATIONS state for `AndroidHost.GetNotificationPermission` on Android 13+: 'granted', 'default' (the
 * prompt can still be shown) or 'denied' (only system settings can change it). Android shows the prompt again after
 * one denial or a dismissal and stops after the second denial. It only says so through
 * shouldShowRequestPermissionRationale, which is true after exactly one denial.
 */
object NotificationPermissionPolicy {
    /** What earlier requests told us. [silentDenials]: answered "no" with no rationale before or after, in a row. */
    data class Record(val blocked: Boolean = false, val silentDenials: Int = 0)

    fun state(granted: Boolean, enabled: Boolean, record: Record): String = when {
        granted -> if (enabled) "granted" else "denied"
        record.blocked -> "denied"
        else -> "default"
    }

    fun afterRequest(record: Record, granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): Record = when {
        granted -> Record()
        // Denied once: the prompt comes back.
        rationaleAfter -> Record()
        // The second denial: the system stops showing the prompt.
        rationaleBefore -> Record(blocked = true)
        // Neither: a dismissed first prompt (it comes back) or a prompt the system no longer shows. Twice in a row
        // counts as blocked, so the page stops offering a button that does nothing.
        else -> (record.silentDenials + 1).let { Record(blocked = it >= 2, silentDenials = it) }
    }
}

/**
 * Page → native message gate of [WebViewHolder]: open, holding (messages queue in arrival order until [release], used
 * during a database import) or blocked (messages are dropped, restart or quit in progress). Thread-safe.
 */
class BridgeGate<T> {
    private enum class State { OPEN, HOLDING, BLOCKED }

    private var state = State.OPEN
    private val held = ArrayList<T>()

    /** Dispatches [message] now, queues it, or drops it. [dispatch] runs under the gate's lock, so order is kept. */
    @Synchronized
    fun submit(message: T, dispatch: (T) -> Unit) {
        when (state) {
            State.OPEN -> dispatch(message)
            State.HOLDING -> held += message
            State.BLOCKED -> Unit
        }
    }

    /** Starts queueing. False when the gate is already blocked. */
    @Synchronized
    fun hold(): Boolean {
        if (state == State.BLOCKED) return false
        state = State.HOLDING
        return true
    }

    /** Dispatches the queued messages in order and opens the gate. No-op unless holding. */
    @Synchronized
    fun release(dispatch: (T) -> Unit) {
        if (state != State.HOLDING) return
        state = State.OPEN
        held.forEach(dispatch)
        held.clear()
    }

    /** Drops everything from now on, including the queued messages. */
    @Synchronized
    fun block() {
        state = State.BLOCKED
        held.clear()
    }

    val heldCount: Int @Synchronized get() = held.size
}
