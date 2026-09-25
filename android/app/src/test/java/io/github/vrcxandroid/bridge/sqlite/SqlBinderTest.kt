package io.github.vrcxandroid.bridge.sqlite

import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.bridge.errorText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SqlBinderTest {
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun bindsByExactName() {
        val values = SqlBinder.resolve(listOf("@key", "@value"), args("""{"@value":"v","@key":"k"}"""))
        assertEquals(listOf(SqlValue.Text("k"), SqlValue.Text("v")), values)
    }

    @Test
    fun matchIsCaseInsensitive() {
        val values = SqlBinder.resolve(listOf("@UserId"), args("""{"@userid":"u"}"""))
        assertEquals(listOf(SqlValue.Text("u")), values)
    }

    @Test
    fun keyWithoutPrefixMatchesAnyPrefix() {
        assertEquals(listOf(SqlValue.Integer(1)), SqlBinder.resolve(listOf(":id"), args("""{"id":1}""")))
        assertEquals(listOf(SqlValue.Integer(1)), SqlBinder.resolve(listOf("\$id"), args("""{"id":1}""")))
        // A key with a prefix must match the prefix too.
        assertMissing { SqlBinder.resolve(listOf(":id"), args("""{"@id":1}""")) }
    }

    @Test
    fun extraArgumentsAreIgnored() {
        val values = SqlBinder.resolve(listOf("@a"), args("""{"@a":1,"@unused":2,"@other":"x"}"""))
        assertEquals(listOf(SqlValue.Integer(1)), values)
    }

    @Test
    fun noParametersAcceptsAnyArguments() {
        assertEquals(emptyList<SqlValue>(), SqlBinder.resolve(emptyList(), args("""{"@a":1}""")))
        assertEquals(emptyList<SqlValue>(), SqlBinder.resolve(emptyList(), null))
    }

    @Test
    fun missingParameterThrowsSystemDataSqliteText() {
        try {
            SqlBinder.resolve(listOf("@a", "@b"), args("""{"@a":1}"""))
            fail("expected an exception")
        } catch (e: DotNetException) {
            assertEquals("SQLiteException: unknown error\r\nInsufficient parameters supplied to the command", errorText(e))
        }
        assertMissing { SqlBinder.resolve(listOf("@a"), null) }
    }

    @Test
    fun explicitNullIsBoundNotMissing() {
        assertEquals(listOf(SqlValue.Null), SqlBinder.resolve(listOf("@a"), args("""{"@a":null}""")))
    }

    @Test
    fun unmatchedArgumentFallsBackToNamelessParameterByPosition() {
        // System.Data.SQLite maps an argument that matches no name to the nameless parameter ";<position>".
        val values = SqlBinder.resolve(listOf(null, "@b"), args("""{"@x":10,"@b":20}"""))
        assertEquals(listOf(SqlValue.Integer(10), SqlValue.Integer(20)), values)
        assertMissing { SqlBinder.resolve(listOf("@b", null), args("""{"@b":20,"@y":1,"@z":2}""")) }
    }

    @Test
    fun typeMapping() {
        assertEquals(SqlValue.Text("x"), SqlBinder.convert(Json.parseToJsonElement("\"x\"")))
        assertEquals(SqlValue.Text("500"), SqlBinder.convert(Json.parseToJsonElement("\"500\"")))
        assertEquals(SqlValue.Integer(500), SqlBinder.convert(Json.parseToJsonElement("500")))
        assertEquals(SqlValue.Integer(500), SqlBinder.convert(Json.parseToJsonElement("500.0")))
        assertEquals(SqlValue.Integer(-3), SqlBinder.convert(Json.parseToJsonElement("-3")))
        assertEquals(SqlValue.Integer(1727265273000), SqlBinder.convert(Json.parseToJsonElement("1727265273000")))
        assertEquals(SqlValue.Real(1.5), SqlBinder.convert(Json.parseToJsonElement("1.5")))
        assertEquals(SqlValue.Real(9007199254740992.0), SqlBinder.convert(Json.parseToJsonElement("9007199254740992")))
        assertEquals(SqlValue.Real(1e21), SqlBinder.convert(Json.parseToJsonElement("1e+21")))
        assertEquals(SqlValue.Integer(1), SqlBinder.convert(Json.parseToJsonElement("true")))
        assertEquals(SqlValue.Integer(0), SqlBinder.convert(Json.parseToJsonElement("false")))
        assertEquals(SqlValue.Null, SqlBinder.convert(JsonNull))
        assertEquals(SqlValue.Text("""{"a":1}"""), SqlBinder.convert(buildJsonObject { put("a", 1) }))
        assertEquals(SqlValue.Text("[1,\"b\"]"), SqlBinder.convert(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(1)); add(kotlinx.serialization.json.JsonPrimitive("b")) }))
    }

    @Test
    fun resolvesTwelveHundredFiftyParameters() {
        val names = (0 until 250).flatMap { n -> listOf("@userId_$n", "@startAt_$n", "@endAt_$n", "@isOpenTail_$n", "@sourceRevision_$n") }
        val a = buildJsonObject {
            for (n in 249 downTo 0) {
                put("@userId_$n", "usr_$n")
                put("@startAt_$n", n * 1000L)
                put("@endAt_$n", n * 1000L + 1)
                put("@isOpenTail_$n", n % 2)
                put("@sourceRevision_$n", "rev")
            }
        }
        val values = SqlBinder.resolve(names, a)
        assertEquals(1250, values.size)
        assertEquals(SqlValue.Text("usr_0"), values[0])
        assertEquals(SqlValue.Integer(249000), values[1246])
    }

    @Test
    fun matchesFollowsMapParameter() {
        assertTrue(SqlBinder.matches("@key", "@KEY"))
        assertTrue(SqlBinder.matches("@key", "key"))
        assertTrue(SqlBinder.matches("?1", "1"))
        assertFalse(SqlBinder.matches("?1", "?1"))
        assertFalse(SqlBinder.matches("@key", "@ke"))
        assertFalse(SqlBinder.matches("@key", ":key"))
        assertTrue(SqlBinder.matches(";0", ";0"))
    }

    private fun assertMissing(block: () -> Unit) {
        try {
            block()
            fail("expected Insufficient parameters")
        } catch (e: DotNetException) {
            assertTrue(e.message!!.contains("Insufficient parameters supplied to the command"))
        }
    }
}
