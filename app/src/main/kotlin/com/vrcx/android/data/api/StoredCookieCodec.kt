package com.vrcx.android.data.api

import okhttp3.Cookie

internal object StoredCookieCodec {
    fun serialize(cookie: Cookie): String = buildString {
        append(cookie.name).append("=").append(escapeValue(cookie.value))
        append("; domain=").append(cookie.domain)
        append("; path=").append(cookie.path)
        append("; expires=").append(cookie.expiresAt)
        if (cookie.secure) append("; secure")
        if (cookie.httpOnly) append("; httponly")
        if (cookie.hostOnly) append("; hostonly")
    }

    fun deserialize(serialized: String): Cookie? = try {
        val parts = serialized.split("; ")
        val nameValue = parts.first().split("=", limit = 2)
        val builder = Cookie.Builder()
            .name(nameValue.first())
            .value(unescapeValue(nameValue.getOrElse(1) { "" }))

        // The domain has to wait for the whole record: whether it is host-only
        // or subdomain-inclusive is decided by a flag that comes after it.
        var domain: String? = null
        var hostOnly = false
        for (part in parts.drop(1)) {
            val keyValue = part.split("=", limit = 2)
            when (keyValue.first().lowercase()) {
                "domain" -> domain = keyValue[1]
                "path" -> builder.path(keyValue[1])
                "expires" -> builder.expiresAt(keyValue[1].toLongOrNull() ?: 0L)
                "secure" -> builder.secure()
                "httponly" -> builder.httpOnly()
                "hostonly" -> hostOnly = true
            }
        }
        domain?.let { if (hostOnly) builder.hostOnlyDomain(it) else builder.domain(it) }
        builder.build()
    } catch (_: Exception) {
        null
    }

    // Fields are separated by "; " and CookieJarImpl joins a host's records with
    // "|", so a value containing either would split the record apart on the way
    // back in. Records written before this escaping still read back unchanged.
    private fun escapeValue(value: String): String =
        value.replace("%", "%25").replace(";", "%3B").replace("|", "%7C")

    private fun unescapeValue(value: String): String =
        value.replace("%3B", ";").replace("%7C", "|").replace("%25", "%")
}

/**
 * VRChat's own hosts. A cookie named `auth` only counts as a VRChat session when
 * it came from one of these — the jar is shared with the image client, which can
 * reach whatever host an avatar or gallery image happens to live on.
 */
internal fun isVrchatCookieHost(host: String): Boolean {
    val normalized = host.lowercase()
    return VRCHAT_COOKIE_DOMAINS.any { normalized == it || normalized.endsWith(".$it") }
}

private val VRCHAT_COOKIE_DOMAINS = listOf("vrchat.cloud", "vrchat.com")
