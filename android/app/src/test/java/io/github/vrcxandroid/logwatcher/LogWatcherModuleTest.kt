package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.bridge.errorText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Return shapes of `window.LogWatcher`. */
class LogWatcherModuleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var watcher: LogWatcher

    @After
    fun close() = watcher.close()

    private fun call(module: LogWatcherModule, method: String, vararg args: String?) = runBlocking {
        module.invoke(method, JsonArray(args.map { if (it == null) JsonNull else JsonPrimitive(it) }))
    }

    @Test
    fun methodsAndShapes() {
        watcher = LogWatcher(tmp.root, TestEnv())
        val module = LogWatcherModule(watcher)
        assertEquals("LogWatcher", module.className)
        assertFalse(module.serialized)

        watcher.onSessionStarted("pc", companionInfo())
        watcher.onProcessState(vrchatRunning = true, steamVrRunning = false, pcUtcNowMs = 0)
        assertEquals(JsonNull, call(module, "SetDateTill", "2023-12-31T00:00:00.000Z"))
        val text = "2024.01.01 10:00:00 Log        -  [Behaviour] Joining wrld_x:1\n"
        watcher.onSnapshot(listOf(PcFileMeta("output_log_x.txt", "id", 1, isoToTicks("2024-01-01T09:00:00Z"), text.length.toLong())))
        watcher.onData("output_log_x.txt", "id", 0, text.toByteArray())
        watcher.onSyncComplete()

        val rows = call(module, "Get") as JsonArray
        assertEquals(1, rows.size)
        val row = rows[0].jsonArray
        assertEquals(
            listOf(JsonPrimitive("output_log_x.txt"), JsonPrimitive("2024-01-01T09:00:00.000Z"), JsonPrimitive("location"), JsonPrimitive("wrld_x:1"), JsonNull),
            row.toList(),
        )
        assertEquals(JsonArray(emptyList()), call(module, "Get"))

        val more = "2024.01.01 10:00:01 Log        -  [Behaviour] OnPlayerJoined A (usr_a)\n"
        watcher.onData("output_log_x.txt", "id", text.length.toLong(), more.toByteArray())
        watcher.awaitIdle()
        val lines = call(module, "GetLogLines") as JsonArray
        assertEquals(1, lines.size)
        assertTrue(lines[0].jsonPrimitive.isString)
        assertEquals(
            "[\"output_log_x.txt\",\"2024-01-01T09:00:01.000Z\",\"player-joined\",\"A\",\"usr_a\"]",
            lines[0].jsonPrimitive.content,
        )
        assertEquals(JsonArray(emptyList()), call(module, "GetLogLines"))
        assertEquals(JsonNull, call(module, "Reset"))
        assertEquals(2, (call(module, "Get") as JsonArray).size)
    }

    @Test
    fun errorsUseDotNetTypes() {
        watcher = LogWatcher(tmp.root, TestEnv())
        val module = LogWatcherModule(watcher)
        for ((method, args, text) in listOf(
            Triple("SetDateTill", arrayOf<String?>("not a date"), "FormatException: "),
            Triple("SetDateTill", arrayOf<String?>(null), "ArgumentNullException: Value cannot be null. (Parameter 's')"),
            Triple("Init", emptyArray(), "MissingMethodException: Method Init does not exist on class LogWatcher"),
        )) {
            try {
                call(module, method, *args)
                fail("$method should reject")
            } catch (e: DotNetException) {
                assertTrue(errorText(e), errorText(e).startsWith(text))
            }
        }
    }
}
