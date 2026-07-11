package com.vrcx.android.data.api

import okhttp3.Cookie

internal object StoredCookieCodec {
    fun serialize(cookie: Cookie): String = buildString {
        append(cookie.name).append("=").append(cookie.value)
        append("; domain=").append(cookie.domain)
        append("; path=").append(cookie.path)
        append("; expires=").append(cookie.expiresAt)
        if (cookie.secure) append("; secure")
        if (cookie.httpOnly) append("; httponly")
    }

    fun deserialize(serialized: String): Cookie? = try {
        val parts = serialized.split("; ")
        val nameValue = parts.first().split("=", limit = 2)
        val builder = Cookie.Builder()
            .name(nameValue.first())
            .value(nameValue.getOrElse(1) { "" })

        for (part in parts.drop(1)) {
            val keyValue = part.split("=", limit = 2)
            when (keyValue.first().lowercase()) {
                "domain" -> builder.domain(keyValue[1])
                "path" -> builder.path(keyValue[1])
                "expires" -> builder.expiresAt(keyValue[1].toLongOrNull() ?: 0L)
                "secure" -> builder.secure()
                "httponly" -> builder.httpOnly()
            }
        }
        builder.build()
    } catch (_: Exception) {
        null
    }
}
