package io.github.vrcxandroid.host

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Launch commands (docs/ARCHITECTURE.md §6.9). The page receives them without the
 * `vrcx://` prefix, trimmed, exactly like Electron's `second-instance` handler (`src-electron/main.js:163-174`).
 */
object LaunchCommands {
    const val SCHEME = "vrcx"

    /** Longest shared text accepted as a `search/<text>` command. */
    const val MAX_SHARED_TEXT = 2048

    /**
     * `vrcx://world/wrld_x` → `world/wrld_x`. Also accepts `vrcx:world/...`. Returns null for other schemes or an empty
     * command. Trailing slashes are dropped (some browsers append one).
     */
    fun fromUri(uri: String?): String? {
        val text = uri?.trim() ?: return null
        val lower = text.lowercase(Locale.ROOT)
        val rest = when {
            lower.startsWith("$SCHEME://") -> text.substring(SCHEME.length + 3)
            lower.startsWith("$SCHEME:") -> text.substring(SCHEME.length + 1)
            else -> return null
        }
        return normalize(rest)
    }

    /** ACTION_SEND text → `search/<text>` (handled by the frontend like a Direct Access paste). */
    fun fromSharedText(text: CharSequence?): String? {
        val trimmed = text?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return "search/" + trimmed.take(MAX_SHARED_TEXT)
    }

    /** Command set after a renderer crash, with the CEF strings (`Cef/CefCustomRequestHandler.cs:63-83`). */
    fun crash(didCrash: Boolean): String = if (didCrash) "crash/Browser crashed." else "crash/Browser was killed."

    /**
     * The command carried by an intent, or null. [action] is the intent action, [data] its data string and
     * [sharedText] `EXTRA_TEXT`.
     */
    fun fromIntent(action: String?, data: String?, sharedText: CharSequence?): String? = when (action) {
        ACTION_SEND -> fromSharedText(sharedText)
        else -> fromUri(data)
    }

    internal fun normalize(command: String): String? {
        var c = command.trim()
        while (c.endsWith("/")) c = c.dropLast(1)
        c = c.trim()
        return c.ifEmpty { null }
    }

    /** Commands that only open a dialog or a search: delivered like any launch command. */
    val NAVIGATION = setOf("world", "avatar", "user", "group", "search")

    /** Commands that change the account or the VRCX configuration: the page asks the user before running them. */
    val NEEDS_CONFIRMATION = setOf("switchavatar", "addavatardb", "local-favorite-world", "local-favorite-avatar", "import")

    /**
     * Route of a command that came from outside the app: an intent from another app or a browser, or a `vrcx:` link
     * inside page content (user bios and descriptions are not trusted either). Only the host itself sets `crash/...`
     * (after a renderer crash), so that and every unknown command is dropped. Names match case-sensitively, like the
     * frontend's `eventLaunchCommand` switch.
     */
    fun routeExternal(command: String): ExternalRoute {
        val name = commandName(command)
        return when (name) {
            in NAVIGATION -> ExternalRoute.Navigate(command)
            in NEEDS_CONFIRMATION -> ExternalRoute.Confirm(command)
            else -> ExternalRoute.Drop
        }
    }

    /** The command name (text before the first '/'), for routing and for logs. */
    fun commandName(command: String): String = command.substringBefore('/')

    /** A dropped command's name reduced to a short, printable form, so logs never carry the payload. */
    fun loggableName(command: String): String =
        commandName(command).take(32).map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '?' }.joinToString("")

    private const val ACTION_SEND = "android.intent.action.SEND"
}

/** Where an external launch command goes (docs/ARCHITECTURE.md §6.9). */
sealed interface ExternalRoute {
    /** `launch-command` event, or `AppApi.GetLaunchCommand` at cold start. */
    data class Navigate(val command: String) : ExternalRoute

    /** `external-launch-command` event, or `AndroidHost.TakeExternalLaunchCommand` at cold start. */
    data class Confirm(val command: String) : ExternalRoute

    data object Drop : ExternalRoute
}

/**
 * Where a launch command goes (docs/ARCHITECTURE.md §6.9): to the running page as a `launch-command` event, or, while no
 * page is connected (cold start, renderer recovery), into a slot that `AppApi.GetLaunchCommand` empties once. Thread-safe.
 */
class LaunchCommandInbox {
    private val pending = AtomicReference<String?>(null)

    /** Replaces the pending command (the last one wins, like a second cold-start deep link would). */
    fun setPending(command: String) {
        pending.set(command)
    }

    /** The pending command once, then "". */
    fun take(): String = pending.getAndSet(null) ?: ""

    /** Sends [command] through [emit] when a page is connected, otherwise keeps it for [take]. */
    fun deliver(command: String, pageConnected: Boolean, emit: (String) -> Unit) {
        if (pageConnected) emit(command) else pending.set(command)
    }
}
