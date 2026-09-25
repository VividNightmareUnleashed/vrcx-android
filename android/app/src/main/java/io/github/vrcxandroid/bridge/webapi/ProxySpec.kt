package io.github.vrcxandroid.bridge.webapi

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder

/**
 * The proxy from `VRCXStorage["VRCX_ProxyServer"]`. Like .NET's `new WebProxy(url)`, a value
 * without a scheme is an HTTP proxy (`host:port`). Supported: `http`, `socks4`, `socks4a`, `socks5` (also `socks`,
 * `socks5h`). OkHttp cannot speak TLS to a proxy, so `https://` proxies are rejected like a malformed URI.
 */
data class ProxySpec(
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    val type: Proxy.Type get() = if (scheme == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS

    fun toProxy(): Proxy = Proxy(type, InetSocketAddress.createUnresolved(host, port))

    /** Rule for `androidx.webkit.ProxyConfig.Builder.addProxyRule`. */
    val webViewRule: String
        get() {
            val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val s = when (scheme) {
                "http" -> "http"
                "socks4", "socks4a" -> "socks4"
                else -> "socks5"
            }
            return "$s://$h:$port"
        }

    companion object {
        private val SOCKS5 = setOf("socks5", "socks", "socks5h")

        /** Returns null for an empty value; throws [IllegalArgumentException] for an unusable one. */
        fun parse(value: String?): ProxySpec? {
            var text = value?.trim()?.trim('"', '\'')?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (!text.contains("://")) text = "http://$text"
            val uri = try {
                URI(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid proxy URI: $value", e)
            }
            val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Invalid proxy URI: $value")
            val normalized = when (scheme) {
                "http" -> "http"
                "socks4", "socks4a" -> scheme
                in SOCKS5 -> "socks5"
                else -> throw IllegalArgumentException("Unsupported proxy scheme: $scheme")
            }
            val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Invalid proxy URI: $value")
            val port = if (uri.port in 1..65535) uri.port else if (uri.port == -1) defaultPort(normalized) else
                throw IllegalArgumentException("Invalid proxy port: $value")
            var user: String? = null
            var pass: String? = null
            uri.rawUserInfo?.let { info ->
                val parts = info.split(':', limit = 2)
                user = URLDecoder.decode(parts[0], "UTF-8")
                pass = parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
            }
            return ProxySpec(normalized, host, port, user, pass)
        }

        private fun defaultPort(scheme: String) = if (scheme == "http") 80 else 1080
    }
}
