package io.github.vrcxandroid.host

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.TtsController
import io.github.vrcxandroid.bridge.BridgeJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * TextToSpeech behind the page's `speechSynthesis` polyfill.
 *
 * The engine is bound lazily (first speak, or when no voice list is cached yet) and released after two idle minutes,
 * so the TTS service does not stay resident for users who never enable notification speech. The voice list is cached
 * in SharedPreferences and pushed as the `tts-voices` event; progress goes out as `tts-event` `{id, type}`.
 * All engine calls run on the main thread.
 */
class AndroidTtsController(private val context: Context) : TtsController {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = ArrayList<JsonObject>()
    private val active = LinkedHashMap<String, JsonElement>()
    private var focusRequest: AudioFocusRequest? = null
    private var listedThisProcess = false

    @Volatile
    private var cachedVoices: JsonArray = loadCachedVoices()

    private val idleShutdown = Runnable { shutdownIfIdle() }
    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        if (cachedVoices.isNotEmpty()) AppGraph.dispatcher.emit(EVENT_VOICES, cachedVoices)
    }

    override fun voices(): JsonArray {
        if (cachedVoices.isEmpty() && !listedThisProcess) main.post { ensureEngine() }
        return cachedVoices
    }

    override fun speak(utterance: JsonObject) {
        main.post {
            main.removeCallbacks(idleShutdown)
            ensureEngine()
            if (ready) speakNow(utterance) else queued += utterance
        }
    }

    override fun cancel() {
        main.post {
            queued.clear()
            active.clear()
            tts?.stop()
            abandonFocus()
            scheduleIdle()
        }
    }

    // ---- engine ----

    private fun ensureEngine() {
        if (tts != null) return
        ready = false
        tts = try {
            TextToSpeech(context.applicationContext) { status -> main.post { onInit(status) } }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech unavailable", t)
            null
        }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed: $status")
            queued.forEach { emitProgress(it["id"] ?: JsonNull, "error") }
            queued.clear()
            runCatching { engine.shutdown() }
            tts = null
            listedThisProcess = true
            return
        }
        ready = true
        engine.setAudioAttributes(speechAttributes)

        engine.setOnUtteranceProgressListener(progressListener)
        refreshVoices(engine)
        val pending = queued.toList()
        queued.clear()
        pending.forEach { speakNow(it) }
        if (pending.isEmpty() && active.isEmpty()) scheduleIdle()
    }

    private fun refreshVoices(engine: TextToSpeech) {
        listedThisProcess = true
        val list = try {
            val defaultName = runCatching { engine.defaultVoice?.name }.getOrNull()
            engine.voices.orEmpty()
                .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
                .map { v ->
                    VoiceInfo(
                        name = v.name,
                        lang = v.locale.toLanguageTag(),
                        isDefault = v.name == defaultName,
                        localService = !v.isNetworkConnectionRequired,
                    )
                }
        } catch (t: Throwable) {
            Log.w(TAG, "could not list voices", t)
            return
        }
        val json = JsonArray(TtsVoiceOrder.order(list).map { it.toJson() })
        if (json == cachedVoices) return
        cachedVoices = json
        prefs.edit().putString(KEY_VOICES, json.toString()).apply()
        AppGraph.dispatcher.emit(EVENT_VOICES, json)
    }

    private fun speakNow(u: JsonObject) {
        val engine = tts ?: return
        val idElement = u["id"] ?: JsonNull
        val id = (idElement as? JsonPrimitive)?.contentOrNull ?: return
        val text = u.string("text").orEmpty()
        val voiceName = u.string("voiceURI") ?: u.string("voice")
        val lang = u.string("lang")
        try {
            val voice = voiceName?.let { name -> engine.voices?.firstOrNull { it.name == name } }
            when {
                voice != null -> engine.voice = voice
                !lang.isNullOrBlank() -> engine.language = Locale.forLanguageTag(lang)
                else -> engine.defaultVoice?.let { engine.voice = it }
            }
            // Web Speech: rate 0.1-10 and pitch 0-2 with 1 as normal, the same scale as Android.
            engine.setSpeechRate((u.number("rate") ?: 1.0).toFloat().coerceIn(0.1f, 10f))
            engine.setPitch((u.number("pitch") ?: 1.0).toFloat().coerceIn(0.1f, 2f))
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (u.number("volume") ?: 1.0).toFloat().coerceIn(0f, 1f))
            }
            active[id] = idElement
            if (engine.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
                active.remove(id)
                emitProgress(idElement, "error")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed", t)
            active.remove(id)
            emitProgress(idElement, "error")
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post {
                requestFocus()
                active[utteranceId]?.let { emitProgress(it, "start") }
            }
        }

        override fun onDone(utteranceId: String) = finished(utteranceId, "end")

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = finished(utteranceId, "error")

        override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId, "error")

        override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId, "end")
    }

    private fun finished(utteranceId: String, type: String) {
        main.post {
            val id = active.remove(utteranceId) ?: return@post
            emitProgress(id, type)
            if (active.isEmpty()) {
                abandonFocus()
                scheduleIdle()
            }
        }
    }

    private fun emitProgress(id: JsonElement, type: String) {
        AppGraph.dispatcher.emit(EVENT_PROGRESS, buildJsonObject {
            put("id", id)
            put("type", type)
        })
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleShutdown)
        main.postDelayed(idleShutdown, IDLE_SHUTDOWN_MS)
    }

    private fun shutdownIfIdle() {
        if (active.isNotEmpty() || queued.isNotEmpty()) return
        tts?.let { runCatching { it.shutdown() } }
        tts = null
        ready = false
    }

    // ---- audio focus (duck other audio while speaking) ----

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        val am = context.getSystemService(AudioManager::class.java) ?: return
        am.abandonAudioFocusRequest(request)
    }

    private fun loadCachedVoices(): JsonArray = try {
        prefs.getString(KEY_VOICES, null)?.let { BridgeJson.parseToJsonElement(it) as? JsonArray } ?: JsonArray(emptyList())
    } catch (e: Exception) {
        JsonArray(emptyList())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val TAG = "VRCXTts"
        private const val PREFS = "vrcx_tts"
        private const val KEY_VOICES = "voices"
        private const val EVENT_VOICES = "tts-voices"
        private const val EVENT_PROGRESS = "tts-event"
        private const val IDLE_SHUTDOWN_MS = 120_000L
    }
}
