package io.github.vrcxandroid.bridge

import android.content.Context
import android.net.Uri
import io.github.vrcxandroid.DatabaseController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Placeholder: SQLite. */
class SQLiteModule(private val context: Context) : BridgeModule, DatabaseController {
    override val className = "SQLite"
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
    override suspend fun importFrom(database: Uri, vrcxJson: Uri?): JsonObject = JsonObject(emptyMap())
    override suspend fun exportTo(target: Uri) {}
    override suspend fun close() {}
}
