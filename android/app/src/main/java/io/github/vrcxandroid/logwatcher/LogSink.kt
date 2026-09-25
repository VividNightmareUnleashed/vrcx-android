package io.github.vrcxandroid.logwatcher

/**
 * What the companion client (package companion) feeds into the log side (package logwatcher). Implemented by
 * [LogWatcher]. All calls for one connection come from one thread, in wire order (docs/PROTOCOL.md §5).
 */
interface LogSink {
    /** A session with [companionId] started (after authOk/paired). The mirror for that companion becomes active. */
    fun onSessionStarted(companionId: String, info: CompanionInfo)

    /** `info` updates (time zone, clock). */
    fun onInfo(info: CompanionInfo)

    /** Mirror state to send in `subscribe.have`. */
    fun have(): List<MirroredFile>

    /** The tillDate (in .NET UTC ticks) to send as `subscribe.sinceUtcTicks`; 0 when unknown. */
    fun sinceUtcTicks(): Long

    fun onSnapshot(files: List<PcFileMeta>)

    /** Contiguous bytes for a file; [offset] equals the mirrored length except after truncate/fetch. */
    fun onData(name: String, fileId: String, offset: Long, bytes: ByteArray)

    fun onTruncate(name: String, fileId: String, newLength: Long)

    fun onProcessState(vrchatRunning: Boolean, steamVrRunning: Boolean, pcUtcNowMs: Long)

    fun onSyncComplete()

    /** Smoothed PC clock minus phone clock, in milliseconds (from heartbeats). */
    fun onClockSkew(skewMs: Long)

    /** Connection lost (the last process state is kept for a grace period, ARCHITECTURE.md §8). */
    fun onDisconnected()
}

/**
 * Log-side hooks the companion client needs outside the stream. Optional: the implementer of [LogSink] (LogWatcher)
 * also implements this, and the client skips a hook while it does not.
 */
interface CompanionMirrorControl {
    /**
     * The user forgot [companionId]: drop its mirror (`filesDir/logmirror/<companionId>/`) and everything kept for it.
     * Called after [LogSink.onDisconnected] of that companion's last session; may block briefly.
     */
    fun forgetCompanion(companionId: String)

    /**
     * Registers (null: removes) the receiver of resend requests: when the mirror finds a gap or loses bytes, it calls
     * [FetchRequester.requestFetch] and the client sends `fetch` (PROTOCOL.md §5.9) on the current session.
     */
    fun setFetchRequester(requester: FetchRequester?)
}

/** Asks the companion to resend [name] from [fromOffset]. Never blocks; ignored while no session is connected. */
fun interface FetchRequester {
    fun requestFetch(name: String, fileId: String, fromOffset: Long)
}

/** `info` message (PROTOCOL.md §5.4). */
data class CompanionInfo(
    val companionVersion: String,
    val machineName: String,
    val pcUtcNowMs: Long,
    val tzWindowsId: String?,
    val tzIanaId: String?,
    val tzSupportsDst: Boolean,
    val tzBaseUtcOffsetMin: Int,
    val tzCurrentUtcOffsetMin: Int,
    val logDir: String?,
    val dirExists: Boolean,
)

/** One `snapshot.files` entry (PROTOCOL.md §5.6), exactly as the PC reported it. */
data class PcFileMeta(
    val name: String,
    val fileId: String,
    val creationTimeUtcTicks: Long,
    val lastWriteTimeUtcTicks: Long,
    val length: Long,
)

/** One `subscribe.have` entry. */
data class MirroredFile(val name: String, val fileId: String, val length: Long)
