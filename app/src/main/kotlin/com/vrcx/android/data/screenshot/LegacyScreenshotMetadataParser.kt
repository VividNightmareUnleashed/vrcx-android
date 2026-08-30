package com.vrcx.android.data.screenshot

private const val FIELD_SEPARATOR = "|"
private const val VALUE_SEPARATOR = ","
private const val PLAYER_SEPARATOR = ";"
private const val KEY_SEPARATOR = ":"

private const val APPLICATION_INDEX = 0
private const val VERSION_INDEX = 1
private const val FIRST_FIELD_INDEX = 2
private const val SCREENSHOT_MANAGER_AUTHOR_INDEX = 2
private const val SCREENSHOT_MANAGER_WORLD_INDEX = 3
private const val DEFAULT_VERSION = 1

private const val ID_INDEX = 0
private const val SECONDARY_VALUE_INDEX = 1
private const val WORLD_NAME_INDEX = 2
private const val PLAYER_NAME_INDEX = 4
private const val PLAYER_POSITION_START_INDEX = 1
private const val POSITION_COMPONENT_COUNT = 3

private const val CVR_APPLICATION = "cvr"
private const val SCREENSHOT_MANAGER_APPLICATION = "screenshotmanager"
private const val AUTHOR_FIELD = "author"
private const val WORLD_FIELD = "world"
private const val POSITION_FIELD = "pos"
private const val PLAYERS_FIELD = "players"

/** Decodes the pipe-delimited metadata written by LFS, CVR, and ScreenshotManager. */
internal object LegacyScreenshotMetadataParser {
    fun parse(metadataString: String): ScreenshotMetadata? = runCatching {
        val parts = normalize(metadataString.split(FIELD_SEPARATOR))
        val format = LegacyFormat(
            application = parts[APPLICATION_INDEX],
            version = parts.getOrNull(VERSION_INDEX)?.toIntOrNull() ?: DEFAULT_VERSION,
        )

        if (format.application == SCREENSHOT_MANAGER_APPLICATION) {
            parseScreenshotManager(parts, format)
        } else {
            LegacyMetadataBuilder(format).apply {
                parts.drop(FIRST_FIELD_INDEX).forEach(::add)
            }.build()
        }
    }.getOrNull()

    private fun normalize(parts: List<String>): List<String> =
        if (parts.getOrNull(VERSION_INDEX) == CVR_APPLICATION) parts.drop(1) else parts

    private fun parseScreenshotManager(parts: List<String>, format: LegacyFormat): ScreenshotMetadata {
        val authorParts = parts.getOrNull(SCREENSHOT_MANAGER_AUTHOR_INDEX)
            ?.removePrefix("$AUTHOR_FIELD$KEY_SEPARATOR")
            ?.split(VALUE_SEPARATOR)
            .orEmpty()
        val worldParts = parts.getOrNull(SCREENSHOT_MANAGER_WORLD_INDEX)
            ?.split(VALUE_SEPARATOR)
            .orEmpty()
        return ScreenshotMetadata(
            application = format.application,
            version = format.version,
            author = ScreenshotAuthor(
                id = authorParts.getOrElse(ID_INDEX) { "" },
                displayName = authorParts.getOrNull(SECONDARY_VALUE_INDEX),
            ),
            world = ScreenshotWorld(
                id = worldParts.getOrElse(ID_INDEX) { "" },
                name = worldParts.getOrNull(WORLD_NAME_INDEX),
                instanceId = listOfNotNull(
                    worldParts.getOrNull(ID_INDEX),
                    worldParts.getOrNull(SECONDARY_VALUE_INDEX),
                ).joinToString(KEY_SEPARATOR),
            ),
        )
    }
}

private data class LegacyFormat(val application: String, val version: Int) {
    val isCvr: Boolean = application == CVR_APPLICATION
    val usesLegacyWorldFormat: Boolean = isCvr || version == DEFAULT_VERSION
}

private class LegacyMetadataBuilder(private val format: LegacyFormat) {
    private var author = ScreenshotAuthor()
    private var world = ScreenshotWorld()
    private var cameraPosition: ScreenshotPosition? = null
    private val players = mutableListOf<ScreenshotPlayer>()

    fun add(encodedField: String) {
        val key = encodedField.substringBefore(KEY_SEPARATOR, missingDelimiterValue = "")
        val value = encodedField.substringAfter(KEY_SEPARATOR, missingDelimiterValue = "")
        if (key.isEmpty() || value.isEmpty()) return

        when (key) {
            AUTHOR_FIELD -> author = parseAuthor(value.split(VALUE_SEPARATOR))
            WORLD_FIELD -> world = parseWorld(value, value.split(VALUE_SEPARATOR))
            POSITION_FIELD -> cameraPosition = value.split(VALUE_SEPARATOR).toPosition()
            PLAYERS_FIELD -> value.split(PLAYER_SEPARATOR).mapTo(players, ::parsePlayer)
        }
    }

    fun build() = ScreenshotMetadata(
        application = format.application,
        version = format.version,
        author = author,
        world = world,
        players = players,
        pos = cameraPosition,
    )

    private fun parseAuthor(parts: List<String>) = ScreenshotAuthor(
        id = if (format.isCvr) "" else parts.getOrElse(ID_INDEX) { "" },
        displayName = if (format.isCvr) {
            formatCvrLabel(parts, SECONDARY_VALUE_INDEX)
        } else {
            parts.getOrNull(SECONDARY_VALUE_INDEX)
        },
    )

    private fun parseWorld(value: String, parts: List<String>) = ScreenshotWorld(
        id = if (format.usesLegacyWorldFormat) "" else parts.getOrElse(ID_INDEX) { "" },
        name = when {
            format.isCvr -> formatCvrLabel(parts, WORLD_NAME_INDEX)
            format.usesLegacyWorldFormat -> value
            else -> parts.getOrNull(WORLD_NAME_INDEX)
        },
        instanceId = if (format.usesLegacyWorldFormat) {
            ""
        } else {
            listOfNotNull(
                parts.getOrNull(ID_INDEX),
                parts.getOrNull(SECONDARY_VALUE_INDEX),
            ).joinToString(KEY_SEPARATOR)
        },
    )

    private fun parsePlayer(encodedPlayer: String): ScreenshotPlayer {
        val parts = encodedPlayer.split(VALUE_SEPARATOR)
        val displayName = if (format.isCvr) {
            formatCvrLabel(parts, PLAYER_NAME_INDEX)
        } else {
            parts.getOrElse(PLAYER_NAME_INDEX) {
                parts.getOrElse(SECONDARY_VALUE_INDEX) { "" }
            }
        }
        return ScreenshotPlayer(
            id = if (format.isCvr) "" else parts.getOrElse(ID_INDEX) { "" },
            displayName = displayName,
            pos = if (parts.size > PLAYER_NAME_INDEX) {
                parts.drop(PLAYER_POSITION_START_INDEX)
                    .take(POSITION_COMPONENT_COUNT)
                    .toPosition()
            } else {
                null
            },
        )
    }

    private fun formatCvrLabel(parts: List<String>, nameIndex: Int): String =
        "${parts.getOrElse(nameIndex) { "" }} (${parts.getOrElse(ID_INDEX) { "" }})"

    private fun List<String>.toPosition(): ScreenshotPosition? {
        if (size < POSITION_COMPONENT_COUNT) return null
        return ScreenshotPosition(
            x = getOrNull(ID_INDEX)?.toFloatOrNull() ?: 0f,
            y = getOrNull(SECONDARY_VALUE_INDEX)?.toFloatOrNull() ?: 0f,
            z = getOrNull(WORLD_NAME_INDEX)?.toFloatOrNull() ?: 0f,
        )
    }
}
