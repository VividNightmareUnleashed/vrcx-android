package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class RotatingFileLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val direct = Executor { it.run() }

    @Test
    fun appendsLines() {
        val dir = temp.newFolder("logs")
        val log = RotatingFileLog(dir, "web.log", maxBytes = 1000, maxFiles = 3, executor = direct)
        log.append("one")
        log.append("two")
        assertEquals("one\ntwo\n", File(dir, "web.log").readText())
    }

    @Test
    fun rotatesBySizeAndKeepsMaxFiles() {
        val dir = temp.newFolder("logs")
        val log = RotatingFileLog(dir, "web.log", maxBytes = 20, maxFiles = 3, executor = direct)
        repeat(10) { log.append("line-$it-xxxxxx") } // 14 bytes per line: one line per file
        assertEquals("line-9-xxxxxx\n", File(dir, "web.log").readText())
        assertEquals("line-8-xxxxxx\n", File(dir, "web.log.1").readText())
        assertEquals("line-7-xxxxxx\n", File(dir, "web.log.2").readText())
        assertFalse(File(dir, "web.log.3").exists())
    }

    @Test
    fun continuesAnExistingFile() {
        val dir = temp.newFolder("logs")
        File(dir, "web.log").writeText("old\n")
        val log = RotatingFileLog(dir, "web.log", maxBytes = 1000, maxFiles = 2, executor = direct)
        log.append("new")
        assertEquals("old\nnew\n", File(dir, "web.log").readText())
    }

    @Test
    fun truncatesVeryLongLines() {
        val dir = temp.newFolder("logs")
        val log = RotatingFileLog(dir, "web.log", maxBytes = 100_000, maxFiles = 2, executor = direct)
        log.append("z".repeat(RotatingFileLog.MAX_LINE * 2))
        val text = File(dir, "web.log").readText()
        assertTrue(text.length <= RotatingFileLog.MAX_LINE + 2)
        assertTrue(text.endsWith("…\n"))
    }

    @Test
    fun anUnwritableDirectoryDisablesTheLogInsteadOfThrowing() {
        val blocker = temp.newFile("not-a-dir")
        val log = RotatingFileLog(File(blocker, "logs"), "web.log", executor = direct)
        log.append("x")
        log.append("y")
    }
}
