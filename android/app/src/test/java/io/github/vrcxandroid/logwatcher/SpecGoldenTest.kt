package io.github.vrcxandroid.logwatcher

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The golden vectors in test resource logwatcher/spec-15.2.txt: the expected output for fixture 1 and phases 1-5
 * (scenario golden-15).
 *
 * Two things differ by design: the `<NOW>` timestamp, and phases 2/3, where the port holds
 * the unterminated `Joining wrld_split:123~priv` back in phase 2 and emits the whole location in phase 3 (so
 * VrcClosedGracefully is still true after phase 2).
 */
class SpecGoldenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private data class Phase(val get: List<String>, val queue: List<String>, val flag: Boolean)

    /** The spec block: `--- phaseN (...): Get()`, records, `...GetLogLines() count=n`, `Q ...`, `--- VrcClosedGracefully=X`. */
    private fun parseSpec(text: String): List<Phase> {
        val phases = ArrayList<Phase>()
        var get = ArrayList<String>()
        var queue = ArrayList<String>()
        var inGet = false
        for (line in text.split('\n')) {
            when {
                line.endsWith("Get()") && line.startsWith("--- phase") -> {
                    get = ArrayList()
                    queue = ArrayList()
                    inGet = true
                }
                line.startsWith("--- phase") && line.contains("GetLogLines()") -> inGet = false
                line.startsWith("Q ") -> queue += normalize(line.substring(2))
                line.startsWith("--- VrcClosedGracefully=") -> phases += Phase(get, queue, line.endsWith("True"))
                line.startsWith("[") && inGet -> get += normalize(line)
            }
        }
        return phases
    }

    /** The report format of ScenarioRunner and the .NET harness. */
    private fun parseReport(text: String): List<Phase> {
        val phases = ArrayList<Phase>()
        var get = ArrayList<String>()
        var queue = ArrayList<String>()
        for (line in text.split('\n')) {
            when {
                line == "--- get" -> {
                    get = ArrayList()
                    queue = ArrayList()
                }
                line.startsWith("[") -> get += normalize(line)
                line.startsWith("Q ") -> queue += normalize(line.substring(2))
                line.startsWith("--- flag ") -> phases += Phase(get, queue, line.endsWith("true"))
            }
        }
        return phases
    }

    private fun normalize(record: String): String = record
        .replace("\"<NOW: DateTime.UtcNow at parse time>\"", "\"NOW\"")
        .replace("\"\\u003CNOW\\u003E\"", "\"NOW\"")

    @Test
    fun harnessReproducesTheSpecVectors() {
        // The .NET harness run of the verbatim scenario is the spec block, record for record.
        assertEquals(parseSpec(Resources.text("spec-15.2.txt")), parseReport(Resources.text("expected/golden-15-verbatim.txt")))
    }

    @Test
    fun kotlinPortMatchesTheSpecVectors() {
        val spec = parseSpec(Resources.text("spec-15.2.txt"))
        val kotlin = parseReport(ScenarioRunner(tmp.newFolder()).run(Resources.text("scenarios/golden-15.txt")))
        assertEquals(5, spec.size)
        assertEquals(5, kotlin.size)

        assertEquals("phase 1", spec[0], kotlin[0])

        val live = spec[1].get[0]
        val split = spec[1].get[1]
        val whole = split.replace("\"wrld_split:123~priv\"", "\"wrld_split:123~private(usr_x)~region(us)\"")
        assertEquals("phase 2 (tail held)", Phase(listOf(live), listOf(live), true), kotlin[1])
        assertEquals("phase 3 (whole line)", Phase(listOf(whole), listOf(whole), false), kotlin[2])

        assertEquals("phase 4", spec[3], kotlin[3])
        assertEquals("phase 5", spec[4], kotlin[4])
    }
}
