package io.github.vrcxandroid.logwatcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogMirrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val log = LwLog { _, _, _ -> }

    private fun mirror(dir: File = tmp.root) = LogMirror(dir, "c1", log).also { it.load() }

    private fun meta(name: String, fileId: String = "id-$name", length: Long = 0, creation: Long = 100, lastWrite: Long = 200) =
        PcFileMeta(name, fileId, creation, lastWrite, length)

    private fun bytesOf(m: LogMirror, name: String): ByteArray = m.openRead(m.entry(name)!!).use { raf ->
        ByteArray(raf.length().toInt()).also { raf.readFully(it) }
    }

    @Test
    fun appendsOnlyContiguousBytes() {
        val m = mirror()
        m.applySnapshot(listOf(meta("a", length = 10)))
        assertEquals(LogMirror.AppendResult.APPENDED, m.append("a", "id-a", 0, "hello".toByteArray()))
        assertEquals(LogMirror.AppendResult.DUPLICATE, m.append("a", "id-a", 0, "hel".toByteArray()))
        // overlapping data: only the new suffix is written
        assertEquals(LogMirror.AppendResult.APPENDED, m.append("a", "id-a", 3, "lo wor".toByteArray()))
        assertArrayEquals("hello wor".toByteArray(), bytesOf(m, "a"))
        // a gap is refused and reported
        assertEquals(LogMirror.AppendResult.GAP, m.append("a", "id-a", 20, "x".toByteArray()))
        assertEquals(listOf(ResyncRequest("a", "id-a", 9)), m.pendingResyncs())
        assertEquals(9, m.entry("a")!!.size)
        // contiguous data clears it
        assertEquals(LogMirror.AppendResult.APPENDED, m.append("a", "id-a", 9, "ld".toByteArray()))
        assertTrue(m.pendingResyncs().isEmpty())
        assertEquals(listOf(MirroredFile("a", "id-a", 11)), m.have())
    }

    @Test
    fun snapshotDeletesMissingFilesAndResetsReplacedOnes() {
        val m = mirror()
        m.applySnapshot(listOf(meta("a"), meta("b")))
        m.append("a", "id-a", 0, "aaa".toByteArray())
        m.append("b", "id-b", 0, "bbb".toByteArray())
        val localA = File(tmp.root, m.entry("a")!!.local)
        assertTrue(localA.exists())

        val gone = m.applySnapshot(listOf(meta("b", fileId = "id-b2", length = 5)))
        assertEquals(listOf("a", "b"), gone)
        assertNull(m.entry("a"))
        assertFalse(localA.exists())
        assertEquals("id-b2", m.entry("b")!!.fileId)
        assertEquals(0, m.entry("b")!!.size)
        assertEquals(5, m.entry("b")!!.pcLength)
    }

    @Test
    fun dataForANewFileIdAtOffsetZeroReplacesTheFile() {
        val m = mirror()
        m.applySnapshot(listOf(meta("a")))
        m.append("a", "id-a", 0, "old content".toByteArray())
        assertEquals(LogMirror.AppendResult.GAP, m.append("a", "id-new", 5, "x".toByteArray()))
        assertEquals(ResyncRequest("a", "id-new", 0), m.pendingResyncs().single())
        assertEquals(LogMirror.AppendResult.REPLACED_AND_APPENDED, m.append("a", "id-new", 0, "new".toByteArray()))
        assertArrayEquals("new".toByteArray(), bytesOf(m, "a"))
        assertTrue(m.pendingResyncs().isEmpty())
    }

    @Test
    fun truncateShrinksTheMirror() {
        val m = mirror()
        m.applySnapshot(listOf(meta("a")))
        m.append("a", "id-a", 0, "0123456789".toByteArray())
        assertFalse(m.truncate("a", "other-id", 2))
        assertFalse(m.truncate("a", "id-a", 10))
        assertTrue(m.truncate("a", "id-a", 4))
        assertArrayEquals("0123".toByteArray(), bytesOf(m, "a"))
        assertEquals(LogMirror.AppendResult.APPENDED, m.append("a", "id-a", 4, "xy".toByteArray()))
        assertArrayEquals("0123xy".toByteArray(), bytesOf(m, "a"))
    }

    @Test
    fun persistsAcrossRestarts() {
        val m = mirror()
        val info = companionInfo(pcUtcNowMs = 42)
        m.setInfo(info)
        m.applySnapshot(listOf(meta("a", length = 3, creation = 7, lastWrite = 8)))
        m.append("a", "id-a", 0, "abc".toByteArray())
        // metadata-only changes are written lazily
        m.applySnapshot(listOf(meta("a", length = 6, creation = 7, lastWrite = 9)))
        assertTrue(m.isDirty)
        m.flushIndex()
        assertFalse(m.isDirty)
        // a stray data file (crash before the index write) is removed on load
        File(tmp.root, "f99.log").writeText("stray")
        assertFalse(File(tmp.root, "index.json.tmp").exists())

        val again = mirror()
        assertEquals(info, again.info)
        val e = again.entry("a")!!
        assertEquals("id-a", e.fileId)
        assertEquals(7, e.creationTicks)
        assertEquals(9, e.lastWriteTicks)
        assertEquals(6, e.pcLength)
        assertEquals(3, e.size)
        assertFalse(File(tmp.root, "f99.log").exists())
        // local names keep increasing after a restart
        again.applySnapshot(listOf(meta("a"), meta("b")))
        assertTrue(again.entry("b")!!.local != e.local)
    }

    @Test
    fun unreadableIndexStartsOver() {
        tmp.root.resolve("index.json").writeText("{not json")
        tmp.root.resolve("f1.log").writeText("orphan")
        val m = mirror()
        assertTrue(m.entries().isEmpty())
        assertNull(m.info)
        assertFalse(tmp.root.resolve("f1.log").exists())
    }

    @Test
    fun dataBeforeTheSnapshotIsKept() {
        val m = mirror()
        assertEquals(LogMirror.AppendResult.APPENDED, m.append("a", "id-a", 0, "abc".toByteArray()))
        assertEquals(0, m.entry("a")!!.lastWriteTicks)
        m.applySnapshot(listOf(meta("a", length = 3, lastWrite = 55)))
        assertEquals(55, m.entry("a")!!.lastWriteTicks)
        assertEquals(3, m.entry("a")!!.size)
    }
}
