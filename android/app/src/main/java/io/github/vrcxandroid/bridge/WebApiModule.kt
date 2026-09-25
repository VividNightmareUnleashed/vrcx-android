package io.github.vrcxandroid.bridge

import android.content.Context
import io.github.vrcxandroid.HttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/** Placeholder: WebApi. */
class WebApiModule(private val context: Context) : BridgeModule, HttpProvider {
    override val className = "WebApi"
    override val serialized = false
    override val client: OkHttpClient by lazy { OkHttpClient() }
    override suspend fun invoke(method: String, args: JsonArray): JsonElement = missingMethod(className, method)
    override suspend fun flushCookies() {}
}
