package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.DotNetException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Behaviour of the LogWatcher around the parser: threading, first run, sync, events, game state, skew, persistence. */
class LogWatcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val env = TestEnv()
    private val watchers = ArrayList<LogWatcher>()

    @After
    fun closeAll() = watchers.forEach { it.close() }

    private fun watcher(config: LogWatcher.Config = LogWatcher.Config(), root: File = tmp.root) =
        LogWatcher(root, env, config).also { watchers += it }

    private val berlin = ZoneId.of("Europe/Berlin")
    private val stampFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

    /** A log line stamped at [utcMs] in PC-local (Berlin) time. */
    private fun line(utcMs: Long, msg: String) =
        "%s Log        -  %s".format(stampFormat.format(Instant.ofEpochMilli(utcMs).atZone(berlin)), msg)

    private fun joined(name: String, at: String = "2024.05.10 12:00:00") = "$at Log        -  [Behaviour] OnPlayerJoined $name (usr_$name)\n"

    /** A PC file the tests append to, reported like the companion does (snapshot, then data). */
    private inner class Pc(val w: LogWatcher, val name: String = "output_log_2024-05-10_11-00-00.txt", val fileId: String = "fid") {
        var bytes = ByteArray(0)
        var creation = isoToTicks("2024-05-10T09:00:00Z")
        var lastWrite = isoToTicks("2024-05-10T09:00:00Z")

        fun meta() = PcFileMeta(name, fileId, creation, lastWrite, bytes.size.toLong())

        fun write(text: String, snapshot: Boolean = true) {
            val offset = bytes.size.toLong()
            val add = text.toByteArray()
            bytes += add
            lastWrite += 10_000_000
            if (snapshot) w.onSnapshot(listOf(meta()))
            w.onData(name, fileId, offset, add)
        }
    }

    private fun startSession(w: LogWatcher, id: String = "pc-1", info: CompanionInfo = companionInfo()) {
        w.onSessionStarted(id, info)
        w.onProcessState(vrchatRunning = true, steamVrRunning = false, pcUtcNowMs = 0)
    }

    private fun names(rows: JsonArray): List<String> = rows.map { it.jsonArray[3].jsonPrimitive.content }

    private fun queueNames(w: LogWatcher): List<String> = runBlocking { w.getLogLines() }.map {
        kotlinx.serialization.json.Json.parseToJsonElement(it.jsonPrimitive.content).jsonArray[3].jsonPrimitive.content
    }

    @Test
    fun startsWithUpstreamDefaults() {
        val w = watcher()
        assertFalse(w.isGameRunning)
        assertFalse(w.isSteamVRRunning)
        assertFalse(w.vrcClosedGracefully)
        assertEquals(0L, w.sinceUtcTicks())
        assertTrue(w.have().isEmpty())
        assertEquals(JsonArray(emptyList()), runBlocking { w.get() })
        assertEquals(JsonArray(emptyList()), runBlocking { w.getLogLines() })
    }

    @Test
    fun nothingIsParsedBeforeSetDateTill() {
        val w = watcher()
        startSession(w)
        val pc = Pc(w)
        pc.write(joined("Early"))
        w.awaitIdle()
        assertEquals(0, queueNames(w).size)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        assertEquals(listOf(isoToTicks("2024-05-01T00:00:00Z")), env.tillChanges)
        assertEquals(isoToTicks("2024-05-01T00:00:00Z"), w.sinceUtcTicks())
        w.onSyncComplete()
        assertEquals(listOf("Early"), names(runBlocking { w.get() }))
    }

    @Test
    fun firstRunLastsUntilTheInitialSync() {
        val w = watcher()
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        val pc = Pc(w)
        pc.write(joined("Backlog1"))
        pc.write(joined("Backlog2"))
        w.awaitIdle()
        assertTrue("no live records during the initial sync", queueNames(w).isEmpty())
        assertFalse(env.eventNames().contains(LogWatcher.EVENT_LOG_AVAILABLE))

        w.onSyncComplete()
        pc.write(joined("Live"))
        w.awaitIdle()
        assertEquals(listOf("Live"), queueNames(w))
        assertTrue(env.eventNames().contains(LogWatcher.EVENT_LOG_AVAILABLE))
        // the startup Get() sees the backlog and (upstream quirk A) the live record
        assertEquals(listOf("Backlog1", "Backlog2", "Live"), names(runBlocking { w.get() }))
    }

    @Test
    fun firstGetWaitsForTheInitialSync() = runBlocking {
        val w = watcher(LogWatcher.Config(initialSyncWaitMs = 10_000))
        startSession(w)
        w.setDateTill("2024-05-01T00:00:00.000Z")
        Pc(w).write(joined("Backlog"))
        val started = System.nanoTime()
        val pending = async(kotlinx.coroutines.Dispatchers.IO) { w.get() }
        delay(300)
        assertFalse("Get() waits while the companion syncs", pending.isCompleted)
        w.onSyncComplete()
        assertEquals(listOf("Backlog"), names(pending.await()))
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(waitedMs in 300..9_000)
    }

    @Test
    fun firstGetGivesUpAfterTheTimeoutAndLaterDataIsLive() = runBlocking {
        val w = watcher(LogWatcher.Config(initialSyncWaitMs = 300))
        startSession(w)
        w.setDateTill("2024-05-01T00:00:00.000Z")
        val pc = Pc(w)
        pc.write(joined("Partial"))
        val started = System.nanoTime()
        assertEquals(listOf("Partial"), names(w.get()))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 250)
        // the late companion's catch-up is processed as live records, in one pass when its sync completes
        pc.write(joined("CatchUp"))
        w.awaitIdle()
        assertTrue(queueNames(w).isEmpty())
        w.onSyncComplete()
        assertEquals(listOf("CatchUp"), queueNames(w))
    }

    @Test
    fun getDoesNotWaitWithoutACompanion() = runBlocking {
        val w = watcher(LogWatcher.Config(initialSyncWaitMs = 5_000))
        w.setDateTill("2024-05-01T00:00:00.000Z")
        val started = System.nanoTime()
        w.get()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        env.connecting = true
        // only the first Get() may wait
        val again = System.nanoTime()
        w.get()
        assertTrue((System.nanoTime() - again) / 1_000_000 < 2_000)
    }

    @Test
    fun getWaitsWhileTheCompanionIsConnecting() = runBlocking {
        val w = watcher(LogWatcher.Config(initialSyncWaitMs = 400))
        env.connecting = true
        w.setDateTill("2024-05-01T00:00:00.000Z")
        val started = System.nanoTime()
        w.get()
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 350)
    }

    @Test
    fun getReturnsAtMost1000RowsWithNullsKept() = runBlocking {
        val w = watcher()
        startSession(w)
        w.setDateTill("2024-05-01T00:00:00.000Z")
        val sb = StringBuilder()
        sb.append("2024.05.10 12:00:00 Log        -  [Behaviour] Joining wrld_x:1\n")
        for (i in 0 until 1500) sb.append("2024.05.10 12:00:01 Log        -  [API] [$i] Sending Get request to https://x/$i\n")
        Pc(w).write(sb.toString())
        w.onSyncComplete()
        val first = w.get()
        assertEquals(1000, first.size)
        val location = first[0].jsonArray
        assertEquals(JsonNull, location[4])
        assertEquals(JsonPrimitive("location"), location[2])
        assertEquals(501, w.get().size)
        assertEquals(0, w.get().size)
    }

    @Test
    fun listIsCappedAfterTheStartupDrain() = runBlocking {
        val w = watcher(LogWatcher.Config(listCap = 3))
        startSession(w)
        w.setDateTill("2024-05-01T00:00:00.000Z")
        w.onSyncComplete()
        assertEquals(0, w.get().size)
        val pc = Pc(w)
        for (i in 1..5) pc.write(joined("L$i"))
        w.awaitIdle()
        assertEquals(listOf("L3", "L4", "L5"), names(w.get()))
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), queueNames(w))
    }

    @Test
    fun resetStartsANewFirstRun() = runBlocking {
        val w = watcher()
        startSession(w)
        w.setDateTill("2024-05-01T00:00:00.000Z")
        val pc = Pc(w)
        pc.write(joined("A"))
        w.onSyncComplete()
        assertEquals(listOf("A"), names(w.get()))
        pc.write(joined("B"))
        w.awaitIdle()
        w.reset()
        assertEquals(listOf("A", "B"), names(w.get()))
        // the queue is not cleared by Reset (upstream), and the re-parse did not queue anything
        assertEquals(listOf("B"), queueNames(w))
    }

    @Test
    fun gameStateEventsAndDisconnectGrace() {
        val w = watcher(LogWatcher.Config(disconnectGraceMs = 300))
        w.onSessionStarted("pc-1", companionInfo())
        w.onProcessState(vrchatRunning = true, steamVrRunning = true, pcUtcNowMs = 0)
        assertTrue(w.isGameRunning)
        assertTrue(w.isSteamVRRunning)
        val first = env.events.last { it.first == LogWatcher.EVENT_GAME_STATE }.second!!.jsonObject
        assertEquals(true, first["isGameRunning"]!!.jsonPrimitive.boolean)
        assertEquals(true, first["isSteamVRRunning"]!!.jsonPrimitive.boolean)
        // unchanged state: no event
        val count = env.eventNames().count { it == LogWatcher.EVENT_GAME_STATE }
        w.onProcessState(vrchatRunning = true, steamVrRunning = true, pcUtcNowMs = 0)
        assertEquals(count, env.eventNames().count { it == LogWatcher.EVENT_GAME_STATE })

        // a reconnect within the grace period keeps the state
        w.onDisconnected()
        assertTrue(w.isGameRunning)
        w.onSessionStarted("pc-1", companionInfo())
        Thread.sleep(500)
        assertTrue(w.isGameRunning)

        // after the grace period the state falls back to false
        w.onDisconnected()
        Thread.sleep(700)
        w.awaitIdle()
        assertFalse(w.isGameRunning)
        assertFalse(w.isSteamVRRunning)
        val last = env.events.last { it.first == LogWatcher.EVENT_GAME_STATE }.second as JsonObject
        assertEquals(false, last["isGameRunning"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun reconnectPublishesAStopAfterTheMissedBytes() {
        val w = watcher(LogWatcher.Config(logAvailableIntervalMs = 60_000))
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        val pc = Pc(w)
        pc.write(joined("Before"))
        w.awaitIdle()
        assertEquals(listOf("Before"), queueNames(w))
        w.onDisconnected()

        // VRChat quit while the phone was away. On reconnect `process` comes first (PROTOCOL.md §5.5), then the bytes
        // written before the quit, then syncComplete.
        w.onSessionStarted("pc-1", companionInfo())
        val mark = env.events.size
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        assertTrue("the stop waits for the missed bytes", w.isGameRunning)
        pc.write(joined("Missed", "2024.05.10 12:00:01") + "2024.05.10 12:00:02 Log        -  VRCApplication: OnApplicationQuit at 5.0\n")
        w.awaitIdle()
        assertTrue(w.isGameRunning)
        // the catch-up is parsed in one pass at syncComplete, before the stop is published
        assertFalse(w.vrcClosedGracefully)
        w.onSyncComplete()
        assertTrue(w.vrcClosedGracefully)
        assertFalse(w.isGameRunning)
        val after = synchronized(env.events) { env.events.drop(mark).map { it.first } }
        assertEquals(listOf(LogWatcher.EVENT_LOG_AVAILABLE, LogWatcher.EVENT_GAME_STATE), after)
        val types = runBlocking { w.getLogLines() }.map {
            kotlinx.serialization.json.Json.parseToJsonElement(it.jsonPrimitive.content).jsonArray[2].jsonPrimitive.content
        }
        assertEquals(listOf("player-joined", "vrc-quit"), types)
    }

    @Test
    fun startsAndOtherCompanionsArePublishedAtOnce() {
        val w = watcher(LogWatcher.Config(disconnectGraceMs = 300))
        startSession(w, "pc-a")
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        assertFalse(w.isGameRunning)
        // VRChat started while the phone was away: the new session's records must see a running game
        w.onDisconnected()
        w.onSessionStarted("pc-a", companionInfo())
        w.onProcessState(vrchatRunning = true, steamVrRunning = true, pcUtcNowMs = 0)
        assertTrue(w.isGameRunning)
        assertTrue(w.isSteamVRRunning)
        // a stop reported by another PC is not a catch-up of the published state
        w.onDisconnected()
        w.onSessionStarted("pc-b", companionInfo())
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        assertFalse(w.isGameRunning)
        w.onSyncComplete()

        // a held-back stop is dropped when the catch-up breaks off; the grace period applies instead
        w.onProcessState(vrchatRunning = true, steamVrRunning = false, pcUtcNowMs = 0)
        w.onDisconnected()
        w.onSessionStarted("pc-b", companionInfo())
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        assertTrue(w.isGameRunning)
        w.onDisconnected()
        assertTrue(w.isGameRunning)
        Thread.sleep(600)
        w.awaitIdle()
        assertFalse(w.isGameRunning)
    }

    @Test
    fun logAvailableIsThrottledAndPrecedesGameState() {
        val w = watcher(LogWatcher.Config(logAvailableIntervalMs = 60_000))
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        val pc = Pc(w)
        pc.write(joined("One"))
        pc.write(joined("Two"))
        w.awaitIdle()
        assertEquals(1, env.eventNames().count { it == LogWatcher.EVENT_LOG_AVAILABLE })
        // a process change first announces the records written before it
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        val tail = env.eventNames().takeLast(2)
        assertEquals(listOf(LogWatcher.EVENT_LOG_AVAILABLE, LogWatcher.EVENT_GAME_STATE), tail)
    }

    @Test
    fun logAvailableRepeatsAfterTheInterval() {
        val w = watcher(LogWatcher.Config(logAvailableIntervalMs = 200))
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        val pc = Pc(w)
        pc.write(joined("One"))
        pc.write(joined("Two"))
        w.awaitIdle()
        assertEquals(1, env.eventNames().count { it == LogWatcher.EVENT_LOG_AVAILABLE })
        Thread.sleep(600)
        w.awaitIdle()
        assertEquals(2, env.eventNames().count { it == LogWatcher.EVENT_LOG_AVAILABLE })
        // the interval has passed: announced at once; the next record waits, and draining the queue cancels it
        pc.write(joined("Three"))
        pc.write(joined("Four"))
        w.awaitIdle()
        assertEquals(3, env.eventNames().count { it == LogWatcher.EVENT_LOG_AVAILABLE })
        assertEquals(listOf("One", "Two", "Three", "Four"), queueNames(w))
        Thread.sleep(600)
        w.awaitIdle()
        assertEquals(3, env.eventNames().count { it == LogWatcher.EVENT_LOG_AVAILABLE })
    }

    @Test
    fun finalTailIsParsedWhenVrchatStops() {
        val w = watcher()
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        val pc = Pc(w)
        pc.write(joined("Complete") + "2024.05.10 12:00:01 Log        -  VRCApplication: OnApplicationQuit at 12.5")
        w.awaitIdle()
        assertEquals(listOf("Complete"), queueNames(w))
        assertFalse(w.vrcClosedGracefully)
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        assertTrue(w.vrcClosedGracefully)
        val quit = runBlocking { w.getLogLines() }.single().jsonPrimitive.content
        assertEquals("[\"output_log_2024-05-10_11-00-00.txt\",\"2024-05-10T10:00:01.000Z\",\"vrc-quit\"]", quit)
    }

    @Test
    fun tailIsNotFinalDuringTheInitialSync() {
        val w = watcher()
        w.onSessionStarted("pc-1", companionInfo())
        w.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        val pc = Pc(w)
        val full = joined("SplitAcrossFrames")
        // a stale snapshot length: after the first frame the mirror looks complete, but VRChat's stop only makes the
        // file final once the initial sync is over, so the frame boundary does not split the line
        w.onSnapshot(listOf(PcFileMeta(pc.name, pc.fileId, pc.creation, pc.lastWrite, 30)))
        w.onData(pc.name, pc.fileId, 0, full.substring(0, 30).toByteArray())
        w.awaitIdle()
        w.onData(pc.name, pc.fileId, 30, full.substring(30).toByteArray())
        w.onSyncComplete()
        assertEquals(listOf("SplitAcrossFrames"), names(runBlocking { w.get() }))
    }

    @Test
    fun futureCheckUsesThePcClock() {
        val w = watcher()
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        val pc = Pc(w)
        pc.write(line(env.now + 60 * 60_000, "[Behaviour] OnPlayerJoined In60 (usr_a)") + "\n" +
            line(env.now + 62 * 60_000, "[Behaviour] OnPlayerJoined In62 (usr_b)") + "\n")
        w.awaitIdle()
        assertEquals(listOf("In60"), queueNames(w))
        assertTrue(env.logs.any { it.startsWith("WARN Invalid log time, too new") })
        // with the PC clock two hours ahead the same kind of line is accepted
        w.onClockSkew(2 * 3600_000L)
        pc.write(line(env.now + 150 * 60_000, "[Behaviour] OnPlayerJoined Skewed (usr_c)") + "\n")
        w.awaitIdle()
        assertEquals(listOf("Skewed"), queueNames(w))
    }

    @Test
    fun setDateTillAppliesClockSkewToANowFallback() {
        val w = watcher()
        w.onSessionStarted("pc-1", companionInfo(pcUtcNowMs = env.now + 30_000))
        val nowIso = Instant.ofEpochMilli(env.now).toString()
        runBlocking { w.setDateTill(nowIso) }
        assertEquals(Ticks.fromEpochMs(env.now + 30_000), w.sinceUtcTicks())
        // a stored date (not "now") is used as is
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        assertEquals(isoToTicks("2024-05-01T00:00:00Z"), w.sinceUtcTicks())
    }

    @Test
    fun setDateTillAppliesALaterSkewOnce() {
        val w = watcher()
        runBlocking { w.setDateTill(Instant.ofEpochMilli(env.now).toString()) }
        assertEquals(Ticks.fromEpochMs(env.now), w.sinceUtcTicks())
        w.onClockSkew(-5_000)
        assertEquals(Ticks.fromEpochMs(env.now - 5_000), w.sinceUtcTicks())
        assertEquals(Ticks.fromEpochMs(env.now - 5_000), env.tillChanges.last())
        w.onClockSkew(-9_000)
        assertEquals(Ticks.fromEpochMs(env.now - 5_000), w.sinceUtcTicks())
    }

    @Test
    fun setDateTillRejectsLikeDotNet() = runBlocking {
        val w = watcher()
        for ((input, type) in listOf(null to "ArgumentNullException", "garbage" to "FormatException", "2024-02-30T00:00:00Z" to "FormatException")) {
            try {
                w.setDateTill(input)
                fail("expected $type for $input")
            } catch (e: DotNetException) {
                assertEquals(type, e.type)
            }
        }
        assertEquals(0L, w.sinceUtcTicks())
    }

    @Test
    fun mirrorAndTimeZoneSurviveARestart() {
        val root = tmp.newFolder("persist")
        val first = watcher(root = root)
        startSession(first, "pc-7", companionInfo("Eastern Standard Time", "America/New_York", baseMin = -300))
        Pc(first).write(joined("Persisted"))
        first.onDisconnected()
        first.awaitIdle()
        first.close()

        val second = watcher(root = root)
        assertEquals(listOf(MirroredFile("output_log_2024-05-10_11-00-00.txt", "fid", joined("Persisted").length.toLong())), second.have())
        runBlocking { second.setDateTill("2024-05-01T00:00:00.000Z") }
        val row = runBlocking { second.get() }.single().jsonArray
        // 12:00 in New York (EDT) is 16:00Z
        assertEquals(JsonPrimitive("2024-05-10T16:00:00.000Z"), row[1])
    }

    @Test
    fun switchingCompanionsUsesSeparateMirrors() {
        val w = watcher()
        startSession(w, "pc-a")
        Pc(w, name = "output_log_a.txt").write(joined("A"))
        startSession(w, "pc-b")
        assertTrue(w.have().isEmpty())
        Pc(w, name = "output_log_b.txt").write(joined("B"))
        assertEquals(listOf("output_log_b.txt"), w.have().map { it.name })
        startSession(w, "pc-a")
        assertEquals(listOf("output_log_a.txt"), w.have().map { it.name })
        w.forgetCompanion("pc-b")
        assertFalse(File(tmp.root, "pc-b").exists())
        w.forgetCompanion("pc-a")
        assertTrue(w.have().isEmpty())
        assertFalse(File(tmp.root, "pc-a").exists())
    }

    @Test
    fun gapsAreReportedOnceUntilTheFetchAnswerArrives() {
        val w = watcher()
        val requests = ArrayList<ResyncRequest>()
        val control: CompanionMirrorControl = w
        control.setFetchRequester { name, fileId, fromOffset -> requests += ResyncRequest(name, fileId, fromOffset) }
        startSession(w)
        val pc = Pc(w)
        pc.write("0123456789")
        w.onData(pc.name, pc.fileId, 20, "late".toByteArray())
        w.onData(pc.name, pc.fileId, 24, "later".toByteArray())
        assertEquals(listOf(ResyncRequest(pc.name, pc.fileId, 10)), requests)
        assertEquals(requests, w.pendingResyncs())
        // a new session asks again for a gap that is still open
        w.onDisconnected()
        startSession(w)
        w.onData(pc.name, pc.fileId, 24, "later".toByteArray())
        assertEquals(2, requests.size)
        w.onData(pc.name, pc.fileId, 10, "abc".toByteArray())
        assertTrue(w.pendingResyncs().isEmpty())
        assertEquals(13L, w.have().single().length)
        // without a requester the gap is only listed
        control.setFetchRequester(null)
        w.onData(pc.name, pc.fileId, 30, "x".toByteArray())
        assertEquals(2, requests.size)
        assertEquals(listOf(ResyncRequest(pc.name, pc.fileId, 13)), w.pendingResyncs())
    }

    // ------------------------------------------------------------------------------------------------------------
    // One Update() per sync (a throwing line re-emits the records before it on each growth)

    /** A file whose second line throws in ParseUserInfo; the records after it are lost, as upstream. */
    private fun badFile(before: Int): String {
        val sb = StringBuilder()
        for (i in 0 until before) sb.append(joined("BeforeBad$i", "2024.05.10 12:00:00"))
        sb.append("2024.05.10 12:00:01 Log        -  [Behaviour] OnPlayerJoined Broken (name\n")
        for (i in 0 until 40) sb.append(joined("After$i", "2024.05.10 12:00:02"))
        return sb.toString()
    }

    /** Sends [text] as the companion does during a sync: one snapshot with the full length, then [frames] data frames. */
    private fun sendInFrames(w: LogWatcher, pc: Pc, text: String, frames: Int) {
        val all = text.toByteArray()
        val start = pc.bytes.size
        pc.bytes += all
        pc.lastWrite += 10_000_000
        w.onSnapshot(listOf(pc.meta()))
        val per = (all.size + frames - 1) / frames
        var off = 0
        while (off < all.size) {
            val end = minOf(all.size, off + per)
            w.onData(pc.name, pc.fileId, (start + off).toLong(), all.copyOfRange(off, end))
            off = end
        }
    }

    private fun drainGet(w: LogWatcher): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val rows = runBlocking { w.get() }
            if (rows.isEmpty()) return out
            out += names(rows)
        }
    }

    @Test
    fun initialSyncInManyFramesIsParsedOnce() {
        val w = watcher()
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        sendInFrames(w, Pc(w), badFile(3), frames = 20)
        w.onSyncComplete()
        // upstream parses the complete file once: the records before the bad line once, nothing after it
        assertEquals(listOf("BeforeBad0", "BeforeBad1", "BeforeBad2"), drainGet(w))
        assertEquals(1, env.logs.count { it.startsWith("WARN Failed to parse log file") })
        assertTrue(queueNames(w).isEmpty())
    }

    @Test
    fun catchUpAfterAReconnectIsParsedOnce() {
        val w = watcher(LogWatcher.Config(logAvailableIntervalMs = 0))
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        assertTrue(drainGet(w).isEmpty())
        val pc = Pc(w)
        pc.write(joined("Live"))
        w.awaitIdle()
        assertEquals(listOf("Live"), queueNames(w))
        w.onDisconnected()
        startSession(w)
        sendInFrames(w, pc, badFile(2), frames = 12)
        w.awaitIdle()
        assertTrue("nothing is parsed before the catch-up completes", queueNames(w).isEmpty())
        w.onSyncComplete()
        assertEquals(listOf("BeforeBad0", "BeforeBad1"), queueNames(w))
        // live growth after the sync re-emits them, like each upstream poll that sees the file grow
        pc.write(joined("Later"))
        w.awaitIdle()
        assertEquals(listOf("BeforeBad0", "BeforeBad1"), queueNames(w))
    }

    @Test
    fun persistedMirrorAndItsCatchUpAreParsedOnceAfterARestart() {
        val root = tmp.newFolder("restart")
        val first = watcher(root = root)
        startSession(first)
        val pc = Pc(first)
        sendInFrames(first, pc, badFile(2), frames = 3)
        first.onSyncComplete()
        first.onDisconnected()
        first.awaitIdle()
        first.close()

        val second = watcher(root = root)
        runBlocking { second.setDateTill("2024-05-01T00:00:00.000Z") }
        startSession(second)
        val resumed = Pc(second).also {
            it.bytes = pc.bytes
            it.lastWrite = pc.lastWrite
        }
        sendInFrames(second, resumed, joined("Appended", "2024.05.10 12:00:03"), frames = 2)
        second.onSyncComplete()
        assertEquals(listOf("BeforeBad0", "BeforeBad1"), drainGet(second))
    }

    @Test
    fun anInterruptedSyncIsParsedAtTheDisconnect() {
        val w = watcher()
        startSession(w)
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        w.onSyncComplete()
        assertTrue(drainGet(w).isEmpty())
        w.onDisconnected()
        startSession(w)
        val pc = Pc(w)
        pc.write(joined("Arrived"))
        w.awaitIdle()
        assertTrue(queueNames(w).isEmpty())
        w.onDisconnected()
        assertEquals(listOf("Arrived"), queueNames(w))
    }

    @Test
    fun forgetThenPairTheSamePcAgainStartsAFreshMirror() {
        val w = watcher()
        startSession(w, "pc-1")
        runBlocking { w.setDateTill("2024-05-01T00:00:00.000Z") }
        val pc = Pc(w)
        pc.write(joined("Old"))
        w.onSyncComplete()
        assertEquals(listOf("Old"), drainGet(w))
        w.onDisconnected()
        val control: CompanionMirrorControl = w
        control.forgetCompanion("pc-1")
        assertFalse(File(tmp.root, "pc-1").exists())

        // paired again without an app restart: subscribe.have is empty, so the companion sends everything from 0
        startSession(w, "pc-1")
        assertTrue(w.have().isEmpty())
        val again = Pc(w)
        again.write(joined("Old") + joined("New", "2024.05.10 12:00:05"))
        w.onSyncComplete()
        assertEquals(listOf(MirroredFile(again.name, again.fileId, again.bytes.size.toLong())), w.have())
        assertEquals(again.bytes.size.toLong(), File(File(tmp.root, "pc-1"), "f1.log").length())
        assertEquals(listOf("Old", "New"), queueNames(w))
    }

    @Test
    fun aMirrorDirectoryDeletedFromOutsideStartsOver() {
        val w = watcher()
        startSession(w, "pc-1")
        val pc = Pc(w)
        pc.write(joined("A"))
        w.onDisconnected()
        w.awaitIdle()
        assertTrue(File(tmp.root, "pc-1").deleteRecursively())
        startSession(w, "pc-1")
        assertTrue(w.have().isEmpty())
        val fresh = Pc(w)
        fresh.write(joined("B"))
        assertEquals(listOf(MirroredFile(fresh.name, fresh.fileId, fresh.bytes.size.toLong())), w.have())
    }

    @Test
    fun replacedFileIsParsedFromTheStart() = runBlocking {
        val w = watcher()
        startSession(w)
        w.setDateTill("2024-05-01T00:00:00.000Z")
        w.onSyncComplete()
        val pc = Pc(w)
        pc.write(joined("Old1") + joined("Old2"))
        w.awaitIdle()
        assertEquals(listOf("Old1", "Old2"), queueNames(w))
        // same name, new fileId, shorter content: a new file for upstream too once a poll saw the change
        val fresh = Pc(w, fileId = "fid-2")
        fresh.write(joined("New"))
        w.awaitIdle()
        assertEquals(listOf("New"), queueNames(w))
    }

    @Test
    fun sinkCallsNeverThrowAtTheCompanionClient() {
        val w = watcher()
        // no session yet: ignored with a warning
        w.onSnapshot(listOf(PcFileMeta("a", "x", 0, 0, 1)))
        w.onData("a", "x", 0, byteArrayOf(1))
        w.onTruncate("a", "x", 0)
        assertTrue(env.logs.count { it.contains("without a session") } >= 3)
    }
}
