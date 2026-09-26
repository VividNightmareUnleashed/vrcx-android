package io.github.vrcxandroid.bridge.webapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ProxySpecTest {
    @Test
    fun emptyMeansNoProxy() {
        assertNull(ProxySpec.parse(null))
        assertNull(ProxySpec.parse(""))
        assertNull(ProxySpec.parse("  "))
    }

    @Test
    fun bareHostPortIsHttp() {
        val spec = ProxySpec.parse("127.0.0.1:8080")!!
        assertEquals(ProxySpec("http", "127.0.0.1", 8080), spec)
        assertEquals(ProxySpec.Kind.HTTP, spec.nativeKind)
        assertEquals("http://127.0.0.1:8080", spec.webViewRule)
    }

    @Test
    fun schemesAndDefaults() {
        assertEquals(ProxySpec("http", "proxy.lan", 80), ProxySpec.parse("http://proxy.lan"))
        assertEquals(ProxySpec("socks5", "proxy.lan", 1080), ProxySpec.parse("socks5://proxy.lan"))
        assertEquals("socks5", ProxySpec.parse("socks://h:1")!!.scheme)
        assertEquals("socks5", ProxySpec.parse("socks5h://h:1")!!.scheme)
        assertEquals(ProxySpec.Kind.SOCKS4, ProxySpec.parse("socks4://h:9")!!.nativeKind)
        assertEquals(ProxySpec.Kind.SOCKS4A, ProxySpec.parse("SOCKS4A://h:9")!!.nativeKind)
        assertEquals("socks4://h:9", ProxySpec.parse("socks4a://h:9")!!.webViewRule)
        assertEquals(ProxySpec.Kind.SOCKS5, ProxySpec.parse("socks5://h:9")!!.nativeKind)
        assertEquals(ProxySpec("http", "h", 3128), ProxySpec.parse("\"http://h:3128\""))
        assertEquals("http://[::1]:8080", ProxySpec.parse("http://[::1]:8080")!!.webViewRule)
    }

    @Test
    fun credentialsAreParsedButNotShown() {
        val spec = ProxySpec.parse("http://us%40er:p%3Ass@h:1")!!
        assertEquals("us@er", spec.username)
        assertEquals("p:ss", spec.password)
        assertEquals("http://h:1", spec.displayName)
        assertEquals("socks4://user@h:1".let { ProxySpec.parse(it)!!.username }, "user")
    }

    @Test
    fun httpsProxyIsWellFormedButNotUsableNatively() {
        // new WebProxy("https://...") does not throw and .NET 8+ supports HTTPS proxies, so the value is not reset.
        val spec = ProxySpec.parse("https://secure.proxy:8443")!!
        assertEquals(ProxySpec("https", "secure.proxy", 8443), spec)
        assertNull(spec.nativeKind)
        assertEquals("https://secure.proxy:8443", spec.webViewRule)
        assertEquals(443, ProxySpec.parse("https://secure.proxy")!!.port)
    }

    @Test
    fun unknownSchemeIsKeptButUnusable() {
        // .NET accepts the URI and fails at request time; it never resets the setting for it.
        val spec = ProxySpec.parse("ftp://h:21")!!
        assertNull(spec.nativeKind)
        assertNull(spec.webViewRule)
        assertEquals("ftp://h:21", spec.displayName)
    }

    @Test
    fun malformedValuesThrow() {
        for (bad in listOf("http://", "ht tp://x:1", "http://h:99999", "h:abc", "://h:1", "http://[::1:80")) {
            try {
                ProxySpec.parse(bad)
                fail("expected $bad to be rejected")
            } catch (_: InvalidProxyException) {
            }
        }
    }

    @Test
    fun rejectionsNeverQuoteTheValue() {
        // URISyntaxException would quote the whole URI, credentials included
        try {
            ProxySpec.parse("http://user:hunter2 secret@proxy.example:8080")
            fail("expected a rejection")
        } catch (e: InvalidProxyException) {
            assertNull(e.cause)
            assertFalse(e.message!!, "hunter2" in e.message!!)
            assertFalse(e.toString(), "proxy.example" in e.toString())
        }
    }
}
