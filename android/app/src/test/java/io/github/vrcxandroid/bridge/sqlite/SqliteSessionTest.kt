package io.github.vrcxandroid.bridge.sqlite

import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.bridge.errorText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SqliteSessionTest {
    private fun call(session: SqliteSession, method: String, sql: String, args: JsonObject? = null) =
        SqliteBridge.invoke(session, method, buildJsonArray {
            add(JsonPrimitive(sql))
            add(args ?: JsonNull)
        })

    @Test
    fun executeJsonReturnsPositionalRowsAsJsonString() {
        val conn = FakeConnection(
            mapOf(
                "SELECT * FROM t" to Script(
                    listOf("i", "r", "s", "n", "b"),
                    listOf(
                        listOf(1L, 1.5, "a \"q\" \\ \n ü", null, byteArrayOf(1, 2, 3)),
                        listOf(9007199254740993L, 2.0, "", null, byteArrayOf()),
                    ),
                ),
            ),
        )
        val session = SqliteSession { conn }
        val result = call(session, "ExecuteJson", "SELECT * FROM t")
        val text = result.jsonPrimitive.content
        assertEquals(
            "[[1,1.5,\"a \\\"q\\\" \\\\ \\n ü\",null,\"AQID\"],[9007199254740993,2.0,\"\",null,\"\"]]",
            text,
        )
        // Round-trips as JSON.
        val parsed = Json.parseToJsonElement(text) as JsonArray
        assertEquals(2, parsed.size)
    }

    @Test
    fun emptyResultIsEmptyArray() {
        val session = SqliteSession { FakeConnection(mapOf("SELECT 1 WHERE 0" to Script(listOf("1")))) }
        assertEquals("[]", call(session, "ExecuteJson", "SELECT 1 WHERE 0").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), call(session, "Execute", "SELECT 1 WHERE 0"))
    }

    @Test
    fun executeReturnsJsonArrays() {
        val conn = FakeConnection(mapOf("SELECT a, b FROM t" to Script(listOf("a", "b"), listOf(listOf(7L, null), listOf("x", 0.25)))))
        val result = call(SqliteSession { conn }, "Execute", "SELECT a, b FROM t")
        assertEquals(
            JsonArray(listOf(JsonArray(listOf(JsonPrimitive(7L), JsonNull)), JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(0.25))))),
            result,
        )
    }

    @Test
    fun nonFiniteRealBecomesNull() {
        val conn = FakeConnection(mapOf("SELECT r" to Script(listOf("r"), listOf(listOf(Double.POSITIVE_INFINITY)))))
        assertEquals("[[null]]", call(SqliteSession { conn }, "ExecuteJson", "SELECT r").jsonPrimitive.content)
    }

    @Test
    fun bindsNamedArgumentsByIndex() {
        val sql = "SELECT value FROM configs WHERE key = @key AND other = @other"
        val conn = FakeConnection(mapOf(sql to Script(listOf("value"), listOf(listOf("v")))))
        val args = buildJsonObject {
            put("@other", 5)
            put("@key", "config:k")
            put("@extra", true)
        }
        call(SqliteSession { conn }, "ExecuteJson", sql, args)
        val stmt = conn.statements.single()
        assertEquals(mapOf(1 to "config:k", 2 to 5L), stmt.binds)
        assertTrue(stmt.closed)
    }

    @Test
    fun trailingSemicolonPreparesOnlyTheStatement() {
        val conn = FakeConnection()
        call(SqliteSession { conn }, "ExecuteNonQuery", "DELETE FROM t;\n")
        assertEquals(listOf("DELETE FROM t", "SELECT changes()"), conn.prepared)
    }

    @Test
    fun executeNonQueryReturnsChangesOrMinusOne() {
        val conn = FakeConnection(changes = 3)
        val session = SqliteSession { conn }
        assertEquals(JsonPrimitive(3), call(session, "ExecuteNonQuery", "UPDATE t SET a = 1"))
        assertEquals(JsonPrimitive(-1), call(session, "ExecuteNonQuery", "BEGIN"))
        assertEquals(JsonPrimitive(-1), call(session, "ExecuteNonQuery", "CREATE TABLE IF NOT EXISTS x (a)"))
        assertEquals(JsonPrimitive(6), call(session, "ExecuteNonQuery", "DELETE FROM a; DELETE FROM b"))
    }

    @Test
    fun multiStatementQueryReturnsFirstResultSetAndRunsTheRest() {
        val conn = FakeConnection(
            mapOf(
                "SELECT 1" to Script(listOf("1"), listOf(listOf(1L))),
                "SELECT 2" to Script(listOf("2"), listOf(listOf(2L))),
            ),
        )
        val text = call(SqliteSession { conn }, "ExecuteJson", "CREATE TABLE x (a); SELECT 1; SELECT 2").jsonPrimitive.content
        assertEquals("[[1]]", text)
        assertEquals(listOf("CREATE TABLE x (a)", "SELECT 1", "SELECT 2"), conn.prepared)
        assertEquals(2, conn.statements[2].steps) // stepped to completion
    }

    @Test
    fun missingParameterRejectsBeforeStepping() {
        val conn = FakeConnection()
        try {
            call(SqliteSession { conn }, "ExecuteNonQuery", "INSERT INTO t VALUES (@a, @b)", buildJsonObject { put("@a", 1) })
            fail()
        } catch (e: DotNetException) {
            assertEquals("SQLiteException: unknown error\r\nInsufficient parameters supplied to the command", errorText(e))
        }
        assertEquals(0, conn.statements.single().steps)
        assertTrue(conn.statements.single().closed)
    }

    @Test
    fun driverErrorsBecomeSqliteExceptionWithVerbatimMessage() {
        val conn = FakeConnection(
            mapOf("INSERT INTO t VALUES (1)" to Script(stepError = "Error code: 19, message: UNIQUE constraint failed: t.a")),
            prepareErrors = mapOf("SELECT * FROM nosuch" to "Error code: 1, message: no such table: nosuch"),
        )
        val session = SqliteSession { conn }
        assertError("SQLiteException: SQL logic error\r\nno such table: nosuch") { call(session, "ExecuteJson", "SELECT * FROM nosuch") }
        assertError("SQLiteException: constraint failed\r\nUNIQUE constraint failed: t.a") { call(session, "ExecuteNonQuery", "INSERT INTO t VALUES (1)") }
    }

    @Test
    fun connectionOpensLazilyAndReopensAfterClose() {
        var opened = 0
        val session = SqliteSession { opened++; FakeConnection() }
        assertEquals(0, opened)
        call(session, "ExecuteNonQuery", "BEGIN")
        call(session, "ExecuteNonQuery", "COMMIT")
        assertEquals(1, opened)
        session.close()
        call(session, "ExecuteNonQuery", "BEGIN")
        assertEquals(2, opened)
    }

    @Test
    fun unknownMethodRejects() {
        assertError("MissingMethodException: Method Nope does not exist on class SQLite") {
            SqliteBridge.invoke(SqliteSession { FakeConnection() }, "Nope", JsonArray(emptyList()))
        }
    }

    @Test
    fun nativeHelpers() = runBlocking {
        val conn = FakeConnection(mapOf("SELECT `value` FROM `cookies` WHERE `key` = @key" to Script(listOf("value"), listOf(listOf("W10=")))))
        val session = SqliteSession { conn }
        assertEquals("W10=", session.queryScalar("SELECT `value` FROM `cookies` WHERE `key` = @key", buildJsonObject { put("@key", "default") }))
    }

    private fun assertError(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: Exception) {
            assertEquals(expected, errorText(e))
        }
    }
}
