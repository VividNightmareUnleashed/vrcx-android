package com.vrcx.android.data.api.model

/**
 * Resolve the avatar URL that should be shown in list items and previews,
 * respecting a user's profile-picture override.
 *
 * VRChat lets users set a `profilePicOverride` (full-size) and an optional
 * `profilePicOverrideThumbnail` that should be shown instead of their
 * current avatar thumbnail. Desktop VRCX honors this everywhere; list
 * screens in this app were previously reading `currentAvatarThumbnailImageUrl`
 * directly, so users with an override but a default VRChat avatar rendered
 * as the generic robot in every list.
 *
 * Resolution order:
 * 1. `profilePicOverrideThumbnail` (explicit list-context thumbnail, when present)
 * 2. `profilePicOverride` (full-size override, used as fallback)
 * 3. `currentAvatarThumbnailImageUrl` (the user's active avatar thumbnail)
 *
 * Returns an empty string when nothing is available; callers typically
 * map that to `null` before passing into `UserAvatar(imageUrl = …)`.
 */
fun VrcUser.displayAvatarUrl(): String {
    if (profilePicOverrideThumbnail.isNotEmpty()) return profilePicOverrideThumbnail
    if (profilePicOverride.isNotEmpty()) return profilePicOverride
    return currentAvatarThumbnailImageUrl
}

fun CurrentUser.displayAvatarUrl(): String {
    if (profilePicOverrideThumbnail.isNotEmpty()) return profilePicOverrideThumbnail
    if (profilePicOverride.isNotEmpty()) return profilePicOverride
    return currentAvatarThumbnailImageUrl
}

fun UserSearchResult.displayAvatarUrl(): String {
    if (profilePicOverride.isNotEmpty()) return profilePicOverride
    return currentAvatarThumbnailImageUrl
}
