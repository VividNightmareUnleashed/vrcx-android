package io.github.vrcxandroid.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Shared JSON configuration for the bridge. */
val BridgeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

private fun JsonArray.at(i: Int): JsonElement? = if (i < size) this[i].takeUnless { it is JsonNull } else null

/** String argument; numbers and booleans are converted with their JSON text, like .NET's implicit ToString. */
fun JsonArray.str(i: Int): String? = when (val e = at(i)) {
    null -> null
    is JsonPrimitive -> e.content
    else -> e.toString()
}

fun JsonArray.strOr(i: Int, default: String): String = str(i) ?: default
fun JsonArray.long(i: Int): Long? = (at(i) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }
fun JsonArray.int(i: Int): Int? = long(i)?.toInt()
fun JsonArray.double(i: Int): Double? = (at(i) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonArray.bool(i: Int): Boolean? = (at(i) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.equals("true", true) }
fun JsonArray.obj(i: Int): JsonObject? = at(i) as? JsonObject
fun JsonArray.arr(i: Int): JsonArray? = at(i) as? JsonArray
fun JsonArray.element(i: Int): JsonElement? = at(i)

fun requireArg(value: String?, name: String): String =
    value ?: throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter '$name')")

/** Convenience constructors for results. */
fun jsonOf(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
fun jsonOf(value: Boolean): JsonElement = JsonPrimitive(value)
fun jsonOf(value: Number): JsonElement = JsonPrimitive(value)
