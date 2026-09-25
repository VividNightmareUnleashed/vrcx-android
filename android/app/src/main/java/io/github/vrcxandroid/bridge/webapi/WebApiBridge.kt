package io.github.vrcxandroid.bridge.webapi

import io.github.vrcxandroid.bridge.jsonOf
import io.github.vrcxandroid.bridge.missingMethod
import io.github.vrcxandroid.bridge.obj
import io.github.vrcxandroid.bridge.requireArg
import io.github.vrcxandroid.bridge.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JS-visible `WebApi` methods:
 * - `ExecuteJson(json)` → JSON **string** `{"status":int,"message":string}` (LINUX path, the one Android uses)
 * - `Execute(options)` → `{Item1: status, Item2: message}`
 * - `GetCookies()` → base64 of the .NET cookie JSON; `SetCookies(b64)` merges; `ClearCookies()` empties the jar and
 *   the WebView cookie store.
 */
internal class WebApiBridge(
    private val engine: WebApiEngine,
    private val cookieJar: PersistentCookieJar,
    /** Clears `android.webkit.CookieManager` (upstream also clears CEF's store). */
    private val clearWebViewCookies: () -> Unit,
) {
    suspend fun invoke(method: String, args: JsonArray): JsonElement = when (method) {
        "ExecuteJson" -> jsonOf(engine.executeJson(requireArg(args.str(0), "options")))
        "Execute" -> {
            val result = engine.execute(args.obj(0))
            buildJsonObject {
                put("Item1", result.status)
                put("Item2", result.message)
            }
        }
        "GetCookies" -> jsonOf(cookieJar.exportBase64())
        "SetCookies" -> {
            cookieJar.importBase64(args.str(0))
            JsonNull
        }
        "ClearCookies" -> {
            clearWebViewCookies()
            cookieJar.clear()
            JsonNull
        }
        else -> missingMethod(CLASS_NAME, method)
    }

    companion object {
        const val CLASS_NAME = "WebApi"
    }
}
