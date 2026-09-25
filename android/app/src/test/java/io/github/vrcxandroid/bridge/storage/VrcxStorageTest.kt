package io.github.vrcxandroid.bridge.storage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VrcxStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun store(file: File = File(tmp.root, "VRCX/VRCX.json")) = VrcxStorage(file, scope, 500, dispatcher)

    @Test
    fun missingFileStartsEmptyAndGetReturnsEmptyString() {
        val s = store()
        assertEquals("", s.get("VRCX_ProxyServer"))
        assertEquals("{}", s.getAll())
        assertFalse(s.file.exists())
    }

    @Test
    fun readsPcFileWithUtf8Bom() {
        val file = File(tmp.root, "VRCX.json")
        val pc = "\uFEFF{\r\n  \"VRCX_ProxyServer\": \"\",\r\n  \"VRCX_CloseToTray\": \"true\",\r\n  \"memo_usr_1\": \"héllo \\\"x\\\"\"\r\n}"
        file.writeBytes(pc.toByteArray(Charsets.UTF_8))
        val s = store(file)
        assertEquals("true", s.get("VRCX_CloseToTray"))
        assertEquals("héllo \"x\"", s.get("memo_usr_1"))
        assertEquals("", s.get("VRCX_ProxyServer"))
    }

    @Test
    fun readsUtf16FileWithBom() {
        val file = File(tmp.root, "VRCX.json")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "{\"a\":\"b\"}".toByteArray(Charsets.UTF_16LE))
        assertEquals("b", store(file).get("a"))
    }

    @Test
    fun corruptFileStartsEmptyAndIsKept() {
        val file = File(tmp.root, "VRCX.json")
        file.writeText("{not json")
        val s = store(file)
        assertEquals("", s.get("a"))
        assertEquals("{not json", File(tmp.root, "VRCX.json.corrupt").readText())
    }

    @Test
    fun setIsSavedAfterDebounceWithoutBomAndIndented() {
        val s = store()
        s.set("b", "2")
        s.set("a", "line\n\"quoted\" \\ é \u0001")
        scope.runCurrent()
        assertFalse("nothing written before the debounce", s.file.exists())
        scope.advanceTimeBy(499)
        scope.runCurrent()
        assertFalse(s.file.exists())
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertTrue(s.file.exists())
        val bytes = s.file.readBytes()
        assertFalse("no BOM", bytes.size >= 3 && bytes[0] == 0xEF.toByte())
        assertEquals(
            "{\n  \"b\": \"2\",\n  \"a\": \"line\\n\\\"quoted\\\" \\\\ é \\u0001\"\n}",
            String(bytes, Charsets.UTF_8),
        )
        assertFalse("temp file renamed away", File(s.file.parentFile, "VRCX.json.tmp").exists())
        // And it reads back.
        assertEquals("line\n\"quoted\" \\ é \u0001", store(s.file).get("a"))
    }

    @Test
    fun debounceRestartsOnEveryChange() {
        val s = store()
        s.set("a", "1")
        scope.advanceTimeBy(400)
        scope.runCurrent()
        s.set("a", "2")
        scope.advanceTimeBy(400)
        scope.runCurrent()
        assertFalse(s.file.exists())
        scope.advanceTimeBy(101)
        scope.runCurrent()
        assertEquals("2", store(s.file).get("a"))
    }

    @Test
    fun saveWritesImmediatelyAndFlushOnlyWhenDirty() {
        val s = store()
        s.set("VRCX_ProxyServer", "127.0.0.1:8080")
        assertTrue(s.isDirty)
        s.save()
        assertFalse(s.isDirty)
        assertEquals("127.0.0.1:8080", store(s.file).get("VRCX_ProxyServer"))
        val modified = s.file.readBytes()
        s.file.delete()
        s.flush() // not dirty: no write
        assertFalse(s.file.exists())
        s.set("x", "y")
        s.flush()
        assertTrue(s.file.exists())
        assertFalse(modified.contentEquals(s.file.readBytes()))
    }

    @Test
    fun emptyMapIsWrittenAsBraces() {
        val s = store()
        s.set("a", "1")
        s.clear()
        s.save()
        assertArrayEquals("{}".toByteArray(), s.file.readBytes())
    }

    @Test
    fun removeAndClear() {
        val s = store()
        s.set("a", "1")
        assertTrue(s.remove("a"))
        assertFalse(s.remove("a"))
        s.save()
        assertFalse(s.isDirty)
        s.clear() // already empty: nothing scheduled
        assertFalse(s.isDirty)
    }

    @Test
    fun getAllUsesSystemTextJsonEscaping() {
        val s = store()
        s.set("k", "a<b>&'+`\"é\u2028")
        assertEquals("{\"k\":\"a\\u003Cb\\u003E\\u0026\\u0027\\u002B\\u0060\\u0022\\u00E9\\u2028\"}", s.getAll())
        val parsed = Json.parseToJsonElement(s.getAll()) as JsonObject
        assertEquals("a<b>&'+`\"é\u2028", (parsed["k"] as JsonPrimitive).content)
    }

    @Test
    fun loadReplacesMemoryWithFile() {
        val s = store()
        s.set("a", "1")
        s.save()
        s.set("a", "2")
        s.load()
        assertEquals("1", s.get("a"))
    }

    @Test
    fun bridgeMethods() {
        val s = store()
        fun call(m: String, vararg a: kotlinx.serialization.json.JsonElement) = StorageBridge.invoke(s, m, JsonArray(a.toList()))

        assertEquals(JsonNull, call("Set", JsonPrimitive("k"), JsonPrimitive("v")))
        assertEquals(JsonPrimitive("v"), call("Get", JsonPrimitive("k")))
        assertEquals(JsonPrimitive(""), call("Get", JsonPrimitive("missing")))
        // Non-string values are coerced; null becomes "".
        call("Set", JsonPrimitive("n"), JsonPrimitive(5))
        assertEquals(JsonPrimitive("5"), call("Get", JsonPrimitive("n")))
        call("Set", JsonPrimitive("z"), JsonNull)
        assertEquals(JsonPrimitive(""), call("Get", JsonPrimitive("z")))

        // GetArray / SetArray / GetObject / SetObject (jsonStorage.js semantics).
        call("SetArray", JsonPrimitive("arr"), buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive("two")) })
        assertEquals(JsonPrimitive("[1,\"two\"]"), call("Get", JsonPrimitive("arr")))
        assertEquals(buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive("two")) }, call("GetArray", JsonPrimitive("arr")))
        assertEquals(JsonArray(emptyList()), call("GetArray", JsonPrimitive("k"))) // "v" is not JSON
        assertEquals(JsonArray(emptyList()), call("GetArray", JsonPrimitive("missing")))
        call("SetObject", JsonPrimitive("obj"), buildJsonObject { put("a", 1) })
        assertEquals(buildJsonObject { put("a", 1) }, call("GetObject", JsonPrimitive("obj")))
        assertEquals(buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive("two")) }, call("GetObject", JsonPrimitive("arr")))
        assertEquals(JsonObject(emptyMap()), call("GetObject", JsonPrimitive("n")))
        assertEquals(JsonObject(emptyMap()), call("GetObject", JsonPrimitive("missing")))
        call("SetObject", JsonPrimitive("nul"), JsonNull)
        assertEquals(JsonPrimitive("null"), call("Get", JsonPrimitive("nul")))

        assertEquals(JsonPrimitive(true), call("Remove", JsonPrimitive("k")))
        assertEquals(JsonPrimitive(false), call("Remove", JsonPrimitive("k")))
        val all = Json.parseToJsonElement((call("GetAll") as JsonPrimitive).content) as JsonObject
        assertEquals(setOf("n", "z", "arr", "obj", "nul"), all.keys)
        call("Save")
        assertTrue(s.file.exists())
        call("Clear")
        assertEquals(JsonPrimitive("{}"), call("GetAll"))
    }

    @Test(expected = io.github.vrcxandroid.bridge.DotNetException::class)
    fun unknownMethodRejects() {
        StorageBridge.invoke(store(), "Nope", JsonArray(emptyList()))
    }
}
