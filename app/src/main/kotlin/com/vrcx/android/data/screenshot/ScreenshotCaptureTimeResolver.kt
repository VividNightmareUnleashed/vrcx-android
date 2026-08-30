package com.vrcx.android.data.screenshot

import com.vrcx.android.data.util.parseInstantMillisOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal object ScreenshotCaptureTimeResolver {
    private val currentFileName = Regex(
        """VRChat_\d{3,}x\d{3,}_(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.(\d{1,})""",
    )
    private val legacyFileName = Regex(
        """VRChat_(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.(\d{3})_\d{3,}x\d{3,}""",
    )

    fun resolve(metadata: ScreenshotMetadata, fileName: String?): Long? = parseDateTime(metadata.timestamp)
        ?: parseDateTime(metadata.creationDate)
        ?: parseVrchatFilenameDate(fileName)

    private fun parseDateTime(value: String?): Long? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { dateTime ->
            parseInstantMillisOrNull(dateTime)
                ?: parseOffsetDateTime(dateTime)
                ?: parseLocalDateTime(dateTime)
        }

    private fun parseOffsetDateTime(value: String): Long? = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()

    private fun parseLocalDateTime(value: String): Long? = runCatching {
        LocalDateTime.parse(value.replace(' ', 'T'))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun parseVrchatFilenameDate(fileName: String?): Long? =
        (currentFileName.find(fileName.orEmpty()) ?: legacyFileName.find(fileName.orEmpty()))
            ?.toEpochMillis()

    private fun MatchResult.toEpochMillis(): Long? = runCatching {
        LocalDateTime.of(
            groupValues[YEAR_GROUP].toInt(),
            groupValues[MONTH_GROUP].toInt(),
            groupValues[DAY_GROUP].toInt(),
            groupValues[HOUR_GROUP].toInt(),
            groupValues[MINUTE_GROUP].toInt(),
            groupValues[SECOND_GROUP].toInt(),
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private const val YEAR_GROUP = 1
    private const val MONTH_GROUP = 2
    private const val DAY_GROUP = 3
    private const val HOUR_GROUP = 4
    private const val MINUTE_GROUP = 5
    private const val SECOND_GROUP = 6
}
