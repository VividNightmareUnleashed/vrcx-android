package io.github.vrcxandroid.bridge.webapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

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
        assertEquals(Proxy.Type.HTTP, spec.toProxy().type())
        assertEquals(8080, (spec.toProxy().address() as InetSocketAddress).port)
        assertEquals("http://127.0.0.1:8080", spec.webViewRule)
    }

    @Test
    fun schemesAndDefaults() {
        assertEquals(ProxySpec("http", "proxy.lan", 80), ProxySpec.parse("http://proxy.lan"))
        assertEquals(ProxySpec("socks5", "proxy.lan", 1080), ProxySpec.parse("socks5://proxy.lan"))
        assertEquals("socks5", ProxySpec.parse("socks://h:1")!!.scheme)
        assertEquals("socks4://h:9", ProxySpec.parse("socks4a://h:9")!!.webViewRule)
        assertEquals(Proxy.Type.SOCKS, ProxySpec.parse("socks5://h:9")!!.type)
        assertEquals(ProxySpec("http", "h", 3128), ProxySpec.parse("\"http://h:3128\""))
        assertEquals("http://[::1]:8080", ProxySpec.parse("http://[::1]:8080")!!.webViewRule)
    }

    @Test
    fun credentials() {
        val spec = ProxySpec.parse("http://us%40er:p%3Ass@h:1")!!
        assertEquals("us@er", spec.username)
        assertEquals("p:ss", spec.password)
    }

    @Test
    fun invalidValuesThrow() {
        for (bad in listOf("http://", "ht tp://x:1", "https://secure.proxy:443", "ftp://h:21", "http://h:99999", "h:abc")) {
            try {
                ProxySpec.parse(bad)
                fail("expected $bad to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
