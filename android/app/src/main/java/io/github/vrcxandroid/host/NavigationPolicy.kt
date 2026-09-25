package io.github.vrcxandroid.host

import java.net.URI
import java.util.Locale

/** What to do with a top-level navigation the page (or a link in it) starts (docs/ARCHITECTURE.md §6.4). */
sealed interface NavigationAction {
    /** Same-origin navigation (reload, hash routes): let the WebView load it. */
    data object Allow : NavigationAction

    /** `vrcx:` link: deliver as a launch command. */
    data class LaunchCommand(val command: String) : NavigationAction

    /** `http(s)` elsewhere: open in a Custom Tab or the browser. */
    data class External(val url: String) : NavigationAction

    /** Anything else (intent:, javascript:, data:, file:, ...). */
    data object Block : NavigationAction
}

object NavigationPolicy {
    fun decide(url: String?, origin: String = HostUrls.ORIGIN): NavigationAction {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return NavigationAction.Block
        val scheme = raw.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme == LaunchCommands.SCHEME) {
            return LaunchCommands.fromUri(raw)?.let { NavigationAction.LaunchCommand(it) } ?: NavigationAction.Block
        }
        if (scheme != "http" && scheme != "https") return NavigationAction.Block
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return NavigationAction.Block
        }
        if (uri.host.isNullOrEmpty()) return NavigationAction.Block
        return if (isSameOrigin(uri, origin)) NavigationAction.Allow else NavigationAction.External(raw)
    }

    fun isSameOrigin(url: String?, origin: String = HostUrls.ORIGIN): Boolean = try {
        url != null && isSameOrigin(URI(url), origin)
    } catch (_: Exception) {
        false
    }

    private fun isSameOrigin(uri: URI, origin: String): Boolean {
        val o = URI(origin)
        if (!uri.scheme.equals(o.scheme, ignoreCase = true)) return false
        if (!uri.host.equals(o.host, ignoreCase = true)) return false
        return effectivePort(uri) == effectivePort(o)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}

/** Fixed URLs of the app origin (docs/ARCHITECTURE.md §4.2). Never change the origin: localStorage is keyed by it. */
object HostUrls {
    const val DOMAIN = "appassets.androidplatform.net"
    const val ORIGIN = "https://$DOMAIN"
    const val START_URL = "$ORIGIN/assets/web/index.html"
    const val ASSETS_PREFIX = "/assets/"
    const val LOCAL_PREFIX = "/local/"
    const val LOCAL_CACHE_PREFIX = "/local/cache/"
}
