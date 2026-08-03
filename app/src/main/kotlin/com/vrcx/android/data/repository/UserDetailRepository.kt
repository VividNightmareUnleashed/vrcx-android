package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.PlayerModerationRequest
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.cache.ProfilePicCacheManager
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.dao.MemoDao
import com.vrcx.android.data.db.dao.NoteDao
import com.vrcx.android.data.db.dao.getMemo
import com.vrcx.android.data.db.dao.getNote
import com.vrcx.android.data.db.dao.isEnabled
import com.vrcx.android.data.db.dao.save
import com.vrcx.android.data.db.dao.saveMemo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class UserDetailProfile(
    val user: VrcUser,
    val note: String? = null,
    val memo: String? = null,
    val notifyEnabled: Boolean = false,
    val localDataError: String? = null,
)

data class FavoriteWorldSection(
    val tag: String,
    val displayName: String,
    val visibility: String,
    val worlds: List<World>,
)

data class FavoriteWorldLoadResult(
    val sections: List<FavoriteWorldSection>,
    val warning: String? = null,
)

enum class UserDetailAction {
    REQUEST_INVITE,
    SEND_INVITE,
    SEND_BOOP,
    SEND_FRIEND_REQUEST,
    CANCEL_FRIEND_REQUEST,
    UNFRIEND,
    BLOCK,
    MUTE,
    HIDE_AVATAR,
    SHOW_AVATAR,
}

/** Owns the remote/local policy for one user-profile feature. */
@Singleton
class UserDetailRepository @Inject constructor(
    private val userRepository: UserRepository,
    private val avatarApi: AvatarApi,
    private val favoriteApi: FavoriteApi,
    private val friendApi: FriendApi,
    private val groupApi: GroupApi,
    private val worldApi: WorldApi,
    private val notificationApi: NotificationApi,
    private val notificationRepository: NotificationRepository,
    private val favoriteRepository: FavoriteRepository,
    private val playerModerationApi: PlayerModerationApi,
    private val authRepository: AuthRepository,
    private val noteDao: NoteDao,
    private val memoDao: MemoDao,
    private val friendNotifyDao: FriendNotifyDao,
    private val friendRepository: FriendRepository,
    private val profilePicCacheManager: ProfilePicCacheManager,
) {
    val favorites: StateFlow<List<Favorite>> = favoriteRepository.favorites

    fun observeIsSelf(userId: String): Flow<Boolean> = authRepository.authState.map { state ->
        (state as? AuthState.LoggedIn)?.user?.id == userId
    }

    suspend fun loadProfile(userId: String): UserDetailProfile {
        val accountId = currentUserId()
        val user = userRepository.getUser(userId, forceRefresh = true)
        if (currentUserId() != accountId) throw CancellationException("Account changed")
        val ownerId = accountId ?: return UserDetailProfile(user = user)

        var note: String? = null
        var memo: String? = null
        var notifyEnabled = false
        var localDataError: String? = null
        try {
            memo = memoDao.getMemo(ownerId, userId)?.memo
            val localNote = noteDao.getNote(ownerId, userId)?.note
            note = user.note?.takeIf { it.isNotBlank() } ?: localNote
            if (!user.note.isNullOrBlank()) {
                noteDao.save(
                    ownerId = ownerId,
                    userId = userId,
                    displayName = user.displayName,
                    note = user.note,
                    createdAt = Instant.now().toString(),
                )
            }
            notifyEnabled = friendNotifyDao.isEnabled(ownerId, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            localDataError = e.message
        }
        if (currentUserId() != ownerId) throw CancellationException("Account changed")

        return UserDetailProfile(
            user = user,
            note = note,
            memo = memo,
            notifyEnabled = notifyEnabled,
            localDataError = localDataError,
        )
    }

    suspend fun cacheProfilePicture(user: VrcUser) {
        val imageUrl = user.displayAvatarUrl()
        if (imageUrl.isNotEmpty()) profilePicCacheManager.cacheImage(imageUrl)
    }

    suspend fun loadMutualFriends(userId: String): List<VrcUser> =
        userRepository.getMutualFriends(userId)

    suspend fun loadGroups(userId: String): List<Group> = BulkPaginator.fetchAll(
        pageSize = PROFILE_PAGE_SIZE,
    ) { offset, count ->
        groupApi.getUserGroups(userId = userId, n = count, offset = offset)
    }

    suspend fun loadWorlds(userId: String): List<World> = BulkPaginator.fetchAll(
        pageSize = PROFILE_PAGE_SIZE,
    ) { offset, count ->
        worldApi.getWorlds(n = count, offset = offset, user = userId)
    }

    suspend fun loadAvatars(userId: String): List<Avatar> = BulkPaginator.fetchAll(
        pageSize = PROFILE_PAGE_SIZE,
    ) { offset, count ->
        avatarApi.getAvatars(
            n = count,
            offset = offset,
            user = userId,
            releaseStatus = "all",
        )
    }

    suspend fun loadFavoriteWorlds(userId: String): FavoriteWorldLoadResult {
        val groups = BulkPaginator.fetchAll<FavoriteGroup>(pageSize = PROFILE_PAGE_SIZE) { offset, count ->
            favoriteApi.getFavoriteGroups(
                n = count,
                offset = offset,
                type = "world",
                ownerId = userId,
            )
        }.filter { it.type == "world" }
        if (groups.isEmpty()) return FavoriteWorldLoadResult(emptyList())

        val results = supervisorScope {
            groups.map { group ->
                async {
                    try {
                        Result.success(
                            FavoriteWorldSection(
                                tag = group.name,
                                displayName = group.displayName.ifBlank { group.name },
                                visibility = group.visibility,
                                worlds = BulkPaginator.fetchAll(pageSize = PROFILE_PAGE_SIZE) { offset, count ->
                                    favoriteApi.getFavoriteWorlds(
                                        n = count,
                                        offset = offset,
                                        tag = group.name,
                                        ownerId = userId,
                                    )
                                },
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val successfulSections = results.mapNotNull { it.getOrNull() }
        if (successfulSections.isEmpty()) {
            throw IllegalStateException("No favorite world groups could be loaded")
        }
        val sections = successfulSections
            .filter { it.worlds.isNotEmpty() }
        val warning = if (results.any { it.isFailure }) {
            "Some favorite world groups could not be loaded"
        } else {
            null
        }
        return FavoriteWorldLoadResult(sections, warning)
    }

    suspend fun loadFavoriteStatus() {
        favoriteRepository.loadFavorites(type = "friend")
    }

    suspend fun addFriendFavorite(userId: String) {
        favoriteRepository.addFavorite("friend", userId)
    }

    suspend fun deleteFavorite(favoriteId: String) {
        favoriteRepository.deleteFavorite(favoriteId)
    }

    suspend fun saveNote(userId: String, displayName: String, text: String): Boolean {
        val ownerId = currentUserId() ?: return false
        userRepository.saveUserNote(userId, text)
        if (currentUserId() != ownerId) return false
        noteDao.save(
            ownerId = ownerId,
            userId = userId,
            displayName = displayName,
            note = text,
            createdAt = Instant.now().toString(),
        )
        return true
    }

    suspend fun saveMemo(userId: String, text: String): Boolean {
        val ownerId = currentUserId() ?: return false
        memoDao.saveMemo(ownerId, userId, text, Instant.now().toString())
        return currentUserId() == ownerId
    }

    suspend fun toggleNotify(userId: String): Boolean = friendRepository.toggleFriendNotify(userId)

    suspend fun performAction(action: UserDetailAction, userId: String) {
        when (action) {
            UserDetailAction.REQUEST_INVITE -> notificationApi.sendRequestInvite(userId)
            UserDetailAction.SEND_INVITE -> notificationRepository.sendInviteToUser(userId)
            UserDetailAction.SEND_BOOP -> userRepository.sendBoop(userId)
            UserDetailAction.SEND_FRIEND_REQUEST -> friendApi.sendFriendRequest(userId)
            UserDetailAction.CANCEL_FRIEND_REQUEST -> friendApi.cancelFriendRequest(userId)
            UserDetailAction.UNFRIEND -> friendApi.deleteFriend(userId)
            UserDetailAction.BLOCK -> playerModerationApi.sendPlayerModeration(
                PlayerModerationRequest(userId, "block")
            )
            UserDetailAction.MUTE -> playerModerationApi.sendPlayerModeration(
                PlayerModerationRequest(userId, "mute")
            )
            UserDetailAction.HIDE_AVATAR -> playerModerationApi.sendPlayerModeration(
                PlayerModerationRequest(userId, "hideAvatar")
            )
            UserDetailAction.SHOW_AVATAR -> playerModerationApi.sendPlayerModeration(
                PlayerModerationRequest(userId, "showAvatar")
            )
        }
    }

    private fun currentUserId(): String? =
        (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id

    private companion object {
        const val PROFILE_PAGE_SIZE = 100
    }
}
