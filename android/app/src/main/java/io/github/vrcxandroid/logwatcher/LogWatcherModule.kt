package io.github.vrcxandroid.logwatcher

import io.github.vrcxandroid.bridge.BridgeModule
import io.github.vrcxandroid.bridge.missingMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Placeholder: LogWatcher bridge: SetDateTill, Get, GetLogLines, Reset. */
class LogWatcherModule(private val watcher: LogWatcher) : BridgeModule {
    override val className = "LogWatcher"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
}
