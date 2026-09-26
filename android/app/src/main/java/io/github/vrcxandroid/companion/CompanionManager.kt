package io.github.vrcxandroid.companion

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.CompanionController
import io.github.vrcxandroid.CompanionVisibility
import io.github.vrcxandroid.EventEmitter
import io.github.vrcxandroid.logwatcher.CompanionMirrorControl
import io.github.vrcxandroid.logwatcher.LogSink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * LAN client for the Windows companion (docs/PROTOCOL.md). The protocol logic lives in [CompanionEngine]; this class
 * wires it to Android: pairings in EncryptedSharedPreferences, a Wi-Fi multicast lock during discovery, the default
 * network callback for immediate reconnects, the LogWatcher as [LogSink] and the bridge for `companion-state` events.
 *
 * JSON shapes (ARCHITECTURE.md §5.1):
 * - [state]: `{status: 'unpaired'|'idle'|'searching'|'connecting'|'connected'|'error', activeId,
 *   paired: [{id, name, hosts, port, fp, pairedAt, lastSeen}], machineName, tz, vrchatRunning, steamVrRunning,
 *   syncing, lastError}`; `pairedAt`/`lastSeen` are epoch milliseconds. `lastError` is null or one of `unreachable`,
 *   `fingerprint`, `not-local`, `version`, `protocol`, `revoked` (pairing removed on the PC), `storage` (the pairing
 *   store cannot be read right now; status is then `error`).
 * - [discover]: `[{id, name, host, port, fp, pairing, hosts}]`.
 * - [pair] / [pairWithQr] reject with [PairingException] (`PairingException: <code>`).
 *
 * The log side is reached through [LogSink] for the stream and, when the LogWatcher implements it,
 * [CompanionMirrorControl] for `forget` (its mirror is dropped) and for gaps the mirror finds (answered with `fetch`).
 *
 * The connection loop starts with the process (no network traffic without a pairing); the host stops it with
 * `setRunning(false)` when background mode is off and the app has been hidden for a while (ARCHITECTURE.md §7).
 * With background mode on, the host reports the Activity's visibility through [CompanionVisibility.setAppVisible]:
 * while hidden, the link runs in the companion's idle mode and an unreachable PC is retried rarely and without
 * broadcast discovery, but at once when a Wi-Fi network appears (PROTOCOL.md §5.10, §5.11).
 */
class CompanionManager(private val context: Context) : CompanionController, CompanionVisibility {
    private val appContext: Context = context.applicationContext ?: context

    private val engine = CompanionEngine(
        store = EncryptedPrefsSecureStore(appContext),
        sinkProvider = ::resolveLogSink,
        emitter = EventEmitter { event, data ->
            try {
                AppGraph.dispatcher.emit(event, data)
            } catch (e: UninitializedPropertyAccessException) {
                // Before AppGraph.init finished: nobody listens yet.
            }
        },
        deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
        multicastLock = AndroidMulticastLock(appContext),
        mirrorCleaner = ::forgetMirror,
    )

    private val networkMonitor =
        AndroidNetworkMonitor(appContext, engine::onNetworkChanged, engine::onNetworkAvailable).also { it.start() }

    init {
        // AppGraph creates the LogWatcher first; resolveLogSink repeats this for any later order.
        mirrorControl()?.setFetchRequester(engine)
    }

    override fun state(): JsonObject = engine.state()
    override suspend fun discover(timeoutMs: Long): JsonArray = engine.discover(timeoutMs)
    override suspend fun pairWithQr(payload: String): JsonObject = engine.pairWithQr(payload)
    override suspend fun pair(target: JsonObject, code: String): JsonObject = engine.pair(target, code)
    override fun forget(companionId: String) = engine.forget(companionId)
    override fun setActive(companionId: String) = engine.setActive(companionId)
    override fun setRunning(running: Boolean) = engine.setRunning(running)
    override fun onTillDateChanged(utcTicks: Long) = engine.onTillDateChanged(utcTicks)

    /** Any thread (the host calls it on Activity start/stop); no network work happens on the caller's thread. */
    override fun setAppVisible(visible: Boolean) = engine.setAppVisible(visible)

    /** Asks for a resend after the log mirror found a gap or lost a file (PROTOCOL.md §5.9 `fetch`). */
    fun requestFetch(name: String, fileId: String, fromOffset: Long) = engine.requestFetch(name, fileId, fromOffset)

    private fun logWatcher(): Any? = try {
        AppGraph.logWatcher
    } catch (e: UninitializedPropertyAccessException) {
        null
    }

    private fun mirrorControl(): CompanionMirrorControl? = logWatcher() as? CompanionMirrorControl

    /** Called once per session, on the sink thread. */
    private fun resolveLogSink(): LogSink {
        val watcher = logWatcher() ?: return NullLogSink
        (watcher as? CompanionMirrorControl)?.setFetchRequester(engine)
        return watcher as? LogSink ?: NullLogSink.also {
            Log.w(TAG, "LogWatcher does not implement LogSink yet; the companion stream is not subscribed")
        }
    }

    /**
     * `forget`: the log side owns the mirror (ARCHITECTURE.md §8: filesDir/logmirror/<companionId>/) and may have it
     * open, so only it deletes the files.
     */
    private fun forgetMirror(companionId: String) {
        val control = mirrorControl()
        if (control == null) {
            Log.w(TAG, "LogWatcher does not implement CompanionMirrorControl; the forgotten companion's mirror stays")
            return
        }
        control.forgetCompanion(companionId)
    }

    private companion object {
        const val TAG = "VRCXCompanion"
    }
}
