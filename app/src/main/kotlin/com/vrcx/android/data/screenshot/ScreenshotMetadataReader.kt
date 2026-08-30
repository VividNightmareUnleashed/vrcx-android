package com.vrcx.android.data.screenshot

import com.vrcx.android.data.util.parseInstantMillisOrNull
import java.io.EOFException
import java.io.InputStream
import java.io.StringReader
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

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

    fun read(inputStream: InputStream, fileName: String? = null): ScreenshotReadResult {
        return try {
            val pngMetadata = inputStream.use(PngMetadataScanner::read)
                ?: return ScreenshotReadResult.Failed(INVALID_PNG_MESSAGE)

            val metadata = parseTextMetadata(pngMetadata.textChunks)
                ?: return ScreenshotReadResult.NoMetadata(pngMetadata.resolution)

            ScreenshotReadResult.Parsed(
                metadata = metadata,
                resolution = pngMetadata.resolution,
                capturedAtEpochMillis = resolveCapturedAt(metadata, fileName),
            )
        } catch (_: EOFException) {
            ScreenshotReadResult.Failed(INVALID_PNG_MESSAGE)
        } catch (_: Exception) {
            ScreenshotReadResult.Failed(UNPARSEABLE_MESSAGE)
        }
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

                trimmed.contains("<x:xmpmeta") -> parseVrchatXmp(trimmed) ?: parsed

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

    private fun parseVrchatXmp(xmlString: String): ScreenshotMetadata? {
        if (xmlString.contains("<!DOCTYPE", ignoreCase = true)) return null
        val start = xmlString.indexOf("<x:xmpmeta")
        if (start < 0) return null

        val values = runCatching {
            val parser = secureXmlPullParser()
            parser.setInput(StringReader(xmlString.substring(start)))
            parser.readXmpValues()
        }.getOrNull() ?: return null

        val authorName = values["Author"]
        val authorId = values["AuthorID"]

        val worldId = values["WorldID"] ?: values["World"].orEmpty()
        return ScreenshotMetadata(
            application = values["CreatorTool"],
            version = 1,
            author = ScreenshotAuthor(
                id = authorId ?: authorName.orEmpty(),
                displayName = if (authorId != null) authorName else null,
            ),
            world = ScreenshotWorld(
                id = worldId,
                name = values["WorldDisplayName"],
                instanceId = worldId,
            ),
            timestamp = values["DateTime"],
            note = values["li"],
        )
    }

    private fun secureXmlPullParser(): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // Pull parsing does not validate schemas or process XInclude. Keeping
        // DOCDECL disabled also prevents entity declarations and external DTDs.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        check(parser.getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES))
        check(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL))
        check(!parser.getFeature(XmlPullParser.FEATURE_VALIDATION))
        return parser
    }

    private fun XmlPullParser.readXmpValues(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        while (next() != XmlPullParser.END_DOCUMENT) {
            check(eventType != XmlPullParser.DOCDECL) { "DOCTYPE declarations are not allowed" }
            if (eventType == XmlPullParser.START_TAG && name in xmpValueNames) {
                nextText().trim().takeIf { it.isNotEmpty() }?.let { value ->
                    values.putIfAbsent(name, value)
                }
            }
        }
        return values
    }

    private fun resolveCapturedAt(metadata: ScreenshotMetadata, fileName: String?): Long? =
        parseDateTime(metadata.timestamp)
            ?: parseDateTime(metadata.creationDate)
            ?: parseVrchatFilenameDate(fileName)

    private fun parseDateTime(value: String?): Long? {
        val dateTime = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseInstantMillisOrNull(dateTime)
            ?: parseOffsetDateTime(dateTime)
            ?: parseLocalDateTime(dateTime)
    }

    private fun parseOffsetDateTime(value: String): Long? = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseLocalDateTime(value: String): Long? {
        val normalized = value.replace(' ', 'T')
        return runCatching {
            LocalDateTime.parse(normalized)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun parseVrchatFilenameDate(fileName: String?): Long? {
        val name = fileName.orEmpty()
        val newFormat = Regex(
            """VRChat_\d{3,}x\d{3,}_(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.(\d{1,})""",
        ).find(name)
        val legacyFormat = Regex(
            """VRChat_(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.(\d{3})_\d{3,}x\d{3,}""",
        ).find(name)
        val match = newFormat ?: legacyFormat ?: return null
        val (year, month, day, hour, minute, second) = match.destructured
        return try {
            LocalDateTime.of(
                year.toInt(),
                month.toInt(),
                day.toInt(),
                hour.toInt(),
                minute.toInt(),
                second.toInt(),
            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private val xmpValueNames = setOf(
        "Author",
        "AuthorID",
        "CreatorTool",
        "DateTime",
        "World",
        "WorldDisplayName",
        "WorldID",
        "li",
    )
}
