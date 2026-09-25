package io.github.vrcxandroid.bridge.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.github.vrcxandroid.bridge.DotNetException
import kotlinx.serialization.json.JsonObject

/**
 * The single connection behind the `SQLite` bridge class. Not thread-safe by design: every call
 * must run on the SQLite lane (the bridge dispatcher serializes page calls; native callers use
 * `SQLiteModule.runNative`), so a JS transaction spanning several calls sees its own uncommitted writes.
 *
 * The connection is opened lazily by [opener] on first use and reopened after [close].
 */
internal class SqliteSession(private val opener: () -> SQLiteConnection) {
    private var connection: SQLiteConnection? = null

    private val scanCache = object : LinkedHashMap<String, List<ScannedStatement>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ScannedStatement>>?) = size > 64
    }

    val isOpen: Boolean get() = connection != null

    fun connection(): SQLiteConnection = connection ?: translating { opener() }.also { connection = it }

    fun close() {
        val c = connection ?: return
        connection = null
        c.close()
    }

    /**
     * `Execute`/`ExecuteJson`: runs every statement of [sql]; the rows of the first statement that returns columns go to
     * [sink] (System.Data.SQLite's reader returns only the first result set).
     */
    fun query(sql: String, args: JsonObject?, sink: RowSink) {
        var delivered = false
        run(sql, args) { statement, _ ->
            val columns = statement.getColumnCount()
            if (!delivered && columns > 0) {
                delivered = true
                while (statement.step()) sink.row(statement, columns)
            } else {
                while (statement.step()) Unit
            }
        }
    }

    /**
     * `ExecuteNonQuery`: runs every statement. Returns the rows changed by INSERT/UPDATE/DELETE/REPLACE statements, or
     * -1 when the text had none (for example `BEGIN`/`COMMIT`), like System.Data.SQLite. JS never uses the value.
     */
    fun executeNonQuery(sql: String, args: JsonObject?): Int {
        var changed = -1
        run(sql, args) { statement, scanned ->
            while (statement.step()) Unit
            if (scanned.writesRows) {
                if (changed < 0) changed = 0
                changed += changes()
            }
        }
        return changed
    }

    /** First column of the first row, or null (native helper). */
    fun queryScalar(sql: String, args: JsonObject? = null): Any? {
        val rows = ListRows()
        query(sql, args, rows)
        return rows.rows.firstOrNull()?.firstOrNull()
    }

    fun queryRows(sql: String, args: JsonObject? = null): List<List<Any?>> = ListRows().also { query(sql, args, it) }.rows

    private fun changes(): Int = connection().prepare("SELECT changes()").use { s ->
        if (s.step()) s.getLong(0).toInt() else 0
    }

    private inline fun run(sql: String, args: JsonObject?, block: (SQLiteStatement, ScannedStatement) -> Unit) {
        translating {
            val conn = connection()
            var unnamedStart = 0
            for (scanned in scan(sql)) {
                conn.prepare(scanned.sql).use { statement ->
                    SqlBinder.bind(statement, SqlBinder.resolve(scanned.params, args, unnamedStart))
                    block(statement, scanned)
                }
                unnamedStart = SqlBinder.unnamedCount(scanned.params)
            }
        }
    }

    private fun scan(sql: String): List<ScannedStatement> =
        scanCache[sql] ?: SqlScanner.scan(sql).also { scanCache[sql] = it }

    private inline fun <T> translating(block: () -> T): T = try {
        block()
    } catch (e: DotNetException) {
        throw e
    } catch (e: RuntimeException) {
        throw SqliteErrors.translate(e) ?: e
    }
}
