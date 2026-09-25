package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the shim's JavaScript tests (shim/vrcx-android-shim.test.mjs, node:test in a node:vm sandbox) as part of
 * `gradlew testDebugUnitTest`. Skipped when Node.js is not installed.
 */
class ShimJsTest {
    private val testFile = File("src/test/java/io/github/vrcxandroid/host/shim/vrcx-android-shim.test.mjs")

    private fun node(): String? {
        val candidates = listOf("node", "node.exe")
        for (c in candidates) {
            try {
                val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) return c
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Test
    fun shimJavaScriptTestsPass() {
        assumeTrue("shim test file not found from ${File(".").absolutePath}", testFile.isFile)
        val node = node()
        assumeTrue("Node.js is not installed", node != null)
        val process = ProcessBuilder(node, "--test", testFile.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        assertEquals("node --test failed:\n$output", 0, process.exitValue())
    }
}
