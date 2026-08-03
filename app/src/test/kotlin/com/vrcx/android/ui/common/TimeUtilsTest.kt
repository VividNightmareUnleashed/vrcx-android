package com.vrcx.android.ui.common

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {
    private val now = Instant.parse("2026-08-03T12:00:00Z")

    @Test
    fun `relative time uses the smallest useful unit`() {
        assertEquals("now", relativeTime("2026-08-03T11:59:30Z", now))
        assertEquals("1m", relativeTime("2026-08-03T11:58:30Z", now))
        assertEquals("2h", relativeTime("2026-08-03T10:00:00Z", now))
        assertEquals("3d", relativeTime("2026-07-31T12:00:00Z", now))
    }

    @Test
    fun `old malformed and future values preserve existing fallbacks`() {
        assertEquals("2026-07-20", relativeTime("2026-07-20T12:00:00Z", now))
        assertEquals("bad timest", relativeTime("bad timestamp", now))
        assertEquals("now", relativeTime("2026-08-04T12:00:00Z", now))
    }
}
