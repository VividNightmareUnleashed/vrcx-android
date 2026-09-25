package io.github.vrcxandroid.companion

import io.github.vrcxandroid.logwatcher.MirroredFile
import io.github.vrcxandroid.logwatcher.PcFileMeta
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Tracks, per `(name, fileId)`, how many bytes the mirror has been given on this connection, so that bytes the mirror
 * already has are never delivered twice and a gap is repaired with `fetch` (PROTOCOL.md §5.7, §5.9).
 *
 * Overlaps happen when a second `subscribe` restarts the sequence while earlier data frames are still in flight;
 * gaps should not happen with a correct companion, but when one does, the frame is dropped and the missing range is
 * fetched. Frames for that file are then dropped until the fetch answer (a frame starting at the fetch offset) arrives.
 */
class OffsetTracker {
    private data class Key(val name: String, val fileId: String)

    sealed interface Decision {
        /** Deliver the frame, minus its first [skip] bytes (which the mirror already has). */
        data class Deliver(val skip: Int) : Decision

        /** Everything in the frame is already mirrored, or a fetch answer is awaited. */
        object Drop : Decision

        /** Gap: drop the frame and send `fetch` from [fromOffset]. */
        data class Fetch(val fromOffset: Long) : Decision
    }

    private val expected = HashMap<Key, Long>()
    private val pendingFetch = HashMap<Key, Long>()

    /** Called with the `have` list of every `subscribe`: the mirror's own state is the truth. */
    fun reset(have: List<MirroredFile>) {
        expected.clear()
        pendingFetch.clear()
        for (f in have) expected[Key(f.name, f.fileId)] = f.length
    }

    /** Files missing from a snapshot were deleted on the PC. */
    fun onSnapshot(files: List<PcFileMeta>) {
        val keep = files.mapTo(HashSet()) { Key(it.name, it.fileId) }
        expected.keys.retainAll(keep)
        pendingFetch.keys.retainAll(keep)
    }

    fun onTruncate(name: String, fileId: String, newLength: Long) {
        val key = Key(name, fileId)
        expected[key] = newLength
        pendingFetch.remove(key)
    }

    /** A `fetch` was sent from [fromOffset] (by the gap logic or on request of the mirror). */
    fun onFetchRequested(name: String, fileId: String, fromOffset: Long) {
        val key = Key(name, fileId)
        expected[key] = fromOffset
        pendingFetch[key] = fromOffset
    }

    fun expectedOffset(name: String, fileId: String): Long = expected[Key(name, fileId)] ?: 0L

    fun onData(name: String, fileId: String, offset: Long, length: Int): Decision {
        val key = Key(name, fileId)
        val end = offset + length
        val fetchFrom = pendingFetch[key]
        if (fetchFrom != null) {
            // Only a frame that starts at or contains the fetch offset resumes the file (the fetch answer, or a
            // restarted sequence that covers it); everything else is in-flight data the answer will repeat.
            val covers = offset == fetchFrom || (offset < fetchFrom && end > fetchFrom)
            if (!covers) return Decision.Drop
            pendingFetch.remove(key)
            expected[key] = maxOf(end, fetchFrom)
            return Decision.Deliver((fetchFrom - offset).toInt())
        }
        val exp = expected[key] ?: 0L
        return when {
            offset == exp -> {
                expected[key] = end
                Decision.Deliver(0)
            }
            offset < exp -> {
                if (end <= exp) {
                    Decision.Drop
                } else {
                    expected[key] = end
                    Decision.Deliver((exp - offset).toInt())
                }
            }
            else -> {
                pendingFetch[key] = exp
                Decision.Fetch(exp)
            }
        }
    }
}

/**
 * Clock skew (PC clock minus phone clock) from heartbeats: `sample = pcUtcNowMs - phone receive time`. Network delay
 * only ever lowers a sample, so the estimate is the largest sample of the last [window] (about 40 s of heartbeats).
 */
class SkewEstimator(private val window: Int = 8) {
    private val samples = ArrayDeque<Long>()

    fun add(sampleMs: Long): Long {
        samples.addLast(sampleMs)
        while (samples.size > window) samples.removeFirst()
        return samples.max()
    }

    fun reset() = samples.clear()
}

/** Reconnect delays: [baseMs] doubling up to [maxMs], with ±15 % jitter, always within [baseMs, maxMs]. */
class Backoff(private val baseMs: Long, private val maxMs: Long, private val random: Random = Random.Default) {
    fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        val raw = minOf(maxMs, baseMs shl shift)
        val jittered = (raw * (0.85 + random.nextDouble() * 0.3)).toLong()
        return jittered.coerceIn(baseMs, maxMs)
    }
}

/**
 * `ack` bookkeeping of one connection (PROTOCOL.md §5.9): the data-frame bytes the log side has consumed, and the
 * total last acknowledged. [consume] runs on the sink thread after a frame was handled; [ackIfAtLeast] may run on any thread
 * (the connection loop sends acks with its keep-alives), and its sends are serialized so the companion always sees
 * increasing totals.
 */
class AckCounter {
    private val consumed = AtomicLong()
    private val lock = Any()
    private var acked = 0L // guarded by lock

    val consumedBytes: Long get() = consumed.get()

    fun consume(bytes: Long) {
        consumed.addAndGet(bytes)
    }

    /**
     * Calls [send] with the consumed total when at least [minBytes] (at least 1) were consumed since the last ack, and
     * returns whether it did. An exception from [send] leaves the counter unchanged.
     */
    fun ackIfAtLeast(minBytes: Long, send: (Long) -> Unit): Boolean = synchronized(lock) {
        val total = consumed.get()
        if (total - acked < minBytes.coerceAtLeast(1)) return false
        send(total)
        acked = total
        true
    }
}

/**
 * Tells which `ConnectivityManager` default-network callbacks are real changes. [initial] is the default network when
 * the callback is registered (null when there is none): only a callback for that same network is the echo of the
 * registration. Any other arrival, including the first network after starting without one, is a change, and so is the
 * loss of the current network.
 */
class DefaultNetworkTracker<N : Any>(initial: N?) {
    private var current: N? = initial

    /** Returns whether [network] replaces the current default network. */
    @Synchronized
    fun onAvailable(network: N): Boolean {
        val changed = network != current
        current = network
        return changed
    }

    /** Returns whether the current default network was lost. */
    @Synchronized
    fun onLost(network: N): Boolean {
        if (network != current) return false
        current = null
        return true
    }
}
