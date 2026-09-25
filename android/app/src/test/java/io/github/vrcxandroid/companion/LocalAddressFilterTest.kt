package io.github.vrcxandroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class LocalAddressFilterTest {
    private fun local(host: String): Boolean {
        val address = LocalAddressFilter.parseLiteral(host) ?: throw AssertionError("not a literal: $host")
        return LocalAddressFilter.isLocal(address)
    }

    @Test
    fun ipv4LocalRanges() {
        listOf(
            "10.0.0.0", "10.1.2.3", "10.255.255.255",
            "172.16.0.0", "172.20.1.1", "172.31.255.255",
            "192.168.0.0", "192.168.1.20", "192.168.255.255",
            "169.254.0.1", "169.254.255.254",
            "127.0.0.1", "127.255.255.254",
            "100.64.0.0", "100.100.100.100", "100.127.255.255",
        ).forEach { assertTrue(it, local(it)) }
    }

    @Test
    fun ipv4PublicAndSpecialAddressesAreRejected() {
        listOf(
            "8.8.8.8", "1.1.1.1", "11.0.0.1", "9.255.255.255",
            "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0",
            "169.253.255.255", "169.255.0.0",
            "100.63.255.255", "100.128.0.0",
            "128.0.0.1", "126.255.255.255",
            "0.0.0.0", "255.255.255.255", "224.0.0.251", "203.0.113.5",
        ).forEach { assertFalse(it, local(it)) }
    }

    @Test
    fun ipv6LocalRanges() {
        listOf(
            "::1", "fe80::1", "fe80::1c2d:3e4f:5a6b:7c8d", "febf:ffff::1",
            "fc00::1", "fd00::1", "fdff:ffff:ffff::1",
            "::ffff:192.168.1.1", "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:100.64.1.1",
            "[fd12:3456:789a::1]",
        ).forEach { assertTrue(it, local(it)) }
    }

    @Test
    fun ipv6PublicAndSpecialAddressesAreRejected() {
        listOf(
            "2001:4860:4860::8888", "2606:4700:4700::1111", "fec0::1", "fe7f::1", "fbff::1",
            "ff02::1", "::", "::2", "::ffff:8.8.8.8", "::ffff:172.32.0.1", "64:ff9b::c0a8:101",
        ).forEach { assertFalse(it, local(it)) }
    }

    @Test
    fun ipv4MappedIpv6AsRawInet6Address() {
        val mapped = ByteArray(16).also {
            it[10] = -1
            it[11] = -1
            it[12] = 192.toByte()
            it[13] = 168.toByte()
            it[14] = 1
            it[15] = 2
        }
        assertTrue(LocalAddressFilter.isLocal(Inet6Address.getByAddress(null, mapped, -1)))
        mapped[12] = 8
        mapped[13] = 8
        assertFalse(LocalAddressFilter.isLocal(Inet6Address.getByAddress(null, mapped, -1)))
    }

    @Test
    fun literalParsingNeverResolvesNames() {
        assertNull(LocalAddressFilter.parseLiteral("example.com"))
        assertNull(LocalAddressFilter.parseLiteral("mypc.local"))
        assertNull(LocalAddressFilter.parseLiteral("999.1.1.1"))
        assertNull(LocalAddressFilter.parseLiteral("1.2.3"))
        assertNull(LocalAddressFilter.parseLiteral("1.2.3.4.5"))
        assertNull(LocalAddressFilter.parseLiteral(""))
        assertNull(LocalAddressFilter.parseLiteral("gg::1"))
        assertNotNull(LocalAddressFilter.parseLiteral(" 192.168.0.1 "))
        assertFalse(LocalAddressFilter.isLocalLiteral("localhost"))
        assertTrue(LocalAddressFilter.isLocalLiteral("192.168.0.1"))
    }

    @Test
    fun hostPortSplitting() {
        assertEquals("192.168.1.5" to 5000, LocalAddressFilter.splitHostPort("192.168.1.5:5000", 49460))
        assertEquals("192.168.1.5" to 49460, LocalAddressFilter.splitHostPort("192.168.1.5", 49460))
        assertEquals("fe80::1" to 49460, LocalAddressFilter.splitHostPort("fe80::1", 49460))
        assertEquals("fe80::1" to 7000, LocalAddressFilter.splitHostPort("[fe80::1]:7000", 49460))
        assertEquals("fd00::2" to 49460, LocalAddressFilter.splitHostPort("[fd00::2]", 49460))
        assertEquals("mypc" to 49460, LocalAddressFilter.splitHostPort(" mypc ", 49460))
        assertNull(LocalAddressFilter.splitHostPort(":80", 49460))
        assertNull(LocalAddressFilter.splitHostPort("1.2.3.4:0", 49460))
        assertNull(LocalAddressFilter.splitHostPort("1.2.3.4:70000", 49460))
        assertNull(LocalAddressFilter.splitHostPort("1.2.3.4:x", 49460))
        assertNull(LocalAddressFilter.splitHostPort("", 49460))
    }

    @Test
    fun loopbackObjectsFromTheJdkAreLocal() {
        assertTrue(LocalAddressFilter.isLocal(InetAddress.getLoopbackAddress()))
    }
}
