package com.vrcx.android.ui.common

import java.util.Locale

private const val BYTES_PER_BINARY_UNIT = 1024

/**
 * Turns a VRChat visibility/permission token (e.g. "friends-only") into a
 * human-readable label ("Friends only"). Shared by favorites and profile
 * detail screens.
 */
fun String.prettyVisibility(): String = replace('-', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Maps a VRChat unity-package platform code to a display label. */
fun platformLabel(platform: String): String = when (platform) {
    "standalonewindows" -> "PC"
    "android" -> "Quest"
    "ios" -> "iOS"
    else -> platform
}

/** Drops VRChat's internal tag prefixes, leaving the user-facing tags. */
fun displayableTags(tags: List<String>): List<String> =
    tags.filter { !it.startsWith("system_") && !it.startsWith("admin_") && !it.startsWith("author_tag") }

/**
 * Renders a byte count as a binary-prefixed size ("512 B", "2.0 KB", "1.4 MB").
 * Pinned to [Locale.US] so the decimal separator matches everywhere the app
 * shows a size, whatever the device locale is.
 */
fun formatByteCount(bytes: Long): String {
    if (bytes < BYTES_PER_BINARY_UNIT) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= BYTES_PER_BINARY_UNIT && unitIndex < units.lastIndex) {
        value /= BYTES_PER_BINARY_UNIT
        unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
