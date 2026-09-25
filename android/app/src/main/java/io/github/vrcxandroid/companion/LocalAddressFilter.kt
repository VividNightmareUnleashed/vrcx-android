package io.github.vrcxandroid.companion

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The "local address" rule of PROTOCOL.md §1. The phone connects only to these addresses, whatever their source
 * (stored pairing, QR code, discovery reply or manual input).
 *
 * IPv4: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 127.0.0.0/8, 100.64.0.0/10.
 * IPv6: fe80::/10, fc00::/7, ::1, and IPv4-mapped IPv6 (::ffff:a.b.c.d) of the IPv4 ranges.
 */
object LocalAddressFilter {
    fun isLocal(address: InetAddress): Boolean {
        val b = address.address
        return when {
            address is Inet4Address || b.size == 4 -> isLocalV4(b[0], b[1])
            address is Inet6Address || b.size == 16 -> isLocalV6(b)
            else -> false
        }
    }

    private fun isLocalV4(b0: Byte, b1: Byte): Boolean {
        val a = b0.toInt() and 0xFF
        val b = b1.toInt() and 0xFF
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            a == 127 ||
            (a == 100 && b in 64..127)
    }

    private fun isLocalV6(b: ByteArray): Boolean {
        if (b.size != 16) return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10 link-local
        if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 unique local
        val firstTenZero = (0 until 10).all { b[it].toInt() == 0 }
        if (firstTenZero && (0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1) return true // ::1
        if (firstTenZero && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()) return isLocalV4(b[12], b[13]) // ::ffff:v4
        return false
    }

    /**
     * Parses an IP literal ("192.168.1.2", "fe80::1%wlan0", "[fd00::1]") without any DNS lookup. Returns null for
     * anything that is not an IP literal.
     */
    fun parseLiteral(host: String): InetAddress? {
        var h = host.trim()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        if (h.isEmpty()) return null
        parseIpv4(h)?.let { return InetAddress.getByAddress(it) }
        if (':' in h) {
            // A string containing ':' is only ever parsed as an IPv6 literal (no name service lookup).
            if (!h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' || it == '_' || it == '-' }) {
                return null
            }
            return try {
                InetAddress.getByName(h)
            } catch (e: UnknownHostException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
        return null
    }

    private fun parseIpv4(h: String): ByteArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    /** True when [host] is an IP literal in a local range. Host names are not resolved here. */
    fun isLocalLiteral(host: String): Boolean = parseLiteral(host)?.let(::isLocal) ?: false

    /**
     * Splits manual input `host`, `host:port`, `[v6]:port` or a bare IPv6 literal into host and port. Returns null when
     * the input is empty or the port is not 1..65535.
     */
    fun splitHostPort(input: String, defaultPort: Int): Pair<String, Int>? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            val rest = s.substring(end + 1)
            if (rest.isEmpty()) return host to defaultPort
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }
        val colons = s.count { it == ':' }
        if (colons == 1) {
            val host = s.substringBefore(':')
            val port = s.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isEmpty()) return null
            return host to port
        }
        return s to defaultPort
    }
}
