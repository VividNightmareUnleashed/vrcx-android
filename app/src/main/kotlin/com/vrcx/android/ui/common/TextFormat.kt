package com.vrcx.android.ui.common

/**
 * Turns a VRChat visibility/permission token (e.g. "friends-only") into a
 * human-readable label ("Friends only"). Shared by favorites and profile
 * detail screens.
 */
fun String.prettyVisibility(): String =
    replace('-', ' ')
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
