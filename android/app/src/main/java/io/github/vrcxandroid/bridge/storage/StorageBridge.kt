package io.github.vrcxandroid.bridge.storage

import io.github.vrcxandroid.bridge.BridgeJson
import io.github.vrcxandroid.bridge.element
import io.github.vrcxandroid.bridge.jsonOf
import io.github.vrcxandroid.bridge.missingMethod
import io.github.vrcxandroid.bridge.requireArg
import io.github.vrcxandroid.bridge.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JS-visible `VRCXStorage` methods.
 *
 * `GetArray`/`SetArray`/`GetObject`/`SetObject` are native here: the frontend assigns JS versions onto the interop
 * proxy (`services/jsonStorage.js`), but the proxy ignores assigned properties, so the calls reach the bridge.
 */
internal object StorageBridge {
    const val CLASS_NAME = "VRCXStorage"

    fun invoke(store: VrcxStorage, method: String, args: JsonArray): JsonElement = when (method) {
        "Load" -> {
            store.load()
            JsonNull
        }
        "Save" -> {
            store.save()
            JsonNull
        }
        "Clear" -> {
            store.clear()
            JsonNull
        }
        "Remove" -> jsonOf(store.remove(key(args)))
        "Get" -> jsonOf(store.get(key(args)))
        "Set" -> {
            store.set(key(args), valueText(args.element(1)))
            JsonNull
        }
        "GetAll" -> jsonOf(store.getAll())
        "GetArray" -> parse(store.get(key(args))) as? JsonArray ?: JsonArray(emptyList())
        "GetObject" -> when (val parsed = parse(store.get(key(args)))) {
            // JS: `object === Object(object)` is true for objects and arrays alike.
            is JsonObject, is JsonArray -> parsed
            else -> JsonObject(emptyMap())
        }
        "SetArray", "SetObject" -> {
            // JS: this.Set(key, JSON.stringify(value)); the shim turns undefined into null.
            store.set(key(args), (args.element(1) ?: JsonNull).toString())
            JsonNull
        }
        else -> missingMethod(CLASS_NAME, method)
    }

    private fun key(args: JsonArray): String = requireArg(args.str(0), "key")

    /** Values are strings upstream; other JSON values are coerced to their text, null to "". */
    private fun valueText(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun parse(text: String): JsonElement? = try {
        BridgeJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }
}
