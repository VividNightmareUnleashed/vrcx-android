package io.github.vrcxandroid.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * One backend class the frontend reaches as `window.<Name>.<Method>(...)`, which the shim turns into
 * `interopApi.callDotNetMethod(className, method, args)` (docs/ARCHITECTURE.md §4.2).
 */
interface BridgeModule {
    /** Class name exactly as the frontend sends it, e.g. "AppApiElectron", "SQLite", "AndroidHost". */
    val className: String

    /**
     * true: calls run one at a time, in arrival order, and each call finishes before the next starts (even if it
     * suspends). false: calls may run concurrently (WebApi).
     */
    val serialized: Boolean get() = true

    /**
     * Executes [method] with the JSON-decoded [args]. Trailing arguments the caller omitted are absent from [args]
     * (use the helpers in Args.kt, which treat absent and null alike). Return [kotlinx.serialization.json.JsonNull]
     * for "no value". Throw to reject the JS promise; the message the page sees is built by [errorText].
     */
    suspend fun invoke(method: String, args: JsonArray): JsonElement
}

/**
 * Rejection whose JS `Error.message` is `"<type>: <message>"`, mimicking the .NET exception text the frontend matches
 * on (for example "SQLiteException: database is locked", "UnauthorizedAccessException: ...").
 */
open class DotNetException(val type: String, message: String) : Exception(message)

fun missingMethod(className: String, method: String): Nothing =
    throw DotNetException("MissingMethodException", "Method $method does not exist on class $className")

/** Text placed in the rejected promise's Error message. */
fun errorText(t: Throwable): String = when (t) {
    is DotNetException -> "${t.type}: ${t.message.orEmpty()}"
    else -> "${t::class.java.simpleName}: ${t.message.orEmpty()}"
}
