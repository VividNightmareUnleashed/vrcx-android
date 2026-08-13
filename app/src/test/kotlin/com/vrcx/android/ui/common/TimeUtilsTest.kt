package com.vrcx.android.ui.common

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {
    private val now = Instant.parse("2026-08-03T12:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `relative time uses the smallest useful unit`() {
        assertEquals("now", relativeTime("2026-08-03T11:59:30Z", now, utc))
        assertEquals("1m", relativeTime("2026-08-03T11:58:30Z", now, utc))
        assertEquals("2h", relativeTime("2026-08-03T10:00:00Z", now, utc))
        assertEquals("3d", relativeTime("2026-07-31T12:00:00Z", now, utc))
    }

    @Test
    fun `old malformed and future values preserve existing fallbacks`() {
        assertEquals("2026-07-20", relativeTime("2026-07-20T12:00:00Z", now, utc))
        assertEquals("bad timest", relativeTime("bad timestamp", now, utc))
        assertEquals("now", relativeTime("2026-08-04T12:00:00Z", now, utc))
    }

    @Test
    fun `an old timestamp is dated in the reader's zone, not UTC`() {
        // 20 July 21:00 UTC is already the morning of 21 July in New Zealand.
        assertEquals(
            "2026-07-21",
            relativeTime("2026-07-20T21:00:00Z", now, ZoneId.of("Pacific/Auckland")),
        )
        // The same instant is still 20 July for a reader at UTC-8.
        assertEquals(
            "2026-07-20",
            relativeTime("2026-07-20T21:00:00Z", now, ZoneId.of("America/Los_Angeles")),
        )
    }
}
