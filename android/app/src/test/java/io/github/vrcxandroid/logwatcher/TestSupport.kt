package io.github.vrcxandroid.logwatcher

import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.Instant
import java.util.Collections

/** Fixed phone clock used by the golden tests: records dated "now" come out as this instant. */
internal val FIXED_NOW_MS: Long = Instant.parse("2026-09-25T12:34:56.789Z").toEpochMilli()

internal class TestEnv(var now: Long = FIXED_NOW_MS) : LogWatcher.Env {
    val events: MutableList<Pair<String, JsonElement?>> = Collections.synchronizedList(ArrayList())
    val tillChanges: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile
    var connecting = false

    override fun nowMs(): Long = now
    override fun emit(event: String, data: JsonElement?) {
        events += event to data
    }

    override fun onTillDateChanged(utcTicks: Long) {
        tillChanges += utcTicks
    }

    override fun companionConnecting(): Boolean = connecting

    override val log = LwLog { level, message, _ -> logs += (if (level == LwLog.WARN) "WARN " else "INFO ") + message }

    fun eventNames(): List<String> = synchronized(events) { events.map { it.first } }
}

internal object Resources {
    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)

    fun bytes(path: String): ByteArray {
        val stream = Resources::class.java.classLoader!!.getResourceAsStream("logwatcher/$path")
            ?: error("missing test resource logwatcher/$path")
        return stream.use { it.readBytes() }
    }

    fun lines(path: String): List<String> = text(path).split('\n').filter { it.isNotEmpty() }

    fun list(dir: String): List<String> {
        val url = Resources::class.java.classLoader!!.getResource("logwatcher/$dir") ?: error("missing $dir")
        return File(url.toURI()).list()!!.sorted()
    }
}

internal fun companionInfo(
    windowsId: String? = "W. Europe Standard Time",
    ianaId: String? = "Europe/Berlin",
    supportsDst: Boolean = true,
    baseMin: Int = 60,
    currentMin: Int = baseMin,
    pcUtcNowMs: Long = 0,
) = CompanionInfo(
    companionVersion = "1.0.0",
    machineName = "PC",
    pcUtcNowMs = pcUtcNowMs,
    tzWindowsId = windowsId,
    tzIanaId = ianaId,
    tzSupportsDst = supportsDst,
    tzBaseUtcOffsetMin = baseMin,
    tzCurrentUtcOffsetMin = currentMin,
    logDir = null,
    dirExists = true,
)

internal fun isoToTicks(iso: String): Long {
    val i = Instant.parse(iso)
    return Ticks.fromEpochSecond(i.epochSecond, i.nano)
}
