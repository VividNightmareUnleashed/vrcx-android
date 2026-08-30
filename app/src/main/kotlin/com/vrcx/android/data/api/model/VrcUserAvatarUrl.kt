package com.vrcx.android.data.api.model

/**
 * Returns the preferred display avatar URL, respecting a user's profile-picture
 * override.
 *
 * VRChat profile-picture overrides take precedence over the current avatar
 * thumbnail. When present, the override thumbnail is preferred over the
 * full-size override.
 *
 * Resolution order:
 * 1. `profilePicOverrideThumbnail` (explicit list-context thumbnail, when present)
 * 2. `profilePicOverride` (full-size override, used as fallback)
 * 3. `currentAvatarThumbnailImageUrl` (the user's active avatar thumbnail)
 *
 * Returns an empty string when nothing is available.
 */
fun VrcUser.displayAvatarUrl(): String {
    if (profilePicOverrideThumbnail.isNotEmpty()) return profilePicOverrideThumbnail
    if (profilePicOverride.isNotEmpty()) return profilePicOverride
    return currentAvatarThumbnailImageUrl
}

/** Returns the override thumbnail, full-size override, or current avatar thumbnail, in that order. */
fun CurrentUser.displayAvatarUrl(): String {
    if (profilePicOverrideThumbnail.isNotEmpty()) return profilePicOverrideThumbnail
    if (profilePicOverride.isNotEmpty()) return profilePicOverride
    return currentAvatarThumbnailImageUrl
}

/** Returns a search result's profile-picture override, or its current avatar thumbnail. */
fun UserSearchResult.displayAvatarUrl(): String {
    if (profilePicOverride.isNotEmpty()) return profilePicOverride
    return currentAvatarThumbnailImageUrl
}
