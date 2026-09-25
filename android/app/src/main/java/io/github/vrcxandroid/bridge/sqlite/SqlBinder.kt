package io.github.vrcxandroid.bridge.sqlite

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** A value to bind, already converted from its JSON argument by [SqlBinder.convert]. */
internal sealed interface SqlValue {
    data object Null : SqlValue
    data class Integer(val value: Long) : SqlValue
    data class Real(val value: Double) : SqlValue
    data class Text(val value: String) : SqlValue
}

/**
 * Maps the JS argument object (`{'@name': value}`) onto a statement's parameters with System.Data.SQLite's rules
 * (`SQLiteParameterCollection.MapParameters` / `SQLiteStatement.MapParameter`):
 *
 * - Each argument, in object order, goes to the first parameter whose name matches, compared case-insensitively. A key
 *   that starts with `:`, `$`, `@` or `;` must match the full name; any other key matches the name without its prefix
 *   character (`key` binds `@key`, `:key` or `$key`).
 * - An argument that matches no name is tried against the nameless parameters by its position in the object
 *   (System.Data.SQLite names them `;0`, `;1`, ...). Otherwise it is ignored.
 * - A parameter left without a value fails with [SqliteErrors.insufficientParameters].
 */
internal object SqlBinder {
    private const val MAX_SAFE_INTEGER = 9007199254740992.0 // 2^53

    /**
     * Values per parameter index for one statement. [unnamedStart] is the first `;N` number used for this statement's
     * nameless parameters (System.Data.SQLite continues from the previous statement's count).
     */
    fun resolve(params: List<String?>, args: JsonObject?, unnamedStart: Int = 0): List<SqlValue> {
        if (params.isEmpty()) return emptyList()
        // First index per folded full name and per folded name without its prefix character, so that thousands of
        // parameters (VIP lists, activityV2's 1250) map in linear time. Equivalent to scanning names in index order.
        val byFullName = HashMap<String, Int>(params.size * 2)
        val byBareName = HashMap<String, Int>(params.size * 2)
        var unnamed = unnamedStart
        for (i in params.indices) {
            val name = params[i] ?: ";${unnamed++}"
            byFullName.putIfAbsent(fold(name), i)
            byBareName.putIfAbsent(fold(name.substring(1)), i)
        }
        val values = arrayOfNulls<SqlValue>(params.size)
        if (args != null) {
            var position = 0
            for ((key, value) in args) {
                val target = find(byFullName, byBareName, key) ?: find(byFullName, byBareName, ";$position")
                if (target != null) values[target] = convert(value)
                position++
            }
        }
        return values.map { it ?: throw SqliteErrors.insufficientParameters() }
    }

    private fun find(byFullName: Map<String, Int>, byBareName: Map<String, Int>, key: String): Int? =
        if (key.isNotEmpty() && key[0] !in ":$@;") byBareName[fold(key)] else byFullName[fold(key)]

    /** Ordinal case-insensitive comparison key (per-character upper-casing, like .NET `OrdinalIgnoreCase`). */
    private fun fold(s: String): String {
        val chars = CharArray(s.length)
        for (i in s.indices) chars[i] = s[i].uppercaseChar()
        return String(chars)
    }

    /** Number of nameless parameters in [params] (to continue `;N` numbering in the next statement). */
    fun unnamedCount(params: List<String?>): Int = params.count { it == null }

    fun bind(statement: SQLiteStatement, values: List<SqlValue>) {
        values.forEachIndexed { i, v ->
            val index = i + 1
            when (v) {
                SqlValue.Null -> statement.bindNull(index)
                is SqlValue.Integer -> statement.bindLong(index, v.value)
                is SqlValue.Real -> statement.bindDouble(index, v.value)
                is SqlValue.Text -> statement.bindText(index, v.value)
            }
        }
    }

    /** `SQLiteStatement.MapParameter` name comparison (reference for [resolve]'s lookup maps). */
    fun matches(paramName: String, key: String): Boolean {
        val startAt = if (key.isNotEmpty() && key[0] !in ":$@;") 1 else 0
        return paramName.length - startAt == key.length && fold(paramName.substring(startAt)) == fold(key)
    }

    /**
     * JSON value → SQLite value: string → TEXT; integral number below 2^53 → INTEGER; other numbers → REAL;
     * boolean → 1/0; null → NULL; object/array → TEXT of its JSON.
     */
    fun convert(value: JsonElement): SqlValue = when (value) {
        is JsonNull -> SqlValue.Null
        is JsonPrimitive -> when {
            value.isString -> SqlValue.Text(value.content)
            value.content == "true" -> SqlValue.Integer(1)
            value.content == "false" -> SqlValue.Integer(0)
            else -> number(value.content)
        }
        is JsonObject, is JsonArray -> SqlValue.Text(value.toString())
    }

    private fun number(text: String): SqlValue {
        text.toLongOrNull()?.let { if (abs(it.toDouble()) < MAX_SAFE_INTEGER) return SqlValue.Integer(it) }
        val d = text.toDoubleOrNull() ?: return SqlValue.Text(text)
        return if (d == Math.floor(d) && !d.isInfinite() && abs(d) < MAX_SAFE_INTEGER) SqlValue.Integer(d.toLong()) else SqlValue.Real(d)
    }
}
