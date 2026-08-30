package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserDetailProfile(
    val user: VrcUser,
    val note: String? = null,
    val memo: String? = null,
    val notifyEnabled: Boolean = false,
    val localDataError: String? = null,
)

/** Coordinates remote profile sections with account-scoped local profile data. */
@Singleton
class UserDetailRepository @Inject internal constructor(
    private val userRepository: UserRepository,
    private val avatarRepository: AvatarRepository,
    private val groupRepository: GroupRepository,
    private val profileWorldLoader: ProfileWorldLoader,
    private val authRepository: AuthRepository,
    private val localProfileRepository: LocalUserProfileRepository,
) {
    fun observeIsSelf(userId: String): Flow<Boolean> = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user?.id == userId
    }

    suspend fun loadProfile(userId: String): UserDetailProfile {
        val accountId = currentUserId()
        val user = userRepository.getUser(userId, forceRefresh = true)
        if (currentUserId() != accountId) throw AccountChangedException()
        val ownerId = accountId ?: return UserDetailProfile(user = user)

        val localProfile = localProfileRepository.load(ownerId, userId, user)
        if (currentUserId() != ownerId) throw AccountChangedException()

        return UserDetailProfile(
            user = user,
            note = localProfile.note,
            memo = localProfile.memo,
            notifyEnabled = localProfile.notifyEnabled,
            localDataError = localProfile.error,
        )
    }

    suspend fun cacheProfilePicture(user: VrcUser) = localProfileRepository.cacheProfilePicture(user)

    suspend fun loadMutualFriends(userId: String): List<VrcUser> = userRepository.getMutualFriends(userId)

    suspend fun loadGroups(userId: String): List<Group> = groupRepository.getUserGroups(userId)

    suspend fun loadWorlds(userId: String): List<World> = profileWorldLoader.loadWorlds(userId)

    suspend fun loadAvatars(userId: String): List<Avatar> = avatarRepository.getUserAvatars(userId)

    suspend fun loadFavoriteWorlds(userId: String): FavoriteWorldLoadResult =
        profileWorldLoader.loadFavoriteWorlds(userId)

    suspend fun loadMemos(ownerId: String): Map<String, String> = localProfileRepository.loadMemos(ownerId)

    private fun currentUserId(): String? = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id
}
