package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Group(
    val bannerId: String = "",
    val bannerUrl: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    val description: String = "",
    val discriminator: String = "",
    val galleries: List<GroupGallery> = emptyList(),
    val iconId: String = "",
    val iconUrl: String = "",
    val id: String = "",
    val isVerified: Boolean = false,
    val joinState: String = "",
    val languages: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val memberCount: Int = 0,
    val memberCountSyncedAt: String = "",
    val membershipStatus: String = "",
    val myMember: GroupMember? = null,
    val name: String = "",
    val onlineMemberCount: Int = 0,
    val ownerId: String = "",
    val privacy: String = "",
    val roles: List<GroupRole> = emptyList(),
    val rules: String = "",
    val shortCode: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("updatedAt") val updatedAt: String = "",
)

@Serializable
data class GroupMember(
    val bannedAt: String? = null,
    @SerialName("createdAt") val createdAt: String = "",
    val groupId: String = "",
    val has2FA: Boolean = false,
    val id: String = "",
    val isRepresenting: Boolean = false,
    val isSubscribedToAnnouncements: Boolean = false,
    val joinedAt: String = "",
    val lastPostReadAt: String = "",
    val mRoleIds: List<String> = emptyList(),
    val managedBy: String? = null,
    val managerNotes: String = "",
    val membershipStatus: String = "",
    val roleIds: List<String> = emptyList(),
    val userId: String = "",
    val visibility: String = "",
    val user: VrcUser? = null,
)

@Serializable
data class GroupRole(
    @SerialName("createdAt") val createdAt: String = "",
    val description: String = "",
    val groupId: String = "",
    val id: String = "",
    val isManagementRole: Boolean = false,
    val isSelfAssignable: Boolean = false,
    val name: String = "",
    val order: Int = 0,
    val permissions: List<String> = emptyList(),
    val requiresPurchase: Boolean = false,
    val requiresTwoFactor: Boolean = false,
    @SerialName("updatedAt") val updatedAt: String = "",
)

@Serializable
data class GroupGallery(
    @SerialName("createdAt") val createdAt: String = "",
    val description: String = "",
    val id: String = "",
    val membersOnly: Boolean = false,
    val name: String = "",
    val roleIdsToAutoApprove: List<String> = emptyList(),
    val roleIdsToManage: List<String> = emptyList(),
    val roleIdsToSubmit: List<String> = emptyList(),
    val roleIdsToView: List<String> = emptyList(),
    @SerialName("updatedAt") val updatedAt: String = "",
)

@Serializable
data class GroupPost(
    val authorId: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    val editorId: String? = null,
    val groupId: String = "",
    val id: String = "",
    val imageId: String? = null,
    val imageUrl: String = "",
    val roleId: String? = null,
    val text: String = "",
    val title: String = "",
    @SerialName("updatedAt") val updatedAt: String = "",
    val visibility: String = "",
)

@Serializable
data class GroupInstance(
    val instanceId: String = "",
    val location: String = "",
    val memberCount: Int = 0,
    val world: World? = null,
)

@Serializable
data class GroupSearchResult(
    val bannerId: String = "",
    val bannerUrl: String = "",
    val description: String = "",
    val discriminator: String = "",
    val iconId: String = "",
    val iconUrl: String = "",
    val id: String = "",
    val memberCount: Int = 0,
    val name: String = "",
    val ownerId: String = "",
    val shortCode: String = "",
    val tags: List<String> = emptyList(),
)
