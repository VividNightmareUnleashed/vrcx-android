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
