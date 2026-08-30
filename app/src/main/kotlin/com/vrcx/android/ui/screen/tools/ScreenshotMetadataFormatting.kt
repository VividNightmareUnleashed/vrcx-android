package com.vrcx.android.ui.screen.tools

import com.vrcx.android.data.screenshot.ScreenshotPosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun ScreenshotPosition.format(): String = String.format(Locale.US, "(%.2f, %.2f, %.2f)", x, y, z)

internal fun formatCapturedAt(epochMillis: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    .withLocale(Locale.getDefault())
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
