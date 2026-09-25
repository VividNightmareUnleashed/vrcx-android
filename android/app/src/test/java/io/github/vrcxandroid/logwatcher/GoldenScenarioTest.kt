package io.github.vrcxandroid.logwatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Differential tests: every scenario in test resources logwatcher/scenarios/ was run by a .NET 10 harness that
 * compiles upstream `Dotnet/LogWatcher.cs` verbatim (LINUX defined, NLog and Program stubbed, the PC time zone forced
 * through `TimeZoneInfo`'s cache) and its report is in logwatcher/expected/. The Kotlin port must print exactly the
 * same report. Records whose timestamp is "now" (lines without a date prefix) are printed as `<NOW>` by both sides.
 *
 * Scenarios whose name ends in `-verbatim` show upstream's timing-dependent behaviour on a partial line;
 * the port deliberately differs there (§17 point 4) and runs the `holdtail` variant instead.
 */
class GoldenScenarioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun check(name: String) {
        val expected = Resources.text("expected/$name.txt")
        val actual = ScenarioRunner(tmp.newFolder(name)).run(Resources.text("scenarios/$name.txt"))
        assertEquals("scenario $name", expected, actual)
    }

    @Test
    fun everyScenarioMatchesUpstream() {
        val names = Resources.list("scenarios").map { it.removeSuffix(".txt") }.filterNot { it.endsWith("-verbatim") }
        assertTrue("scenarios found: $names", names.size >= 16)
        println("golden scenarios: $names")
        val failures = ArrayList<String>()
        for (name in names) {
            try {
                check(name)
            } catch (e: AssertionError) {
                failures += "$name: ${e.message}"
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
