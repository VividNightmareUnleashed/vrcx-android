package com.vrcx.android.data.api.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class VrcUser(
    val ageVerificationStatus: String = "",
    val ageVerified: Boolean = false,
    val allowAvatarCopying: Boolean = false,
    val badges: JsonElement? = null,
    val bio: String = "",
    val bioLinks: JsonElement? = null,
    val currentAvatarImageUrl: String = "",
    val currentAvatarTags: JsonElement? = null,
    val currentAvatarThumbnailImageUrl: String = "",
    @SerialName("date_joined") val dateJoined: String = "",
    val developerType: String = "",
    val discordId: String = "",
    val displayName: String = "",
    val friendKey: String = "",
    val friendRequestStatus: String? = null,
    val id: String = "",
    val instanceId: String? = null,
    val isFriend: Boolean = false,
    @SerialName("last_activity") val lastActivity: String = "",
    @SerialName("last_login") val lastLogin: String = "",
    @SerialName("last_mobile") val lastMobile: String? = null,
    @SerialName("last_platform") val lastPlatform: String = "",
    val location: String? = null,
    val note: String? = null,
    val platform: String? = null,
    val profilePicOverride: String = "",
    val profilePicOverrideThumbnail: String = "",
    val pronouns: String = "",
    val state: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val tags: List<String> = emptyList(),
    val travelingToInstance: String? = null,
    val travelingToLocation: String? = null,
    val travelingToWorld: String? = null,
    val userIcon: String = "",
    val worldId: String? = null,
)

@Serializable
data class CurrentUser(
    val acceptedPrivacyVersion: Int = 0,
    val acceptedTOSVersion: Int = 0,
    val accountDeletionDate: JsonElement? = null,
    val accountDeletionLog: JsonElement? = null,
    val activeFriends: JsonElement? = null,
    val ageVerificationStatus: String = "",
    val ageVerified: Boolean = false,
    val allowAvatarCopying: Boolean = false,
    val badges: JsonElement? = null,
    val bio: String = "",
    val bioLinks: JsonElement? = null,
    val currentAvatar: String = "",
    val currentAvatarImageUrl: String = "",
    val currentAvatarTags: JsonElement? = null,
    val currentAvatarThumbnailImageUrl: String = "",
    @SerialName("date_joined") val dateJoined: String = "",
    val developerType: String = "",
    val discordId: String = "",
    val displayName: String = "",
    val emailVerified: Boolean = false,
    val fallbackAvatar: String = "",
    val friendGroupNames: JsonElement? = null,
    val friendKey: String = "",
    val friends: JsonElement? = null,
    val googleId: String = "",
    val hasBirthday: Boolean = false,
    val hasEmail: Boolean = false,
    val hasLoggedInFromClient: Boolean = false,
    val hasPendingEmail: Boolean = false,
    val hideContentFilterSettings: Boolean = false,
    val homeLocation: String = "",
    val id: String = "",
    val isAdult: Boolean = false,
    val isBoopingEnabled: Boolean = false,
    val isFriend: Boolean = false,
    @SerialName("last_activity") val lastActivity: String = "",
    @SerialName("last_login") val lastLogin: String = "",
    @SerialName("last_mobile") val lastMobile: String? = null,
    @SerialName("last_platform") val lastPlatform: String = "",
    val location: String? = null,
    val obfuscatedEmail: String = "",
    val obfuscatedPendingEmail: String = "",
    val oculusId: String = "",
    val offlineFriends: JsonElement? = null,
    val onlineFriends: JsonElement? = null,
    val pastDisplayNames: JsonElement? = null,
    val picoId: String = "",
    val platform: String? = null,
    val presence: JsonElement? = null,
    val profilePicOverride: String = "",
    val profilePicOverrideThumbnail: String = "",
    val pronouns: String = "",
    val queuedInstance: String? = null,
    val state: String = "",
    val status: String = "",
    val statusDescription: String = "",
    val tags: List<String> = emptyList(),
    val travelingToInstance: String? = null,
    val travelingToLocation: String? = null,
    val travelingToWorld: String? = null,
    val userIcon: String = "",
    val worldId: String? = null,
)

@Serializable
data class UserSearchResult(
    val bio: String = "",
    val bioLinks: JsonElement? = null,
    val currentAvatarImageUrl: String = "",
    val currentAvatarThumbnailImageUrl: String = "",
    val developerType: String = "",
    val displayName: String = "",
    val id: String = "",
    val isFriend: Boolean = false,
    @SerialName("last_platform") val lastPlatform: String = "",
    val profilePicOverride: String = "",
    val pronouns: String? = null,
    val status: String = "",
    val statusDescription: String = "",
    val tags: List<String> = emptyList(),
    val userIcon: String = "",
)

/** Sparse patch body for `PUT users/{userId}`. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class UpdateCurrentUserRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val status: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val statusDescription: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bio: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val pronouns: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val homeLocation: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val profilePicOverride: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val userIcon: String? = null,
)
