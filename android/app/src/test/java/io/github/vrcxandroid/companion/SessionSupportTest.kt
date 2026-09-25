package io.github.vrcxandroid.companion

import io.github.vrcxandroid.companion.OffsetTracker.Decision
import io.github.vrcxandroid.logwatcher.MirroredFile
import io.github.vrcxandroid.logwatcher.PcFileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionSupportTest {
    @Test
    fun contiguousDataIsDelivered() {
        val t = OffsetTracker()
        t.reset(listOf(MirroredFile("a", "1", 10)))
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 10, 5))
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 15, 5))
        assertEquals(20L, t.expectedOffset("a", "1"))
        // A file the mirror does not have starts at 0.
        assertEquals(Decision.Deliver(0), t.onData("b", "2", 0, 3))
    }

    @Test
    fun overlapsAreTrimmedAndDuplicatesDropped() {
        val t = OffsetTracker()
        t.reset(listOf(MirroredFile("a", "1", 100)))
        assertEquals(Decision.Drop, t.onData("a", "1", 50, 50))
        assertEquals(Decision.Drop, t.onData("a", "1", 0, 10))
        assertEquals(Decision.Deliver(10), t.onData("a", "1", 90, 20))
        assertEquals(110L, t.expectedOffset("a", "1"))
    }

    @Test
    fun gapRequestsFetchAndWaitsForTheAnswer() {
        val t = OffsetTracker()
        t.reset(listOf(MirroredFile("a", "1", 100)))
        assertEquals(Decision.Fetch(100), t.onData("a", "1", 150, 10))
        // In-flight frames after the gap are dropped until the fetch answer starts at 100.
        assertEquals(Decision.Drop, t.onData("a", "1", 160, 10))
        assertEquals(Decision.Drop, t.onData("a", "1", 40, 10))
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 100, 60))
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 160, 10))
        assertEquals(Decision.Drop, t.onData("a", "1", 150, 10))
    }

    @Test
    fun externalFetchRewindsTheFile() {
        val t = OffsetTracker()
        t.reset(listOf(MirroredFile("a", "1", 100)))
        t.onFetchRequested("a", "1", 0)
        assertEquals(Decision.Drop, t.onData("a", "1", 100, 10)) // live data sent before the fetch was seen
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 0, 110))
        assertEquals(110L, t.expectedOffset("a", "1"))
    }

    @Test
    fun fetchAnswerStartingEarlierIsTrimmed() {
        val t = OffsetTracker()
        t.onFetchRequested("a", "1", 50)
        assertEquals(Decision.Deliver(30), t.onData("a", "1", 20, 40))
        assertEquals(60L, t.expectedOffset("a", "1"))
    }

    @Test
    fun truncateAndSnapshotReset() {
        val t = OffsetTracker()
        t.reset(listOf(MirroredFile("a", "1", 100), MirroredFile("b", "2", 5)))
        t.onTruncate("a", "1", 20)
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 20, 5))
        // "b" disappears from the snapshot (deleted on the PC); if it comes back it starts from 0.
        t.onSnapshot(listOf(PcFileMeta("a", "1", 0, 0, 25)))
        assertEquals(0L, t.expectedOffset("b", "2"))
        assertEquals(Decision.Deliver(0), t.onData("b", "2", 0, 5))
        // Same name, new file id: a different file.
        assertEquals(Decision.Deliver(0), t.onData("a", "9", 0, 5))
    }

    @Test
    fun resetReplacesTrackedState() {
        val t = OffsetTracker()
        t.reset(listOf(MirroredFile("a", "1", 100)))
        t.onData("a", "1", 100, 50)
        // The mirror reports less than what was delivered (it lost data): trust the mirror.
        t.reset(listOf(MirroredFile("a", "1", 120)))
        assertEquals(Decision.Deliver(0), t.onData("a", "1", 120, 30))
    }

    @Test
    fun skewEstimateIsTheLargestRecentSample() {
        val s = SkewEstimator(window = 3)
        assertEquals(100L, s.add(100))
        assertEquals(100L, s.add(80))
        assertEquals(120L, s.add(120))
        assertEquals(120L, s.add(90))
        assertEquals(120L, s.add(95))
        assertEquals(95L, s.add(70)) // 120 left the window
    }

    @Test
    fun backoffDoublesWithJitterWithinBounds() {
        val b = Backoff(1_000, 60_000, Random(42))
        val first = b.delayFor(0)
        assertTrue(first in 1_000..1_150)
        val second = b.delayFor(1)
        assertTrue(second in 1_700..2_300)
        val fifth = b.delayFor(4)
        assertTrue(fifth in 13_600..18_400)
        repeat(50) {
            val d = b.delayFor(10 + it)
            assertTrue("$d", d in 51_000..60_000)
        }
        repeat(200) { attempt ->
            val d = b.delayFor(attempt % 12)
            assertTrue(d in 1_000..60_000)
        }
    }
}
