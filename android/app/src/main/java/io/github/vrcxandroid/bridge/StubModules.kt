package io.github.vrcxandroid.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Placeholder: Discord stub values. */
class DiscordModule : BridgeModule {
    override val className = "Discord"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
}

/** Placeholder: AssetBundleManager stub values. */
class AssetBundleManagerModule : BridgeModule {
    override val className = "AssetBundleManager"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
}
