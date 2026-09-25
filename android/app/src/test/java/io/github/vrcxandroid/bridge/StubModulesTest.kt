package io.github.vrcxandroid.bridge

import io.github.vrcxandroid.BuildConfig
import io.github.vrcxandroid.bridge.webapi.VrcxVersion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StubModulesTest {
    private fun call(module: BridgeModule, method: String, vararg args: JsonElement) =
        runBlocking { module.invoke(method, JsonArray(args.toList())) }

    @Test
    fun discordEchoesSetActiveAndIgnoresSetAssets() {
        val d = DiscordModule()
        assertEquals("Discord", d.className)
        assertEquals(JsonPrimitive(true), call(d, "SetActive", JsonPrimitive(true)))
        assertEquals(JsonPrimitive(false), call(d, "SetActive", JsonPrimitive(false)))
        val assets = Array<JsonElement>(17) { JsonPrimitive("") }
        assertEquals(JsonNull, call(d, "SetAssets", *assets))
        assertMissing { call(d, "Nope") }
    }

    @Test
    fun assetBundleManagerSafeValues() {
        val a = AssetBundleManagerModule()
        assertEquals("AssetBundleManager", a.className)
        val id = JsonPrimitive("avtr_1")
        assertEquals(
            buildJsonObject {
                put("Item1", -1)
                put("Item2", false)
                put("Item3", "")
            },
            call(a, "CheckVRChatCache", id, JsonPrimitive(1), JsonPrimitive(""), JsonPrimitive(0)),
        )
        assertEquals(JsonPrimitive(""), call(a, "GetVRChatCacheFullLocation", id, JsonPrimitive(1)))
        assertEquals(JsonNull, call(a, "DeleteCache", id, JsonPrimitive(1), JsonPrimitive(""), JsonPrimitive(0)))
        assertEquals(JsonNull, call(a, "DeleteAllCache"))
        assertEquals(JsonArray(emptyList()), call(a, "SweepCache"))
        val size = call(a, "GetCacheSize") as JsonPrimitive
        assertEquals("0", size.content)
        assertEquals(false, size.isString)
    }

    @Test
    fun assetBundleHelpersArePortedExactly() {
        assertEquals("00000000000000000000000001000000", AssetBundleManagerModule.assetVersion(1, 0))
        assertEquals("000000000000000002000000ff000000", AssetBundleManagerModule.assetVersion(255, 2))
        // SHA-256("abc") = BA7816BF8F01CFEA...
        assertEquals("BA7816BF8F01CFEA", AssetBundleManagerModule.assetId("ab", "c"))
        assertEquals(255 to 2, AssetBundleManagerModule.reverseHexToDecimal("02000000" + "0".repeat(16) + "ff000000"))
        assertEquals(0 to 0, AssetBundleManagerModule.reverseHexToDecimal("short"))
        assertEquals(0 to 0, AssetBundleManagerModule.reverseHexToDecimal("zz" + "0".repeat(30)))
    }

    @Test
    fun versionString() {
        assertEquals("VRCX 2026.09.16", VrcxVersion.format("2026.09.16\n"))
        assertEquals("VRCX Nightly 2026.09.16-22bcd96", VrcxVersion.format("2026.09.16-22bcd96"))
        assertEquals("VRCX Nightly Build", VrcxVersion.format(""))
        assertEquals(VrcxVersion.format(BuildConfig.VRCX_VERSION), VrcxVersion.current)
    }

    private fun assertMissing(block: () -> Unit) {
        try {
            block()
            fail()
        } catch (e: DotNetException) {
            assertEquals("MissingMethodException", e.type)
        }
    }
}
