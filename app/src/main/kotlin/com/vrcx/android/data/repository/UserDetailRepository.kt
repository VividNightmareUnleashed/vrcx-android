package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.FavoriteGroup
import com.vrcx.android.data.api.model.Group
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
import com.vrcx.android.data.db.dao.memosByUserId
import com.vrcx.android.data.db.dao.save
import com.vrcx.android.data.db.dao.saveMemo
import com.vrcx.android.data.util.captureFailure
import com.vrcx.android.data.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/** Owns the remote/local policy for one user-profile feature. */
@Singleton
class UserDetailRepository @Inject constructor(
    private val userRepository: UserRepository,
    private val avatarRepository: AvatarRepository,
    private val favoriteApi: FavoriteApi,
    private val groupRepository: GroupRepository,
    private val worldApi: WorldApi,
    private val favoriteRepository: FavoriteRepository,
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
        if (currentUserId() != accountId) throw AccountChangedException()
        val ownerId = accountId ?: return UserDetailProfile(user = user)

        var note: String? = null
        var memo: String? = null
        var notifyEnabled = false
        val localDataError = captureFailure {
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
        }?.message
        if (currentUserId() != ownerId) throw AccountChangedException()

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

    suspend fun loadGroups(userId: String): List<Group> = groupRepository.getUserGroups(userId)

    suspend fun loadWorlds(userId: String): List<World> = BulkPaginator.fetchAll(
        pageSize = PROFILE_PAGE_SIZE,
    ) { offset, count ->
        worldApi.getWorlds(n = count, offset = offset, user = userId)
    }

    suspend fun loadAvatars(userId: String): List<Avatar> = avatarRepository.getUserAvatars(userId)

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

        // Bounded: OkHttp caps at 5 requests per host app-wide, so an unbounded
        // fan-out here queues rather than parallelises and can starve the
        // profile's other tab loads.
        val gate = Semaphore(FAVORITE_GROUP_CONCURRENCY)
        val results = supervisorScope {
            groups.map { group ->
                async {
                    runCatchingCancellable {
                        FavoriteWorldSection(
                            tag = group.name,
                            displayName = group.displayName.ifBlank { group.name },
                            visibility = group.visibility,
                            worlds = gate.withPermit {
                                BulkPaginator.fetchAll(pageSize = PROFILE_PAGE_SIZE) { offset, count ->
                                    favoriteApi.getFavoriteWorlds(
                                        n = count,
                                        offset = offset,
                                        tag = group.name,
                                        ownerId = userId,
                                    )
                                }
                            },
                        )
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

    /** Every memo [ownerId] has saved, keyed by the user each one is about. */
    suspend fun loadMemos(ownerId: String): Map<String, String> = memoDao.memosByUserId(ownerId)

    suspend fun saveMemo(userId: String, text: String): Boolean {
        val ownerId = currentUserId() ?: return false
        memoDao.saveMemo(ownerId, userId, text, Instant.now().toString())
        return currentUserId() == ownerId
    }

    suspend fun toggleNotify(userId: String): Boolean = friendRepository.toggleFriendNotify(userId)

    private fun currentUserId(): String? =
        (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id

    private companion object {
        const val PROFILE_PAGE_SIZE = 100
        const val FAVORITE_GROUP_CONCURRENCY = 4
    }
}
