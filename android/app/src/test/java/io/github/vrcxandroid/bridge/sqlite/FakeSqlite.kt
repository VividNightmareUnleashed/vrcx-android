package io.github.vrcxandroid.bridge.sqlite

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/** A scripted result for one SQL text. */
class Script(
    val columns: List<String> = emptyList(),
    val rows: List<List<Any?>> = emptyList(),
    /** Thrown from step(), in the bundled driver's message format. */
    val stepError: String? = null,
)

/** In-memory stand-in for the bundled driver: returns scripted rows and records binds and prepared SQL. */
class FakeConnection(
    private val scripts: Map<String, Script> = emptyMap(),
    var changes: Long = 0,
    /** SQL texts whose prepare() fails, with the driver's message. */
    private val prepareErrors: Map<String, String> = emptyMap(),
) : SQLiteConnection {
    val prepared = ArrayList<String>()
    val statements = ArrayList<FakeStatement>()
    var closed = false

    override fun prepare(sql: String): SQLiteStatement {
        check(!closed) { "closed" }
        prepared.add(sql)
        prepareErrors[sql]?.let { throw RuntimeException(it) }
        val script = if (sql == "SELECT changes()") Script(listOf("changes()"), listOf(listOf(changes))) else scripts[sql] ?: Script()
        return FakeStatement(sql, script).also { statements.add(it) }
    }

    override fun close() {
        closed = true
    }
}

class FakeStatement(val sql: String, private val script: Script) : SQLiteStatement {
    val binds = HashMap<Int, Any?>()
    var steps = 0
    var closed = false
    private var row = -1

    override fun bindBlob(index: Int, value: ByteArray) { binds[index] = value }
    override fun bindDouble(index: Int, value: Double) { binds[index] = value }
    override fun bindLong(index: Int, value: Long) { binds[index] = value }
    override fun bindText(index: Int, value: String) { binds[index] = value }
    override fun bindNull(index: Int) { binds[index] = null }

    private fun cell(index: Int): Any? = script.rows[row][index]

    override fun getBlob(index: Int): ByteArray = cell(index) as ByteArray
    override fun getDouble(index: Int): Double = (cell(index) as Number).toDouble()
    override fun getLong(index: Int): Long = (cell(index) as Number).toLong()
    override fun getText(index: Int): String = cell(index).toString()
    override fun isNull(index: Int): Boolean = cell(index) == null
    override fun getColumnCount(): Int = script.columns.size
    override fun getColumnName(index: Int): String = script.columns[index]
    override fun getColumnType(index: Int): Int = when (cell(index)) {
        null -> SQLITE_DATA_NULL
        is Long, is Int -> SQLITE_DATA_INTEGER
        is Double -> SQLITE_DATA_FLOAT
        is ByteArray -> SQLITE_DATA_BLOB
        else -> SQLITE_DATA_TEXT
    }

    override fun step(): Boolean {
        steps++
        script.stepError?.let { throw RuntimeException(it) }
        row++
        return row < script.rows.size
    }

    override fun reset() { row = -1 }
    override fun clearBindings() { binds.clear() }
    override fun close() { closed = true }
}
