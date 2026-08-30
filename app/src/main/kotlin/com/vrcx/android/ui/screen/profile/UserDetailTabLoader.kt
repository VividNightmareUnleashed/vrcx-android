package com.vrcx.android.ui.screen.profile

import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.repository.FavoriteWorldLoadResult
import com.vrcx.android.data.repository.UserDetailRepository

internal sealed interface UserDetailTabResult {
    data object Info : UserDetailTabResult

    data class Mutuals(val users: List<VrcUser>) : UserDetailTabResult

    data class Groups(val groups: List<Group>) : UserDetailTabResult

    data class Worlds(val worlds: List<World>) : UserDetailTabResult

    data class Avatars(val avatars: List<Avatar>) : UserDetailTabResult

    data class FavoriteWorlds(val result: FavoriteWorldLoadResult) : UserDetailTabResult
}

internal class UserDetailTabLoader(private val userId: String, private val repository: UserDetailRepository) {
    suspend fun load(tab: UserDetailTab): UserDetailTabResult = when (tab) {
        UserDetailTab.INFO -> UserDetailTabResult.Info
        UserDetailTab.MUTUALS -> UserDetailTabResult.Mutuals(repository.loadMutualFriends(userId))
        UserDetailTab.GROUPS -> UserDetailTabResult.Groups(repository.loadGroups(userId))
        UserDetailTab.WORLDS -> UserDetailTabResult.Worlds(repository.loadWorlds(userId))
        UserDetailTab.AVATARS -> UserDetailTabResult.Avatars(repository.loadAvatars(userId))
        UserDetailTab.FAVORITE_WORLDS -> UserDetailTabResult.FavoriteWorlds(repository.loadFavoriteWorlds(userId))
    }
}

internal fun UserDetailTabResult.applyTo(state: UserDetailUiState): UserDetailUiState = when (this) {
    UserDetailTabResult.Info -> state

    is UserDetailTabResult.Mutuals -> state.copy(mutualFriends = users)

    is UserDetailTabResult.Groups -> state.copy(userGroups = groups)

    is UserDetailTabResult.Worlds -> state.copy(userWorlds = worlds)

    is UserDetailTabResult.Avatars -> state.copy(userAvatars = avatars)

    is UserDetailTabResult.FavoriteWorlds -> {
        val selectedTag = state.selectedFavoriteWorldTag?.takeIf { selected ->
            result.sections.any { it.tag == selected }
        } ?: result.sections.firstOrNull()?.tag
        state.copy(
            favoriteWorldSections = result.sections,
            selectedFavoriteWorldTag = selectedTag,
            message = result.warning ?: state.message,
        )
    }
}
