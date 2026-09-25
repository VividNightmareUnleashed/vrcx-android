package io.github.vrcxandroid.bridge.appapi

import android.content.Context
import io.github.vrcxandroid.bridge.BridgeModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * `AppApiElectron` on the bridge (JS `window.AppApi`). Calls run one at a time in
 * arrival order on the module's lane; the work itself lives in [AppApi], which reaches Android through
 * [AndroidAppApiPlatform] (created lazily, after AppGraph.init has wired the collaborators).
 */
class AppApiModule(private val context: Context) : BridgeModule {
    override val className = AppApi.CLASS_NAME

    private val api: AppApi by lazy { AppApi(AndroidAppApiPlatform(context.applicationContext)) }

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = api.call(method, args)
}
