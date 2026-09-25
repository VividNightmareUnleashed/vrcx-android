package io.github.vrcxandroid.bridge

import android.content.Context
import io.github.vrcxandroid.StorageController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Placeholder: VRCXStorage. */
class VRCXStorageModule(private val context: Context) : BridgeModule, StorageController {
    override val className = "VRCXStorage"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
    override fun get(key: String): String = ""
    override fun set(key: String, value: String) {}
    override suspend fun flush() {}
}
