package io.github.vrcxandroid.companion

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Battery behaviour of the client (PROTOCOL.md §5.10, §5.11): the `idle` hint and its confirmation, the stretched
 * keep-alive and read timeout, the hidden reconnect backoff without discovery, and the retries that end it early.
 */
class CompanionBatteryTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val engines = CopyOnWriteArrayList<CompanionEngine>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()
    private val sink = RecordingLogSink()
    private val emitter = RecordingEmitter()
    private val file1 = "output_log_2024-01-01_09-59-00.txt"

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        closeables.forEach { it.close() }
    }

    private fun config(
        readTimeoutMs: Int = 5_000,
        idleReadTimeoutMs: Int = 90_000,
        keepAliveAfterMs: Long = 10_000,
        idleKeepAliveAfterMs: Long = 45_000,
        backoffBaseMs: Long = 50,
        backoffMaxMs: Long = 300,
        hiddenBackoffMaxMs: Long = 600_000,
        reconnectDiscoveryMs: Long = 0,
        discoveryPort: Int = 9,
        startRunning: Boolean = true,
    ) = CompanionConfig(
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs,
        idleReadTimeoutMs = idleReadTimeoutMs,
        keepAliveAfterMs = keepAliveAfterMs,
        idleKeepAliveAfterMs = idleKeepAliveAfterMs,
        backoffBaseMs = backoffBaseMs,
        backoffMaxMs = backoffMaxMs,
        hiddenBackoffMaxMs = hiddenBackoffMaxMs,
        reconnectDiscoveryMs = reconnectDiscoveryMs,
        discoveryPort = discoveryPort,
        discoveryTargets = { listOf(loopback) },
        subscribeWaitMs = 5_000,
        startRunning = startRunning,
    )

    private fun engine(store: SecureStore, config: CompanionConfig): CompanionEngine =
        CompanionEngine(store, { sink }, emitter, "Pixel Test", MulticastLockHandle.NONE, {}, config).also { engines += it }

    private fun server(alias: String = "companion", id: String = "3f2b8c1e-5d6a-4f70-9e1b-00000000c0de"): FakeCompanionServer =
        FakeCompanionServer(alias, id).also { closeables += it }

    private fun FakeCompanionServer.withLog(): FakeCompanionServer {
        files += ServerFile(file1, "0001", 638_396_207_400_000_000, 638_500_000_000_000_000, "2024.01.01 10:00:00 Log -  x\r\n")
        return this
    }

    private fun pairedStore(server: FakeCompanionServer, hosts: List<String> = listOf("127.0.0.1"), port: Int = server.port) =
        InMemorySecureStore().also { store ->
            store.put(PairingRepository.KEY_DEVICE_ID, "device-1")
            val token = PairingCrypto.randomToken()
            server.tokens["device-1"] = token
            PairingRepository(store).save(
                listOf(PairedCompanion(server.id, "TESTPC", server.fp, token, hosts, port, 1L, 1L)),
                server.id,
            )
        }

    private fun awaitSync() = waitFor(message = "sync, events ${sink.events}") { sink.events.contains("sync") }

    private fun FakeCompanionServer.ServerConn.sent(): List<String> = (seen + messages).mapNotNull { it.s("t") }

    private fun keepAlives(conn: FakeCompanionServer.ServerConn) = conn.sent().count { it == "ping" || it == "ack" }

    /** Sends a heartbeat every [everyMs] for [forMs]. */
    private fun heartbeats(conn: FakeCompanionServer.ServerConn, forMs: Long, everyMs: Long = 100) {
        val end = System.nanoTime() + forMs * 1_000_000
        while (System.nanoTime() < end) {
            conn.heartbeat()
            Thread.sleep(everyMs)
        }
    }

    // ---- idle mode -------------------------------------------------------------------------------------------------

    @Test
    fun hiddenAppTellsTheCompanionAndStretchesTheKeepAliveOnceConfirmed() {
        val server = server().withLog()
        val engine = engine(pairedStore(server), config(keepAliveAfterMs = 300, idleKeepAliveAfterMs = 2_000))
        val conn = server.nextConnection()
        awaitSync()
        // Foreground: heartbeats are answered after 300 ms without sending.
        heartbeats(conn, 700)
        waitFor(message = "foreground keep-alive") { keepAlives(conn) >= 1 }

        engine.setAppVisible(false)
        val idle = conn.awaitMessage(CompanionProtocol.T_IDLE)
        assertEquals(true, idle.b("on"))
        Thread.sleep(200) // the confirmation reaches the phone

        // Idle: the same heartbeats go unanswered for 2 s ...
        val before = keepAlives(conn)
        heartbeats(conn, 1_400)
        assertEquals("phone messages ${conn.sent()}", before, keepAlives(conn))
        // ... and then get their answer.
        heartbeats(conn, 1_000)
        waitFor(message = "idle keep-alive") { keepAlives(conn) > before }

        // Back in the foreground: told at once, and answering every 300 ms again after the confirmation.
        engine.setAppVisible(true)
        val active = conn.awaitMessage(CompanionProtocol.T_IDLE)
        assertEquals(false, active.b("on"))
        Thread.sleep(200)
        val resumed = keepAlives(conn)
        heartbeats(conn, 900)
        waitFor(message = "foreground keep-alive again") { keepAlives(conn) >= resumed + 2 }
        assertFalse(conn.closed)
        assertEquals(1, server.accepted.get())
    }

    @Test
    fun companionThatIgnoresIdleKeepsTheNormalTimings() {
        val server = server().withLog()
        server.echoIdle = false
        val engine = engine(pairedStore(server), config(keepAliveAfterMs = 300, idleKeepAliveAfterMs = 60_000))
        val conn = server.nextConnection()
        awaitSync()
        engine.setAppVisible(false)
        conn.awaitMessage(CompanionProtocol.T_IDLE)
        val before = keepAlives(conn)
        heartbeats(conn, 1_000)
        waitFor(message = "normal keep-alive without a confirmation") { keepAlives(conn) > before }
    }

    @Test
    fun sessionStartedWhileHiddenSendsIdleAfterAuthentication() {
        val server = server().withLog()
        val engine = engine(pairedStore(server), config(startRunning = false))
        engine.setAppVisible(false)
        engine.setRunning(true)
        val conn = server.nextConnection()
        assertEquals(true, conn.awaitMessage(CompanionProtocol.T_IDLE).b("on"))
        awaitSync()
        // Visibility changes without a change send nothing more.
        engine.setAppVisible(false)
        Thread.sleep(200)
        assertEquals(1, conn.sent().count { it == CompanionProtocol.T_IDLE })
    }

    @Test
    fun confirmedIdleUsesTheLongReadTimeout() {
        val server = server().withLog()
        val engine = engine(
            pairedStore(server),
            config(readTimeoutMs = 1_000, idleReadTimeoutMs = 3_000, keepAliveAfterMs = 60_000, idleKeepAliveAfterMs = 60_000),
        )
        val conn = server.nextConnection()
        awaitSync()
        engine.setAppVisible(false)
        conn.awaitMessage(CompanionProtocol.T_IDLE)

        // The companion is silent for longer than the normal read timeout: the phone stays.
        Thread.sleep(2_000)
        assertFalse(conn.closed)
        assertEquals(1, server.accepted.get())
        // After the idle read timeout it gives up and reconnects.
        waitFor(timeoutMs = 5_000, message = "the idle read timeout") { server.accepted.get() >= 2 }
    }

    @Test
    fun processMessagesAlsoSampleTheClockSkew() {
        val server = server().withLog()
        engine(pairedStore(server), config())
        val conn = server.nextConnection()
        awaitSync()
        server.pcClockOffsetMs = 9_000
        conn.sendControl(server.processMessage())
        waitFor(message = "skew from process, skews ${sink.skews}") { sink.skews.last() >= 8_500 }
        assertTrue("skew ${sink.skews}", sink.skews.last() in 8_500..9_100)
    }

    // ---- reconnects while hidden -----------------------------------------------------------------------------------

    /** A TCP port that accepts and closes at once (no TLS): every connection attempt fails, and is counted. */
    private fun countingDeadPort(): Pair<ServerSocket, AtomicInteger> {
        val socket = ServerSocket(0, 50, loopback)
        val attempts = AtomicInteger()
        Thread({
            while (!socket.isClosed) {
                try {
                    socket.accept().close()
                    attempts.incrementAndGet()
                } catch (e: IOException) {
                    return@Thread
                }
            }
        }, "dead-port").apply { isDaemon = true; start() }
        closeables += socket
        return socket to attempts
    }

    @Test
    fun hiddenAppBacksOffLongerAndSkipsDiscovery() {
        val server = server()
        val (dead, attempts) = countingDeadPort()
        val responder = FakeDiscoveryResponder({ discoveryReply("someone-else", "fp", 1) }).also { closeables += it }
        val engine = engine(
            pairedStore(server, port = dead.localPort),
            config(
                backoffBaseMs = 200,
                backoffMaxMs = 200,
                hiddenBackoffMaxMs = 60_000,
                reconnectDiscoveryMs = 200,
                discoveryPort = responder.port,
                startRunning = false,
            ),
        )
        engine.setAppVisible(false)
        engine.setRunning(true)
        Thread.sleep(3_000)
        // 200, 400, 800, 1600 ms: at most about five attempts, and no broadcast.
        val hidden = attempts.get()
        assertTrue("attempts while hidden: $hidden", hidden in 1..6)
        assertEquals(emptyList<String>(), responder.requests.toList())

        // Visible: retried at once, then every 200 ms, with discovery.
        engine.setAppVisible(true)
        waitFor(timeoutMs = 3_000, message = "visible retries, attempts ${attempts.get()}") { attempts.get() >= hidden + 4 }
        waitFor(message = "discovery while visible") { responder.requests.isNotEmpty() }
    }

    @Test
    fun wifiAvailableAndBecomingVisibleEndTheHiddenBackoff() {
        val server = server()
        val (dead, attempts) = countingDeadPort()
        val engine = engine(
            pairedStore(server, port = dead.localPort),
            config(backoffBaseMs = 60_000, backoffMaxMs = 60_000, hiddenBackoffMaxMs = 600_000, startRunning = false),
        )
        engine.setAppVisible(false)
        engine.setRunning(true)
        waitFor(message = "first attempt") { attempts.get() == 1 }
        Thread.sleep(300)
        assertEquals(1, attempts.get()) // now waiting at least a minute

        engine.onNetworkAvailable()
        waitFor(timeoutMs = 3_000, message = "retry on Wi-Fi") { attempts.get() == 2 }

        engine.setAppVisible(true)
        waitFor(timeoutMs = 3_000, message = "retry when visible") { attempts.get() == 3 }

        // Hiding again does not start an attempt, and neither does Wi-Fi while connected (covered elsewhere).
        engine.setAppVisible(false)
        Thread.sleep(300)
        assertEquals(3, attempts.get())
    }

    @Test
    fun wifiAvailableLeavesAWorkingConnectionAlone() {
        val server = server().withLog()
        val engine = engine(pairedStore(server), config())
        val conn = server.nextConnection()
        awaitSync()
        engine.onNetworkAvailable()
        Thread.sleep(400)
        assertFalse(conn.closed)
        assertEquals(1, server.accepted.get())
    }

    // ---- pairing identity ------------------------------------------------------------------------------------------

    private fun pairingError(block: suspend () -> Unit): String {
        try {
            runBlocking { block() }
        } catch (e: PairingException) {
            return e.code
        }
        fail("expected PairingException")
        return ""
    }

    @Test
    fun aKnownCompanionIdWithAnotherKeyIsRefusedBeforeAnyProof() {
        val real = server()
        val store = pairedStore(real)
        val before = PairingRepository(store).load().records.single()
        val impostor = server(alias = "impostor", id = real.id).apply { pairingCode = "ABCDE12345" }
        val engine = engine(store, config(startRunning = false))

        // Manual pairing captures whatever certificate answers; a QR or discovery reply can name any key.
        val manual = buildJsonObject {
            put("host", "127.0.0.1")
            put("port", impostor.port)
        }
        assertEquals(PairingException.FINGERPRINT, pairingError { engine.pair(manual, "ABCDE12345") })
        val claimed = buildJsonObject {
            put("host", "127.0.0.1")
            put("port", impostor.port)
            put("fp", impostor.fp)
            put("id", real.id)
        }
        assertEquals(PairingException.FINGERPRINT, pairingError { engine.pair(claimed, "ABCDE12345") })

        assertTrue("proofs sent: ${impostor.pairRequests}", impostor.pairRequests.isEmpty())
        assertEquals(before, PairingRepository(store).load().records.single())
    }

    @Test
    fun theSameCompanionCanBePairedAgain() {
        val server = server().withLog().apply { pairingCode = "ABCDE12345" }
        val store = pairedStore(server)
        val engine = engine(store, config(startRunning = false))
        val target = buildJsonObject {
            put("host", "127.0.0.1")
            put("port", server.port)
        }
        val state: JsonObject = runBlocking { engine.pair(target, "ABCDE12345") }
        assertEquals(server.id, state.s("activeId"))
        assertEquals(server.fp, PairingRepository(store).load().records.single().fp)
    }
}
