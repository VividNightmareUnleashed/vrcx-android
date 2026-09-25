package io.github.vrcxandroid.bridge.webapi

import io.github.vrcxandroid.bridge.storage.JsonText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** A cookie plus the creation time .NET records as `Cookie.TimeStamp`. */
class StoredCookie(val cookie: Cookie, val timeStamp: Long)

/**
 * The cookie format upstream persists and hands to the frontend: a JSON array of
 * `System.Net.Cookie` serialized by System.Text.Json with default options, then base64 of its UTF-8 bytes. Using the
 * same shape lets a database imported from a PC keep its session and keeps `savedCredentials[*].cookies` portable.
 *
 * Written fields, in .NET's order: `Comment, CommentUri, HttpOnly, Discard, Domain, Expired, Expires, Name, Path,
 * Port, Secure, TimeStamp, Value, Version` (as in the PC sample in `NetCookieCodecTest`). `Expires` is always
 * `9999-12-31T23:59:59.9999999` (upstream forces `DateTime.MaxValue`), `TimeStamp` is ISO-8601 UTC with 7 fractional
 * digits. A domain cookie is written with a leading `.`; a host-only cookie with the bare host.
 */
object NetCookieCodec {
    const val MAX_EXPIRES = "9999-12-31T23:59:59.9999999"
    const val EMPTY_BASE64 = "W10="

    private val json = Json { isLenient = true }
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun encode(cookies: Collection<StoredCookie>): String {
        val sb = StringBuilder(64 + cookies.size * 320)
        sb.append('[')
        var first = true
        for (stored in cookies) {
            if (!first) sb.append(',')
            first = false
            val c = stored.cookie
            sb.append("{\"Comment\":\"\",\"CommentUri\":null,\"HttpOnly\":").append(c.httpOnly)
            sb.append(",\"Discard\":false,\"Domain\":")
            JsonText.appendQuotedSystemTextJson(sb, if (c.hostOnly) c.domain else ".${c.domain}")
            sb.append(",\"Expired\":false,\"Expires\":\"").append(MAX_EXPIRES).append("\",\"Name\":")
            JsonText.appendQuotedSystemTextJson(sb, c.name)
            sb.append(",\"Path\":")
            JsonText.appendQuotedSystemTextJson(sb, c.path)
            sb.append(",\"Port\":\"\",\"Secure\":").append(c.secure)
            sb.append(",\"TimeStamp\":\"").append(TIMESTAMP.format(Instant.ofEpochMilli(stored.timeStamp)))
            sb.append("\",\"Value\":")
            JsonText.appendQuotedSystemTextJson(sb, c.value)
            sb.append(",\"Version\":0}")
        }
        sb.append(']')
        return sb.toString()
    }

    fun encodeBase64(cookies: Collection<StoredCookie>): String =
        Base64.getEncoder().encodeToString(encode(cookies).toByteArray(Charsets.UTF_8))

    /**
     * Reads a cookie array. Entries that cannot become a valid cookie (no name or domain, expired, rejected by OkHttp)
     * are skipped; text that is not a JSON array throws.
     */
    fun decode(text: String, now: Long): List<StoredCookie> {
        val array = json.parseToJsonElement(text.removePrefix("﻿")) as? JsonArray
            ?: throw IllegalArgumentException("Cookie data is not a JSON array")
        return array.mapNotNull { (it as? JsonObject)?.let { obj -> decodeOne(obj, now) } }
    }

    fun decodeBase64(base64: String, now: Long): List<StoredCookie> {
        val bytes = Base64.getMimeDecoder().decode(base64.trim())
        return decode(String(bytes, Charsets.UTF_8), now)
    }

    private fun decodeOne(obj: JsonObject, now: Long): StoredCookie? {
        // System.Text.Json is case-sensitive by default; accept any case to be lenient with hand-made data.
        val fields = obj.entries.associate { it.key.lowercase() to it.value }
        fun string(name: String) = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun bool(name: String) = (fields[name] as? JsonPrimitive)?.booleanOrNull ?: false

        val name = string("name")?.takeIf { it.isNotEmpty() } ?: return null
        val rawDomain = string("domain")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAt = string("expires")?.let(::parseExpires)
        if (expiresAt != null && expiresAt <= now) return null
        return try {
            val builder = Cookie.Builder()
                .name(name)
                .value(string("value").orEmpty())
                .path(string("path")?.takeIf { it.startsWith("/") } ?: "/")
            val domain = rawDomain.trimStart('.').lowercase()
            if (rawDomain.startsWith('.')) builder.domain(domain) else builder.hostOnlyDomain(domain)
            if (bool("secure")) builder.secure()
            if (bool("httponly")) builder.httpOnly()
            if (expiresAt != null) builder.expiresAt(expiresAt)
            StoredCookie(builder.build(), string("timestamp")?.let(::parseTimestamp) ?: now)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** null = no expiry (`DateTime.MaxValue`, `DateTime.MinValue` or unreadable). */
    fun parseExpires(text: String): Long? {
        if (text.startsWith("9999-12-31") || text.startsWith("0001-01-01")) return null
        return parseDate(text)
    }

    private fun parseTimestamp(text: String): Long? = parseDate(text)

    /** .NET writes `DateTime` as ISO-8601 with an offset (`Z`, `+02:00`) or none (unspecified; read as UTC). */
    private fun parseDate(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
