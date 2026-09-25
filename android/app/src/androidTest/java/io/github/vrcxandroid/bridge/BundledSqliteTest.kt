package io.github.vrcxandroid.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vrcxandroid.bridge.sqlite.SqliteBridge
import io.github.vrcxandroid.bridge.sqlite.SqliteSession
import io.github.vrcxandroid.bridge.sqlite.VrcxDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The real bundled SQLite behind the `SQLite` bridge class. */
@RunWith(AndroidJUnit4::class)
class BundledSqliteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var session: SqliteSession

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "sqlite-test-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, "VRCX.sqlite3")
        session = SqliteSession { VrcxDatabase.open(file) }
    }

    @After
    fun tearDown() {
        session.close()
        dir.deleteRecursively()
    }

    private fun call(method: String, sql: String, args: JsonObject? = null): JsonElement =
        SqliteBridge.invoke(session, method, buildJsonArray {
            add(JsonPrimitive(sql))
            add(args ?: JsonNull)
        })

    private fun exec(sql: String, args: JsonObject? = null) = call("ExecuteNonQuery", sql, args).jsonPrimitive.content.toInt()

    private fun json(sql: String, args: JsonObject? = null) = call("ExecuteJson", sql, args).jsonPrimitive.content

    private fun assertError(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected: $expected")
        } catch (e: Exception) {
            assertEquals(expected, errorText(e))
        }
    }

    @Test
    fun versionAndPragmas() {
        val version = json("SELECT sqlite_version()").let { (Json.parseToJsonElement(it) as JsonArray)[0] as JsonArray }[0].jsonPrimitive.content
        val (major, minor) = version.split('.').map { it.toInt() }
        assertTrue("SQLite $version must be >= 3.35", major > 3 || (major == 3 && minor >= 35))
        assertEquals("[[\"wal\"]]", json("PRAGMA journal_mode"))
        assertEquals("[[5000]]", json("PRAGMA busy_timeout"))
        assertEquals("[[1]]", json("PRAGMA synchronous"))
    }

    @Test
    fun upsertDropColumnAndSqliteSchema() {
        exec("CREATE TABLE IF NOT EXISTS favorite_avatar (id INTEGER PRIMARY KEY, created_at TEXT, avatar_id TEXT UNIQUE, group_name TEXT)")
        val upsert = "INSERT INTO favorite_avatar (avatar_id, created_at, group_name) VALUES (@avatar_id, @created_at, @group_name) " +
            "ON CONFLICT(avatar_id) DO UPDATE SET created_at = @created_at, group_name = @group_name"
        exec(upsert, buildJsonObject { put("@avatar_id", "avtr_1"); put("@created_at", "a"); put("@group_name", "g1") })
        exec(upsert, buildJsonObject { put("@avatar_id", "avtr_1"); put("@created_at", "b"); put("@group_name", "g2") })
        assertEquals("[[\"avtr_1\",\"b\",\"g2\"]]", json("SELECT avatar_id, created_at, group_name FROM favorite_avatar"))

        exec("ALTER TABLE favorite_avatar ADD group_name2 TEXT DEFAULT ''")
        assertError("SQLiteException: SQL logic error\r\nduplicate column name: group_name2") {
            exec("ALTER TABLE favorite_avatar ADD group_name2 TEXT DEFAULT ''")
        }
        exec("ALTER TABLE favorite_avatar DROP COLUMN group_name2")
        val dropMissing = try {
            exec("ALTER TABLE favorite_avatar DROP COLUMN group_name2")
            ""
        } catch (e: Exception) {
            errorText(e)
        }
        assertTrue(dropMissing, dropMissing.startsWith("SQLiteException: SQL logic error\r\nno such column: \"group_name2\"") ||
            dropMissing.startsWith("SQLiteException: SQL logic error\r\nno such column: group_name2"))

        assertEquals("[[\"favorite_avatar\"]]", json("SELECT name FROM sqlite_schema WHERE type='table' AND name LIKE 'favorite_%'"))
    }

    @Test
    fun twelveHundredFiftyParameterInsert() {
        exec("CREATE TABLE s (user_id TEXT, start_at INTEGER, end_at INTEGER, is_open_tail INTEGER, source_revision TEXT)")
        val values = StringBuilder()
        val args = buildJsonObject {
            for (n in 0 until 250) {
                if (n > 0) values.append(", ")
                values.append("(@userId_$n, @startAt_$n, @endAt_$n, @isOpenTail_$n, @sourceRevision_$n)")
                put("@userId_$n", "usr_$n")
                put("@startAt_$n", 1_727_000_000_000L + n)
                put("@endAt_$n", 1_727_000_000_500L + n)
                put("@isOpenTail_$n", n % 2 == 0)
                put("@sourceRevision_$n", "rev")
            }
        }
        exec("BEGIN")
        assertEquals(250, exec("INSERT OR REPLACE INTO s (user_id, start_at, end_at, is_open_tail, source_revision) VALUES $values", args))
        exec("COMMIT")
        assertEquals("[[250,125,1727000000249]]", json("SELECT COUNT(*), SUM(is_open_tail), MAX(start_at) FROM s"))
    }

    @Test
    fun nullBlobAndTypeMapping() {
        exec("CREATE TABLE t (i INTEGER, r REAL, s TEXT, b BLOB, n TEXT)")
        exec("INSERT INTO t VALUES (@i, @r, @s, x'010203', @n)", buildJsonObject {
            put("@i", 42); put("@r", 1.5); put("@s", "ü \"q\""); put("@n", JsonNull)
        })
        assertEquals("[[42,1.5,\"ü \\\"q\\\"\",\"AQID\",null]]", json("SELECT * FROM t"))
        assertEquals(
            buildJsonArray { add(buildJsonArray { add(JsonPrimitive(42L)); add(JsonPrimitive(1.5)); add(JsonPrimitive("ü \"q\"")); add(JsonPrimitive("AQID")); add(JsonNull) }) },
            call("Execute", "SELECT * FROM t"),
        )
        // Integral numbers bind as INTEGER (a TEXT column stores "500", not "500.0"); booleans as 1/0.
        exec("CREATE TABLE c (key TEXT, value TEXT)")
        exec("INSERT INTO c VALUES (@k, @v)", buildJsonObject { put("@k", "x"); put("@v", 500.0) })
        exec("INSERT INTO c VALUES (@k, @v)", buildJsonObject { put("@k", "y"); put("@v", true) })
        assertEquals("[[\"500\",\"text\"],[\"1\",\"text\"]]", json("SELECT value, typeof(value) FROM c"))
        assertEquals("[[\"integer\",\"real\",\"integer\"]]", json("SELECT typeof(@a), typeof(@b), typeof(@c)", buildJsonObject {
            put("@a", 7); put("@b", 7.25); put("@c", false)
        }))
    }

    @Test
    fun errorTextAndMissingParameter() {
        assertError("SQLiteException: SQL logic error\r\nno such table: nosuch") { json("SELECT * FROM nosuch") }
        exec("CREATE TABLE u (a TEXT PRIMARY KEY)")
        exec("INSERT INTO u VALUES ('x')")
        assertError("SQLiteException: constraint failed\r\nUNIQUE constraint failed: u.a") { exec("INSERT INTO u VALUES ('x')") }
        assertError("SQLiteException: unknown error\r\nInsufficient parameters supplied to the command") {
            exec("INSERT INTO u VALUES (@a)", buildJsonObject { put("@b", "extra") })
        }
    }

    @Test
    fun trailingSemicolonCteAndNonQueryResults() {
        exec("CREATE TABLE g (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER);")
        assertEquals(-1, exec("BEGIN"))
        assertEquals(2, exec("INSERT INTO g (t) VALUES (1), (2);"))
        assertEquals(-1, exec("COMMIT"))
        assertEquals(1, exec("UPDATE g SET t = 5 WHERE id = @id", buildJsonObject { put("@id", 1) }))
        assertEquals("[[7]]", json("WITH x AS (SELECT SUM(t) AS s FROM g) SELECT s FROM x;"))
        assertEquals(-1, exec("VACUUM"))
        exec("PRAGMA optimize")
    }

    @Test
    fun likeAndDateFunctionsWork() {
        exec("CREATE TABLE f (created_at TEXT, name TEXT)")
        exec("INSERT INTO f VALUES (datetime('now', @off), 'Alice')", buildJsonObject { put("@off", "-1 days") })
        assertEquals("[[1]]", json("SELECT COUNT(*) FROM f WHERE name LIKE @q AND created_at > datetime('now', '-7 days')", buildJsonObject { put("@q", "%ali%") }))
    }
}
