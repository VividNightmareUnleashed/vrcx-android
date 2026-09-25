package io.github.vrcxandroid.host

import android.content.Context
import io.github.vrcxandroid.TtsController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Placeholder: TextToSpeech behind the speechSynthesis polyfill. */
class AndroidTtsController(private val context: Context) : TtsController {
    override fun voices(): JsonArray = JsonArray(emptyList())
    override fun speak(utterance: JsonObject) {}
    override fun cancel() {}
}
