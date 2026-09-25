package io.github.vrcxandroid.companion

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.CompanionController
import io.github.vrcxandroid.EventEmitter
import io.github.vrcxandroid.logwatcher.LogSink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * LAN client for the Windows companion (docs/PROTOCOL.md). The protocol logic lives in [CompanionEngine]; this class
 * wires it to Android: pairings in EncryptedSharedPreferences, a Wi-Fi multicast lock during discovery, the default
 * network callback for immediate reconnects, the LogWatcher as [LogSink] and the bridge for `companion-state` events.
 *
 * JSON shapes (ARCHITECTURE.md §5.1):
 * - [state]: `{status: 'unpaired'|'idle'|'searching'|'connecting'|'connected'|'error', activeId,
 *   paired: [{id, name, hosts, port, fp, pairedAt, lastSeen}], machineName, tz, vrchatRunning, steamVrRunning,
 *   syncing, lastError}`; `pairedAt`/`lastSeen` are epoch milliseconds. `lastError` is null or one of `unreachable`,
 *   `fingerprint`, `not-local`, `version`, `protocol`, `revoked` (pairing removed on the PC).
 * - [discover]: `[{id, name, host, port, fp, pairing, hosts}]`.
 * - [pair] / [pairWithQr] reject with [PairingException] (`PairingException: <code>`).
 *
 * The connection loop starts with the process (no network traffic without a pairing); the host stops it with
 * `setRunning(false)` when background mode is off and the app has been hidden for a while (ARCHITECTURE.md §7).
 */
class CompanionManager(private val context: Context) : CompanionController {
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
        mirrorCleaner = ::deleteMirror,
    )

    private val networkMonitor = AndroidNetworkMonitor(appContext, engine::onNetworkChanged).also { it.start() }

    override fun state(): JsonObject = engine.state()
    override suspend fun discover(timeoutMs: Long): JsonArray = engine.discover(timeoutMs)
    override suspend fun pairWithQr(payload: String): JsonObject = engine.pairWithQr(payload)
    override suspend fun pair(target: JsonObject, code: String): JsonObject = engine.pair(target, code)
    override fun forget(companionId: String) = engine.forget(companionId)
    override fun setActive(companionId: String) = engine.setActive(companionId)
    override fun setRunning(running: Boolean) = engine.setRunning(running)
    override fun onTillDateChanged(utcTicks: Long) = engine.onTillDateChanged(utcTicks)

    /** Lets the log mirror ask for a resend after it found a gap or lost a file (PROTOCOL.md §5.9 `fetch`). */
    fun requestFetch(name: String, fileId: String, fromOffset: Long) = engine.requestFetch(name, fileId, fromOffset)

    private fun resolveLogSink(): LogSink {
        val watcher: Any = try {
            AppGraph.logWatcher
        } catch (e: UninitializedPropertyAccessException) {
            return NullLogSink
        }
        return watcher as? LogSink ?: NullLogSink.also {
            Log.w(TAG, "LogWatcher does not implement LogSink yet; the companion stream is not subscribed")
        }
    }

    /** `forget`: the mirror of a forgotten companion (ARCHITECTURE.md §8: filesDir/logmirror/<companionId>/). */
    private fun deleteMirror(companionId: String) {
        val owner = try {
            AppGraph.logWatcher as Any
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        if (owner is CompanionMirrorOwner) {
            owner.forgetCompanion(companionId)
            return
        }
        if (!CompanionProtocol.isSafeId(companionId)) return
        val root = File(appContext.filesDir, "logmirror")
        val dir = File(root, companionId)
        if (dir.parentFile == root && dir.exists() && !dir.deleteRecursively()) {
            Log.w(TAG, "could not delete the mirror of a forgotten companion")
        }
    }

    private companion object {
        const val TAG = "VRCXCompanion"
    }
}
