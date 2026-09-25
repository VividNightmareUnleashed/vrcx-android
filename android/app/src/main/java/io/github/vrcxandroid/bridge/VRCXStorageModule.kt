package io.github.vrcxandroid.bridge

import android.content.Context
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.StorageController
import io.github.vrcxandroid.bridge.storage.StorageBridge
import io.github.vrcxandroid.bridge.storage.VrcxStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * `VRCXStorage`: the flat key/value store in `filesDir/VRCX/VRCX.json` (ARCHITECTURE.md §5).
 * The file is loaded lazily on first access; see [VrcxStorage] for the save rules.
 */
class VRCXStorageModule(private val context: Context) : BridgeModule, StorageController {
    override val className = StorageBridge.CLASS_NAME

    internal val store = VrcxStorage(File(File(context.filesDir, "VRCX"), "VRCX.json"), AppGraph.scope)

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = StorageBridge.invoke(store, method, args)

    override fun get(key: String): String = store.get(key)

    override fun set(key: String, value: String) = store.set(key, value)

    override suspend fun flush() = withContext(Dispatchers.IO) { store.flush() }

    /** Database import: replaces every key with the imported VRCX.json and writes it now. */
    internal fun replaceAll(entries: Map<String, String>) = store.replaceAll(entries)
}
