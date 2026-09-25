package io.github.vrcxandroid.bridge.webapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NetCookieCodecTest {
    private val now = 1_790_000_000_000L // 2026-09-21

    /** Verified System.Text.Json output from a PC (the ellipses are part of the sample value). */
    private val pcSample = """[{"Comment":"","CommentUri":null,"HttpOnly":true,"Discard":false,"Domain":"api.vrchat.cloud","Expired":false,""" +
        """"Expires":"9999-12-31T23:59:59.9999999","Name":"auth","Path":"/","Port":"","Secure":true,""" +
        """"TimeStamp":"2026-09-25T13:54:33.9403132Z","Value":"authcookie_\u2026","Version":0},""" +
        """{"Comment":"","CommentUri":null,"HttpOnly":true,"Discard":false,"Domain":"api.vrchat.cloud","Expired":false,""" +
        """"Expires":"9999-12-31T23:59:59.9999999","Name":"twoFactorAuth","Path":"/","Port":"","Secure":false,""" +
        """"TimeStamp":"2026-09-25T15:54:33.9403132+02:00","Value":"tfa","Version":0}]"""

    @Test
    fun decodesPcSample() {
        val cookies = NetCookieCodec.decode(pcSample, now)
        assertEquals(2, cookies.size)
        val auth = cookies[0].cookie
        assertEquals("auth", auth.name)
        assertEquals("authcookie_…", auth.value)
        assertEquals("api.vrchat.cloud", auth.domain)
        assertTrue(auth.hostOnly)
        assertEquals("/", auth.path)
        assertTrue(auth.secure)
        assertTrue(auth.httpOnly)
        assertEquals(java.time.Instant.parse("2026-09-25T13:54:33.940Z").toEpochMilli(), cookies[0].timeStamp)
        assertEquals(cookies[0].timeStamp, cookies[1].timeStamp) // same instant, other offset
        assertTrue(auth.matches("https://api.vrchat.cloud/api/1/auth/user".toHttpUrl()))
        assertFalse(auth.matches("http://api.vrchat.cloud/api/1/auth/user".toHttpUrl())) // secure
        assertFalse(auth.matches("https://files.api.vrchat.cloud/".toHttpUrl())) // host-only
        assertFalse(cookies[1].cookie.secure)
    }

    @Test
    fun decodesBase64Blob() {
        val blob = Base64.getEncoder().encodeToString(pcSample.toByteArray())
        assertEquals(listOf("auth", "twoFactorAuth"), NetCookieCodec.decodeBase64(blob, now).map { it.cookie.name })
        assertEquals(emptyList<StoredCookie>(), NetCookieCodec.decodeBase64(NetCookieCodec.EMPTY_BASE64, now))
    }

    @Test
    fun encodesAllFourteenFieldsInDotNetOrder() {
        val cookie = Cookie.Builder().name("auth").value("authcookie_1+2").hostOnlyDomain("api.vrchat.cloud").path("/")
            .secure().httpOnly().expiresAt(now + 1000).build()
        val stamp = java.time.Instant.parse("2026-09-25T13:54:33.940Z").toEpochMilli()
        val json = NetCookieCodec.encode(listOf(StoredCookie(cookie, stamp)))
        assertEquals(
            """[{"Comment":"","CommentUri":null,"HttpOnly":true,"Discard":false,"Domain":"api.vrchat.cloud","Expired":false,""" +
                """"Expires":"9999-12-31T23:59:59.9999999","Name":"auth","Path":"/","Port":"","Secure":true,""" +
                """"TimeStamp":"2026-09-25T13:54:33.9400000Z","Value":"authcookie_1\u002B2","Version":0}]""",
            json,
        )
        val keys = (Json.parseToJsonElement(json) as JsonArray)[0].let { (it as JsonObject).keys.toList() }
        assertEquals(
            listOf("Comment", "CommentUri", "HttpOnly", "Discard", "Domain", "Expired", "Expires", "Name", "Path", "Port", "Secure", "TimeStamp", "Value", "Version"),
            keys,
        )
    }

    @Test
    fun emptyJarIsW10() {
        assertEquals("[]", NetCookieCodec.encode(emptyList()))
        assertEquals("W10=", NetCookieCodec.encodeBase64(emptyList()))
    }

    @Test
    fun roundTripKeepsDomainCookiesSecureAndPaths() {
        val cookies = listOf(
            StoredCookie(Cookie.Builder().name("a").value("1").domain("vrchat.cloud").path("/api").build(), now - 5000),
            StoredCookie(Cookie.Builder().name("b").value("é\"x").hostOnlyDomain("example.com").secure().build(), now - 1),
        )
        val json = NetCookieCodec.encode(cookies)
        val obj = (Json.parseToJsonElement(json) as JsonArray)[0] as JsonObject
        assertEquals(".vrchat.cloud", (obj["Domain"] as JsonPrimitive).content)

        val back = NetCookieCodec.decode(json, now)
        assertEquals(2, back.size)
        assertFalse(back[0].cookie.hostOnly)
        assertEquals("vrchat.cloud", back[0].cookie.domain)
        assertEquals("/api", back[0].cookie.path)
        assertTrue(back[0].cookie.matches("https://api.vrchat.cloud/api/1".toHttpUrl()))
        assertEquals(now - 5000, back[0].timeStamp)
        assertEquals("é\"x", back[1].cookie.value)
        assertTrue(back[1].cookie.secure)
        assertTrue(back[1].cookie.hostOnly)
    }

    @Test
    fun expiryHandling() {
        fun entry(expires: String) =
            """[{"Name":"n","Value":"v","Domain":"h.example","Path":"/","Expires":"$expires"}]"""
        assertEquals(1, NetCookieCodec.decode(entry("9999-12-31T23:59:59.9999999"), now).size)
        assertEquals(1, NetCookieCodec.decode(entry("0001-01-01T00:00:00"), now).size)
        assertEquals(0, NetCookieCodec.decode(entry("2020-01-01T00:00:00Z"), now).size) // expired
        val future = NetCookieCodec.decode(entry("2030-01-01T00:00:00+01:00"), now).single()
        assertEquals(java.time.Instant.parse("2029-12-31T23:00:00Z").toEpochMilli(), future.cookie.expiresAt)
    }

    @Test
    fun invalidEntriesAreSkipped() {
        val json = """[{"Name":"","Domain":"a.b"},{"Name":"x","Domain":""},{"Name":"ok","Value":"1","Domain":"a.b"},42]"""
        assertEquals(listOf("ok"), NetCookieCodec.decode(json, now).map { it.cookie.name })
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonArrayThrows() {
        NetCookieCodec.decode("{}", now)
    }
}
