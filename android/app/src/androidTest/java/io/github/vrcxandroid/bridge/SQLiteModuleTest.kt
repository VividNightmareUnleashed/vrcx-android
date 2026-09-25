package io.github.vrcxandroid.bridge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vrcxandroid.bridge.sqlite.SqliteSession
import io.github.vrcxandroid.bridge.sqlite.VrcxDatabase
import io.github.vrcxandroid.bridge.webapi.NetCookieCodec
import io.github.vrcxandroid.bridge.webapi.PersistentCookieJar
import io.github.vrcxandroid.bridge.webapi.SqliteCookieBlobStore
import io.github.vrcxandroid.bridge.webapi.StoredCookie
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The `SQLite` module behind a real [BridgeDispatcher]: FIFO lane, import and export. */
@RunWith(AndroidJUnit4::class)
class SQLiteModuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher = BridgeDispatcher(scope)
    private lateinit var dir: File
    private lateinit var dbFile: File
    private lateinit var module: SQLiteModule
    private var imported: Map<String, String>? = null
    private var importHookCalls = 0
    private var beforeReplaceHook: () -> Unit = {}

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "sqlite-module-${UUID.randomUUID()}").apply { mkdirs() }
        dbFile = File(dir, "VRCX.sqlite3")
        module = SQLiteModule(context, dbFile, { dispatcher }, { beforeReplaceHook() }) { entries ->
            importHookCalls++
            imported = entries
        }
        dispatcher.register(module)
    }

    @After
    fun tearDown() {
        runBlocking { module.close() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun message(id: Int, method: String, sql: String, args: JsonObject? = null) = buildJsonObject {
        put("id", id)
        put("c", "SQLite")
        put("m", method)
        put("a", buildJsonArray { add(JsonPrimitive(sql)); add(args ?: JsonNull) })
    }.toString()

    /** Sends every message without waiting (like un-awaited JS calls) and returns the replies in arrival order. */
    private fun sendAll(messages: List<String>): List<JsonObject> {
        val replies = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(messages.size)
        for (m in messages) dispatcher.handle(m) { replies.add(it); latch.countDown() }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        return replies.map { Json.parseToJsonElement(it) as JsonObject }
    }

    @Test
    fun transactionSpanningSeveralUnawaitedCallsIsFifo() {
        val replies = sendAll(
            listOf(
                message(1, "ExecuteNonQuery", "CREATE TABLE IF NOT EXISTS t (a INTEGER)"),
                message(2, "ExecuteNonQuery", "BEGIN"),
                message(3, "ExecuteNonQuery", "INSERT INTO t VALUES (@a)", buildJsonObject { put("@a", 1) }),
                message(4, "ExecuteNonQuery", "INSERT INTO t VALUES (@a)", buildJsonObject { put("@a", 2) }),
                message(5, "ExecuteJson", "SELECT COUNT(*) FROM t"),
                message(6, "ExecuteNonQuery", "ROLLBACK"),
                message(7, "ExecuteJson", "SELECT COUNT(*) FROM t"),
                message(8, "ExecuteNonQuery", "BEGIN"),
                message(9, "ExecuteNonQuery", "INSERT INTO t VALUES (3)"),
                message(10, "ExecuteNonQuery", "COMMIT"),
                message(11, "Execute", "SELECT a FROM t"),
                message(12, "ExecuteJson", "SELECT * FROM missing_table"),
            ),
        )
        assertEquals((1..12).toList(), replies.map { it["id"]!!.jsonPrimitive.long.toInt() })
        assertEquals("[[2]]", replies[4]["r"]!!.jsonPrimitive.content) // sees its own uncommitted rows
        assertEquals("[[0]]", replies[6]["r"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonArray(listOf(JsonPrimitive(3L))))), replies[10]["r"])
        assertFalse(replies[11]["ok"]!!.jsonPrimitive.boolean)
        assertEquals("SQLiteException: no such table: missing_table", replies[11]["e"]!!.jsonPrimitive.content)
    }

    @Test
    fun nativeCallsRunOnTheLaneInOrder() {
        sendAll(listOf(message(1, "ExecuteNonQuery", "CREATE TABLE n (a)"), message(2, "ExecuteNonQuery", "BEGIN")))
        // A native call queued now runs after the page's BEGIN, inside its transaction.
        val count = runBlocking {
            module.runNative { s ->
                s.executeNonQuery("INSERT INTO n VALUES (1)", null)
                s.queryScalar("SELECT COUNT(*) FROM n")
            }
        }
        assertEquals(1L, count)
        sendAll(listOf(message(3, "ExecuteNonQuery", "ROLLBACK")))
        assertEquals(0L, runBlocking { module.runNative { it.queryScalar("SELECT COUNT(*) FROM n") } })
    }

    @Test
    fun cookieJarPersistsInTheCookiesTable() = runBlocking {
        val store = SqliteCookieBlobStore { module }
        val jar = PersistentCookieJar(store, scope, 50)
        val url = "https://api.vrchat.cloud/api/1/auth/user".toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "auth=authcookie_abc; Path=/; Secure; HttpOnly")!!))
        jar.flush()
        val blob = module.runNative { it.queryScalar("SELECT `value` FROM `cookies` WHERE `key` = 'default'") } as String
        val json = String(java.util.Base64.getDecoder().decode(blob))
        assertTrue(json, json.contains("\"Name\":\"auth\"") && json.contains("\"Secure\":true"))
        // A new jar (next process) reads it back.
        val restored = PersistentCookieJar(store, scope, 50)
        assertEquals(listOf("authcookie_abc"), restored.loadForRequest(url).map { it.value })
        restored.clear()
        assertEquals("W10=", module.runNative { it.queryScalar("SELECT `value` FROM `cookies` WHERE `key` = 'default'") })
    }

    private fun makePcDatabase(file: File, withConfigs: Boolean = true, cookies: String = "W10=") {
        file.delete()
        val s = SqliteSession { VrcxDatabase.open(file) }
        if (withConfigs) {
            s.executeNonQuery("CREATE TABLE configs (`key` TEXT PRIMARY KEY, `value` TEXT)", null)
            s.executeNonQuery("INSERT INTO configs VALUES ('config:from', 'pc')", null)
            s.executeNonQuery("CREATE TABLE `cookies` (`key` TEXT PRIMARY KEY, `value` TEXT)", null)
            s.executeNonQuery("INSERT INTO cookies VALUES ('default', @v)", buildJsonObject { put("@v", cookies) })
        } else {
            s.executeNonQuery("CREATE TABLE other (a)", null)
        }
        // PC databases use the rollback journal; switching back also checkpoints the WAL into the file.
        s.queryRows("PRAGMA journal_mode=DELETE")
        s.close()
    }

    @Test
    fun importReplacesDatabaseAndStorage() {
        sendAll(listOf(message(1, "ExecuteNonQuery", "CREATE TABLE configs (`key` TEXT PRIMARY KEY, `value` TEXT)"),
            message(2, "ExecuteNonQuery", "INSERT INTO configs VALUES ('config:from', 'android')")))
        val pc = File(dir, "pc.sqlite3").also { makePcDatabase(it) }
        val json = File(dir, "VRCX.json").apply {
            writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\r\n  \"VRCX_ProxyServer\": \"\"\r\n}".toByteArray())
        }
        val result = runBlocking { module.importFrom(Uri.fromFile(pc), Uri.fromFile(json)) }
        assertTrue(result.toString(), result["ok"]!!.jsonPrimitive.boolean)
        assertEquals(mapOf("VRCX_ProxyServer" to ""), imported)
        val replies = sendAll(listOf(message(3, "ExecuteJson", "SELECT value FROM configs")))
        assertEquals("[[\"pc\"]]", replies[0]["r"]!!.jsonPrimitive.content)
        assertEquals("[[\"wal\"]]", sendAll(listOf(message(4, "ExecuteJson", "PRAGMA journal_mode")))[0]["r"]!!.jsonPrimitive.content)
        assertFalse(File(dir, "import.sqlite3.tmp").exists())
    }

    @Test
    fun cookieSaveQueuedBehindTheImportDoesNotOverwriteTheImportedSession() = runBlocking {
        val url = "https://api.vrchat.cloud/api/1/auth/user".toHttpUrl()
        val jar = PersistentCookieJar(SqliteCookieBlobStore { module }, scope, 60_000)
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "auth=android; Path=/")!!))
        jar.flush()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "auth=android2; Path=/")!!)) // changed, not saved yet
        val pcSession = NetCookieCodec.encodeBase64(
            listOf(StoredCookie(Cookie.Builder().name("auth").value("pc").hostOnlyDomain("api.vrchat.cloud").path("/").build(), System.currentTimeMillis())),
        )
        val pc = File(dir, "pc.sqlite3").also { makePcDatabase(it, cookies = pcSession) }
        val lateSave = CompletableDeferred<Job>()
        beforeReplaceHook = {
            // Runs on the lane inside the import. A save requested now (the 1 s debounce firing mid-import) is queued
            // on the lane behind the replacement.
            lateSave.complete(scope.launch { jar.flush() })
            Thread.sleep(300)
            // What WebApiModule.onDatabaseReplacing does in the app.
            jar.invalidate()
        }
        val result = module.importFrom(Uri.fromFile(pc), null)
        assertTrue(result.toString(), result["ok"]!!.jsonPrimitive.boolean)
        lateSave.await().join()
        val stored = module.runNative { it.queryScalar("SELECT `value` FROM `cookies` WHERE `key` = 'default'") }
        assertEquals("the imported PC session survives", pcSession, stored)
        assertEquals(listOf("pc"), jar.loadForRequest(url).map { it.value })
    }

    @Test
    fun importRejectsInvalidFilesAndKeepsTheCurrentDatabase() {
        sendAll(listOf(message(1, "ExecuteNonQuery", "CREATE TABLE keep (a)"), message(2, "ExecuteNonQuery", "INSERT INTO keep VALUES (1)")))
        val notSqlite = File(dir, "notes.txt").apply { writeText("x".repeat(200)) }
        val notVrcx = File(dir, "other.sqlite3").also { makePcDatabase(it, withConfigs = false) }
        val pc = File(dir, "pc.sqlite3").also { makePcDatabase(it) }
        val badJson = File(dir, "bad.json").apply { writeText("{broken") }

        for ((db, json) in listOf(notSqlite to null, notVrcx to null, pc to badJson)) {
            val result = runBlocking { module.importFrom(Uri.fromFile(db), json?.let { Uri.fromFile(it) }) }
            assertFalse(result.toString(), result["ok"]!!.jsonPrimitive.boolean)
            assertTrue(result["message"]!!.jsonPrimitive.content.isNotEmpty())
        }
        assertEquals(0, importHookCalls)
        assertNull(imported)
        assertEquals("[[1]]", sendAll(listOf(message(3, "ExecuteJson", "SELECT a FROM keep")))[0]["r"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportCheckpointsWalIntoACompleteCopy() {
        sendAll(
            listOf(
                message(1, "ExecuteNonQuery", "CREATE TABLE configs (`key` TEXT PRIMARY KEY, `value` TEXT)"),
                message(2, "ExecuteNonQuery", "INSERT INTO configs VALUES ('config:a', 'b')"),
            ),
        )
        assertTrue("writes are in the WAL", File(dbFile.path + "-wal").length() > 0)
        val out = File(dir, "export.sqlite3")
        runBlocking { module.exportTo(Uri.fromFile(out)) }
        val header = out.readBytes().copyOf(20)
        assertEquals("rollback-journal format like PC files", listOf<Byte>(1, 1), listOf(header[18], header[19]))
        // The copy alone (no -wal next to it) holds the data and passes validation.
        assertNull(VrcxDatabase.validate(out))
        val copy = SqliteSession { VrcxDatabase.open(out) }
        assertEquals("b", copy.queryScalar("SELECT value FROM configs WHERE key = 'config:a'"))
        copy.close()
        // The page keeps working after the export.
        assertEquals("[[1]]", sendAll(listOf(message(3, "ExecuteJson", "SELECT COUNT(*) FROM configs")))[0]["r"]!!.jsonPrimitive.content)
    }
}
