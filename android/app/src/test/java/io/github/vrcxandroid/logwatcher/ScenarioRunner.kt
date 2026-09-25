package io.github.vrcxandroid.logwatcher

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Plays a scenario script (test resources logwatcher/scenarios/, the same scripts the .NET differential harness runs
 * against upstream LogWatcher.cs) against the Kotlin [LogWatcher] and prints the same report format:
 *
 * ```
 * tz <windows id>[|<iana id>] | tz fixed <minutes>   PC time zone (session start on first use)
 * holdtail                                          harness only (the port always holds unterminated tails)
 * till <iso>                                        LogWatcher.SetDateTill
 * file <name> <creation iso>                        new empty PC file
 * write <name> <lastWrite iso> <fixture file>       append fixture bytes
 * text <name> <lastWrite iso> <text>                append text; escapes \n \r \t \\ \xHH (raw byte) \uXXXX
 * truncate <name> <lastWrite iso> <length>
 * touch <name> <lastWrite iso>
 * delete <name>
 * final                                             VRChat stopped (tails of finished files are parsed)
 * process <vrchat> <steamvr>                        process state (Kotlin only)
 * reset                                             LogWatcher.Reset
 * get | lines | flag                                observations: Get() loop, GetLogLines(), VrcClosedGracefully
 * ```
 *
 * The PC side is modelled like the companion does it: at every observation point the changes since the last one are
 * sent as one poll (snapshot, truncations, then data per file in creation order), mirroring upstream's one
 * `Update()` per `Get()` in the harness.
 */
internal class ScenarioRunner(private val root: File, private val env: TestEnv = TestEnv()) {
    private class PcFile(val name: String, val fileId: String, val creationTicks: Long) {
        var lastWriteTicks = creationTicks
        val bytes = ByteArrayOutputStream()
        var sent = 0
        var truncatedTo = -1
    }

    val watcher = LogWatcher(root, env, LogWatcher.Config(logAvailableIntervalMs = 0))
    private val files = LinkedHashMap<String, PcFile>()
    private var fileCounter = 0
    private var sessionStarted = false
    private var firstGetDone = false
    private val out = StringBuilder()
    private val nowIso = Ticks.formatIso(Ticks.fromEpochMs(env.now))

    fun run(script: String): String {
        for (raw in script.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) continue
            exec(line)
        }
        watcher.close()
        return out.toString()
    }

    private fun exec(line: String) {
        val sp = line.indexOf(' ')
        val cmd = if (sp < 0) line else line.substring(0, sp)
        val rest = if (sp < 0) "" else line.substring(sp + 1)
        when (cmd) {
            "tz" -> {
                val info = infoFor(rest)
                if (!sessionStarted) {
                    watcher.onSessionStarted("test-companion", info)
                    watcher.onProcessState(vrchatRunning = true, steamVrRunning = false, pcUtcNowMs = 0)
                    sessionStarted = true
                } else {
                    watcher.onInfo(info)
                }
            }
            "holdtail" -> Unit
            "till" -> runBlocking { watcher.setDateTill(rest) }
            "file" -> {
                val a = rest.split(' ')
                files[a[0]] = PcFile(a[0], "fid-${++fileCounter}", isoToTicks(a[1]))
            }
            "write", "text" -> {
                val p1 = rest.indexOf(' ')
                val p2 = rest.indexOf(' ', p1 + 1)
                val f = files.getValue(rest.substring(0, p1))
                val arg = rest.substring(p2 + 1)
                val bytes = if (cmd == "write") Resources.bytes("fixtures/$arg") else unescape(arg)
                f.bytes.write(bytes)
                f.lastWriteTicks = isoToTicks(rest.substring(p1 + 1, p2))
            }
            "truncate" -> {
                val a = rest.split(' ')
                val f = files.getValue(a[0])
                val n = a[2].toInt()
                val kept = f.bytes.toByteArray().copyOf(n)
                f.bytes.reset()
                f.bytes.write(kept)
                f.lastWriteTicks = isoToTicks(a[1])
                if (n < f.sent) {
                    f.truncatedTo = n
                    f.sent = n
                }
            }
            "touch" -> {
                val a = rest.split(' ')
                files.getValue(a[0]).lastWriteTicks = isoToTicks(a[1])
            }
            "delete" -> files.remove(rest)
            "final" -> {
                sync()
                watcher.onProcessState(vrchatRunning = false, steamVrRunning = false, pcUtcNowMs = 0)
                watcher.onProcessState(vrchatRunning = true, steamVrRunning = false, pcUtcNowMs = 0)
            }
            "process" -> {
                val a = rest.split(' ')
                sync()
                watcher.onProcessState(a[0].toBooleanStrict(), a[1].toBooleanStrict(), 0)
            }
            "reset" -> {
                sync()
                runBlocking { watcher.reset() }
            }
            "get" -> {
                sync()
                if (!firstGetDone) {
                    firstGetDone = true
                    watcher.onSyncComplete()
                }
                watcher.awaitIdle()
                out.append("--- get\n")
                var calls = 0
                while (true) {
                    val rows = runBlocking { watcher.get() }
                    calls++
                    for (r in rows) out.append(recordJson(r.jsonArray)).append('\n')
                    if (rows.isEmpty()) break
                }
                out.append("--- end get calls=").append(calls).append('\n')
            }
            "lines" -> {
                sync()
                watcher.awaitIdle()
                val q = runBlocking { watcher.getLogLines() }
                out.append("--- lines count=").append(q.size).append('\n')
                for (l in q) {
                    val text = l.jsonPrimitive.content
                    val parsed = Json.parseToJsonElement(text).jsonArray
                    out.append("Q ").append(if (isNow(parsed)) recordJson(parsed) else text).append('\n')
                }
            }
            "flag" -> {
                sync()
                watcher.awaitIdle()
                out.append("--- flag ").append(watcher.vrcClosedGracefully).append('\n')
            }
            else -> error("unknown command $cmd")
        }
    }

    /** One companion poll: snapshot, truncations, then new bytes per file (oldest first). */
    private fun sync() {
        if (!sessionStarted) return
        val ordered = files.values.sortedWith(compareBy({ it.creationTicks }, { it.name }))
        watcher.onSnapshot(ordered.map { PcFileMeta(it.name, it.fileId, it.creationTicks, it.lastWriteTicks, it.bytes.size().toLong()) })
        for (f in ordered) {
            if (f.truncatedTo >= 0) {
                watcher.onTruncate(f.name, f.fileId, f.truncatedTo.toLong())
                f.truncatedTo = -1
            }
        }
        for (f in ordered) {
            val all = f.bytes.toByteArray()
            if (all.size > f.sent) {
                watcher.onData(f.name, f.fileId, f.sent.toLong(), all.copyOfRange(f.sent, all.size))
                f.sent = all.size
            }
        }
    }

    private fun isNow(rec: JsonArray): Boolean = rec.size > 1 && (rec[1] as? JsonPrimitive)?.contentOrNull == nowIso

    private fun recordJson(rec: JsonArray): String {
        val arr = rec.map { (it as? JsonPrimitive)?.contentOrNull }.toTypedArray()
        if (isNow(rec)) arr[1] = "<NOW>"
        return DotNetJson.serialize(arr)
    }

    private fun infoFor(spec: String): CompanionInfo {
        val s = spec.trim()
        if (s.startsWith("fixed ")) {
            val min = s.substring(6).trim().toInt()
            return companionInfo(windowsId = "Fixed", ianaId = null, supportsDst = false, baseMin = min)
        }
        val bar = s.indexOf('|')
        val windowsId = if (bar < 0) s else s.substring(0, bar)
        val iana = if (bar < 0) null else s.substring(bar + 1)
        return companionInfo(windowsId = windowsId, ianaId = iana, supportsDst = true, baseMin = 0)
    }

    companion object {
        /** Same escapes as the harness: `\n \r \t \\ \xHH \uXXXX`; everything else is UTF-8 encoded. */
        fun unescape(s: String): ByteArray {
            val out = ByteArrayOutputStream()
            val sb = StringBuilder()
            fun flush() {
                if (sb.isEmpty()) return
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                sb.clear()
            }
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    sb.append(c)
                    i++
                    continue
                }
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    'x' -> {
                        flush()
                        out.write(s.substring(i + 2, i + 4).toInt(16))
                        i += 2
                    }
                    'u' -> {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> error("bad escape \\$n")
                }
                i += 2
            }
            flush()
            return out.toByteArray()
        }
    }
}
