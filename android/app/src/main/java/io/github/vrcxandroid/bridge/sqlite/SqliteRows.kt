package io.github.vrcxandroid.bridge.sqlite

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteStatement
import io.github.vrcxandroid.bridge.storage.JsonText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * Cell mapping by storage class: INTEGER → Long, REAL → Double, TEXT → String,
 * BLOB → base64 String, NULL → null. Non-finite REAL values (not valid JSON) become null.
 */
internal object SqliteValues {
    fun read(statement: SQLiteStatement, column: Int): Any? = when (statement.getColumnType(column)) {
        SQLITE_DATA_INTEGER -> statement.getLong(column)
        SQLITE_DATA_FLOAT -> statement.getDouble(column).takeIf { it.isFinite() }
        SQLITE_DATA_TEXT -> statement.getText(column)
        SQLITE_DATA_BLOB -> Base64.getEncoder().encodeToString(statement.getBlob(column))
        else -> null
    }
}

/** Receives the rows of the first result set of a command. */
internal interface RowSink {
    fun row(statement: SQLiteStatement, columnCount: Int)
}

/** Builds the `ExecuteJson` text `[[v, ...], ...]` directly, without an intermediate tree (results can be several MB). */
internal class JsonTextRows(capacity: Int = 256) : RowSink {
    private val sb = StringBuilder(capacity).append('[')
    private var rows = 0

    override fun row(statement: SQLiteStatement, columnCount: Int) {
        if (rows++ > 0) sb.append(',')
        sb.append('[')
        for (i in 0 until columnCount) {
            if (i > 0) sb.append(',')
            when (val v = SqliteValues.read(statement, i)) {
                null -> sb.append("null")
                is Long -> sb.append(v)
                is Double -> sb.append(v.toString())
                is String -> JsonText.appendQuoted(sb, v)
                else -> JsonText.appendQuoted(sb, v.toString())
            }
        }
        sb.append(']')
    }

    fun text(): String = sb.toString() + "]"
}

/** Builds the `Execute` result: an array of positional row arrays. */
internal class JsonArrayRows : RowSink {
    private val rows = ArrayList<JsonElement>()

    override fun row(statement: SQLiteStatement, columnCount: Int) {
        val cells = ArrayList<JsonElement>(columnCount)
        for (i in 0 until columnCount) {
            cells.add(
                when (val v = SqliteValues.read(statement, i)) {
                    null -> JsonNull
                    is Long -> JsonPrimitive(v)
                    is Double -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                },
            )
        }
        rows.add(JsonArray(cells))
    }

    fun result(): JsonArray = JsonArray(rows)
}

/** Plain Kotlin rows for native callers (cookie jar, import validation). */
internal class ListRows : RowSink {
    val rows = ArrayList<List<Any?>>()

    override fun row(statement: SQLiteStatement, columnCount: Int) {
        rows.add(List(columnCount) { SqliteValues.read(statement, it) })
    }
}
