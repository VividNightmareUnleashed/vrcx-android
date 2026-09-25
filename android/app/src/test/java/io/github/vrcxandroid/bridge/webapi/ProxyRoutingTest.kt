package io.github.vrcxandroid.bridge.webapi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A SOCKS4/4a-only proxy, like the servers the JDK's SOCKS5-first client cannot use: a connection that does not start
 * with version 4 (for example a SOCKS5 greeting) is closed. Granted requests are relayed to the destination.
 */
private class FakeSocks4Server(private val status: Int = 90) : Closeable {
    data class Seen(val version: Int, val command: Int, val port: Int, val ip: String, val userId: String, val host: String?)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<Seen>()
    val rejectedGreetings = CopyOnWriteArrayList<Int>()
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "fake-socks4") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                sockets += client
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    private fun readCString(input: DataInputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val b = input.readByte()
            if (b == 0.toByte()) return String(bytes.toByteArray(), Charsets.UTF_8)
            bytes += b
        }
    }

    private fun handle(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val version = input.readUnsignedByte()
            if (version != 4) {
                rejectedGreetings += version
                client.close()
                return
            }
            val command = input.readUnsignedByte()
            val port = input.readUnsignedShort()
            val ip = ByteArray(4).also { input.readFully(it) }
            val userId = readCString(input)
            val nameFollows = ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()
            val host = if (nameFollows) readCString(input) else null
            requests += Seen(version, command, port, ip.joinToString(".") { (it.toInt() and 0xFF).toString() }, userId, host)
            val out = client.getOutputStream()
            if (status != 90) {
                out.write(byteArrayOf(0, status.toByte(), 0, 0, 0, 0, 0, 0))
                out.flush()
                client.close()
                return
            }
            val address = if (host != null) InetAddress.getAllByName(host).first { it is Inet4Address } else InetAddress.getByAddress(ip)
            val target = Socket(address, port).also { sockets += it }
            out.write(byteArrayOf(0, 90, 0, 0, 0, 0, 0, 0))
            out.flush()
            thread(isDaemon = true) {
                try {
                    client.getInputStream().copyTo(target.getOutputStream())
                } catch (_: IOException) {
                }
                try {
                    target.shutdownOutput()
                } catch (_: IOException) {
                }
            }
            try {
                target.getInputStream().copyTo(out)
            } catch (_: IOException) {
            }
        } catch (_: IOException) {
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    override fun close() {
        server.close()
        sockets.forEach {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
    }
}

class ProxyRoutingTest {
    private lateinit var web: MockWebServer
    private val closeables = ArrayList<Closeable>()
    private val clients = ArrayList<OkHttpClient>()

    @Before
    fun start() {
        web = MockWebServer()
        web.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        clients.forEach {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        closeables.forEach { it.close() }
        web.shutdown()
    }

    private fun socks(status: Int = 90) = FakeSocks4Server(status).also { closeables += it }

    private fun client(proxy: String) =
        HttpClients.create(CookieJar.NO_COOKIES, "VRCX test", ProxySpec.parse(proxy)).also { clients += it }

    private fun get(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { "${it.code} ${it.body!!.string()}" }

    @Test
    fun socks4ResolvesLocallyAndSendsTheIpv4Address() {
        val proxy = socks()
        web.enqueue(MockResponse().setBody("through socks4"))
        assertEquals("200 through socks4", get(client("socks4://127.0.0.1:${proxy.port}"), "http://localhost:${web.port}/api/1/config"))
        assertEquals(listOf(FakeSocks4Server.Seen(4, 1, web.port, "127.0.0.1", "", null)), proxy.requests)
        assertEquals("the tunnel carried the request unchanged", "/api/1/config", web.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertTrue("no SOCKS5 greeting", proxy.rejectedGreetings.isEmpty())
    }

    @Test
    fun okHttpsOwnSocksProxyCannotUseAV4OnlyServer() {
        // Why SOCKS4 has its own socket factory: OkHttp's SOCKS route (java.net.Socket(Proxy) with an unresolved
        // destination) greets with SOCKS5, and the JDK cannot fall back to SOCKS4 for an unresolved address.
        val proxy = socks()
        val jdkSocks = OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxy.port)))
            .build()
            .also { clients += it }
        try {
            get(jdkSocks, "http://localhost:${web.port}/")
            fail("the JDK SOCKS client unexpectedly worked with a SOCKS4-only server")
        } catch (_: IOException) {
        }
        assertEquals(5, proxy.rejectedGreetings.firstOrNull())
        assertTrue(proxy.requests.isEmpty())
    }

    @Test
    fun socks4aSendsTheHostNameAndUserId() {
        val proxy = socks()
        web.enqueue(MockResponse().setBody("through socks4a"))
        assertEquals("200 through socks4a", get(client("socks4a://vrcx@127.0.0.1:${proxy.port}"), "http://localhost:${web.port}/x"))
        assertEquals(listOf(FakeSocks4Server.Seen(4, 1, web.port, "0.0.0.255", "vrcx", "localhost")), proxy.requests)
    }

    @Test
    fun socks4aWithAnIpLiteralSendsTheAddress() {
        val proxy = socks()
        web.enqueue(MockResponse().setBody("ok"))
        assertEquals("200 ok", get(client("socks4a://127.0.0.1:${proxy.port}"), "http://127.0.0.1:${web.port}/"))
        assertEquals("127.0.0.1", proxy.requests.single().ip)
        assertNull(proxy.requests.single().host)
    }

    @Test
    fun httpsRunsInsideTheSocks4Tunnel() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        web.useHttps(serverCertificates.sslSocketFactory(), false)
        web.enqueue(MockResponse().setBody("secure"))
        val proxy = socks()
        val client = client("socks4a://127.0.0.1:${proxy.port}").newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
            .also { clients += it }
        assertEquals("200 secure", get(client, "https://localhost:${web.port}/api/1/auth/user"))
        assertEquals("localhost", proxy.requests.single().host)
        assertEquals("/api/1/auth/user", web.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun connectionsThroughTheTunnelAreReused() {
        val proxy = socks()
        web.enqueue(MockResponse().setBody("1"))
        web.enqueue(MockResponse().setBody("2"))
        val client = client("socks4://127.0.0.1:${proxy.port}")
        assertEquals("200 1", get(client, "http://127.0.0.1:${web.port}/a"))
        assertEquals("200 2", get(client, "http://127.0.0.1:${web.port}/b"))
        assertEquals(1, proxy.requests.size)
    }

    @Test
    fun rejectedRequestsFailWithTheDotNetMessages() {
        for ((status, message) in listOf(
            91 to "SOCKS server failed to connect to the destination.",
            93 to "Failed to authenticate with the SOCKS server.",
        )) {
            val proxy = socks(status)
            try {
                get(client("socks4://127.0.0.1:${proxy.port}"), "http://127.0.0.1:${web.port}/")
                fail("expected a failure for status $status")
            } catch (e: IOException) {
                assertEquals(message, e.message)
            }
        }
        assertEquals(0, web.requestCount)
    }

    @Test
    fun webApiReportsMinusOneWhenTheProxyRefuses() {
        val proxy = socks(91)
        val engine = WebApiEngine({ client("socks4://127.0.0.1:${proxy.port}") }, FakeImages())
        val result = runBlocking { engine.execute(buildJsonObject { put("url", "http://127.0.0.1:${web.port}/"); put("method", "GET") }) }
        assertEquals(WebApiResult(-1, "SOCKS server failed to connect to the destination."), result)
    }

    @Test
    fun httpsProxyFailsEveryCallInsteadOfBypassingIt() {
        val engine = WebApiEngine({ client("https://127.0.0.1:${web.port}") }, FakeImages())
        val result = runBlocking { engine.execute(buildJsonObject { put("url", "http://127.0.0.1:${web.port}/"); put("method", "GET") }) }
        assertEquals(-1, result.status)
        assertTrue(result.message, result.message.contains("https://127.0.0.1:${web.port} is not supported"))
        assertEquals("nothing was sent, not even directly", 0, web.requestCount)
    }

    @Test
    fun httpProxyGetsAbsoluteFormRequests() {
        web.enqueue(MockResponse().setBody("proxied"))
        assertEquals("200 proxied", get(client("127.0.0.1:${web.port}"), "http://api.vrchat.invalid/api/1/config"))
        val recorded = web.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET http://api.vrchat.invalid/api/1/config HTTP/1.1", recorded.requestLine)
    }

    @Test
    fun httpProxyCredentialsAnswerA407() {
        web.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"p\""))
        web.enqueue(MockResponse().setBody("ok"))
        assertEquals("200 ok", get(client("http://us%40er:pw@127.0.0.1:${web.port}"), "http://api.vrchat.invalid/"))
        assertNull(web.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
        assertEquals(okhttp3.Credentials.basic("us@er", "pw"), web.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
    }

    // ---- protocol pieces ----

    @Test
    fun connectRequestBytes() {
        val ipv4 = Socks4.connectRequest(InetSocketAddress(InetAddress.getByName("1.2.3.4"), 443), "", remoteResolution = false)
        assertArrayEquals(byteArrayOf(4, 1, 1, 187.toByte(), 1, 2, 3, 4, 0), ipv4)

        val named = Socks4.connectRequest(
            InetSocketAddress(Socks4.unresolvedPlaceholder("api.vrchat.cloud"), 443),
            "id",
            remoteResolution = true,
        )
        assertArrayEquals(
            byteArrayOf(4, 1, 1, 187.toByte(), 0, 0, 0, 255.toByte(), 'i'.code.toByte(), 'd'.code.toByte(), 0) +
                "api.vrchat.cloud".toByteArray() + byteArrayOf(0),
            named,
        )
        val unresolved = Socks4.connectRequest(InetSocketAddress.createUnresolved("h", 80), "", remoteResolution = true)
        assertArrayEquals(byteArrayOf(4, 1, 0, 80, 0, 0, 0, 255.toByte(), 0, 'h'.code.toByte(), 0), unresolved)
    }

    @Test
    fun connectRequestRejectsWhatSocks4CannotCarry() {
        expect<SocketException>("SOCKS4 does not support IPv6 addresses.") {
            Socks4.connectRequest(InetSocketAddress(InetAddress.getByName("::1"), 80), "", remoteResolution = true)
        }
        expect<UnknownHostException>(null) {
            Socks4.connectRequest(InetSocketAddress.createUnresolved("h", 80), "", remoteResolution = false)
        }
        expect<SocketException>("Encoding the user name took more than the maximum of 255 bytes.") {
            Socks4.connectRequest(InetSocketAddress(InetAddress.getByName("1.2.3.4"), 80), "u".repeat(256), remoteResolution = false)
        }
    }

    private inline fun <reified T : Exception> expect(message: String?, block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: Exception) {
            assertTrue("got $e", e is T)
            if (message != null) assertEquals(message, e.message)
        }
    }

    private fun dns(resolver: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolver(hostname)
    }

    @Test
    fun dnsForSocks4KeepsIpv4Only() {
        val system = dns { listOf(InetAddress.getByName("::1"), InetAddress.getByName("10.0.0.1")) }
        assertEquals(listOf(InetAddress.getByName("10.0.0.1")), Socks4Dns(remoteResolution = false, system).lookup("api.vrchat.cloud"))
        val v6only = dns { listOf(InetAddress.getByName("::1")) }
        expect<UnknownHostException>(null) { Socks4Dns(remoteResolution = false, v6only).lookup("api.vrchat.cloud") }
    }

    @Test
    fun dnsForSocks4aDoesNotResolveNamesLocally() {
        val noLookups = dns { throw AssertionError("local DNS lookup for $it") }
        val address = Socks4Dns(remoteResolution = true, noLookups).lookup("api.vrchat.cloud").single()
        assertEquals("api.vrchat.cloud", InetSocketAddress(address, 443).hostString)
        // IP literals go through without DNS.
        val literal = Socks4Dns(remoteResolution = true).lookup("127.0.0.1").single()
        assertEquals("127.0.0.1", literal.hostAddress)
    }
}
