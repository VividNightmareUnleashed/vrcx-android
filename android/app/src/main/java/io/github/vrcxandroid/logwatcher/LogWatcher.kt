package io.github.vrcxandroid.logwatcher

import android.content.Context
import android.util.Log
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.GameStateProvider
import io.github.vrcxandroid.bridge.DotNetException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Kotlin port of upstream `Dotnet/LogWatcher.cs` over the companion log mirror (
 * docs/ARCHITECTURE.md §8), plus the game state the companion reports.
 *
 * Threading: all state lives on one dedicated thread ("LogWatcher"). The bridge methods ([setDateTill], [get],
 * [getLogLines], [reset]) hop to it; the [LogSink] calls block their caller until they are applied, in wire order,
 * which gives the companion client natural backpressure. Nothing polls: `Update()` runs when data, a snapshot, a
 * truncation, a process change or the end of the initial sync arrives (after `SetDateTill`, like upstream's thread),
 * and inside `Get()`.
 *
 * Delivery is pull mode only (`LINUX = true` frontend): records parsed after the first run are queued for
 * `GetLogLines()`, and the page is told with the event `log-available` (no payload) at most once per second.
 * `game-state` `{isGameRunning, isSteamVRRunning}` is emitted on every change, after any `log-available` for records
 * that precede the change.
 *
 * Deliberate differences from upstream:
 * - only complete lines are parsed; an unterminated tail waits for its terminator unless the file is final
 *   (a newer file exists, or the live companion reports VRChat stopped) and fully mirrored;
 * - `m_FirstRun` stays true until the companion's initial sync completed or the first `Get()` ran, and that first
 *   `Get()` waits up to 8 s for the initial sync while a companion session is syncing or connecting;
 * - after the startup `Get()` loop has emptied the list, the list keeps only the newest 10 000 records (nothing
 *   reads it again), and the pull queue is capped at 100 000 lines;
 * - `SetDateTill` shifts a tillDate within 5 s of the phone clock by the PC clock skew.
 *
 * Gap handling for the companion client: when `data` arrives beyond the mirrored length, the bytes are dropped and a
 * [ResyncRequest] is reported through [resyncListener] (once per gap, on the LogWatcher thread; the listener must not
 * block) and listed by [pendingResyncs] until contiguous data arrives. The client answers it with a `fetch`
 * (PROTOCOL.md §5.9).
 */
class LogWatcher internal constructor(
    private val root: File,
    private val env: Env,
    private val config: Config = Config(),
) : GameStateProvider, LogSink {

    /** Production constructor used by AppGraph: the mirror lives under `filesDir/logmirror/`. */
    constructor(context: Context) : this(File(context.filesDir, "logmirror"), AndroidEnv)

    internal class Config(
        val disconnectGraceMs: Long = 120_000,
        val logAvailableIntervalMs: Long = 1_000,
        val initialSyncWaitMs: Long = 8_000,
        val indexFlushDelayMs: Long = 30_000,
        val listCap: Int = 10_000,
        val queueCap: Int = 100_000,
    )

    /** The outside world, replaced by fakes in the JVM tests. */
    internal interface Env {
        /** Phone wall clock, epoch milliseconds. */
        fun nowMs(): Long

        /** Native -> page event (BridgeDispatcher.emit). Must not block. */
        fun emit(event: String, data: JsonElement?)

        /** Tells the companion client the new tillDate (CompanionController.onTillDateChanged). Must not block. */
        fun onTillDateChanged(utcTicks: Long)

        /** Whether a companion connection attempt is in progress (worth waiting for its initial sync). */
        fun companionConnecting(): Boolean

        val log: LwLog
    }

    @Volatile
    private var thread: Thread? = null
    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "LogWatcher").also {
            it.isDaemon = true
            thread = it
        }
    }.apply { removeOnCancelPolicy = true }
    private val dispatcher = executor.asCoroutineDispatcher()

    // --- state confined to the LogWatcher thread ---
    private val engine = LogEngine(
        pcNowMs = { env.nowMs() + skewMs },
        log = env.log,
        onQueued = { unsignalled = true },
        listCap = config.listCap,
        queueCap = config.queueCap,
    )
    private var mirror: LogMirror? = null
    private var threadActive = false
    private var tillSet = false
    private var skewKnown = false
    private var skewFromHeartbeat = false
    private var tillAwaitingSkew = false
    private var processKnown = false
    private var vrchatRunning = false
    private var initialSyncDone = false
    private var getCalled = false
    private var updatePending = false
    private var unsignalled = false
    private var lastLogAvailableNs = 0L
    private var logAvailableEverSent = false
    private var logAvailableScheduled = false
    private var graceTask: ScheduledFuture<*>? = null
    private var indexFlushTask: ScheduledFuture<*>? = null
    private val reportedResyncs = HashMap<String, ResyncRequest>()

    // --- read from other threads ---
    @Volatile
    private var skewMs = 0L

    @Volatile
    private var sessionActive = false

    @Volatile
    private var syncing = false

    @Volatile
    private var gameRunning = false

    @Volatile
    private var steamVrRunning = false

    private val initialSync = CompletableDeferred<Unit>()

    /** Called once per detected gap with the `fetch` the companion client should send. See the class KDoc. */
    @Volatile
    var resyncListener: ((ResyncRequest) -> Unit)? = null

    init {
        executor.execute { loadActiveMirror() }
    }

    // ================================================================================================================
    // GameStateProvider

    override val isGameRunning: Boolean get() = gameRunning
    override val isSteamVRRunning: Boolean get() = steamVrRunning

    /** Upstream `LogWatcher.VrcClosedGracefully`: false on each `location`, true on `vrc-quit`. */
    override val vrcClosedGracefully: Boolean get() = engine.vrcClosedGracefully

    // ================================================================================================================
    // Bridge methods (LogWatcherModule)

    /**
     * `SetDateTill(date)`: `DateTime.Parse(date, InvariantCulture).ToUniversalTime()`, then starts updating.
     * Throws `ArgumentNullException` / `FormatException` like .NET.
     */
    suspend fun setDateTill(date: String?) {
        onThread {
            if (date == null) throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 's')")
            var ticks = DotNetDateParse.toUtcTicks(date, engine.zone)
            tillAwaitingSkew = false
            // the frontend's fallback tillDate is "now" on the phone clock; convert it to the PC clock.
            if (abs(ticks - Ticks.fromEpochMs(env.nowMs())) <= 5 * Ticks.PER_SECOND) {
                if (skewKnown) {
                    if (skewMs != 0L) {
                        ticks += skewMs * Ticks.PER_MS
                        env.log.log(LwLog.INFO, "SetDateTill: applied PC clock skew of $skewMs ms", null)
                    }
                } else {
                    tillAwaitingSkew = true
                }
            }
            engine.tillDateTicks = Ticks.clamp(ticks)
            tillSet = true
            threadActive = true
            env.log.log(LwLog.INFO, "SetDateTill: ${Ticks.formatIso(engine.tillDateTicks)}", null)
            env.onTillDateChanged(engine.tillDateTicks)
            requestUpdate()
        }
    }

    /**
     * `Get()`: runs `Update()` and returns up to 1000 of the oldest listed records as arrays whose elements are
     * strings or null. The first call waits (suspends, at most [Config.initialSyncWaitMs]) for the companion's
     * initial sync while one is in progress.
     */
    suspend fun get(): JsonArray {
        val mayWait = onThread { !getCalled && !initialSyncDone }
        if (mayWait && (sessionActive || env.companionConnecting())) {
            withTimeoutOrNull(config.initialSyncWaitMs) { initialSync.await() }
        }
        val rows = onThread {
            getCalled = true
            runUpdate()
            val batch = engine.takeBatch()
            if (batch.isEmpty()) engine.listCapActive = true
            batch
        }
        return JsonArray(rows.map { row -> JsonArray(row.map { if (it == null) JsonNull else JsonPrimitive(it) }) })
    }

    /** `GetLogLines()`: the queued records, each as its JSON text. */
    suspend fun getLogLines(): JsonArray {
        val lines = onThread {
            unsignalled = false
            engine.takeLogLines()
        }
        return JsonArray(lines.map(::JsonPrimitive))
    }

    /** `Reset()`: the next `Update()` forgets every context and the list and starts a new first run. */
    suspend fun reset() {
        onThread {
            engine.resetLog = true
            // Upstream interrupts its thread's sleep; the thread only updates while active.
            if (threadActive) runUpdate()
        }
    }

    // ================================================================================================================
    // LogSink (companion client)

    override fun onSessionStarted(companionId: String, info: CompanionInfo): Unit = sink("onSessionStarted", Unit) {
        cancelGrace()
        if (mirror?.companionId != companionId) {
            val previous = mirror
            previous?.flushIndex()
            mirror = LogMirror(dirFor(companionId), companionId, env.log).also { it.load() }
            if (previous != null) engine.forgetAll()
            reportedResyncs.clear()
            writeActive(companionId)
        }
        sessionActive = true
        syncing = true
        processKnown = false
        applyInfo(info)
        requestUpdate()
    }

    override fun onInfo(info: CompanionInfo): Unit = sink("onInfo", Unit) { applyInfo(info) }

    override fun have(): List<MirroredFile> = sink("have", emptyList()) { mirror?.have() ?: emptyList() }

    override fun sinceUtcTicks(): Long = sink("sinceUtcTicks", 0L) { if (tillSet) engine.tillDateTicks else 0L }

    override fun onSnapshot(files: List<PcFileMeta>): Unit = sink("onSnapshot", Unit) {
        val m = mirror ?: return@sink noSession("snapshot")
        // A file that left the snapshot (or came back with a new fileId) is forgotten, like upstream's Update()
        // does when a poll does not see it: a file with that name is then parsed from 0.
        for (name in m.applySnapshot(files)) {
            engine.forget(name)
            reportedResyncs.remove(name)
        }
        if (m.isDirty) scheduleIndexFlush()
        requestUpdate()
    }

    override fun onData(name: String, fileId: String, offset: Long, bytes: ByteArray): Unit = sink("onData", Unit) {
        val m = mirror ?: return@sink noSession("data")
        val result = try {
            m.append(name, fileId, offset, bytes)
        } catch (e: Exception) {
            env.log.log(LwLog.WARN, "log mirror write failed for $name: ${e.message}", e)
            val e2 = m.entry(name)
            reportResync(ResyncRequest(name, fileId, e2?.size ?: 0))
            return@sink
        }
        when (result) {
            LogMirror.AppendResult.APPENDED -> {
                reportedResyncs.remove(name)
                requestUpdate()
            }
            LogMirror.AppendResult.REPLACED_AND_APPENDED -> {
                reportedResyncs.remove(name)
                engine.forget(name)
                requestUpdate()
            }
            LogMirror.AppendResult.DUPLICATE -> Unit
            LogMirror.AppendResult.GAP -> m.pendingResyncs().firstOrNull { it.name == name }?.let(::reportResync)
        }
    }

    override fun onTruncate(name: String, fileId: String, newLength: Long): Unit = sink("onTruncate", Unit) {
        val m = mirror ?: return@sink noSession("truncate")
        if (m.truncate(name, fileId, newLength)) {
            reportedResyncs.remove(name)
            requestUpdate()
        }
    }

    override fun onProcessState(vrchatRunning: Boolean, steamVrRunning: Boolean, pcUtcNowMs: Long): Unit =
        sink("onProcessState", Unit) {
            cancelGrace()
            processKnown = true
            this.vrchatRunning = vrchatRunning
            // Bytes written before the change are parsed (and a final tail flushed) before the new state is shown.
            if (threadActive) runUpdate()
            setGameState(vrchatRunning, steamVrRunning)
        }

    override fun onSyncComplete(): Unit = sink("onSyncComplete", Unit) {
        syncing = false
        if (!initialSyncDone) {
            initialSyncDone = true
            initialSync.complete(Unit)
        }
        if (threadActive) runUpdate()
        mirror?.flushIndex()
    }

    override fun onClockSkew(skewMs: Long): Unit = sink("onClockSkew", Unit) {
        this.skewMs = skewMs
        skewKnown = true
        skewFromHeartbeat = true
        applyPendingTillSkew()
    }

    override fun onDisconnected(): Unit = sink("onDisconnected", Unit) {
        sessionActive = false
        syncing = false
        processKnown = false
        mirror?.flushIndex()
        cancelGrace()
        graceTask = executor.schedule(
            {
                graceTask = null
                setGameState(false, false)
            },
            config.disconnectGraceMs, TimeUnit.MILLISECONDS,
        )
    }

    // ================================================================================================================
    // Other API for the companion package

    /** Gaps waiting for a `fetch` answer. */
    fun pendingResyncs(): List<ResyncRequest> = sink("pendingResyncs", emptyList()) { mirror?.pendingResyncs() ?: emptyList() }

    /** Deletes the mirror of a companion the user forgot. */
    fun forgetCompanion(companionId: String): Unit = sink("forgetCompanion", Unit) {
        val m = mirror
        if (m != null && m.companionId == companionId) {
            m.deleteAll()
            mirror = null
            engine.forgetAll()
            reportedResyncs.clear()
            File(root, ACTIVE).delete()
        } else {
            dirFor(companionId).deleteRecursively()
        }
    }

    /** Waits until everything submitted so far has run on the LogWatcher thread (tests). */
    internal fun awaitIdle() {
        onThreadBlocking { }
    }

    internal fun close() {
        executor.shutdownNow()
    }

    // ================================================================================================================
    // Internals

    private fun loadActiveMirror() {
        try {
            val active = File(root, ACTIVE)
            if (!active.exists()) return
            val id = active.readText().trim()
            if (id.isEmpty()) return
            val m = LogMirror(dirFor(id), id, env.log)
            m.load()
            mirror = m
            engine.zone = PcZone.of(m.info) { env.log.log(LwLog.WARN, it, null) }
        } catch (e: Exception) {
            env.log.log(LwLog.WARN, "cannot load the log mirror: ${e.message}", e)
        }
    }

    private fun applyInfo(info: CompanionInfo) {
        mirror?.setInfo(info)
        val zone = PcZone.of(info) { env.log.log(LwLog.WARN, it, null) }
        if (zone.zone != engine.zone.zone) engine.zone = zone
        if (!skewFromHeartbeat && info.pcUtcNowMs > 0) {
            // First estimate until heartbeats provide a smoothed value (PROTOCOL.md §5.9).
            skewMs = info.pcUtcNowMs - env.nowMs()
            skewKnown = true
            applyPendingTillSkew()
        }
    }

    private fun applyPendingTillSkew() {
        if (!tillAwaitingSkew) return
        tillAwaitingSkew = false
        if (skewMs == 0L) return
        engine.tillDateTicks = Ticks.clamp(engine.tillDateTicks + skewMs * Ticks.PER_MS)
        env.log.log(LwLog.INFO, "SetDateTill: applied PC clock skew of $skewMs ms", null)
        env.onTillDateChanged(engine.tillDateTicks)
    }

    private fun requestUpdate() {
        if (!threadActive || updatePending) return
        updatePending = true
        executor.execute { if (updatePending) runUpdate() }
    }

    private fun runUpdate() {
        updatePending = false
        val m = mirror
        engine.update(if (m == null) emptyList() else views(m), endFirstRun = initialSyncDone || getCalled)
        signalLogAvailable(force = false)
    }

    private fun views(m: LogMirror): List<LogFileView> {
        val entries = m.entries().toList()
        val newest = entries.maxOfOrNull { it.creationTicks } ?: 0L
        val vrchatStopped = processKnown && !vrchatRunning && !syncing
        return entries.map { e ->
            val final = e.size >= e.pcLength && (e.creationTicks < newest || vrchatStopped)
            LogFileView(e.name, e.creationTicks, e.lastWriteTicks, e.size, final) { m.openRead(e) }
        }
    }

    private fun signalLogAvailable(force: Boolean) {
        if (!unsignalled) return
        val now = System.nanoTime()
        val intervalNs = config.logAvailableIntervalMs * 1_000_000
        val waitNs = if (logAvailableEverSent) lastLogAvailableNs + intervalNs - now else 0L
        if (force || waitNs <= 0) {
            unsignalled = false
            logAvailableEverSent = true
            lastLogAvailableNs = now
            env.emit(EVENT_LOG_AVAILABLE, null)
        } else if (!logAvailableScheduled) {
            logAvailableScheduled = true
            executor.schedule(
                {
                    logAvailableScheduled = false
                    signalLogAvailable(force = false)
                },
                waitNs, TimeUnit.NANOSECONDS,
            )
        }
    }

    private fun setGameState(vrc: Boolean, steamVr: Boolean) {
        if (vrc == gameRunning && steamVr == steamVrRunning) return
        signalLogAvailable(force = true)
        gameRunning = vrc
        steamVrRunning = steamVr
        env.emit(EVENT_GAME_STATE, buildJsonObject {
            put("isGameRunning", vrc)
            put("isSteamVRRunning", steamVr)
        })
    }

    private fun cancelGrace() {
        graceTask?.cancel(false)
        graceTask = null
    }

    private fun scheduleIndexFlush() {
        if (indexFlushTask != null) return
        indexFlushTask = executor.schedule(
            {
                indexFlushTask = null
                mirror?.flushIndex()
            },
            config.indexFlushDelayMs, TimeUnit.MILLISECONDS,
        )
    }

    private fun reportResync(r: ResyncRequest) {
        if (reportedResyncs[r.name] == r) return
        reportedResyncs[r.name] = r
        env.log.log(LwLog.WARN, "gap in ${r.name}: resync from ${r.fromOffset}", null)
        try {
            resyncListener?.invoke(r)
        } catch (e: Exception) {
            env.log.log(LwLog.WARN, "resync listener failed: ${e.message}", e)
        }
    }

    private fun noSession(what: String) {
        env.log.log(LwLog.WARN, "companion $what without a session; ignored", null)
    }

    private fun dirFor(companionId: String): File {
        val safe = companionId.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")
        return File(root, safe.ifEmpty { "_" }.let { if (it == "." || it == "..") "_$it" else it })
    }

    private fun writeActive(companionId: String) {
        try {
            root.mkdirs()
            LogMirror.writeAtomically(File(root, ACTIVE), companionId)
        } catch (e: Exception) {
            env.log.log(LwLog.WARN, "cannot persist the active companion: ${e.message}", e)
        }
    }

    private suspend fun <T> onThread(block: () -> T): T = withContext(dispatcher) { block() }

    private fun <T> onThreadBlocking(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val future = executor.submit(Callable { block() })
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Runs a LogSink call on the thread; failures are logged, never thrown at the companion client. */
    private inline fun <T> sink(what: String, fallback: T, crossinline block: () -> T): T = try {
        onThreadBlocking { block() }
    } catch (e: Exception) {
        env.log.log(LwLog.WARN, "LogWatcher.$what failed: ${e.message}", e)
        fallback
    }

    companion object {
        const val EVENT_LOG_AVAILABLE = "log-available"
        const val EVENT_GAME_STATE = "game-state"
        private const val ACTIVE = "active"
    }
}

/** Production [LogWatcher.Env]. */
private object AndroidEnv : LogWatcher.Env {
    private const val TAG = "VRCXLogWatcher"

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun emit(event: String, data: JsonElement?) {
        try {
            AppGraph.dispatcher.emit(event, data)
        } catch (e: UninitializedPropertyAccessException) {
            // before AppGraph.init: nobody listens yet
        }
    }

    override fun onTillDateChanged(utcTicks: Long) {
        // Asynchronous: the companion client may be blocked in a LogSink call waiting for the LogWatcher thread.
        AppGraph.scope.launch {
            try {
                AppGraph.companion.onTillDateChanged(utcTicks)
            } catch (e: UninitializedPropertyAccessException) {
                // no companion client yet
            }
        }
    }

    override fun companionConnecting(): Boolean = try {
        when (AppGraph.companion.state()["status"]?.jsonPrimitive?.contentOrNull) {
            "connecting", "searching" -> true
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    override val log = LwLog { level, message, error ->
        Log.println(level, TAG, if (error == null) message else message + "\n" + Log.getStackTraceString(error))
    }
}
