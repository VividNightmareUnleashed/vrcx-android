package io.github.vrcxandroid.bridge.sqlite

import io.github.vrcxandroid.bridge.element
import io.github.vrcxandroid.bridge.jsonOf
import io.github.vrcxandroid.bridge.missingMethod
import io.github.vrcxandroid.bridge.requireArg
import io.github.vrcxandroid.bridge.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The JS-visible `SQLite` methods:
 * - `ExecuteJson(sql, args)` → JSON **string** `[[...], ...]` (NULL → `null`, BLOB → base64)
 * - `Execute(sql, args)` → the same rows as a JSON array
 * - `ExecuteNonQuery(sql, args)` → rows changed, or -1
 *
 * `args` is the frontend's `{'@name': value}` object (a JS `Map` the shim encodes as an object) or null.
 * Errors reject as `SQLiteException: <sqlite message>`, e.g. `SQLiteException: database is locked` ([SqliteErrors]).
 */
internal object SqliteBridge {
    const val CLASS_NAME = "SQLite"

    fun invoke(session: SqliteSession, method: String, args: JsonArray): JsonElement = when (method) {
        "ExecuteJson" -> {
            val rows = JsonTextRows()
            session.query(sql(args), sqlArgs(args), rows)
            jsonOf(rows.text())
        }
        "Execute" -> {
            val rows = JsonArrayRows()
            session.query(sql(args), sqlArgs(args), rows)
            rows.result()
        }
        "ExecuteNonQuery" -> jsonOf(session.executeNonQuery(sql(args), sqlArgs(args)))
        // Host-only upstream (Init/Exit); harmless to expose.
        "Init" -> {
            session.connection()
            JsonNull
        }
        "Exit" -> {
            session.close()
            JsonNull
        }
        else -> missingMethod(CLASS_NAME, method)
    }

    private fun sql(args: JsonArray): String = requireArg(args.str(0), "sql")

    /** Only an object carries named arguments; null, absent or any other value binds nothing. */
    private fun sqlArgs(args: JsonArray): JsonObject? = args.element(1) as? JsonObject
}
