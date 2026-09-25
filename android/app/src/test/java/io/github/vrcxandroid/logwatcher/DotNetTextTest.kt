package io.github.vrcxandroid.logwatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** The .NET text behaviours, checked against the .NET 10 probe outputs in test resources logwatcher/probes/. */
class DotNetTextTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun jsonEscapingMatchesSystemTextJson() {
        var checked = 0
        var lone = 0
        for (line in Resources.lines("probes/json.txt")) {
            val (key, expected) = line.split('\t', limit = 2)
            val record: Array<String?> = when {
                key == "MIX" -> arrayOf("a", null, "", "x\"y\\z</script>&'+`" + 0x2028.toChar() + 0xE9.toChar())
                // "S <UTF-16 units>": strings with lone or misordered surrogates
                key.startsWith("S ") -> {
                    lone++
                    arrayOf(String(key.substring(2).split(' ').map { it.toInt(16).toChar() }.toCharArray()))
                }
                else -> arrayOf(String(Character.toChars(key.toInt(16))))
            }
            assertEquals("code point $key", expected, DotNetJson.serialize(record))
            checked++
        }
        assertTrue(checked > 256)
        assertTrue(lone >= 10)
    }

    @Test
    fun loneSurrogatesFromUpstreamIndexArithmeticBecomeReplacementCharacters() {
        // `Remove(Length - 1)` on a URL ending in U+1F600 keeps its high surrogate (A11/A12/A19/A20); .NET 10 writes
        // `\uFFFD` for it, never the lone `\uD83D`
        assertEquals("[\"https://y/\\uFFFD\"]", DotNetJson.serialize(arrayOf("https://y/\uD83D")))
        // the +17 skip of OnPlayerJoined lands on the low half of a pair (A1)
        assertEquals("[\"\\uFFFDName\"]", DotNetJson.serialize(arrayOf("\uDE00Name")))
        assertEquals("[\"\\uD83D\\uDE00\"]", DotNetJson.serialize(arrayOf("\uD83D\uDE00")))
    }

    @Test
    fun jsonNullStaysNull() {
        // the location record with an unknown world name
        assertEquals(
            "[\"f\",\"2024-01-01T09:00:00.000Z\",\"location\",\"wrld_x\",null]",
            DotNetJson.serialize(arrayOf("f", "2024-01-01T09:00:00.000Z", "location", "wrld_x", null)),
        )
    }

    @Test
    fun streamReaderDecodingMatchesDotNet() {
        val file = tmp.newFile("utf8.bin")
        var checked = 0
        for (line in Resources.lines("probes/utf8.txt")) {
            val tab = line.indexOf('\t')
            val input = hex(line.substring(0, tab))
            val expected = line.substring(tab + 1)
            file.writeBytes(input)
            val actual = readAllLines(file, final = true).joinToString("|") { units(it) }
            assertEquals("input ${line.substring(0, tab)}", expected, actual)
            checked++
        }
        assertTrue(checked > 6000)
    }

    @Test
    fun goldenVector15_3() {
        val bytes = hex("61C362E69763FFF09F9864EDA080650A")
        val expected = "0061 FFFD 0062 FFFD 0063 FFFD FFFD 0064 FFFD FFFD FFFD 0065"
        assertEquals(expected, units(DotNetUtf8.decode(bytes, 0, bytes.size - 1)))
        // and the JDK decoder really differs on the surrogate sequence
        assertFalse(expected == units(String(bytes, 0, bytes.size - 1, Charsets.UTF_8)))
    }

    @Test
    fun unterminatedTailIsHeldUntilFinal() {
        val file = tmp.newFile("tail.bin")
        file.writeBytes("a\r\nb\rc\nhalf".toByteArray() + byteArrayOf(0xE2.toByte(), 0x99.toByte()))
        RandomAccessFile(file, "r").use { raf ->
            val r = MirrorLineReader(raf, 0, file.length())
            assertEquals("a", r.nextLine(false))
            assertEquals("b", r.nextLine(false))
            assertEquals("c", r.nextLine(false))
            assertNull(r.nextLine(false))
            assertTrue(r.heldTail)
            assertEquals(7L, r.resumePosition)
        }
        RandomAccessFile(file, "r").use { raf ->
            val r = MirrorLineReader(raf, 7, file.length())
            assertEquals("half" + 0xFFFD.toChar(), r.nextLine(true))
            assertNull(r.nextLine(true))
            assertEquals(file.length(), r.resumePosition)
        }
    }

    @Test
    fun bomIsDetectedAgainWhenOnlyItIsAvailable() {
        val file = tmp.newFile("bom.bin")
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61))
        RandomAccessFile(file, "r").use { raf ->
            val r = MirrorLineReader(raf, 0, file.length())
            assertNull(r.nextLine(false))
            assertEquals(0L, r.resumePosition)
        }
    }

    @Test
    fun cultureStartsWithMatchesIcuForEveryCodePoint() {
        val probe = Resources.lines("probes/startswith.txt").associate { l ->
            val (k, v) = l.split('\t')
            k to parseRanges(v)
        }
        val p = "http://127.0.0.1:22500"
        val ignoredAtStart = probe.getValue("ignoredAtStart")
        val ignoredInside = probe.getValue("ignoredInside")
        val breaksAfter = probe.getValue("breaksAfter")
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp in 0xD800..0xDFFF) {
                cp++
                continue
            }
            val c = String(Character.toChars(cp))
            assertEquals("start %X".format(cp), cp in ignoredAtStart, DotNetStrings.cultureStartsWith("$c$p/x", p))
            assertEquals("inside %X".format(cp), cp in ignoredInside, DotNetStrings.cultureStartsWith("http://127.0.0$c.1:22500/x", p))
            assertEquals("after %X".format(cp), cp !in breaksAfter, DotNetStrings.cultureStartsWith("$p$c/x", p))
            cp++
        }
    }

    @Test
    fun cleanIdKeepsOnlyTheUpstreamSet() {
        assertEquals("usr_x-y~z:(a)1", DotNetStrings.cleanId("usr_" + 0xE9.toChar() + "x-y~z:(a)" + 0xFF21.toChar() + " 1" + 0x666.toChar()))
        assertEquals("Natsumi-sama", DotNetStrings.cleanId("Natsumi-sama " + 0x2661.toChar()))
        assertEquals("abc", DotNetStrings.cleanId("abc"))
        assertEquals("wrld_ax", DotNetStrings.cleanLocation("wrld_a/x"))
    }

    @Test
    fun csharpStringHelpers() {
        assertTrue(LogEngine.compareAt("0123456789STEAMVR HMD Model: ", 10, "STEAMVR HMD Model: ", 20))
        assertFalse(LogEngine.compareAt("0123456789STEAMVR HMD Model: Index", 10, "STEAMVR HMD Model: ", 20))
        assertTrue(LogEngine.compareAt("0123456789VR Disabled more", 10, "VR Disabled", 11))
        assertFalse(LogEngine.compareAt("0123456789VR Disable", 10, "VR Disabled", 11))
        assertEquals("", LogEngine.sub("abc", 3))
        assertThrows { LogEngine.sub("abc", 4) }
        assertThrows { LogEngine.sub("abc", 2, -1) }
        assertThrows { LogEngine.removeLast("") }
        assertEquals("ab", LogEngine.removeLast("abc"))
        assertEquals("Rize" + 0x2661.toChar() + " (JP)" to "usr_x", LogEngine.parseUserInfo("Rize" + 0x2661.toChar() + " (JP) (usr_x)"))
        assertEquals("OldFormatName" to "", LogEngine.parseUserInfo("OldFormatName"))
        assertThrows { LogEngine.parseUserInfo("Broken (name") }
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = try {
            block()
            false
        } catch (e: IndexOutOfBoundsException) {
            true
        }
        assertTrue("expected an exception", threw)
    }

    companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun units(s: String): String = s.map { "%04X".format(it.code) }.joinToString(" ")

        fun readAllLines(file: File, final: Boolean): List<String> = RandomAccessFile(file, "r").use { raf ->
            val r = MirrorLineReader(raf, 0, file.length())
            generateSequence { r.nextLine(final) }.toList()
        }

        fun parseRanges(spec: String): Set<Int> {
            val out = HashSet<Int>()
            for (part in spec.split(',')) {
                val d = part.indexOf('-')
                if (d < 0) out += part.toInt(16) else (part.substring(0, d).toInt(16)..part.substring(d + 1).toInt(16)).forEach { out += it }
            }
            return out
        }
    }
}
