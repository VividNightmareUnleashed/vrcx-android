package io.github.vrcxandroid.bridge.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.Charset

/**
 * The `VRCX.json` file format (upstream `JsonFileSerializer`): a flat JSON object of
 * string → string written by Newtonsoft with `Formatting.Indented`.
 *
 * Reading is BOM-aware like .NET's `StreamReader` (UTF-8, UTF-16 LE/BE), so files imported from a PC load as-is.
 * Writing produces the same indented layout without a BOM (.NET reads both).
 */
internal object VrcxJsonFormat {
    private val json = Json { isLenient = true }

    /** Parses file bytes. Throws when the content is not a JSON object (callers treat that as a corrupt file). */
    fun decode(bytes: ByteArray): LinkedHashMap<String, String> {
        val text = decodeText(bytes)
        val result = LinkedHashMap<String, String>()
        if (text.isBlank()) return result // Newtonsoft returns null for empty input; upstream then uses an empty map.
        val element = json.parseToJsonElement(text)
        if (element is JsonNull) return result
        val obj = element as? JsonObject ?: throw IllegalArgumentException("VRCX.json is not a JSON object")
        for ((key, value) in obj) {
            result[key] = valueText(value)
        }
        return result
    }

    /** Newtonsoft `Formatting.Indented`: two-space indent, `"key": "value"`, no trailing newline. */
    fun encode(map: Map<String, String>): ByteArray {
        if (map.isEmpty()) return "{}".toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(64 + map.size * 48)
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append(NEWLINE).append("  ")
            JsonText.appendQuotedNewtonsoft(sb, k)
            sb.append(": ")
            JsonText.appendQuotedNewtonsoft(sb, v)
        }
        sb.append(NEWLINE).append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private const val NEWLINE = "\n"

    private fun valueText(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonNull -> ""
        is JsonPrimitive -> when {
            value.isString -> value.content
            // Newtonsoft reads a boolean token into a string property as bool.ToString().
            value.content == "true" -> "True"
            value.content == "false" -> "False"
            else -> value.content
        }
        else -> value.toString()
    }

    private fun decodeText(bytes: ByteArray): String {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
        val (charset: Charset, skip: Int) = when {
            startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return String(bytes, skip, bytes.size - skip, charset)
    }
}
