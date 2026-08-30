package com.vrcx.android.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/** Owns writes to persisted preferences shown on a user's profile. */
@Singleton
class ProfilePreferenceActions @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val localProfileRepository: LocalUserProfileRepository,
) {
    suspend fun addFriendFavorite(userId: String) {
        favoriteRepository.addFavorite("friend", userId)
    }

    suspend fun deleteFavorite(favoriteId: String) {
        favoriteRepository.deleteFavorite(favoriteId)
    }

    suspend fun toggleNotify(userId: String): Boolean = friendRepository.toggleFriendNotify(userId)

    suspend fun saveNote(userId: String, displayName: String, text: String): Boolean {
        val ownerId = currentUserId() ?: return false
        userRepository.saveUserNote(userId, text)
        return if (currentUserId() == ownerId) {
            localProfileRepository.saveNote(ownerId, userId, displayName, text)
            true
        } else {
            false
        }
    }

    suspend fun saveMemo(userId: String, text: String): Boolean {
        val ownerId = currentUserId() ?: return false
        localProfileRepository.saveMemo(ownerId, userId, text)
        return currentUserId() == ownerId
    }

    private fun currentUserId(): String? = (authRepository.authState.value as? AuthState.LoggedIn)?.user?.id
}
