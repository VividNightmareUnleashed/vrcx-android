package io.github.vrcxandroid.companion

import android.content.Context
import io.github.vrcxandroid.CompanionController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Placeholder: docs/PROTOCOL.md client. */
class CompanionManager(private val context: Context) : CompanionController {
    override fun state(): JsonObject = JsonObject(emptyMap())
    override suspend fun discover(timeoutMs: Long): JsonArray = JsonArray(emptyList())
    override suspend fun pairWithQr(payload: String): JsonObject = state()
    override suspend fun pair(target: JsonObject, code: String): JsonObject = state()
    override fun forget(companionId: String) {}
    override fun setActive(companionId: String) {}
    override fun setRunning(running: Boolean) {}
    override fun onTillDateChanged(utcTicks: Long) {}
}
