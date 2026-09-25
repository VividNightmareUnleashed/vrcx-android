package io.github.vrcxandroid.host

import java.util.Locale

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

    private const val ACTION_SEND = "android.intent.action.SEND"
}
