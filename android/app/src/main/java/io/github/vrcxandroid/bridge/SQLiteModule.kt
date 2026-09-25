package io.github.vrcxandroid.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.DatabaseController
import io.github.vrcxandroid.bridge.sqlite.SqliteBridge
import io.github.vrcxandroid.bridge.sqlite.SqliteSession
import io.github.vrcxandroid.bridge.sqlite.VrcxDatabase
import io.github.vrcxandroid.bridge.storage.VrcxJsonFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * `SQLite`: the VRCX database at `filesDir/VRCX/VRCX.sqlite3` on the bundled SQLite (ARCHITECTURE.md §5).
 *
 * One connection, used only on this module's serialized lane: page calls arrive there through the dispatcher, and native
 * code must go through [runNative] (which is `AppGraph.dispatcher.runSerialized("SQLite")`). The connection is opened
 * lazily on the lane, never on the main thread. `VRCXStorage["VRCX_DatabaseLocation"]` (a PC path, only ever written as
 * `''` by the frontend) is ignored: the database always lives in app-private storage.
 */
class SQLiteModule internal constructor(
    private val context: Context,
    private val dbFile: File,
    private val dispatcher: () -> BridgeDispatcher,
    /** Runs on the lane right before the file is replaced; must not suspend or touch the lane. */
    private val beforeReplace: () -> Unit = {},
    private val afterImport: suspend (storageEntries: Map<String, String>?) -> Unit,
) : BridgeModule, DatabaseController {
    constructor(context: Context) : this(
        context,
        File(File(context.filesDir, "VRCX"), "VRCX.sqlite3"),
        { AppGraph.dispatcher },
        // The cookie jar must not write the old session into the imported database before the restart. Its saves are
        // ordered on this lane, so dropping the session here fences every save still queued behind the replacement.
        { (AppGraph.http as? WebApiModule)?.onDatabaseReplacing() },
        { entries -> if (entries != null) (AppGraph.storage as? VRCXStorageModule)?.replaceAll(entries) },
    )

    override val className = SqliteBridge.CLASS_NAME

    private val session = SqliteSession { VrcxDatabase.open(dbFile) }

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = SqliteBridge.invoke(session, method, args)

    /**
     * Runs [block] on the SQLite lane, in order with the page's calls. Must not be called from the lane itself (it
     * would wait for its own queue).
     */
    internal suspend fun <T> runNative(block: (SqliteSession) -> T): T =
        dispatcher().runSerialized(className) { block(session) }

    override suspend fun importFrom(database: Uri, vrcxJson: Uri?): JsonObject = withContext(Dispatchers.IO) {
        val dir = dbFile.absoluteFile.parentFile!!
        dir.mkdirs()
        val staged = File(dir, "import.sqlite3.tmp")
        try {
            VrcxDatabase.deleteSidecars(staged)
            try {
                copyFromUri(database, staged)
            } catch (e: Exception) {
                return@withContext result(false, "The selected database could not be read: ${e.message}")
            }
            VrcxDatabase.validate(staged)?.let { return@withContext result(false, it) }
            VrcxDatabase.deleteSidecars(staged)

            val storageEntries = if (vrcxJson == null) null else try {
                VrcxJsonFormat.decode(readUri(vrcxJson))
            } catch (e: Exception) {
                return@withContext result(false, "The selected VRCX.json could not be read: ${e.message}")
            }

            runNative { s ->
                beforeReplace()
                s.close()
                VrcxDatabase.deleteSidecars(dbFile)
                VrcxDatabase.replace(staged, dbFile)
            }
            afterImport(storageEntries)
            result(true, if (storageEntries != null) "Imported VRCX.sqlite3 and VRCX.json." else "Imported VRCX.sqlite3.")
        } catch (e: Exception) {
            Log.e(TAG, "Database import failed", e)
            result(false, "Import failed: ${e.message}")
        } finally {
            staged.delete()
            VrcxDatabase.deleteSidecars(staged)
        }
    }

    /**
     * Checkpoints the WAL into the main file and copies it, on the lane so no write can interleave. The copy is marked
     * as a rollback-journal database (like upstream's PC files), which is exact because the WAL is empty after
     * `wal_checkpoint(TRUNCATE)`.
     */
    override suspend fun exportTo(target: Uri) {
        runNative { s ->
            s.connection()
            VrcxDatabase.checkpoint(s)
            val output = openOutput(target) ?: throw IOException("Cannot write to $target")
            output.use { out -> dbFile.inputStream().use { VrcxDatabase.copyAsRollbackJournal(it, out) } }
        }
    }

    override suspend fun close() {
        runNative { it.close() }
    }

    private fun copyFromUri(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { i -> target.outputStream().use { o -> VrcxDatabase.copy(i, o) } }
    }

    private fun readUri(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        return input.use { it.readBytes() }
    }

    private fun openOutput(uri: Uri) = try {
        context.contentResolver.openOutputStream(uri, "wt")
    } catch (e: Exception) {
        // Some providers do not support truncate mode.
        context.contentResolver.openOutputStream(uri, "w")
    }

    private fun result(ok: Boolean, message: String) = buildJsonObject {
        put("ok", ok)
        put("message", message)
    }

    private companion object {
        const val TAG = "SQLiteModule"
    }
}
