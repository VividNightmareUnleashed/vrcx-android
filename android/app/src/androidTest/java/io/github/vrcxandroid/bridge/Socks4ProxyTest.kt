package io.github.vrcxandroid.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vrcxandroid.bridge.webapi.HttpClients
import io.github.vrcxandroid.bridge.webapi.ProxySpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SOCKS4/4a through the shared client on Android's own socket and TLS stack (Conscrypt layers TLS over the socket's file
 * descriptor, so the tunnel must start exactly after the 8-byte SOCKS reply). Everything runs on loopback.
 */
@RunWith(AndroidJUnit4::class)
class Socks4ProxyTest {
    private lateinit var web: MockWebServer
    private lateinit var proxyServer: ServerSocket
    private lateinit var trusted: HandshakeCertificates
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val requests = CopyOnWriteArrayList<String>()
    private val clients = ArrayList<OkHttpClient>()

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        web = MockWebServer()
        web.useHttps(HandshakeCertificates.Builder().heldCertificate(localhost).build().sslSocketFactory(), false)
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        trusted = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        proxyServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!proxyServer.isClosed) {
                val client = try {
                    proxyServer.accept()
                } catch (_: IOException) {
                    break
                }
                sockets += client
                thread(isDaemon = true) { serveSocks4(client) }
            }
        }
    }

    @After
    fun tearDown() {
        clients.forEach { it.connectionPool.evictAll() }
        proxyServer.close()
        sockets.forEach {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        web.shutdown()
    }

    /** SOCKS4/4a only: anything but version 4 (such as the JDK's SOCKS5 greeting) is dropped. */
    private fun serveSocks4(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            if (input.readUnsignedByte() != 4) return client.close()
            input.readUnsignedByte() // CONNECT
            val port = input.readUnsignedShort()
            val ip = ByteArray(4).also { input.readFully(it) }
            fun cString() = generateSequence { input.readByte().takeIf { it != 0.toByte() } }.toList().toByteArray().toString(Charsets.UTF_8)
            val user = cString()
            val host = if (ip[0].toInt() == 0 && ip[1].toInt() == 0 && ip[2].toInt() == 0 && ip[3].toInt() != 0) cString() else null
            requests += "${ip.joinToString(".") { (it.toInt() and 0xFF).toString() }} $port $user ${host ?: "-"}"
            val target = Socket(if (host != null) InetAddress.getAllByName(host).first { it is Inet4Address } else InetAddress.getByAddress(ip), port)
            sockets += target
            client.getOutputStream().apply {
                write(byteArrayOf(0, 90, 0, 0, 0, 0, 0, 0))
                flush()
            }
            thread(isDaemon = true) {
                try {
                    client.getInputStream().copyTo(target.getOutputStream())
                } catch (_: IOException) {
                }
            }
            target.getInputStream().copyTo(client.getOutputStream())
        } catch (_: IOException) {
        }
    }

    private fun client(proxy: String): OkHttpClient =
        HttpClients.create(CookieJar.NO_COOKIES, "VRCX test", ProxySpec.parse(proxy)).newBuilder()
            .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .build()
            .also { clients += it }

    private fun get(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { "${it.code} ${it.body!!.string()}" }

    @Test
    fun httpsThroughSocks4a() {
        web.enqueue(MockResponse().setBody("secure 4a"))
        web.enqueue(MockResponse().setBody("reused"))
        val client = client("socks4a://vrcx@127.0.0.1:${proxyServer.localPort}")
        assertEquals("200 secure 4a", get(client, "https://localhost:${web.port}/api/1/config"))
        assertEquals("200 reused", get(client, "https://localhost:${web.port}/api/1/auth/user"))
        assertEquals(listOf("0.0.0.255 ${web.port} vrcx localhost"), requests)
        assertEquals("/api/1/config", web.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun httpsThroughSocks4() {
        web.enqueue(MockResponse().setBody("secure 4"))
        assertEquals("200 secure 4", get(client("socks4://127.0.0.1:${proxyServer.localPort}"), "https://localhost:${web.port}/"))
        assertEquals(listOf("127.0.0.1 ${web.port}  -"), requests)
    }
}
