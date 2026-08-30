package com.vrcx.android.data.screenshot

import java.io.EOFException
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ScreenshotMetadata(
    val application: String? = null,
    val version: Int? = null,
    val author: ScreenshotAuthor = ScreenshotAuthor(),
    val world: ScreenshotWorld = ScreenshotWorld(),
    val players: List<ScreenshotPlayer> = emptyList(),
    val pos: ScreenshotPosition? = null,
    val timestamp: String? = null,
    val creationDate: String? = null,
    val note: String? = null,
)

@Serializable
data class ScreenshotAuthor(val id: String = "", val displayName: String? = null)

@Serializable
data class ScreenshotWorld(val id: String = "", val name: String? = null, val instanceId: String = "")

@Serializable
data class ScreenshotPlayer(val id: String = "", val displayName: String = "", val pos: ScreenshotPosition? = null)

@Serializable
data class ScreenshotPosition(
    @SerialName("x") val x: Float = 0f,
    @SerialName("y") val y: Float = 0f,
    @SerialName("z") val z: Float = 0f,
)

/** What reading one image can tell the caller. Exactly one of these three. */
sealed interface ScreenshotReadResult {
    /** The image carried metadata this reader understands. */
    data class Parsed(val metadata: ScreenshotMetadata, val resolution: String?, val capturedAtEpochMillis: Long?) :
        ScreenshotReadResult

    /** A readable PNG that embeds no VRChat or VRCX metadata. */
    data class NoMetadata(val resolution: String?) : ScreenshotReadResult

    /** The image could not be read far enough to answer either way. */
    data class Failed(val message: String) : ScreenshotReadResult
}

object ScreenshotMetadataReader {
    internal const val INVALID_PNG_MESSAGE =
        "Invalid file selected. Please select a valid PNG screenshot."
    internal const val UNPARSEABLE_MESSAGE = "Failed to parse screenshot metadata."

    private val json = Json { ignoreUnknownKeys = true }

    fun read(inputStream: InputStream, fileName: String? = null): ScreenshotReadResult = try {
        val pngMetadata = inputStream.use(PngMetadataScanner::read)
        when (pngMetadata) {
            null -> ScreenshotReadResult.Failed(INVALID_PNG_MESSAGE)

            else -> parseTextMetadata(pngMetadata.textChunks)?.let { metadata ->
                ScreenshotReadResult.Parsed(
                    metadata = metadata,
                    resolution = pngMetadata.resolution,
                    capturedAtEpochMillis = ScreenshotCaptureTimeResolver.resolve(metadata, fileName),
                )
            } ?: ScreenshotReadResult.NoMetadata(pngMetadata.resolution)
        }
    } catch (_: EOFException) {
        ScreenshotReadResult.Failed(INVALID_PNG_MESSAGE)
    } catch (_: Exception) {
        ScreenshotReadResult.Failed(UNPARSEABLE_MESSAGE)
    }

    private fun parseTextMetadata(textChunks: Map<String, List<String>>): ScreenshotMetadata? {
        val candidates = buildList {
            textChunks["XML:com.adobe.xmp"]?.let(::addAll)
            textChunks["Description"]?.let(::addAll)
        }

        var parsed: ScreenshotMetadata? = null
        for (metadata in candidates) {
            val trimmed = metadata.trim()
            parsed = when {
                trimmed.startsWith("{") && trimmed.endsWith("}") -> parseVrcxJson(trimmed) ?: parsed

                trimmed.contains("<x:xmpmeta") -> VrchatXmpParser.parse(trimmed) ?: parsed

                trimmed.startsWith("lfs") || trimmed.startsWith("screenshotmanager") -> {
                    LegacyScreenshotMetadataParser.parse(trimmed) ?: parsed
                }

                else -> parsed
            }
        }
        return parsed
    }

    private fun parseVrcxJson(metadata: String): ScreenshotMetadata? = runCatching {
        json.decodeFromString<ScreenshotMetadata>(metadata)
    }.getOrNull()
}
