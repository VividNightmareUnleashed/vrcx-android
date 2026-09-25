package io.github.vrcxandroid.bridge.appapi

import android.content.Context
import io.github.vrcxandroid.bridge.BridgeModule
import io.github.vrcxandroid.bridge.missingMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Placeholder: AppApiElectron. */
class AppApiModule(private val context: Context) : BridgeModule {
    override val className = "AppApiElectron"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
}
