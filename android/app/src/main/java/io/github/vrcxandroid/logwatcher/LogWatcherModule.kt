package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.BridgeModule
import io.github.vrcxandroid.bridge.missingMethod
import io.github.vrcxandroid.bridge.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * `window.LogWatcher`:
 * - `SetDateTill(iso)` -> `undefined` (rejects `FormatException: ...` / `ArgumentNullException: ...` like .NET);
 * - `Get()` -> `string[][]` of at most 1000 rows, `[]` when empty; elements are strings or `null`;
 * - `GetLogLines()` -> `string[]`, each the JSON text of one record array;
 * - `Reset()` -> `undefined`.
 *
 * Not serialized on a dispatcher lane: every method hops to the [LogWatcher]'s own thread, which orders them, and the
 * first `Get()` may suspend for up to 8 s waiting for the companion's initial sync without holding back the
 * per-second `GetLogLines()` calls.
 */
class LogWatcherModule(private val watcher: LogWatcher) : BridgeModule {
    override val className = "LogWatcher"

    override val serialized: Boolean get() = false

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = when (method) {
        "SetDateTill" -> {
            watcher.setDateTill(args.str(0))
            JsonNull
        }
        "Get" -> watcher.get()
        "GetLogLines" -> watcher.getLogLines()
        "Reset" -> {
            watcher.reset()
            JsonNull
        }
        else -> missingMethod(className, method)
    }
}
