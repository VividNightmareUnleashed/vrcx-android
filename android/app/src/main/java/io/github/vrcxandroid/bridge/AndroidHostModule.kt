package io.github.vrcxandroid.bridge

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Placeholder: AndroidHost, ARCHITECTURE.md §5. */
class AndroidHostModule(private val context: Context) : BridgeModule {
    override val className = "AndroidHost"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
}
