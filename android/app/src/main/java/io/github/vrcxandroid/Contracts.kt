package io.github.vrcxandroid

import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

// Interfaces shared between packages. Each is implemented by exactly one package (see AppGraph).

/** Implemented by bridge/WebApiModule. */
interface HttpProvider {
    /** Client with the persistent VRChat cookie jar, proxy and "VRCX <version>" User-Agent. */
    val client: OkHttpClient
    /** Flushes the cookie jar to the database now. */
    suspend fun flushCookies()
}

/** Implemented by bridge/SQLiteModule. */
interface DatabaseController {
    /** Replaces the database with a PC VRCX.sqlite3 (and optionally VRCX.json). Returns {ok, message}. */
    suspend fun importFrom(database: Uri, vrcxJson: Uri?): JsonObject
    /** Checkpoints WAL and copies the database to [target]. */
    suspend fun exportTo(target: Uri)
    /** Closes the connection (before restart or import). */
    suspend fun close()
}

/** Implemented by bridge/VRCXStorageModule. */
interface StorageController {
    fun get(key: String): String
    fun set(key: String, value: String)
    /** Writes pending changes now. */
    suspend fun flush()
}

/** Implemented by logwatcher/LogWatcher (fed by the companion). */
interface GameStateProvider {
    val isGameRunning: Boolean
    val isSteamVRRunning: Boolean
    val vrcClosedGracefully: Boolean
}

/** Implemented by companion/CompanionManager. JSON shapes are defined in companion/CompanionManager.kt KDoc. */
interface CompanionController {
    /** {paired:[...], activeId, status:"unpaired"|"searching"|"connecting"|"connected"|"error", machineName, tz, vrchatRunning, steamVrRunning, lastError} */
    fun state(): JsonObject
    /** UDP discovery for [timeoutMs]; returns companions found [{id,name,host,port,fp,pairing}]. */
    suspend fun discover(timeoutMs: Long): JsonArray
    /** Pairs using a scanned vrcxc://pair?... payload. Returns state(). */
    suspend fun pairWithQr(payload: String): JsonObject
    /** Pairs with {host, port, fp?} and a typed code. fp null = capture the certificate on first use. Returns state(). */
    suspend fun pair(target: JsonObject, code: String): JsonObject
    fun forget(companionId: String)
    fun setActive(companionId: String)
    /** Starts/stops the connection loop (background mode off + app hidden → stop). */
    fun setRunning(running: Boolean)
    /** Called by LogWatcher.SetDateTill so the next subscribe asks for the right files. */
    fun onTillDateChanged(utcTicks: Long)
}

/**
 * Battery hints for the companion link (docs/ARCHITECTURE.md §7). The host calls this on Activity start/stop; the
 * companion client stretches keep-alives, reconnect backoff and discovery while the app is hidden.
 * Default no-op so implementations can adopt it independently.
 */
interface CompanionVisibility {
    fun setAppVisible(visible: Boolean) {}
}

/** Implemented by host/TtsController. */
interface TtsController {
    fun voices(): JsonArray
    fun speak(utterance: JsonObject)
    fun cancel()
}

/** Marker so modules can emit events without depending on the host package. */
fun interface EventEmitter {
    fun emit(event: String, data: JsonElement?)
}
