package io.github.vrcxandroid.companion

import android.util.Log
import io.github.vrcxandroid.CompanionController
import io.github.vrcxandroid.EventEmitter
import io.github.vrcxandroid.logwatcher.CompanionInfo
import io.github.vrcxandroid.logwatcher.LogSink
import io.github.vrcxandroid.logwatcher.MirroredFile
import io.github.vrcxandroid.logwatcher.PcFileMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Tunables of the client. The defaults are the values of docs/PROTOCOL.md; tests shorten them. */
data class CompanionConfig(
    val connectTimeoutMs: Int = 4_000,
    /** PROTOCOL.md §5.9: close after 20 s without receiving anything. */
    val readTimeoutMs: Int = 20_000,
    /** PROTOCOL.md §5.9: `ping` when nothing else was sent for 30 s. */
    val pingIdleMs: Long = 30_000,
    /**
     * A heartbeat (every 5 s) is answered with `ping` (or a pending `ack`) when nothing was sent for this long, so the
     * companion, which closes after 20 s of silence, always hears from the phone well within its timeout.
     */
    val heartbeatReplyAfterMs: Long = 10_000,
    /** `ack` whenever this many data-frame bytes arrived since the last one (PROTOCOL.md: at least every 1 MiB). */
    val ackEveryBytes: Long = 512L * 1024,
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 60_000,
    /** Discovery run when every stored address fails (0 disables it). */
    val reconnectDiscoveryMs: Long = 1_500,
    /**
     * How long an authenticated session waits for the LogWatcher's tillDate before subscribing with 0 ("send
     * everything"). Upstream reads nothing before `SetDateTill`, and a 0 subscription streams every log on the PC.
     */
    val subscribeWaitMs: Long = 30_000,
    /** Process flags stay valid this long after a disconnect (ARCHITECTURE.md §8). */
    val processGraceMs: Long = 120_000,
    val discoveryPort: Int = CompanionProtocol.DEFAULT_DISCOVERY_PORT,
    val discoveryTargets: () -> List<InetAddress> = CompanionDiscovery::broadcastTargets,
    /** Whether the connection loop runs before the host calls `setRunning`. */
    val startRunning: Boolean = true,
)

/** Log sink used while the log side does not implement [LogSink]: the client authenticates but never subscribes. */
object NullLogSink : LogSink {
    override fun onSessionStarted(companionId: String, info: CompanionInfo) {}
    override fun onInfo(info: CompanionInfo) {}
    override fun have(): List<MirroredFile> = emptyList()
    override fun sinceUtcTicks(): Long = 0L
    override fun onSnapshot(files: List<PcFileMeta>) {}
    override fun onData(name: String, fileId: String, offset: Long, bytes: ByteArray) {}
    override fun onTruncate(name: String, fileId: String, newLength: Long) {}
    override fun onProcessState(vrchatRunning: Boolean, steamVrRunning: Boolean, pcUtcNowMs: Long) {}
    override fun onSyncComplete() {}
    override fun onClockSkew(skewMs: Long) {}
    override fun onDisconnected() {}
}

/**
 * Optional interface for the log side: drops everything it keeps for a companion that was forgotten (its mirror
 * under `filesDir/logmirror/<companionId>/` and in-memory state). Without it the client deletes the directory itself.
 */
interface CompanionMirrorOwner {
    fun forgetCompanion(companionId: String)
}

/**
 * The phone side of docs/PROTOCOL.md, free of Android APIs so it runs in JVM tests. [CompanionManager] wires it to
 * EncryptedSharedPreferences, WifiManager, ConnectivityManager, the LogWatcher and the bridge.
 *
 * Threads:
 * - `companion-loop`: connects (with backoff), authenticates, then reads frames until the connection ends.
 * - `companion-sink`: every [LogSink] call, in wire order, plus the writes that depend on them (subscribe, ack, fetch,
 *   ping). Frames are handed over from the loop thread in the order they were read.
 * Pairing and discovery run on the caller's coroutine (Dispatchers.IO).
 */
class CompanionEngine(
    private val store: SecureStore,
    private val sinkProvider: () -> LogSink,
    private val emitter: EventEmitter,
    private val deviceName: String,
    private val multicastLock: MulticastLockHandle = MulticastLockHandle.NONE,
    private val mirrorCleaner: (String) -> Unit = {},
    private val config: CompanionConfig = CompanionConfig(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) : CompanionController, Closeable {

    private enum class Phase(val wire: String) {
        IDLE("idle"),
        CONNECTING("connecting"),
        SEARCHING("searching"),
        CONNECTED("connected"),
        ERROR("error"),
    }

    private enum class Outcome { ABORTED, FAILED, SYNCED, HALTED }

    private data class ProcessState(val companionId: String, val vrchat: Boolean, val steamVr: Boolean)

    private val repository = PairingRepository(store)
    private val connector = TlsConnector(config.connectTimeoutMs, config.readTimeoutMs)
    private val discovery = CompanionDiscovery(multicastLock, config.discoveryPort, config.discoveryTargets)
    private val backoff = Backoff(config.backoffBaseMs, config.backoffMaxMs)

    private val mutex = ReentrantLock()
    private val changed = mutex.newCondition()

    // ---- guarded by mutex ----
    private var loaded = false
    private var records: List<PairedCompanion> = emptyList()
    private var activeId: String? = null
    private var deviceId: String = ""
    private var running = config.startRunning
    private var closed = false
    private var phase = Phase.IDLE
    private var lastError: String? = null
    private var haltedId: String? = null
    private var generation = 0
    private var wakeRequested = false
    private var connection: TlsConnection? = null
    private var session: Session? = null
    private var liveInfo: CompanionInfo? = null
    private var process: ProcessState? = null
    private var disconnectedAtMs: Long? = null
    private var syncing = false
    private var tillTicks = 0L

    private val emitLock = Any()
    private var lastEmitted: JsonObject? = null

    private val sinkExecutor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "companion-sink").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    private val loopThread = Thread(::runLoop, "companion-loop").apply { isDaemon = true }

    // ================================================================================================================
    // CompanionController
    // ================================================================================================================

    override fun state(): JsonObject = mutex.withLock {
        ensureLoadedLocked()
        val active = activeRecordLocked()
        val status = when {
            records.isEmpty() -> "unpaired"
            !running || active == null -> Phase.IDLE.wire
            haltedId == activeId -> Phase.ERROR.wire
            else -> phase.wire
        }
        val connected = status == Phase.CONNECTED.wire
        val p = process?.takeIf { it.companionId == activeId }
        val processValid = p != null && (connected ||
            disconnectedAtMs?.let { monotonicMs() - it < config.processGraceMs } == true)
        val info = liveInfo
        buildJsonObject {
            put("status", status)
            put("activeId", jsonOrNull(activeId))
            put("paired", JsonArray(records.map { it.toPublicJson() }))
            // Last `info` value; before the first one, the name the companion announced when pairing.
            put(
                "machineName",
                jsonOrNull(
                    info?.machineName?.takeIf { it.isNotEmpty() } ?: active?.machineName
                        ?: active?.name?.takeIf { it.isNotEmpty() },
                ),
            )
            put("tz", info?.let(::tzJson) ?: active?.tz ?: JsonNull)
            put("vrchatRunning", processValid && p!!.vrchat)
            put("steamVrRunning", processValid && p!!.steamVr)
            put("syncing", connected && syncing)
            put("lastError", jsonOrNull(if (connected) null else lastError))
        }
    }

    override suspend fun discover(timeoutMs: Long): JsonArray = withContext(Dispatchers.IO) {
        val found = discovery.discover(timeoutMs.coerceIn(200, 15_000))
        update {
            ensureLoadedLocked()
            var dirty = false
            records = records.map { rec ->
                val hit = found.firstOrNull { it.id == rec.id && PairingCrypto.constantTimeEquals(it.fp, rec.fp) }
                    ?: return@map rec
                val hosts = (hit.hosts + rec.hosts).distinct()
                if (hosts == rec.hosts && hit.port == rec.port) rec else rec.copy(hosts = hosts, port = hit.port)
                    .also { dirty = true }
            }
            if (dirty) persistLocked()
        }
        buildJsonArray { found.forEach { add(it.toJson()) } }
    }

    override suspend fun pairWithQr(payload: String): JsonObject = withContext(Dispatchers.IO) {
        val qr = PairingQr.parse(payload)
        val addresses = qr.hosts.mapNotNull(LocalAddressFilter::parseLiteral).filter(LocalAddressFilter::isLocal)
        if (addresses.isEmpty()) throw PairingException(PairingException.NOT_LOCAL)
        pairWith(addresses, qr.port, qr.fp, qr.id, qr.name, qr.code, qr.hosts)
        state()
    }

    override suspend fun pair(target: JsonObject, code: String): JsonObject = withContext(Dispatchers.IO) {
        val normalized = PairingCrypto.normalizeCode(code) ?: throw PairingException(PairingException.CODE)
        val (host, port) = LocalAddressFilter.splitHostPort(
            target.str("host").orEmpty(),
            target.int("port")?.takeIf { it in 1..65535 } ?: CompanionProtocol.DEFAULT_TCP_PORT,
        ) ?: throw PairingException(PairingException.UNREACHABLE)
        val literal = LocalAddressFilter.parseLiteral(host)
        val addresses = if (literal != null) {
            if (!LocalAddressFilter.isLocal(literal)) throw PairingException(PairingException.NOT_LOCAL)
            listOf(literal)
        } else {
            val all = try {
                InetAddress.getAllByName(host).toList()
            } catch (e: UnknownHostException) {
                throw PairingException(PairingException.UNREACHABLE, e)
            }
            all.filter(LocalAddressFilter::isLocal).ifEmpty { throw PairingException(PairingException.NOT_LOCAL) }
        }
        val fp = target.str("fp")?.takeIf { it.isNotBlank() }
        val extraHosts = if (literal == null) listOf(host) else emptyList()
        pairWith(addresses, port, fp, target.str("id"), target.str("name"), normalized, extraHosts)
        state()
    }

    override fun forget(companionId: String) {
        var endedSession: Session? = null
        val known = update {
            ensureLoadedLocked()
            val rec = records.firstOrNull { it.id == companionId } ?: return@update false
            records = records - rec
            if (haltedId == companionId) haltedId = null
            if (activeId == companionId) {
                endedSession = session
                activeId = records.maxByOrNull { it.lastSeen }?.id
                dropConnectionLocked()
                wakeRequested = true
            }
            persistLocked()
            changed.signalAll()
            true
        }
        if (!known) return
        // After the session's onDisconnected, so the log side never sees data for a mirror that is gone.
        post {
            endedSession?.end()
            try {
                mirrorCleaner(companionId)
            } catch (e: Exception) {
                Log.w(TAG, "mirror cleanup failed", e)
            }
        }
    }

    override fun setActive(companionId: String) {
        update {
            ensureLoadedLocked()
            if (records.none { it.id == companionId }) return@update
            if (activeId == companionId) {
                // Selecting the active companion again retries after an error (for example "pairing removed").
                if (haltedId == companionId || phase == Phase.ERROR || connection == null) {
                    haltedId = null
                    wakeRequested = true
                    changed.signalAll()
                }
                return@update
            }
            activeId = companionId
            haltedId = null
            dropConnectionLocked()
            wakeRequested = true
            persistLocked()
            changed.signalAll()
        }
    }

    override fun setRunning(running: Boolean) {
        update {
            if (running) {
                if (!this.running || connection == null) wakeRequested = true
                this.running = true
            } else if (this.running) {
                this.running = false
                dropConnectionLocked()
            }
            changed.signalAll()
        }
    }

    override fun onTillDateChanged(utcTicks: Long) {
        val current = mutex.withLock {
            tillTicks = utcTicks
            session
        }
        current?.let { s -> post { s.onTillChanged() } }
    }

    // ================================================================================================================
    // Extra entry points
    // ================================================================================================================

    /**
     * Asks the companion to resend a file from [fromOffset] (PROTOCOL.md §5.9), for example when the mirror finds a
     * gap or lost its copy. Ignored while not connected: the next `subscribe.have` covers it.
     */
    fun requestFetch(name: String, fileId: String, fromOffset: Long) {
        val current = mutex.withLock { session } ?: return
        post { current.fetch(name, fileId, fromOffset) }
    }

    /** The default network changed: reconnect now instead of waiting for the backoff or the read timeout. */
    fun onNetworkChanged() {
        update {
            if (!running || closed) return@update
            wakeRequested = true
            connection?.close()
            changed.signalAll()
        }
    }

    /** Stops every thread (tests; the app keeps the engine for the process lifetime). */
    override fun close() {
        mutex.withLock {
            closed = true
            dropConnectionLocked()
            changed.signalAll()
        }
        loopThread.join(5_000)
        sinkExecutor.shutdown()
        sinkExecutor.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ================================================================================================================
    // Connection loop (companion-loop thread)
    // ================================================================================================================

    private fun runLoop() {
        var attempt = 0
        while (true) {
            var record: PairedCompanion? = null
            var gen = 0
            mutex.withLock {
                ensureLoadedLocked()
                while (!closed && !canConnectLocked()) changed.await()
                if (closed) return
                if (wakeRequested) {
                    wakeRequested = false
                    attempt = 0
                }
                record = activeRecordLocked()
                gen = generation
            }
            val outcome = try {
                connectOnce(record!!, gen)
            } catch (t: Throwable) {
                Log.e(TAG, "connection attempt crashed", t)
                Outcome.FAILED
            }
            val delay = when (outcome) {
                Outcome.ABORTED, Outcome.HALTED -> 0L
                Outcome.SYNCED -> {
                    attempt = 0
                    backoff.delayFor(attempt++)
                }
                Outcome.FAILED -> backoff.delayFor(attempt++)
            }
            if (delay > 0) waitForRetry(delay, gen)
        }
    }

    private fun canConnectLocked(): Boolean =
        running && activeRecordLocked() != null && haltedId != activeId

    private fun waitForRetry(delayMs: Long, gen: Int) {
        mutex.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(delayMs)
            while (remaining > 0 && !closed && !wakeRequested && running && generation == gen) {
                remaining = changed.awaitNanos(remaining)
            }
        }
    }

    private fun isCurrent(gen: Int): Boolean = mutex.withLock { !closed && running && generation == gen }

    private fun connectOnce(record: PairedCompanion, gen: Int): Outcome {
        update {
            if (generation == gen && (phase == Phase.IDLE || phase == Phase.CONNECTED)) phase = Phase.CONNECTING
        }
        var sawFingerprint = false
        var sawNotLocal = false
        var sawAddress = false
        val tried = HashSet<String>()
        var usedHost: String? = null

        fun tryHosts(hosts: List<String>, fp: String, port: Int): TlsConnection? {
            for (host in hosts) {
                if (!tried.add(host)) continue
                if (!isCurrent(gen)) return null
                val addresses = resolveForLoop(host)
                if (addresses.isEmpty()) {
                    if (LocalAddressFilter.parseLiteral(host) != null) sawNotLocal = true
                    continue
                }
                sawAddress = true
                for (address in addresses) {
                    try {
                        return connector.connect(address, port, fp).also { usedHost = host }
                    } catch (e: FingerprintMismatchException) {
                        sawFingerprint = true
                    } catch (e: IOException) {
                        // unreachable at this address
                    }
                }
            }
            return null
        }

        var current = record
        var conn = tryHosts(current.hosts, current.fp, current.port)
        if (conn == null && config.reconnectDiscoveryMs > 0 && isCurrent(gen)) {
            update { if (generation == gen && phase != Phase.ERROR) phase = Phase.SEARCHING }
            val found = discovery.discover(config.reconnectDiscoveryMs) {
                it.id == current.id && PairingCrypto.constantTimeEquals(it.fp, current.fp)
            }
            val hit = found.firstOrNull { it.id == current.id && PairingCrypto.constantTimeEquals(it.fp, current.fp) }
            if (hit != null) {
                current = replaceRecord(current.id) {
                    it.copy(hosts = (hit.hosts + it.hosts).distinct(), port = hit.port)
                } ?: current
                conn = tryHosts(hit.hosts, current.fp, current.port)
            }
        }
        if (conn == null) {
            if (!isCurrent(gen)) return Outcome.ABORTED
            update {
                if (generation == gen) {
                    when {
                        sawFingerprint -> fail(PairingException.FINGERPRINT)
                        sawNotLocal && !sawAddress -> fail(PairingException.NOT_LOCAL)
                        else -> {
                            if (phase != Phase.ERROR) phase = Phase.SEARCHING
                            lastError = PairingException.UNREACHABLE
                        }
                    }
                }
            }
            return Outcome.FAILED
        }
        val registered = mutex.withLock {
            if (closed || !running || generation != gen) false else {
                connection = conn
                true
            }
        }
        if (!registered) {
            conn.close()
            return Outcome.ABORTED
        }
        try {
            return runSession(conn, current, gen, usedHost ?: current.hosts.first())
        } finally {
            conn.close()
            mutex.withLock { if (connection === conn) connection = null }
        }
    }

    /** Stored hosts are IP literals; names (manual pairing by host name) are resolved and filtered. */
    private fun resolveForLoop(host: String): List<InetAddress> {
        LocalAddressFilter.parseLiteral(host)?.let { return if (LocalAddressFilter.isLocal(it)) listOf(it) else emptyList() }
        return try {
            InetAddress.getAllByName(host).filter(LocalAddressFilter::isLocal)
        } catch (e: IOException) {
            emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Must be called with the mutex held. */
    private fun fail(code: String) {
        phase = Phase.ERROR
        lastError = code
    }

    /** A handshake that ended without a session: [code] becomes `lastError`; [halt] stops retrying (revoked). */
    private class HandshakeFailure(val code: String, val halt: Boolean = false) : Exception(code)

    /** `hello` → `auth` → `authOk` (PROTOCOL.md §5.1, §5.3). */
    private fun authenticate(conn: TlsConnection, record: PairedCompanion): HelloMessage {
        val first = conn.readFrame()
        if (first !is Frame.Control || first.type != CompanionProtocol.T_HELLO) throw ProtocolException("expected hello")
        val hello = HelloMessage.parse(first.json)
        if (hello.version != CompanionProtocol.VERSION) throw HandshakeFailure(PairingException.VERSION)
        if (hello.id != record.id) throw HandshakeFailure(PairingException.FINGERPRINT)
        conn.sendControl(control(CompanionProtocol.T_AUTH) {
            put("deviceId", currentDeviceId())
            put("token", record.token)
        })
        while (true) {
            val frame = conn.readFrame()
            if (frame !is Frame.Control) throw ProtocolException("data before authentication")
            when (frame.type) {
                CompanionProtocol.T_AUTH_OK -> return hello
                CompanionProtocol.T_AUTH_FAIL -> throw HandshakeFailure(REVOKED, halt = true)
                else -> Unit // heartbeats and unknown types before authOk are ignored
            }
        }
    }

    private fun runSession(conn: TlsConnection, record: PairedCompanion, gen: Int, usedHost: String): Outcome {
        val hello = try {
            authenticate(conn, record)
        } catch (e: HandshakeFailure) {
            update {
                if (generation == gen) {
                    if (e.halt) haltedId = record.id
                    fail(e.code)
                }
            }
            return if (e.halt) Outcome.HALTED else Outcome.FAILED
        } catch (e: ProtocolException) {
            update { if (generation == gen) fail(PairingException.PROTOCOL) }
            return Outcome.FAILED
        } catch (e: IOException) {
            update {
                if (generation == gen && phase != Phase.ERROR) {
                    phase = Phase.SEARCHING
                    lastError = PairingException.UNREACHABLE
                }
            }
            return if (isCurrent(gen)) Outcome.FAILED else Outcome.ABORTED
        }

        // ---- authenticated ----
        val sess = Session(conn, record.id)
        val accepted = update {
            if (generation != gen || closed || !running) return@update false
            val now = wallClock()
            replaceRecordLocked(record.id) {
                it.copy(lastSeen = now, name = hello.name.ifBlank { it.name }).withPreferredHost(usedHost)
            }
            persistLocked()
            phase = Phase.CONNECTED
            lastError = null
            session = sess
            disconnectedAtMs = null
            syncing = false
            if (process?.companionId != record.id) process = null
            if (liveInfo != null && activeId != record.id) liveInfo = null
            true
        }
        if (!accepted) return Outcome.ABORTED
        post { sess.begin() }

        var synced = false
        var protocolError = false
        try {
            while (true) {
                val frame = conn.readFrame()
                val receivedAt = wallClock()
                if (frame is Frame.Control && frame.type == CompanionProtocol.T_SYNC_COMPLETE) synced = true
                if (!post { sess.handle(frame, receivedAt) }) break
            }
        } catch (e: ProtocolException) {
            Log.w(TAG, "protocol error: ${e.message}")
            protocolError = true
        } catch (e: IOException) {
            // closed, timed out or reset
        } finally {
            post { sess.end() }
            update {
                if (session === sess) session = null
                disconnectedAtMs = monotonicMs()
                syncing = false
                replaceRecordLocked(record.id) { it.copy(lastSeen = wallClock()) }
                persistLocked()
                if (generation == gen && phase == Phase.CONNECTED) {
                    if (protocolError) fail(PairingException.PROTOCOL) else phase = Phase.CONNECTING
                }
            }
            // Re-evaluate the process flags once the grace period is over.
            schedule(config.processGraceMs + 100) { emitState() }
        }
        if (!isCurrent(gen)) return Outcome.ABORTED
        return if (synced && !protocolError) Outcome.SYNCED else Outcome.FAILED
    }

    // ================================================================================================================
    // Pairing (caller's IO thread)
    // ================================================================================================================

    private fun pairWith(
        addresses: List<InetAddress>,
        port: Int,
        fp: String?,
        expectedId: String?,
        nameHint: String?,
        code: String,
        extraHosts: List<String>,
    ) {
        var lastUnreachable: Exception? = null
        for (address in addresses) {
            val conn = try {
                connector.connect(address, port, fp)
            } catch (e: FingerprintMismatchException) {
                throw PairingException(PairingException.FINGERPRINT, e)
            } catch (e: NotLocalAddressException) {
                throw PairingException(PairingException.NOT_LOCAL, e)
            } catch (e: IOException) {
                lastUnreachable = e
                continue
            }
            conn.use {
                completePairing(it, address, port, fp, expectedId, nameHint, code, extraHosts)
            }
            return
        }
        throw PairingException(PairingException.UNREACHABLE, lastUnreachable)
    }

    private fun completePairing(
        conn: TlsConnection,
        address: InetAddress,
        port: Int,
        fp: String?,
        expectedId: String?,
        nameHint: String?,
        code: String,
        extraHosts: List<String>,
    ) {
        fun read(): Frame = try {
            conn.readFrame()
        } catch (e: ProtocolException) {
            throw PairingException(PairingException.PROTOCOL, e)
        } catch (e: EOFException) {
            throw PairingException(PairingException.CLOSED, e)
        } catch (e: IOException) {
            throw PairingException(PairingException.UNREACHABLE, e)
        }

        val first = read()
        if (first !is Frame.Control || first.type != CompanionProtocol.T_HELLO) {
            throw PairingException(PairingException.PROTOCOL)
        }
        val hello = HelloMessage.parse(first.json)
        if (hello.version != CompanionProtocol.VERSION) throw PairingException(PairingException.VERSION)
        if (!CompanionProtocol.isSafeId(hello.id) || hello.nonce.isEmpty()) {
            throw PairingException(PairingException.PROTOCOL)
        }
        if (expectedId != null && hello.id != expectedId) throw PairingException(PairingException.FINGERPRINT)
        // No proof is sent while no pairing window is open.
        if (!hello.pairing) throw PairingException(PairingException.CLOSED)

        val pinnedFp = fp ?: conn.fingerprint
        val clientNonce = PairingCrypto.randomToken()
        try {
            conn.sendControl(control(CompanionProtocol.T_PAIR) {
                put("deviceId", currentDeviceId())
                put("deviceName", deviceName)
                put("nonce", clientNonce)
                put("proof", PairingCrypto.clientProof(code, pinnedFp, hello.nonce, clientNonce))
            })
        } catch (e: IOException) {
            throw PairingException(PairingException.CLOSED, e)
        }
        while (true) {
            val frame = read()
            if (frame !is Frame.Control) throw PairingException(PairingException.PROTOCOL)
            when (frame.type) {
                CompanionProtocol.T_PAIRED -> {
                    val token = frame.json.str("token")?.takeIf { it.isNotEmpty() }
                        ?: throw PairingException(PairingException.PROTOCOL)
                    val expected = PairingCrypto.serverProof(code, pinnedFp, hello.nonce, clientNonce)
                    val proof = frame.json.str("proof").orEmpty()
                    if (!PairingCrypto.constantTimeEquals(proof, expected)) {
                        throw PairingException(PairingException.FINGERPRINT)
                    }
                    val now = wallClock()
                    val usedHost = address.hostAddress ?: extraHosts.first()
                    onPaired(
                        PairedCompanion(
                            id = hello.id,
                            name = hello.name.ifBlank { nameHint.orEmpty() },
                            fp = pinnedFp,
                            token = token,
                            hosts = (listOf(usedHost) + extraHosts).distinct(),
                            port = port,
                            pairedAt = now,
                            lastSeen = now,
                        ),
                    )
                    return
                }
                CompanionProtocol.T_PAIR_FAIL -> throw PairingException.fromPairFail(frame.json.str("reason"))
                else -> Unit
            }
        }
    }

    private fun onPaired(record: PairedCompanion) {
        update {
            ensureLoadedLocked()
            val previous = records.firstOrNull { it.id == record.id }
            records = records.filter { it.id != record.id } +
                record.copy(machineName = previous?.machineName, tz = previous?.tz)
            activeId = record.id
            haltedId = null
            // A new token: reconnect with auth even when this companion was already connected.
            dropConnectionLocked()
            wakeRequested = true
            persistLocked()
            changed.signalAll()
        }
    }

    // ================================================================================================================
    // Session (companion-sink thread)
    // ================================================================================================================

    /** One authenticated connection. Every method runs on the sink thread. */
    private inner class Session(private val conn: TlsConnection, private val companionId: String) {
        private val sink: LogSink = try {
            sinkProvider()
        } catch (e: Exception) {
            Log.w(TAG, "log sink unavailable", e)
            NullLogSink
        }
        private val tracker = OffsetTracker()
        private val skew = SkewEstimator()
        private var lastSkew: Long? = null
        private var started = false
        private var ended = false
        private var failed = false
        private var subscribed = false
        /** A second subscribe was sent and its sequence (starting with a snapshot) has not begun yet. */
        private var awaitingRestart = false
        private var allowZeroSince = false
        private var waitTask: ScheduledFuture<*>? = null
        private var pingTask: ScheduledFuture<*>? = null
        private var receivedDataBytes = 0L
        private var ackedDataBytes = 0L

        fun begin() {
            schedulePing(config.pingIdleMs)
        }

        fun handle(frame: Frame, receivedAt: Long) {
            if (failed) return
            try {
                when (frame) {
                    is Frame.Data -> onData(frame)
                    is Frame.Control -> onControl(frame, receivedAt)
                }
            } catch (e: IOException) {
                // A write failed (or the log side reported an I/O error): the connection is gone. Later frames of
                // this connection are skipped; the next subscribe resynchronizes from the mirror's `have` list.
                failed = true
                conn.close()
            } catch (e: Exception) {
                Log.e(TAG, "log sink failed on ${frameKind(frame)}; reconnecting", e)
                failed = true
                conn.close()
            }
        }

        private fun frameKind(frame: Frame) = if (frame is Frame.Control) frame.type else "data"

        private fun onControl(frame: Frame.Control, receivedAt: Long) {
            val json = frame.json
            when (frame.type) {
                CompanionProtocol.T_INFO -> {
                    val info = parseInfo(json)
                    if (!started) {
                        sink.onSessionStarted(companionId, info)
                        started = true
                    } else {
                        sink.onInfo(info)
                    }
                    sampleSkew(info.pcUtcNowMs, receivedAt)
                    onInfoReceived(companionId, info)
                    maybeSubscribe(force = false)
                }
                CompanionProtocol.T_HEARTBEAT -> {
                    if (started) sampleSkew(json.long("pcUtcNowMs") ?: 0L, receivedAt)
                    if (monotonicMs() - conn.lastSentAtMs >= config.heartbeatReplyAfterMs) {
                        if (receivedDataBytes > ackedDataBytes) sendAck() else sendPing()
                    }
                }
                CompanionProtocol.T_SNAPSHOT -> if (started) {
                    awaitingRestart = false
                    val files = parseSnapshot(json)
                    tracker.onSnapshot(files)
                    sink.onSnapshot(files)
                }
                CompanionProtocol.T_PROCESS -> if (started) {
                    val vr = json.bool("vrchatRunning") ?: false
                    val svr = json.bool("steamVrRunning") ?: false
                    sink.onProcessState(vr, svr, json.long("pcUtcNowMs") ?: 0L)
                    onProcessReceived(companionId, vr, svr)
                }
                CompanionProtocol.T_TRUNCATE -> if (started) {
                    val name = json.str("name") ?: return
                    val fileId = json.str("fileId") ?: return
                    val newLength = json.long("newLength")?.coerceAtLeast(0) ?: return
                    tracker.onTruncate(name, fileId, newLength)
                    sink.onTruncate(name, fileId, newLength)
                }
                CompanionProtocol.T_SYNC_COMPLETE -> if (started) {
                    // After a re-subscribe, a syncComplete that arrives before the restarted sequence's snapshot ends
                    // the superseded sequence; the log side hears only about the sync it asked for last.
                    if (!awaitingRestart) {
                        sink.onSyncComplete()
                        setSyncing(companionId, false)
                    }
                    if (receivedDataBytes > ackedDataBytes) sendAck()
                }
                else -> Unit // unknown and out-of-place types are ignored (PROTOCOL.md §4, §6)
            }
        }

        private fun onData(frame: Frame.Data) {
            receivedDataBytes += frame.wireBytes
            if (started) {
                when (val d = tracker.onData(frame.name, frame.fileId, frame.offset, frame.bytes.size)) {
                    is OffsetTracker.Decision.Deliver -> {
                        val bytes = if (d.skip == 0) frame.bytes else frame.bytes.copyOfRange(d.skip, frame.bytes.size)
                        if (bytes.isNotEmpty()) sink.onData(frame.name, frame.fileId, frame.offset + d.skip, bytes)
                    }
                    OffsetTracker.Decision.Drop -> Unit
                    is OffsetTracker.Decision.Fetch -> sendFetch(frame.name, frame.fileId, d.fromOffset)
                }
            }
            if (receivedDataBytes - ackedDataBytes >= config.ackEveryBytes) sendAck()
        }

        fun onTillChanged() {
            if (ended || failed || !started) return
            // A known tillDate: subscribe now, or restart the sequence with the new value (PROTOCOL.md §5.5).
            guarded { maybeSubscribe(force = subscribed) }
        }

        fun fetch(name: String, fileId: String, fromOffset: Long) {
            if (ended || failed || !started) return
            guarded { sendFetch(name, fileId, fromOffset.coerceAtLeast(0)) }
        }

        fun end() {
            if (ended) return
            ended = true
            waitTask?.cancel(false)
            pingTask?.cancel(false)
            if (started) {
                try {
                    sink.onDisconnected()
                } catch (e: Exception) {
                    Log.w(TAG, "log sink failed on disconnect", e)
                }
            }
        }

        private fun maybeSubscribe(force: Boolean) {
            if (!started || ended || failed || sink === NullLogSink) return
            if (subscribed && !force) return
            val since = sink.sinceUtcTicks().takeIf { it > 0 } ?: currentTillTicks()
            if (since <= 0 && !allowZeroSince) {
                if (waitTask == null) {
                    waitTask = schedule(config.subscribeWaitMs) {
                        waitTask = null
                        if (!ended && !failed) {
                            allowZeroSince = true
                            guarded { maybeSubscribe(force = false) }
                        }
                    }
                }
                return
            }
            waitTask?.cancel(false)
            waitTask = null
            val have = sink.have()
            tracker.reset(have)
            conn.sendControl(control(CompanionProtocol.T_SUBSCRIBE) {
                put("sinceUtcTicks", since.coerceAtLeast(0))
                put("have", buildJsonArray {
                    for (f in have) {
                        add(buildJsonObject {
                            put("name", f.name)
                            put("fileId", f.fileId)
                            put("length", f.length)
                        })
                    }
                })
            })
            awaitingRestart = subscribed
            subscribed = true
            setSyncing(companionId, true)
        }

        private fun sendAck() {
            conn.sendControl(control(CompanionProtocol.T_ACK) { put("bytes", receivedDataBytes) })
            ackedDataBytes = receivedDataBytes
        }

        private fun sendPing() = conn.sendControl(control(CompanionProtocol.T_PING))

        private fun sendFetch(name: String, fileId: String, fromOffset: Long) {
            tracker.onFetchRequested(name, fileId, fromOffset)
            conn.sendControl(control(CompanionProtocol.T_FETCH) {
                put("name", name)
                put("fileId", fileId)
                put("fromOffset", fromOffset)
            })
        }

        private fun schedulePing(delayMs: Long) {
            pingTask = schedule(delayMs.coerceAtLeast(1)) {
                if (ended || failed) return@schedule
                val idle = monotonicMs() - conn.lastSentAtMs
                if (idle >= config.pingIdleMs) {
                    guarded { sendPing() }
                    schedulePing(config.pingIdleMs)
                } else {
                    schedulePing(config.pingIdleMs - idle)
                }
            }
        }

        private fun sampleSkew(pcUtcNowMs: Long, receivedAt: Long) {
            if (pcUtcNowMs <= 0) return
            val estimate = skew.add(pcUtcNowMs - receivedAt)
            if (estimate != lastSkew) {
                lastSkew = estimate
                sink.onClockSkew(estimate)
            }
        }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                conn.close()
            } catch (e: Exception) {
                Log.e(TAG, "log sink failed; reconnecting", e)
                failed = true
                conn.close()
            }
        }
    }

    // ---- callbacks from the sink thread ----

    private fun onInfoReceived(companionId: String, info: CompanionInfo) {
        update {
            if (activeId != companionId) return@update
            liveInfo = info
            val tz = tzJson(info)
            val machine = info.machineName.takeIf { it.isNotEmpty() }
            val rec = activeRecordLocked() ?: return@update
            if (rec.machineName != machine || rec.tz != tz) {
                replaceRecordLocked(companionId) { it.copy(machineName = machine, tz = tz) }
                persistLocked()
            }
        }
    }

    private fun onProcessReceived(companionId: String, vrchat: Boolean, steamVr: Boolean) {
        update { if (activeId == companionId) process = ProcessState(companionId, vrchat, steamVr) }
    }

    private fun setSyncing(companionId: String, value: Boolean) {
        update { if (activeId == companionId) syncing = value }
    }

    private fun currentTillTicks(): Long = mutex.withLock { tillTicks }

    private fun currentDeviceId(): String = mutex.withLock {
        ensureLoadedLocked()
        deviceId
    }

    // ================================================================================================================
    // Helpers
    // ================================================================================================================

    private fun ensureLoadedLocked() {
        if (loaded) return
        val snapshot = try {
            repository.load()
        } catch (e: Exception) {
            Log.e(TAG, "cannot load pairings", e)
            PairingRepository.Snapshot(emptyList(), null, UUID.randomUUID().toString())
        }
        records = snapshot.records
        activeId = snapshot.activeId
        deviceId = snapshot.deviceId
        loaded = true
    }

    private fun persistLocked() {
        try {
            repository.save(records, activeId)
        } catch (e: Exception) {
            Log.e(TAG, "cannot save pairings", e)
        }
    }

    private fun activeRecordLocked(): PairedCompanion? = records.firstOrNull { it.id == activeId }

    private fun replaceRecordLocked(id: String, change: (PairedCompanion) -> PairedCompanion): PairedCompanion? {
        var out: PairedCompanion? = null
        records = records.map { if (it.id == id) change(it).also { c -> out = c } else it }
        return out
    }

    private fun replaceRecord(id: String, change: (PairedCompanion) -> PairedCompanion): PairedCompanion? =
        update {
            replaceRecordLocked(id, change).also { if (it != null) persistLocked() }
        }

    /** Ends the current connection and forgets per-companion live state. Mutex held. */
    private fun dropConnectionLocked() {
        generation++
        connection?.close()
        connection = null
        session = null
        liveInfo = null
        process = null
        syncing = false
        disconnectedAtMs = null
        phase = Phase.IDLE
        lastError = null
    }

    /** Runs [block] under the mutex, then emits `companion-state` if the state changed. */
    private inline fun <T> update(block: () -> T): T {
        val result = mutex.withLock(block)
        emitState()
        return result
    }

    private fun emitState() {
        synchronized(emitLock) {
            val s = state()
            if (s == lastEmitted) return
            lastEmitted = s
            try {
                emitter.emit(EVENT_STATE, s)
            } catch (e: Exception) {
                Log.w(TAG, "cannot emit companion state", e)
            }
        }
    }

    private fun post(block: () -> Unit): Boolean = try {
        sinkExecutor.execute(block)
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    private fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*>? = try {
        sinkExecutor.schedule(block, delayMs, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        null
    }

    init {
        // Last in the class body: every field above is initialised before the loop thread reads it.
        loopThread.start()
    }

    companion object {
        private const val TAG = "VRCXCompanion"
        const val EVENT_STATE = "companion-state"

        /** `lastError` after `authFail`: the pairing was removed on the PC. */
        const val REVOKED = "revoked"
    }
}
