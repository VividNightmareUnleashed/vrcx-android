package com.vrcx.android.data.screenshot

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import com.vrcx.android.data.util.parseInstantMillisOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Document

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
data class ScreenshotAuthor(
    val id: String = "",
    val displayName: String? = null,
)

@Serializable
data class ScreenshotWorld(
    val id: String = "",
    val name: String? = null,
    val instanceId: String = "",
)

@Serializable
data class ScreenshotPlayer(
    val id: String = "",
    val displayName: String = "",
    val pos: ScreenshotPosition? = null,
)

@Serializable
data class ScreenshotPosition(
    @SerialName("x") val x: Float = 0f,
    @SerialName("y") val y: Float = 0f,
    @SerialName("z") val z: Float = 0f,
)

/** What reading one image can tell the caller. Exactly one of these three. */
sealed interface ScreenshotReadResult {
    /** The image carried metadata this reader understands. */
    data class Parsed(
        val metadata: ScreenshotMetadata,
        val resolution: String?,
        val capturedAtEpochMillis: Long?,
    ) : ScreenshotReadResult

    /** A readable PNG that embeds no VRChat or VRCX metadata. */
    data class NoMetadata(val resolution: String?) : ScreenshotReadResult

    /** The image could not be read far enough to answer either way. */
    data class Failed(val message: String) : ScreenshotReadResult
}

object ScreenshotMetadataReader {
    internal const val INVALID_PNG_MESSAGE =
        "Invalid file selected. Please select a valid PNG screenshot."
    internal const val UNPARSEABLE_MESSAGE = "Failed to parse screenshot metadata."

    private const val MAX_CHUNKS_TO_READ = 4096
    private const val MAX_CHUNK_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_CHUNK_BYTES = 1024 * 1024
    private const val MAX_IHDR_BYTES = 64
    private const val MAX_BYTES_AFTER_IDAT = 128L * 1024 * 1024
    private val pngSignature = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun read(inputStream: InputStream, fileName: String? = null): ScreenshotReadResult {
        return try {
            val pngMetadata = inputStream.use(::readPngMetadata)
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

    private fun readPngMetadata(inputStream: InputStream): PngMetadata? {
        val input = DataInputStream(inputStream.buffered())
        val signature = ByteArray(pngSignature.size)
        input.readFully(signature)
        if (!signature.contentEquals(pngSignature)) return null

        var resolution: String? = null
        val chunks = mutableMapOf<String, MutableList<String>>()
        var sawImageData = false
        var bytesAfterIdat = 0L
        repeat(MAX_CHUNKS_TO_READ) {
            val length = input.readInt()
            if (length < 0 || length > MAX_CHUNK_BYTES) return PngMetadata(resolution, chunks)

            val typeBytes = ByteArray(4)
            input.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.US_ASCII)
            if (type == "IDAT") sawImageData = true
            if (sawImageData) {
                bytesAfterIdat += length.toLong() + 12L
                if (bytesAfterIdat > MAX_BYTES_AFTER_IDAT) return PngMetadata(resolution, chunks)
            }

            val shouldRead = (type == "IHDR" && length <= MAX_IHDR_BYTES) ||
                (type == "iTXt" && length <= MAX_TEXT_CHUNK_BYTES)
            val data = if (shouldRead) {
                ByteArray(length).also(input::readFully)
            } else {
                input.skipPngData(length)
                null
            }
            input.skipPngCrc()

            when (type) {
                "IHDR" -> resolution = data?.let(::readIhdrResolution)
                "iTXt" -> data?.let(::readITXtChunk)?.let { (keyword, text) ->
                    chunks.getOrPut(keyword) { mutableListOf() }.add(text)
                }
            }
            if (type == "IEND") return PngMetadata(resolution, chunks)
        }

        return PngMetadata(resolution, chunks)
    }

    private fun DataInputStream.skipPngCrc() {
        val crc = ByteArray(4)
        readFully(crc)
    }

    private fun DataInputStream.skipPngData(length: Int) {
        var remaining = length
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() == -1) throw EOFException()
                remaining--
            }
        }
    }

    private fun readIhdrResolution(data: ByteArray): String? {
        if (data.size < 8) return null
        val width = data.readIntBigEndian(0)
        val height = data.readIntBigEndian(4)
        return "${width}x${height}"
    }

    private fun readITXtChunk(data: ByteArray): Pair<String, String>? {
        val keywordEnd = data.indexOf(0)
        if (keywordEnd <= 0 || keywordEnd > 79 || data.size < keywordEnd + 5) return null

        val keyword = String(data, 0, keywordEnd, StandardCharsets.UTF_8)
        val compressionFlagOffset = keywordEnd + 1
        val compressionFlag = data[compressionFlagOffset].toInt()
        if (compressionFlag != 0) return null

        var offset = compressionFlagOffset + 2
        offset = data.indexOf(0, offset).takeIf { it >= 0 }?.plus(1) ?: return null
        offset = data.indexOf(0, offset).takeIf { it >= 0 }?.plus(1) ?: return null
        if (offset > data.size) return null

        val text = String(data, offset, data.size - offset, StandardCharsets.UTF_8)
        return keyword to text
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
                    parseLegacyMetadata(trimmed) ?: parsed
                }
                else -> parsed
            }
        }
        return parsed
    }

    private fun parseVrcxJson(metadata: String): ScreenshotMetadata? {
        return runCatching { json.decodeFromString<ScreenshotMetadata>(metadata) }.getOrNull()
    }

    private fun parseVrchatXmp(xmlString: String): ScreenshotMetadata? {
        val start = xmlString.indexOf("<x:xmpmeta")
        if (start < 0) return null

        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setXmlFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setXmlFeature("http://xml.org/sax/features/external-general-entities", false)
                setXmlFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.newDocumentBuilder().parse(xmlString.substring(start).byteInputStream())
        }.getOrNull() ?: return null

        val authorName = doc.textByLocalName("Author")
        val authorId = doc.textByLocalName("AuthorID")

        val worldId = doc.textByLocalName("WorldID") ?: doc.textByLocalName("World").orEmpty()
        return ScreenshotMetadata(
            application = doc.textByLocalName("CreatorTool"),
            version = 1,
            author = ScreenshotAuthor(
                id = authorId ?: authorName.orEmpty(),
                displayName = if (authorId != null) authorName else null,
            ),
            world = ScreenshotWorld(
                id = worldId,
                name = doc.textByLocalName("WorldDisplayName"),
                instanceId = worldId,
            ),
            timestamp = doc.textByLocalName("DateTime"),
            note = doc.textByLocalName("li"),
        )
    }

    private fun resolveCapturedAt(metadata: ScreenshotMetadata, fileName: String?): Long? {
        return parseDateTime(metadata.timestamp)
            ?: parseDateTime(metadata.creationDate)
            ?: parseVrchatFilenameDate(fileName)
    }

    private fun parseDateTime(value: String?): Long? {
        val dateTime = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseInstantMillisOrNull(dateTime)
            ?: parseOffsetDateTime(dateTime)
            ?: parseLocalDateTime(dateTime)
    }

    private fun parseOffsetDateTime(value: String): Long? {
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

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

    private fun Document.textByLocalName(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun DocumentBuilderFactory.setXmlFeature(feature: String, enabled: Boolean) {
        runCatching { setFeature(feature, enabled) }
    }

    private fun parseLegacyMetadata(metadataString: String): ScreenshotMetadata? {
        return runCatching {
            var parts = metadataString.split("|")
            if (parts.getOrNull(1) == "cvr") parts = parts.drop(1)
            val application = parts[0]
            val version = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val isCvr = application == "cvr"

            val players = mutableListOf<ScreenshotPlayer>()

            if (application == "screenshotmanager") {
                val authorParts = parts.getOrNull(2)?.removePrefix("author:")?.split(",").orEmpty()
                val worldParts = parts.getOrNull(3)?.split(",").orEmpty()
                return@runCatching ScreenshotMetadata(
                    application = application,
                    version = version,
                    author = ScreenshotAuthor(
                        id = authorParts.getOrElse(0) { "" },
                        displayName = authorParts.getOrNull(1),
                    ),
                    world = ScreenshotWorld(
                        id = worldParts.getOrElse(0) { "" },
                        name = worldParts.getOrNull(2),
                        instanceId = listOfNotNull(worldParts.getOrNull(0), worldParts.getOrNull(1))
                            .joinToString(":"),
                    ),
                )
            }

            var parsedAuthor = ScreenshotAuthor()
            var parsedWorld = ScreenshotWorld()
            var cameraPosition: ScreenshotPosition? = null
            for (part in parts.drop(2)) {
                val key = part.substringBefore(":", missingDelimiterValue = "")
                val value = part.substringAfter(":", missingDelimiterValue = "")
                if (key.isEmpty() || value.isEmpty()) continue
                val valueParts = value.split(",")

                when (key) {
                    "author" -> parsedAuthor = ScreenshotAuthor(
                        id = if (isCvr) "" else valueParts.getOrElse(0) { "" },
                        displayName = if (isCvr) {
                            "${valueParts.getOrElse(1) { "" }} (${valueParts.getOrElse(0) { "" }})"
                        } else {
                            valueParts.getOrNull(1)
                        },
                    )
                    "world" -> parsedWorld = ScreenshotWorld(
                        id = if (isCvr || version == 1) "" else valueParts.getOrElse(0) { "" },
                        name = if (isCvr) {
                            "${valueParts.getOrElse(2) { "" }} (${valueParts.getOrElse(0) { "" }})"
                        } else if (version == 1) {
                            value
                        } else {
                            valueParts.getOrNull(2)
                        },
                        instanceId = if (isCvr || version == 1) {
                            ""
                        } else {
                            listOfNotNull(valueParts.getOrNull(0), valueParts.getOrNull(1)).joinToString(":")
                        },
                    )
                    "pos" -> cameraPosition = valueParts.toPosition()
                    "players" -> value.split(";").forEach { player ->
                        val playerParts = player.split(",")
                        val displayName = if (isCvr) {
                            "${playerParts.getOrElse(4) { "" }} (${playerParts.getOrElse(0) { "" }})"
                        } else {
                            playerParts.getOrElse(4) {
                                playerParts.getOrElse(1) { "" }
                            }
                        }
                        players.add(
                            ScreenshotPlayer(
                                id = if (isCvr) "" else playerParts.getOrElse(0) { "" },
                                displayName = displayName,
                                pos = if (playerParts.size >= 5) {
                                    playerParts.drop(1).take(3).toPosition()
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                }
            }

            ScreenshotMetadata(
                application = application,
                version = version,
                author = parsedAuthor,
                world = parsedWorld,
                players = players,
                pos = cameraPosition,
            )
        }.getOrNull()
    }

    private fun List<String>.toPosition(): ScreenshotPosition? {
        if (size < 3) return null
        return ScreenshotPosition(
            x = getOrNull(0)?.toFloatOrNull() ?: 0f,
            y = getOrNull(1)?.toFloatOrNull() ?: 0f,
            z = getOrNull(2)?.toFloatOrNull() ?: 0f,
        )
    }

    private fun ByteArray.readIntBigEndian(offset: Int): Int {
        return ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
    }

    private fun ByteArray.indexOf(value: Int, startIndex: Int = 0): Int {
        for (index in startIndex until size) {
            if (this[index].toInt() == value) return index
        }
        return -1
    }

    private data class PngMetadata(
        val resolution: String?,
        val textChunks: Map<String, List<String>>,
    )
}
