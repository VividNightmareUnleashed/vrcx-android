package io.github.vrcxandroid.bridge.webapi

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * SOCKS4 and SOCKS4a for OkHttp. Upstream's .NET handler speaks both, but OkHttp hands SOCKS
 * proxies to `java.net.Socket(Proxy)`, whose client always greets with SOCKS5 and can fall back to SOCKS4 only for
 * addresses it has already resolved. OkHttp passes unresolved ones, so a SOCKS4-only server would never work.
 *
 * Instead, the client connects "directly" and [Socks4SocketFactory]'s sockets open the TCP connection to the proxy
 * and run the SOCKS4 CONNECT handshake inside `connect()`. After the proxy grants the request the connection is a
 * plain tunnel, so TLS and HTTP run over the same socket unchanged. As in .NET's `SocksHelper`: `socks4` resolves the
 * host locally and sends an IPv4 address; `socks4a` lets the proxy resolve names (DSTIP `0.0.0.255` + host name);
 * IPv6 destinations are refused; the user id is the proxy URI's user name.
 */
internal object Socks4 {
    private const val VERSION = 4
    private const val CONNECT = 1
    private const val GRANTED = 90
    private const val IDENT_MISMATCH = 93
    private const val MAX_STRING_BYTES = 255

    /** SOCKS4a's "the host name follows" address (.NET sends 0.0.0.255 too). */
    private val NAME_FOLLOWS = byteArrayOf(0, 0, 0, 255.toByte())

    /** A stand-in address that carries [host] to [connectRequest] without a local DNS lookup (SOCKS4a). */
    fun unresolvedPlaceholder(host: String): InetAddress = InetAddress.getByAddress(host, NAME_FOLLOWS)

    fun isIpLiteral(host: String): Boolean = host.contains(':') || IPV4_LITERAL.matches(host)

    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** The CONNECT request for [target]; [remoteResolution] is SOCKS4a. */
    fun connectRequest(target: InetSocketAddress, userId: String, remoteResolution: Boolean): ByteArray {
        val address = target.address
        val hostName: String?
        val ip: ByteArray
        when {
            address == null || (remoteResolution && address.address.contentEquals(NAME_FOLLOWS)) -> {
                if (!remoteResolution) throw UnknownHostException("SOCKS4 needs a resolved IPv4 address for ${target.hostString}")
                hostName = target.hostString
                ip = NAME_FOLLOWS
            }
            address is Inet4Address -> {
                hostName = null
                ip = address.address
            }
            else -> throw SocketException("SOCKS4 does not support IPv6 addresses.")
        }
        val out = ByteArrayOutputStream(16 + userId.length + (hostName?.length ?: 0))
        out.write(VERSION)
        out.write(CONNECT)
        out.write(target.port ushr 8 and 0xFF)
        out.write(target.port and 0xFF)
        out.write(ip)
        out.write(encode(userId, "user name"))
        out.write(0)
        if (hostName != null) {
            out.write(encode(hostName, "host name"))
            out.write(0)
        }
        return out.toByteArray()
    }

    /** Checks the 8-byte reply (VN, CD, DSTPORT, DSTIP). Like .NET, only the status byte is interpreted. */
    fun checkReply(reply: ByteArray) {
        when (reply[1].toInt() and 0xFF) {
            GRANTED -> Unit
            IDENT_MISMATCH -> throw SocketException("Failed to authenticate with the SOCKS server.")
            else -> throw SocketException("SOCKS server failed to connect to the destination.")
        }
    }

    private fun encode(text: String, what: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw SocketException("Encoding the $what took more than the maximum of 255 bytes.")
        if (bytes.contains(0)) throw SocketException("The $what contains a NUL character.")
        return bytes
    }
}

/**
 * Name resolution for a SOCKS4 proxy: IPv4 addresses only (SOCKS4), or no local lookup at all (SOCKS4a, the proxy
 * resolves the name). IP literals are passed through without DNS.
 */
internal class Socks4Dns(private val remoteResolution: Boolean, private val system: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (Socks4.isIpLiteral(hostname)) return system.lookup(hostname)
        if (remoteResolution) return listOf(Socks4.unresolvedPlaceholder(hostname))
        val ipv4 = system.lookup(hostname).filterIsInstance<Inet4Address>()
        if (ipv4.isEmpty()) throw UnknownHostException("No IPv4 address found for $hostname (SOCKS4 supports IPv4 only)")
        return ipv4
    }
}

/** Creates [Socks4Socket]s for OkHttp's direct routes. */
internal class Socks4SocketFactory(private val proxy: ProxySpec) : SocketFactory() {
    private val remoteResolution = proxy.nativeKind == ProxySpec.Kind.SOCKS4A

    override fun createSocket(): Socket = Socks4Socket(proxy.host, proxy.port, proxy.username.orEmpty(), remoteResolution)

    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(target(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(target(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }

    private fun target(host: String, port: Int) =
        InetSocketAddress(Socks4Dns(remoteResolution).lookup(host).first(), port)
}

/**
 * A socket whose `connect(target)` connects to the SOCKS4 proxy instead and asks it to open [target]. The handshake
 * uses the connect timeout as its read timeout. Nothing is buffered, so the stream starts exactly at the tunnel.
 */
internal class Socks4Socket(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val userId: String,
    private val remoteResolution: Boolean,
) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("Unsupported address type")
        val request = Socks4.connectRequest(target, userId, remoteResolution)
        val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
        if (proxyAddress.isUnresolved) throw UnknownHostException("Cannot resolve the SOCKS proxy $proxyHost")
        super.connect(proxyAddress, timeout)
        try {
            val previousTimeout = soTimeout
            if (timeout > 0) soTimeout = timeout
            val output = getOutputStream()
            output.write(request)
            output.flush()
            val reply = ByteArray(8)
            DataInputStream(getInputStream()).readFully(reply)
            Socks4.checkReply(reply)
            soTimeout = previousTimeout
        } catch (e: IOException) {
            try {
                close()
            } catch (_: IOException) {
            }
            throw e
        }
    }
}
