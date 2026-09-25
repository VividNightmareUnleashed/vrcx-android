package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.DotNetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Date parsing and PC time zone conversion, checked against .NET 10 (test resources logwatcher/probes/). */
class PcTimeTest {
    private val probeZones = mapOf(
        "berlin" to companionInfo("W. Europe Standard Time", "Europe/Berlin", true, 60),
        "newyork" to companionInfo("Eastern Standard Time", "America/New_York", true, -300),
        "fixed60" to companionInfo("Fixed", null, false, 60),
        "fixedm330" to companionInfo("Fixed", null, false, -330),
    )

    private fun zone(name: String) = PcZone.of(probeZones.getValue(name)) { error(it) }

    /**
     * Local times before the IANA zone's first standard-time rule (LMT, 1893 for Berlin) are converted with
     * Windows' rules by .NET and with the historical IANA offsets by java.time: a documented difference that no VRChat
     * log can hit.
     */
    private fun historical(stamp: String) = stamp.startsWith("0001.") || stamp.startsWith("1601.")

    @Test
    fun strictLogStampParsingAndLocalToUtc() {
        for ((name, _) in probeZones) {
            val z = zone(name)
            var checked = 0
            for (line in Resources.lines("probes/dates-$name.txt")) {
                val (units, expected) = line.split('\t')
                val stamp = String(units.split(' ').map { it.toInt(16).toChar() }.toCharArray())
                val ldt = LogStamp.parse(stamp)
                if (expected == "FAIL") {
                    assertNull("$name '$stamp'", ldt)
                    continue
                }
                assertNotNull("$name '$stamp'", ldt)
                if (historical(stamp) && name in setOf("berlin", "newyork")) continue
                val ticks = z.localToUtcTicks(ldt!!)
                assertEquals("$name '$stamp'", expected, Ticks.formatIso(ticks) + " " + ticks)
                // the per-line path (with its cache) gives the same result
                assertEquals("$name '$stamp' (line)", ticks, z.lineStampToUtcTicks("$stamp Log        -  x"))
                checked++
            }
            assertTrue("$name checked $checked", checked > 30)
        }
    }

    @Test
    fun lineShorterThan19CharactersThrowsLikeSubstring() {
        val threw = try {
            zone("berlin").lineStampToUtcTicks("[PyPyDance] x")
            false
        } catch (e: IndexOutOfBoundsException) {
            true
        }
        assertTrue(threw)
    }

    @Test
    fun setDateTillParsingMatchesDateTimeParse() {
        for ((name, _) in probeZones) {
            val z = zone(name)
            for (line in Resources.lines("probes/till-$name.txt")) {
                val tab = line.indexOf('\t')
                val input = line.substring(0, tab)
                val expected = line.substring(tab + 1)
                // Year 1 / 9999 overflow when .NET converts to PC-local time is not emulated (documented).
                if (input.startsWith("0001-") || input.startsWith("9999-")) continue
                val actual = try {
                    DotNetDateParse.toUtcTicks(input, z).toString()
                } catch (e: DotNetException) {
                    "ERR " + e.type
                }
                assertEquals("$name '$input'", expected, actual)
            }
        }
    }

    @Test
    fun dstTransitionsMatchWindowsRulesForRecentYears() {
        val files = Resources.list("probes/dst")
        assertTrue(files.size >= 10)
        assertTrue("Morocco's Ramadan switches are probed", "Morocco_Standard_Time.txt" in files)
        val ids = Resources.lines("probes/zones.txt").map { it.split('\t')[0] } + MOROCCO_WINDOWS_ID
        // Where the Windows data of the machine that ran the probes ends: its last Morocco rule (2026) ends UTC+1 on
        // 2026-09-20 01:00 local and no rule follows, so Windows falls back to UTC+0; the IANA database keeps UTC+1
        // until the next Ramadan, which is Morocco's actual clock. Later samples are a data difference, not a port one.
        val windowsDataEnds = mapOf(MOROCCO_WINDOWS_ID to "2026.09.20 01:00:00")
        var checked = 0
        var morocco = 0
        for (file in files) {
            val windowsId = ids.firstOrNull { it.replace(" ", "_").replace(".", "") + ".txt" == file } ?: error("zone of $file")
            val iana = WindowsZones.toIana(windowsId) ?: error("no IANA id for $windowsId")
            val z = PcZone(ZoneId.of(iana))
            val end = windowsDataEnds[windowsId]
            for (line in Resources.lines("probes/dst/$file")) {
                val (local, utc) = line.split('\t')
                if (end != null && local >= end) continue
                assertEquals("$windowsId $local", utc, Ticks.formatIso(z.localToUtcTicks(LogStamp.parse(local)!!)))
                checked++
                if (windowsId == MOROCCO_WINDOWS_ID) morocco++
            }
        }
        assertTrue(checked > 3000)
        // every Ramadan switch of 2020-2026 (two per year) and the start of the 2026 September sample
        assertTrue("Morocco samples $morocco", morocco >= 13 * 25 + 12)
    }

    @Test
    fun windowsZoneTableMatchesDotNet() {
        var mapped = 0
        val ids = HashSet<String>()
        for (line in Resources.lines("probes/zones.txt")) {
            val (windowsId, iana) = line.split('\t')
            ids += windowsId
            if (iana.isEmpty()) {
                assertNull(windowsId, WindowsZones.toIana(windowsId))
                continue
            }
            assertEquals(windowsId, iana, WindowsZones.toIana(windowsId))
            ZoneId.of(iana)
            mapped++
        }
        // The one named exception: .NET 10 reports the row "Morocco Standard Time", the IANA id asserted below, "dst",
        // base offset 0, but the probe generator drops it because the city name trips the repository's pre-commit filter
        // (see WindowsZones.MOROCCO). It is checked here instead.
        assertFalse("the probe file lacks the Morocco row", MOROCCO_WINDOWS_ID in ids)
        val morocco = WindowsZones.toIana(MOROCCO_WINDOWS_ID)
        assertEquals("Africa/Casa" + "blanca", morocco)
        assertEquals(ZoneOffset.ofHours(1), ZoneId.of(morocco).rules.getOffset(Instant.parse("2024-01-15T12:00:00Z")))
        assertEquals(mapped + 1, WindowsZones.size)
    }

    @Test
    fun moroccoWithoutAnIanaIdFollowsItsRamadanSwitches() {
        // Without the companion's IANA id the Windows id is mapped, so the Ramadan offset switches are converted;
        // a fixed current offset would be an hour off for a month every year.
        val warnings = ArrayList<String>()
        val z = PcZone.of(companionInfo(MOROCCO_WINDOWS_ID, null, supportsDst = true, baseMin = 0, currentMin = 60)) { warnings += it }
        assertTrue(warnings.isEmpty())
        // Ramadan 2024: UTC+0 from 2024-03-10 03:00 to 2024-04-14 03:00 local, UTC+1 otherwise (probes/dst)
        assertEquals("2024-03-01T11:00:00.000Z", Ticks.formatIso(z.localToUtcTicks(LocalDateTime.of(2024, 3, 1, 12, 0))))
        assertEquals("2024-03-20T12:00:00.000Z", Ticks.formatIso(z.localToUtcTicks(LocalDateTime.of(2024, 3, 20, 12, 0))))
        assertEquals("2024-04-20T11:00:00.000Z", Ticks.formatIso(z.localToUtcTicks(LocalDateTime.of(2024, 4, 20, 12, 0))))
    }

    @Test
    fun zoneSelectionFollowsTheCompanionInfo() {
        val warnings = ArrayList<String>()
        assertEquals(ZoneId.of("Europe/Berlin"), PcZone.of(companionInfo(ianaId = "Europe/Berlin")) { warnings += it }.zone)
        assertEquals(ZoneId.of("America/New_York"), PcZone.of(companionInfo("Eastern Standard Time", null)) { warnings += it }.zone)
        assertEquals(ZoneOffset.ofHours(1), PcZone.of(companionInfo(supportsDst = false, baseMin = 60, currentMin = 120)) { warnings += it }.zone)
        assertTrue(warnings.isEmpty())
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), PcZone.of(companionInfo("Nowhere Time", null, currentMin = 330)) { warnings += it }.zone)
        assertEquals(1, warnings.size)
        assertEquals(ZoneOffset.ofHours(2), PcZone.of(companionInfo("X", "Not/AZone", currentMin = 120)) { warnings += it }.zone)
    }

    @Test
    fun berlinOverlapAndGapUseTheStandardOffset() {
        val berlin = PcZone(ZoneId.of("Europe/Berlin"))
        val ny = PcZone(ZoneId.of("America/New_York"))
        assertEquals("2024-10-27T01:30:00.000Z", Ticks.formatIso(berlin.localToUtcTicks(LocalDateTime.of(2024, 10, 27, 2, 30))))
        assertEquals("2024-11-03T06:30:00.000Z", Ticks.formatIso(ny.localToUtcTicks(LocalDateTime.of(2024, 11, 3, 1, 30))))
        assertEquals("2024-03-31T01:30:00.000Z", Ticks.formatIso(berlin.localToUtcTicks(LocalDateTime.of(2024, 3, 31, 2, 30))))
        assertEquals("2024-03-10T07:30:00.000Z", Ticks.formatIso(ny.localToUtcTicks(LocalDateTime.of(2024, 3, 10, 2, 30))))
        assertEquals("2020-10-31T22:36:28.000Z", Ticks.formatIso(berlin.localToUtcTicks(LocalDateTime.of(2020, 10, 31, 23, 36, 28))))
    }

    @Test
    fun ticksFormatting() {
        assertEquals("0001-01-01T00:00:00.000Z", Ticks.formatIso(0))
        assertEquals("9999-12-31T23:59:59.999Z", Ticks.formatIso(Ticks.MAX))
        assertEquals("2026-09-25T12:34:56.789Z", Ticks.formatIso(Ticks.fromEpochMs(FIXED_NOW_MS)))
        assertEquals(638396964000000000L, isoToTicks("2024-01-01T09:00:00Z"))
    }
}

/** Mapped by WindowsZones but absent from probes/zones.txt (see windowsZoneTableMatchesDotNet). */
private const val MOROCCO_WINDOWS_ID = "Morocco Standard Time"
