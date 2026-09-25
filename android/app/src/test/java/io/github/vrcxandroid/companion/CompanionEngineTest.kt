package io.github.vrcxandroid.companion

import io.github.vrcxandroid.logwatcher.LogSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class CompanionEngineTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val engines = CopyOnWriteArrayList<CompanionEngine>()
    private val closeables = CopyOnWriteArrayList<AutoCloseable>()
    private val sink = RecordingLogSink()
    private val emitter = RecordingEmitter()

    private val since = 638_400_000_000_000_000L
    private val file1 = "output_log_2024-01-01_09-59-00.txt"
    private val file2 = "output_log_2024-01-02_10-00-00.txt"
    private val oldFile = "output_log_2023-01-01_00-00-00.txt"

    @After
    fun tearDown() {
        engines.forEach { it.close() }
        closeables.forEach { it.close() }
    }

    private fun config(
        subscribeWaitMs: Long = 5_000,
        backoffBaseMs: Long = 50,
        backoffMaxMs: Long = 300,
        reconnectDiscoveryMs: Long = 0,
        discoveryPort: Int = 9,
        heartbeatReplyAfterMs: Long = 10_000,
        pingIdleMs: Long = 30_000,
        ackEveryBytes: Long = 512L * 1024,
    ) = CompanionConfig(
        connectTimeoutMs = 2_000,
        readTimeoutMs = 5_000,
        backoffBaseMs = backoffBaseMs,
        backoffMaxMs = backoffMaxMs,
        reconnectDiscoveryMs = reconnectDiscoveryMs,
        discoveryPort = discoveryPort,
        discoveryTargets = { listOf(loopback) },
        subscribeWaitMs = subscribeWaitMs,
        heartbeatReplyAfterMs = heartbeatReplyAfterMs,
        pingIdleMs = pingIdleMs,
        ackEveryBytes = ackEveryBytes,
    )

    private fun engine(
        store: SecureStore = InMemorySecureStore(),
        config: CompanionConfig = config(),
        logSink: LogSink = sink,
        cleaner: (String) -> Unit = {},
    ): CompanionEngine = CompanionEngine(store, { logSink }, emitter, "Pixel Test", MulticastLockHandle.NONE, cleaner, config)
        .also { engines += it }

    private fun server(alias: String = "companion"): FakeCompanionServer =
        FakeCompanionServer(alias).also { closeables += it }

    private fun logText(lines: Int, tag: String = "") = buildString {
        for (i in 1..lines) {
            append("2024.01.01 10:%02d:%02d Log        -  [Behaviour] line %s%d\r\n".format(i / 60 % 60, i % 60, tag, i))
        }
    }

    private fun FakeCompanionServer.withLogs(): FakeCompanionServer {
        files += ServerFile(file1, "0001", 638_396_207_400_000_000, 638_500_000_000_000_000, logText(300, "a"))
        files += ServerFile(file2, "0002", 638_397_072_000_000_000, 638_500_000_000_000_001, logText(40, "b"))
        files += ServerFile(oldFile, "0000", 638_080_000_000_000_000, 638_080_000_000_000_000, "old\n")
        return this
    }

    private fun pairedStore(server: FakeCompanionServer, deviceId: String = "device-1", hosts: List<String> = listOf("127.0.0.1")): InMemorySecureStore {
        val store = InMemorySecureStore()
        store.put(PairingRepository.KEY_DEVICE_ID, deviceId)
        val token = PairingCrypto.randomToken()
        server.tokens[deviceId] = token
        PairingRepository(store).save(
            listOf(PairedCompanion(server.id, "TESTPC", server.fp, token, hosts, server.port, 1L, 1L)),
            server.id,
        )
        return store
    }

    private fun target(server: FakeCompanionServer, fp: String? = server.fp, id: String? = null) = buildJsonObject {
        put("host", "127.0.0.1")
        put("port", server.port)
        if (fp != null) put("fp", fp)
        if (id != null) put("id", id)
    }

    private fun pairingError(block: suspend () -> Unit): String {
        try {
            runBlocking { block() }
        } catch (e: PairingException) {
            return e.code
        }
        fail("expected PairingException")
        return ""
    }

    private fun awaitSyncCount(n: Int) = waitFor(message = "$n sync(s), events ${sink.events}") {
        sink.events.count { it == "sync" } >= n
    }

    private fun awaitStatus(engine: CompanionEngine, status: String) =
        waitFor(message = "status $status, state ${engine.state()}") { engine.state().s("status") == status }

    private fun assertMirrorMatches(server: FakeCompanionServer, vararg names: String) {
        for (name in names) {
            val f = server.files.first { it.name == name }
            assertEquals(name, String(f.bytes), sink.content(name))
        }
    }

    // ---- pairing -----------------------------------------------------------------------------------------------

    @Test
    fun pairsWithCodeThenAuthenticatesAndSyncsInWireOrder() {
        val server = server().withLogs()
        server.pairingCode = "ABCDE12345"
        val engine = engine()

        val paired = runBlocking { engine.pair(target(server), "abcde-12345") }
        assertEquals(server.id, paired.s("activeId"))
        val record = (paired["paired"] as JsonArray).single() as JsonObject
        assertEquals(server.fp, record.s("fp"))
        assertEquals("TESTPC", record.s("name"))
        assertEquals(listOf(JsonPrimitive("127.0.0.1")), (record["hosts"] as JsonArray).toList())
        assertFalse(record.containsKey("token"))
        assertEquals(1, server.pairRequests.size)
        assertEquals("Pixel Test", server.pairRequests.single().s("deviceName"))

        val conn = server.nextConnection()
        val subscribe = conn.awaitMessage("subscribe")
        assertEquals(since.toString(), (subscribe["sinceUtcTicks"] as JsonPrimitive).content)
        assertEquals(0, (subscribe["have"] as JsonArray).size)
        awaitSyncCount(1)

        // Wire order: session, snapshot, process, data (oldest file first), syncComplete.
        val events = sink.events.toList()
        assertEquals("session:${server.id}:TESTPC:Europe/Berlin", events.first())
        assertTrue(events[1].startsWith("snapshot:"))
        assertTrue(events[1].contains("$oldFile#0000=4"))
        assertEquals("process:true,false", events[2])
        val data = events.filter { it.startsWith("data:") }
        val firstFile2 = data.indexOfFirst { it.startsWith("data:$file2") }
        assertTrue(data.take(firstFile2).all { it.startsWith("data:$file1") })
        assertTrue(data.none { it.startsWith("data:$oldFile") })
        assertEquals("sync", events.last())
        assertEquals(emptyList<String>(), sink.errors)
        assertMirrorMatches(server, file1, file2)
        assertEquals(setOf("companion-sink"), sink.threads.toSet())

        // One ack after syncComplete covering every data-frame byte (length prefix included).
        val ack = conn.awaitMessage("ack")
        assertEquals(conn.sentDataBytes.get().toString(), (ack["bytes"] as JsonPrimitive).content)

        awaitStatus(engine, "connected")
        val state = engine.state()
        assertEquals(
            setOf("status", "activeId", "paired", "machineName", "tz", "vrchatRunning", "steamVrRunning", "syncing", "lastError"),
            state.keys,
        )
        assertEquals("TESTPC", state.s("machineName"))
        assertEquals(true, state.b("vrchatRunning"))
        assertEquals(false, state.b("steamVrRunning"))
        assertEquals(false, state.b("syncing"))
        assertEquals(JsonNull, state["lastError"])
        assertEquals("Europe/Berlin", (state["tz"] as JsonObject).s("ianaId"))
        assertTrue(emitter.states.any { it.s("status") == "connected" })
        assertTrue(emitter.states.none { it.toString().contains(server.tokens.values.first()) })
    }

    @Test
    fun wrongCodeIsRejectedByTheCompanion() {
        val server = server()
        server.pairingCode = "ABCDE12345"
        val engine = engine()
        assertEquals(PairingException.CODE, pairingError { engine.pair(target(server), "ABCDE-12346") })
        assertEquals(1, server.failedPairAttempts.get())
        assertEquals("unpaired", engine.state().s("status"))
    }

    @Test
    fun malformedCodeIsRejectedWithoutConnecting() {
        val server = server()
        server.pairingCode = "ABCDE12345"
        val engine = engine()
        assertEquals(PairingException.CODE, pairingError { engine.pair(target(server), "ABC") })
        assertEquals(PairingException.CODE, pairingError { engine.pair(target(server), "ABCDU-12345") })
        assertEquals(0, server.accepted.get())
    }

    @Test
    fun closedPairingWindowSendsNoProof() {
        val server = server()
        val engine = engine()
        assertEquals(PairingException.CLOSED, pairingError { engine.pair(target(server), "ABCDE12345") })
        assertTrue(server.pairRequests.isEmpty())
    }

    @Test
    fun fingerprintMismatchAbortsTheHandshake() {
        val impostor = server("impostor")
        impostor.pairingCode = "ABCDE12345"
        val engine = engine()
        val pinned = TestKeys.fingerprint("companion")
        assertEquals(PairingException.FINGERPRINT, pairingError { engine.pair(target(impostor, fp = pinned), "ABCDE12345") })
        assertTrue(impostor.pairRequests.isEmpty())
        assertEquals("unpaired", engine.state().s("status"))
    }

    @Test
    fun capturedFingerprintIsStoredForManualPairing() {
        val server = server()
        server.pairingCode = "ABCDE12345"
        val engine = engine()
        val state = runBlocking { engine.pair(target(server, fp = null), "ABCDE12345") }
        val record = (state["paired"] as JsonArray).single() as JsonObject
        assertEquals(server.fp, record.s("fp"))
        server.nextConnection() // the stored pin works for the authenticated reconnect
    }

    @Test
    fun relayWithAnotherCertificateCannotPair() {
        // The companion computes the proof with its own fingerprint, which differs from the one the phone saw.
        val server = server()
        server.pairingCode = "ABCDE12345"
        server.proofFpOverride = TestKeys.fingerprint("impostor")
        val engine = engine()
        assertEquals(PairingException.CODE, pairingError { engine.pair(target(server, fp = null), "ABCDE12345") })
    }

    @Test
    fun unexpectedCompanionIdIsRejected() {
        val server = server()
        server.pairingCode = "ABCDE12345"
        val engine = engine()
        assertEquals(
            PairingException.FINGERPRINT,
            pairingError { engine.pair(target(server, id = "another-companion"), "ABCDE12345") },
        )
    }

    @Test
    fun nonLocalTargetsAreRefusedBeforeConnecting() {
        val engine = engine()
        val public = buildJsonObject {
            put("host", "8.8.8.8")
            put("port", 49460)
        }
        assertEquals(PairingException.NOT_LOCAL, pairingError { engine.pair(public, "ABCDE12345") })
        assertEquals(
            PairingException.NOT_LOCAL,
            pairingError { engine.pairWithQr("vrcxc://pair?v=1&id=abc&h=203.0.113.9&p=49460&fp=x&c=ABCDE12345") },
        )
        assertEquals(PairingException.INVALID_QR, pairingError { engine.pairWithQr("https://example.com") })
    }

    @Test
    fun pairsFromQrPayload() {
        val server = server().withLogs()
        server.pairingCode = "ABCDE12345"
        val engine = engine()
        val qr = "vrcxc://pair?v=1&id=${server.id}&n=TESTPC&h=127.0.0.2,127.0.0.1&p=${server.port}" +
            "&fp=${server.fp}&c=abcde-12345"
        val state = runBlocking { engine.pairWithQr(qr) }
        val record = (state["paired"] as JsonArray).single() as JsonObject
        // The address that worked comes first; the other listed one is kept for later.
        assertEquals(listOf("127.0.0.1", "127.0.0.2"), (record["hosts"] as JsonArray).map { (it as JsonPrimitive).content })
        server.nextConnection()
        awaitSyncCount(1)
        assertMirrorMatches(server, file1, file2)
    }

    // ---- authenticated sessions --------------------------------------------------------------------------------

    @Test
    fun subscribesWithHaveOffsetsAndResumes() {
        val server = server().withLogs()
        val content1 = server.files.first { it.name == file1 }.bytes
        sink.preload(file1, "0001", content1.copyOf(100))
        sink.preload("output_log_gone.txt", "0099", "x".toByteArray())
        engine(pairedStore(server))

        val conn = server.nextConnection()
        val subscribe = conn.awaitMessage("subscribe")
        val have = (subscribe["have"] as JsonArray).map { it as JsonObject }
        assertEquals(
            listOf("$file1/0001/100", "output_log_gone.txt/0099/1"),
            have.map { "${it.s("name")}/${it.s("fileId")}/${(it["length"] as JsonPrimitive).content}" },
        )
        awaitSyncCount(1)
        assertEquals("data:$file1@100+4096", sink.dataEvents().first())
        assertNull(sink.mirror["output_log_gone.txt"])
        assertEquals(emptyList<String>(), sink.errors)
        assertMirrorMatches(server, file1, file2)
    }

    @Test
    fun liveTruncateDataAndHeartbeatSkew() {
        val server = server().withLogs()
        server.pcClockOffsetMs = 5_000
        engine(pairedStore(server))
        val conn = server.nextConnection()
        awaitSyncCount(1)
        waitFor(message = "skew from info") { sink.skews.isNotEmpty() }
        assertTrue("skew ${sink.skews}", sink.skews.last() in 4_500..5_100)

        conn.sendControl(control(CompanionProtocol.T_TRUNCATE) {
            put("name", file1)
            put("fileId", "0001")
            put("newLength", 10)
        })
        conn.sendData(file1, "0001", 10, "new tail\n".toByteArray())
        conn.sendControl(buildJsonObject { put("t", "futureMessage") }) // unknown types are ignored
        conn.heartbeat(System.currentTimeMillis() + 7_000)
        waitFor(message = "live data") { sink.events.contains("data:$file1@10+9") }
        val tail = sink.events.dropWhile { it != "sync" }
        assertEquals(listOf("sync", "truncate:$file1:10", "data:$file1@10+9"), tail)
        assertEquals(String(server.files.first { it.name == file1 }.bytes.copyOf(10)) + "new tail\n", sink.content(file1))
        waitFor(message = "skew from heartbeat") { sink.skews.last() >= 6_500 }
        assertTrue("skew ${sink.skews}", sink.skews.last() in 6_500..7_100)

        conn.sendControl(control(CompanionProtocol.T_PROCESS) {
            put("vrchatRunning", false)
            put("steamVrRunning", true)
            put("pcUtcNowMs", 1)
        })
        waitFor(message = "process in state") { engines.first().state().b("steamVrRunning") == true }
        assertEquals(false, engines.first().state().b("vrchatRunning"))
    }

    @Test
    fun acksAtLeastEveryThreshold() {
        val server = server()
        server.files += ServerFile(file1, "0001", 1, since + 1, logText(4000))
        server.chunkSize = 16 * 1024
        engine(pairedStore(server), config(ackEveryBytes = 20_000))
        val conn = server.nextConnection()
        awaitSyncCount(1)
        waitFor(message = "final ack") {
            conn.messages.any { it.s("t") == "ack" && (it["bytes"] as JsonPrimitive).content == conn.sentDataBytes.get().toString() }
        }
        val acks = conn.messages.filter { it.s("t") == "ack" }.map { (it["bytes"] as JsonPrimitive).content.toLong() }
        assertTrue("acks $acks", acks.size >= 3)
        var previous = 0L
        for (a in acks) {
            assertTrue(a > previous)
            assertTrue("gap ${a - previous}", a - previous <= 20_000 + 16 * 1024 + 256)
            previous = a
        }
        assertMirrorMatches(server, file1)
    }

    @Test
    fun answersHeartbeatsAndPingsWhenIdle() {
        val server = server().withLogs()
        engine(pairedStore(server), config(heartbeatReplyAfterMs = 0, pingIdleMs = 400))
        val conn = server.nextConnection()
        awaitSyncCount(1)
        conn.awaitMessage("ack")
        conn.heartbeat()
        conn.awaitMessage("ping", 3_000)
        // Without any traffic the idle timer sends another ping.
        conn.awaitMessage("ping", 3_000)
    }

    @Test
    fun reconnectsAfterTheServerDropsTheConnection() {
        val server = server().withLogs()
        val engine = engine(pairedStore(server))
        val first = server.nextConnection()
        awaitSyncCount(1)
        val before = sink.content(file1)!!.length

        first.close()
        server.files.first { it.name == file1 }.append("after reconnect\n")

        val second = server.nextConnection()
        val subscribe = second.awaitMessage("subscribe")
        val have = (subscribe["have"] as JsonArray).map { it as JsonObject }.associate {
            it.s("name") to (it["length"] as JsonPrimitive).content.toLong()
        }
        assertEquals(before.toLong(), have[file1])
        awaitSyncCount(2)
        val afterFirstSync = sink.events.dropWhile { it != "sync" }.drop(1)
        assertEquals("disconnected", afterFirstSync.first())
        assertTrue(afterFirstSync[1].startsWith("session:${server.id}"))
        assertEquals(listOf("data:$file1@$before+16"), afterFirstSync.filter { it.startsWith("data:") })
        assertEquals(emptyList<String>(), sink.errors)
        assertMirrorMatches(server, file1, file2)
        awaitStatus(engine, "connected")
    }

    @Test
    fun gapIsRepairedWithFetchAndOverlapIsTrimmed() {
        val server = server().withLogs()
        engine(pairedStore(server))
        val conn = server.nextConnection()
        awaitSyncCount(1)
        val f = server.files.first { it.name == file1 }
        val length = f.bytes.size.toLong()

        // Overlap: the first 5 bytes are already mirrored.
        f.append("0123456789")
        conn.sendData(f.name, f.fileId, length - 5, f.bytes.copyOfRange((length - 5).toInt(), (length + 5).toInt()), compress = false)
        waitFor(message = "trimmed overlap") { sink.events.contains("data:$file1@$length+5") }

        // Gap: bytes length+5 until length+20 never arrive; the phone asks for them and ignores the frame after the gap.
        f.append("abcdefghij")
        f.append("KLMNOPQRST")
        conn.sendData(f.name, f.fileId, length + 20, "KLMNOPQRST".toByteArray(), compress = false)
        val fetch = conn.awaitMessage("fetch")
        assertEquals(file1, fetch.s("name"))
        assertEquals("0001", fetch.s("fileId"))
        assertEquals((length + 5).toString(), (fetch["fromOffset"] as JsonPrimitive).content)
        conn.sendRange(f, length + 5)
        waitFor(message = "fetch answer") { sink.content(file1) == String(f.bytes) }
        assertTrue(sink.events.none { it == "data:$file1@${length + 20}+10" })
        assertEquals(emptyList<String>(), sink.errors)
    }

    @Test
    fun waitsForTillDateAndResubscribesWhenItChanges() {
        val server = server().withLogs()
        sink.since = 0
        val engine = engine(pairedStore(server), config(subscribeWaitMs = 20_000))
        val conn = server.nextConnection()
        waitFor(message = "session") { sink.events.any { it.startsWith("session:") } }
        Thread.sleep(300)
        assertTrue(conn.messages.none { it.s("t") == "subscribe" })

        engine.onTillDateChanged(since)
        val first = conn.awaitMessage("subscribe")
        assertEquals(since.toString(), (first["sinceUtcTicks"] as JsonPrimitive).content)
        awaitSyncCount(1)

        engine.onTillDateChanged(since + 1)
        val second = conn.awaitMessage("subscribe")
        assertEquals((since + 1).toString(), (second["sinceUtcTicks"] as JsonPrimitive).content)
        assertEquals(2, (second["have"] as JsonArray).size)
        awaitSyncCount(2)
        // Nothing is sent twice after the restart.
        assertEquals(emptyList<String>(), sink.errors)
        assertMirrorMatches(server, file1, file2)
    }

    @Test
    fun subscribesWithZeroAfterWaitingForTillDate() {
        val server = server().withLogs()
        sink.since = 0
        engine(pairedStore(server), config(subscribeWaitMs = 300))
        val conn = server.nextConnection()
        val subscribe = conn.awaitMessage("subscribe")
        assertEquals("0", (subscribe["sinceUtcTicks"] as JsonPrimitive).content)
        awaitSyncCount(1)
        assertTrue(sink.dataEvents().any { it.startsWith("data:$oldFile") })
    }

    @Test
    fun authFailMarksThePairingRevokedAndStopsRetrying() {
        val server = server()
        val store = pairedStore(server)
        val token = server.tokens.remove("device-1")!!
        val engine = engine(store)
        awaitStatus(engine, "error")
        assertEquals(CompanionEngine.REVOKED, engine.state().s("lastError"))
        Thread.sleep(500)
        assertEquals(1, server.accepted.get())

        server.tokens["device-1"] = token
        engine.setActive(server.id)
        server.nextConnection()
        awaitStatus(engine, "connected")
        assertEquals(JsonNull, engine.state()["lastError"])
    }

    @Test
    fun protocolVersionMismatchIsReported() {
        val server = server()
        server.helloVersion = 2
        val engine = engine(pairedStore(server))
        waitFor(message = "version error") { engine.state().s("lastError") == PairingException.VERSION }
        assertEquals("error", engine.state().s("status"))
    }

    @Test
    fun setRunningFalseClosesAndStops() {
        val server = server().withLogs()
        val engine = engine(pairedStore(server))
        val conn = server.nextConnection()
        awaitStatus(engine, "connected")

        engine.setRunning(false)
        assertEquals("idle", engine.state().s("status"))
        waitFor(message = "server sees the close") { conn.closed }
        waitFor(message = "disconnected") { sink.events.contains("disconnected") }
        val accepted = server.accepted.get()
        Thread.sleep(400)
        assertEquals(accepted, server.accepted.get())

        engine.setRunning(true)
        server.nextConnection()
        awaitStatus(engine, "connected")
    }

    @Test
    fun networkChangeReconnectsImmediately() {
        val server = server().withLogs()
        val engine = engine(pairedStore(server), config(backoffBaseMs = 20_000, backoffMaxMs = 20_000))
        server.nextConnection()
        awaitSyncCount(1)
        engine.onNetworkChanged()
        val start = System.nanoTime()
        server.nextConnection(5_000)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000)
        awaitSyncCount(2)
    }

    @Test
    fun forgetRemovesTheRecordAfterDisconnectingAndCleansTheMirror() {
        val server = server().withLogs()
        val order = CopyOnWriteArrayList<String>()
        sink.onEvent = { order += it }
        val store = pairedStore(server)
        val engine = engine(store, cleaner = { id -> order += "cleanup:$id" })
        server.nextConnection()
        awaitSyncCount(1)

        engine.forget(server.id)
        assertEquals("unpaired", engine.state().s("status"))
        waitFor(message = "cleanup") { order.contains("cleanup:${server.id}") }
        assertTrue(order.indexOf("disconnected") in 0 until order.indexOf("cleanup:${server.id}"))
        assertTrue(PairingRepository(store).load().records.isEmpty())
        assertEquals(JsonNull, engine.state()["activeId"])
    }

    @Test
    fun setActiveSwitchesCompanions() {
        val a = server().withLogs()
        val b = FakeCompanionServer(id = "b0b0b0b0-0000-4000-8000-000000000002", machine = "OTHERPC").also { closeables += it }
        val store = pairedStore(a)
        val tokenB = PairingCrypto.randomToken()
        b.tokens["device-1"] = tokenB
        val repo = PairingRepository(store)
        val loaded = repo.load()
        repo.save(
            loaded.records + PairedCompanion(b.id, "OTHERPC", b.fp, tokenB, listOf("127.0.0.1"), b.port, 2L, 0L),
            a.id,
        )
        val engine = engine(store)
        a.nextConnection()
        awaitStatus(engine, "connected")
        waitFor(message = "info") { sink.events.any { it.startsWith("session:${a.id}") } }
        assertEquals("TESTPC", engine.state().s("machineName"))

        engine.setActive(b.id)
        b.nextConnection()
        waitFor(message = "other companion") { engine.state().s("machineName") == "OTHERPC" }
        assertEquals(b.id, engine.state().s("activeId"))
        assertTrue(sink.events.contains("session:${b.id}:OTHERPC:Europe/Berlin"))
        val disconnectIndex = sink.events.indexOf("disconnected")
        assertTrue(disconnectIndex in 0 until sink.events.indexOf("session:${b.id}:OTHERPC:Europe/Berlin"))
    }

    @Test
    fun rediscoversTheCompanionWhenStoredHostsFail() {
        val server = server().withLogs()
        val responder = FakeDiscoveryResponder({
            buildJsonObject {
                put("t", "vrcx-companion")
                put("v", 1)
                put("id", server.id)
                put("name", "TESTPC")
                put("port", server.port)
                put("fp", server.fp)
                put("pairing", false)
            }
        }).also { closeables += it }
        val store = pairedStore(server, hosts = listOf("127.0.0.2"))
        val engine = engine(store, config(reconnectDiscoveryMs = 1_500, discoveryPort = responder.port))
        server.nextConnection(15_000)
        awaitStatus(engine, "connected")
        val hosts = PairingRepository(store).load().records.single().hosts
        assertEquals("127.0.0.1", hosts.first())
        assertTrue(hosts.contains("127.0.0.2"))
    }

    @Test
    fun discoverReturnsCompanionsAndRefreshesKnownHosts() {
        val server = server()
        val responder = FakeDiscoveryResponder({
            buildJsonObject {
                put("t", "vrcx-companion")
                put("v", 1)
                put("id", server.id)
                put("name", "TESTPC")
                put("port", server.port)
                put("fp", server.fp)
                put("pairing", true)
            }
        }).also { closeables += it }
        val store = pairedStore(server, hosts = listOf("192.168.77.1"))
        val engine = engine(store, config(discoveryPort = responder.port).copy(startRunning = false))
        val found = runBlocking { engine.discover(500) }
        val entry = found.single() as JsonObject
        assertEquals(server.id, entry.s("id"))
        assertEquals("127.0.0.1", entry.s("host"))
        assertEquals(true, entry.b("pairing"))
        assertEquals(listOf("127.0.0.1", "192.168.77.1"), PairingRepository(store).load().records.single().hosts)
    }
}
