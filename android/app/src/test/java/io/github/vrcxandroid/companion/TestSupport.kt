package io.github.vrcxandroid.companion

import io.github.vrcxandroid.EventEmitter
import io.github.vrcxandroid.logwatcher.CompanionInfo
import io.github.vrcxandroid.logwatcher.LogSink
import io.github.vrcxandroid.logwatcher.MirroredFile
import io.github.vrcxandroid.logwatcher.PcFileMeta
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Test identities from src/test/resources/companion/test-companion.p12 (aliases `companion` and `impostor`). */
object TestKeys {
    private val password = "changeit".toCharArray()

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("PKCS12").apply {
            val stream = TestKeys::class.java.getResourceAsStream("/companion/test-companion.p12")
                ?: error("test keystore missing")
            stream.use { load(it, password) }
        }
    }

    fun certificate(alias: String): X509Certificate = keyStore.getCertificate(alias) as X509Certificate

    fun fingerprint(alias: String): String = PairingCrypto.fingerprint(certificate(alias))

    /** Server SSLContext presenting only [alias]. */
    fun serverContext(alias: String): SSLContext {
        val single = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        single.setKeyEntry(alias, keyStore.getKey(alias, password) as PrivateKey, password, keyStore.getCertificateChain(alias))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(single, password)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}

/** Polls [condition] until it holds or [timeoutMs] passes. */
fun waitFor(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $message")
        Thread.sleep(10)
    }
}

/** A [LogSink] that records every call and keeps an in-memory byte mirror. */
class RecordingLogSink(@Volatile var since: Long = 638_400_000_000_000_000L) : LogSink {
    class MirrorFile(val fileId: String, val bytes: ByteArrayOutputStream = ByteArrayOutputStream())

    val events = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArraySet<String>()
    val skews = CopyOnWriteArrayList<Long>()
    val errors = CopyOnWriteArrayList<String>()
    val mirror = ConcurrentHashMap<String, MirrorFile>()

    /** Optional hook so tests can observe ordering against other callbacks. */
    var onEvent: ((String) -> Unit)? = null

    private fun record(event: String) {
        threads += Thread.currentThread().name
        events += event
        onEvent?.invoke(event)
    }

    fun preload(name: String, fileId: String, bytes: ByteArray) {
        mirror[name] = MirrorFile(fileId).also { it.bytes.write(bytes) }
    }

    fun content(name: String): String? = mirror[name]?.bytes?.toByteArray()?.toString(Charsets.UTF_8)

    fun dataEvents(): List<String> = events.filter { it.startsWith("data:") }

    override fun onSessionStarted(companionId: String, info: CompanionInfo) =
        record("session:$companionId:${info.machineName}:${info.tzIanaId}")

    override fun onInfo(info: CompanionInfo) = record("info:${info.machineName}")

    override fun have(): List<MirroredFile> {
        threads += Thread.currentThread().name
        return mirror.map { (name, f) -> MirroredFile(name, f.fileId, f.bytes.size().toLong()) }.sortedBy { it.name }
    }

    override fun sinceUtcTicks(): Long = since

    override fun onSnapshot(files: List<PcFileMeta>) {
        record("snapshot:" + files.joinToString(",") { "${it.name}#${it.fileId}=${it.length}" })
        val keep = files.associate { it.name to it.fileId }
        mirror.entries.removeIf { (name, f) -> keep[name] != f.fileId }
    }

    override fun onData(name: String, fileId: String, offset: Long, bytes: ByteArray) {
        record("data:$name@$offset+${bytes.size}")
        val file = mirror[name]?.takeIf { it.fileId == fileId } ?: MirrorFile(fileId).also { mirror[name] = it }
        val current = file.bytes.toByteArray()
        when {
            offset == current.size.toLong() -> file.bytes.write(bytes)
            offset > current.size -> errors += "gap in $name at $offset (have ${current.size})"
            else -> {
                errors += "overlap in $name at $offset (have ${current.size})"
                val merged = ByteArrayOutputStream()
                merged.write(current, 0, offset.toInt())
                merged.write(bytes)
                mirror[name] = MirrorFile(fileId, merged)
            }
        }
    }

    override fun onTruncate(name: String, fileId: String, newLength: Long) {
        record("truncate:$name:$newLength")
        val file = mirror[name] ?: return
        val current = file.bytes.toByteArray()
        if (newLength < current.size) {
            mirror[name] = MirrorFile(fileId).also { it.bytes.write(current, 0, newLength.toInt()) }
        }
    }

    override fun onProcessState(vrchatRunning: Boolean, steamVrRunning: Boolean, pcUtcNowMs: Long) =
        record("process:$vrchatRunning,$steamVrRunning")

    override fun onSyncComplete() = record("sync")

    override fun onClockSkew(skewMs: Long) {
        threads += Thread.currentThread().name
        skews += skewMs
    }

    override fun onDisconnected() = record("disconnected")
}

/** Collects `companion-state` events. */
class RecordingEmitter : EventEmitter {
    val states = CopyOnWriteArrayList<JsonObject>()
    override fun emit(event: String, data: JsonElement?) {
        if (event == CompanionEngine.EVENT_STATE) states += data as JsonObject
    }
}

fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** A log file on the fake PC. */
class ServerFile(
    val name: String,
    @Volatile var fileId: String,
    val creationTicks: Long,
    @Volatile var lastWriteTicks: Long,
    content: String,
) {
    @Volatile
    var bytes: ByteArray = content.toByteArray(Charsets.UTF_8)

    fun append(text: String) {
        bytes += text.toByteArray(Charsets.UTF_8)
        lastWriteTicks += 10_000_000
    }
}

/**
 * In-process companion: TLS server on 127.0.0.1 with a key from the test keystore, speaking the companion side of
 * docs/PROTOCOL.md (hello, pair, auth, info, subscribe sequence). Tests drive live frames through [ServerConn].
 */
class FakeCompanionServer(
    alias: String = "companion",
    val id: String = "3f2b8c1e-5d6a-4f70-9e1b-00000000c0de",
    val machine: String = "TESTPC",
) : Closeable {
    val fp: String = TestKeys.fingerprint(alias)
    private val server = TestKeys.serverContext(alias).serverSocketFactory
        .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val port: Int get() = server.localPort

    @Volatile var pairingCode: String? = null
    @Volatile var helloVersion = CompanionProtocol.VERSION
    /** When set, the server computes pairing proofs with this fingerprint (a relay with another certificate). */
    @Volatile var proofFpOverride: String? = null
    @Volatile var autoSync = true
    @Volatile var vrchatRunning = true
    @Volatile var steamVrRunning = false
    @Volatile var pcClockOffsetMs = 0L
    @Volatile var chunkSize = 4096

    val tokens = ConcurrentHashMap<String, String>()
    val files = CopyOnWriteArrayList<ServerFile>()
    val accepted = AtomicInteger()
    val failedPairAttempts = AtomicInteger()
    val pairRequests = CopyOnWriteArrayList<JsonObject>()
    val connections = LinkedBlockingQueue<ServerConn>()
    val allConnections = CopyOnWriteArrayList<ServerConn>()

    @Volatile private var closed = false
    private val acceptThread = Thread({ acceptLoop() }, "fake-companion-accept").apply { isDaemon = true; start() }

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (e: IOException) {
                return
            }
            accepted.incrementAndGet()
            Thread({ handle(socket) }, "fake-companion-conn").apply { isDaemon = true; start() }
        }
    }

    inner class ServerConn(internal val socket: SSLSocket, val deviceId: String) {
        val messages = LinkedBlockingQueue<JsonObject>()
        val sentDataBytes = AtomicLong()
        @Volatile var closed = false
        private val out = socket.outputStream

        fun send(frame: ByteArray) = synchronized(this) {
            out.write(frame)
            out.flush()
        }

        fun sendControl(json: JsonObject) = send(FrameCodec.encodeControl(json))

        fun sendData(name: String, fileId: String, offset: Long, raw: ByteArray, compress: Boolean = true) {
            val frame = FrameCodec.encodeData(name, fileId, offset, raw, compress)
            sentDataBytes.addAndGet(frame.size.toLong())
            send(frame)
        }

        fun heartbeat(pcUtcNowMs: Long = System.currentTimeMillis() + pcClockOffsetMs) =
            sendControl(control(CompanionProtocol.T_HEARTBEAT) { put("pcUtcNowMs", pcUtcNowMs) })

        /** Next phone message of [type], skipping others (which stay recorded in [seen]). */
        fun awaitMessage(type: String, timeoutMs: Long = 10_000): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw AssertionError("no '$type' from the phone")
                val m = messages.poll(remaining, TimeUnit.NANOSECONDS) ?: continue
                seen += m
                if (m.s("t") == type) return m
            }
        }

        val seen = CopyOnWriteArrayList<JsonObject>()

        fun close() {
            closed = true
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        /** The subscribe sequence of PROTOCOL.md §5.5. */
        fun sync(subscribe: JsonObject) {
            val since = (subscribe["sinceUtcTicks"] as JsonPrimitive).content.toLong()
            val have = (subscribe["have"] as JsonArray).associate {
                val o = it as JsonObject
                o.s("name")!! to (o.s("fileId")!! to (o["length"] as JsonPrimitive).content.toLong())
            }
            sendControl(snapshotMessage())
            sendControl(processMessage())
            for (f in files.sortedBy { it.creationTicks }) {
                if (f.lastWriteTicks < since) continue
                val h = have[f.name]
                val start = if (h != null && h.first == f.fileId) h.second else 0L
                sendRange(f, start)
            }
            sendControl(control(CompanionProtocol.T_SYNC_COMPLETE))
        }

        fun sendRange(f: ServerFile, start: Long) {
            val bytes = f.bytes
            var pos = start.toInt()
            var compress = true
            while (pos < bytes.size) {
                val end = minOf(bytes.size, pos + chunkSize)
                sendData(f.name, f.fileId, pos.toLong(), bytes.copyOfRange(pos, end), compress)
                compress = !compress
                pos = end
            }
        }
    }

    fun snapshotMessage(): JsonObject = control(CompanionProtocol.T_SNAPSHOT) {
        put("files", buildJsonArray {
            for (f in files) {
                add(buildJsonObject {
                    put("name", f.name)
                    put("fileId", f.fileId)
                    put("creationTimeUtcTicks", f.creationTicks)
                    put("lastWriteTimeUtcTicks", f.lastWriteTicks)
                    put("length", f.bytes.size)
                })
            }
        })
    }

    fun processMessage(): JsonObject = control(CompanionProtocol.T_PROCESS) {
        put("vrchatRunning", vrchatRunning)
        put("steamVrRunning", steamVrRunning)
        put("pcUtcNowMs", System.currentTimeMillis() + pcClockOffsetMs)
    }

    private fun infoMessage(): JsonObject = control(CompanionProtocol.T_INFO) {
        put("companionVersion", "1.0.0")
        put("machineName", machine)
        put("pcUtcNowMs", System.currentTimeMillis() + pcClockOffsetMs)
        put("tz", buildJsonObject {
            put("windowsId", "W. Europe Standard Time")
            put("ianaId", "Europe/Berlin")
            put("supportsDst", true)
            put("baseUtcOffsetMin", 60)
            put("currentUtcOffsetMin", 120)
        })
        put("logDir", "C:\\VRChat")
        put("dirExists", true)
    }

    private fun handle(socket: SSLSocket) {
        try {
            socket.soTimeout = 30_000
            socket.startHandshake()
            val input = BufferedInputStream(socket.inputStream)
            val out = socket.outputStream
            fun sendRaw(json: JsonObject) {
                out.write(FrameCodec.encodeControl(json))
                out.flush()
            }
            val helloNonce = PairingCrypto.randomToken()
            val code = pairingCode
            sendRaw(control(CompanionProtocol.T_HELLO) {
                put("v", helloVersion)
                put("id", id)
                put("name", machine)
                put("nonce", helloNonce)
                put("pairing", code != null)
            })
            val first = FrameCodec.read(input) as Frame.Control
            val deviceId = first.json.s("deviceId").orEmpty()
            when (first.type) {
                CompanionProtocol.T_PAIR -> {
                    pairRequests += first.json
                    if (code == null) {
                        sendRaw(control(CompanionProtocol.T_PAIR_FAIL) { put("reason", "closed") })
                        socket.close()
                        return
                    }
                    val proofFp = proofFpOverride ?: fp
                    val clientNonce = first.json.s("nonce").orEmpty()
                    val expected = PairingCrypto.clientProof(code, proofFp, helloNonce, clientNonce)
                    if (first.json.s("proof") != expected) {
                        failedPairAttempts.incrementAndGet()
                        sendRaw(control(CompanionProtocol.T_PAIR_FAIL) { put("reason", "code") })
                        socket.close()
                        return
                    }
                    val token = PairingCrypto.randomToken()
                    tokens[deviceId] = token
                    pairingCode = null
                    sendRaw(control(CompanionProtocol.T_PAIRED) {
                        put("token", token)
                        put("proof", PairingCrypto.serverProof(code, proofFp, helloNonce, clientNonce))
                    })
                }
                CompanionProtocol.T_AUTH -> {
                    if (tokens[deviceId] == null || tokens[deviceId] != first.json.s("token")) {
                        sendRaw(control(CompanionProtocol.T_AUTH_FAIL))
                        socket.close()
                        return
                    }
                    sendRaw(control(CompanionProtocol.T_AUTH_OK))
                }
                else -> {
                    socket.close()
                    return
                }
            }
            sendRaw(infoMessage())
            val conn = ServerConn(socket, deviceId)
            allConnections += conn
            if (first.type == CompanionProtocol.T_AUTH) connections += conn
            while (!closed) {
                val frame = FrameCodec.read(input)
                if (frame !is Frame.Control) continue
                conn.messages += frame.json
                if (frame.type == CompanionProtocol.T_SUBSCRIBE && autoSync) conn.sync(frame.json)
            }
        } catch (e: Exception) {
            // connection ended
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            allConnections.filter { it.socket === socket }.forEach { it.closed = true }
        }
    }

    fun nextConnection(timeoutMs: Long = 10_000): ServerConn =
        connections.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("no authenticated connection")

    override fun close() {
        closed = true
        try {
            server.close()
        } catch (_: IOException) {
        }
        allConnections.forEach { it.close() }
    }
}

/** UDP responder answering discovery requests like the companion (PROTOCOL.md §2). */
class FakeDiscoveryResponder(
    private val reply: () -> JsonObject,
    private val extraReplies: List<ByteArray> = emptyList(),
) : Closeable {
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    val port: Int get() = socket.localPort
    val requests = CopyOnWriteArrayList<String>()
    private val thread = Thread({ loop() }, "fake-discovery").apply { isDaemon = true; start() }

    private fun loop() {
        val buf = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                return
            }
            requests += String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            for (extra in extraReplies) socket.send(DatagramPacket(extra, extra.size, packet.socketAddress))
            val bytes = reply().toString().toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
        }
    }

    override fun close() = socket.close()
}

class CountingLock : MulticastLockHandle {
    val acquired = AtomicInteger()
    val released = AtomicInteger()
    override fun acquire(): Boolean {
        acquired.incrementAndGet()
        return true
    }

    override fun release() {
        released.incrementAndGet()
    }
}
