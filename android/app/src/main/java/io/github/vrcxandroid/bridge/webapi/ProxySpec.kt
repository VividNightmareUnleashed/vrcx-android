package io.github.vrcxandroid.bridge.webapi

import java.net.URI
import java.net.URLDecoder

/** A proxy value that is not a valid URI (upstream's `UriFormatException`, the only case that resets the setting). */
class InvalidProxyException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * The proxy from `VRCXStorage["VRCX_ProxyServer"]`. Like .NET's `new WebProxy(url)`, a value
 * without a scheme is an HTTP proxy (`host:port`).
 *
 * Native requests support `http`, `socks4`, `socks4a` and `socks5` (also written `socks` or `socks5h`), like upstream's
 * .NET handler; SOCKS4 runs through [Socks4SocketFactory]. A well-formed value OkHttp cannot use (`https://`, which needs
 * TLS to the proxy, or a scheme .NET rejects as well) has a null [nativeKind]: it is kept rather than reset, and native
 * requests fail instead of bypassing the proxy. The WebView can still use an `https` proxy ([webViewRule]).
 */
data class ProxySpec(
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    enum class Kind { HTTP, SOCKS4, SOCKS4A, SOCKS5 }

    /** How OkHttp reaches this proxy, or null when it cannot. */
    val nativeKind: Kind?
        get() = when (scheme) {
            "http" -> Kind.HTTP
            "socks4" -> Kind.SOCKS4
            "socks4a" -> Kind.SOCKS4A
            "socks5" -> Kind.SOCKS5
            else -> null
        }

    /** `scheme://host:port` without credentials, for messages and logs. */
    val displayName: String get() = "$scheme://${bracketedHost()}${if (port > 0) ":$port" else ""}"

    /** Rule for `androidx.webkit.ProxyConfig.Builder.addProxyRule`, or null when the WebView cannot use it. */
    val webViewRule: String?
        get() {
            val s = when (scheme) {
                "http" -> "http"
                "https" -> "https"
                "socks4", "socks4a" -> "socks4"
                "socks5" -> "socks5"
                else -> return null
            }
            return "$s://${bracketedHost()}:$port"
        }

    private fun bracketedHost() = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    companion object {
        private val SOCKS5 = setOf("socks5", "socks", "socks5h")

        /** Returns null for an empty value; throws [InvalidProxyException] for a malformed one. */
        fun parse(value: String?): ProxySpec? {
            var text = value?.trim()?.trim('"', '\'')?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (!text.contains("://")) text = "http://$text"
            val uri = try {
                URI(text)
            } catch (e: Exception) {
                throw InvalidProxyException("Invalid proxy URI", e)
            }
            val scheme = uri.scheme?.lowercase() ?: throw InvalidProxyException("Invalid proxy URI: no scheme")
            val normalized = if (scheme in SOCKS5) "socks5" else scheme
            val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }
                ?: throw InvalidProxyException("Invalid proxy URI: no host")
            val port = when {
                uri.port in 1..65535 -> uri.port
                uri.port == -1 -> defaultPort(normalized)
                else -> throw InvalidProxyException("Invalid proxy URI: bad port")
            }
            var user: String? = null
            var pass: String? = null
            uri.rawUserInfo?.let { info ->
                val parts = info.split(':', limit = 2)
                user = URLDecoder.decode(parts[0], "UTF-8")
                pass = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
            }
            return ProxySpec(normalized, host, port, user, pass)
        }

        private fun defaultPort(scheme: String) = when (scheme) {
            "http" -> 80
            "https" -> 443
            "socks4", "socks4a", "socks5" -> 1080
            else -> -1
        }
    }
}
