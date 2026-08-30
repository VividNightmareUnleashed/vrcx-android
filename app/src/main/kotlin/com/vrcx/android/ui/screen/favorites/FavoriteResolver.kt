package com.vrcx.android.ui.screen.favorites

import com.vrcx.android.data.api.model.Avatar
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.World
import com.vrcx.android.data.api.model.displayAvatarUrl
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.repository.UserRepository
import com.vrcx.android.data.util.runCatchingCancellable

internal data class FavoriteResolutionInputs(
    val favorites: List<Favorite>,
    val worlds: List<World>,
    val avatars: List<Avatar>,
    val friends: Map<String, FriendContext>,
)

internal class FavoriteResolver(private val userRepository: UserRepository) {
    suspend fun resolve(inputs: FavoriteResolutionInputs): List<ResolvedFavorite> {
        val worldsById = inputs.worlds.associateBy { it.id }
        val avatarsById = inputs.avatars.associateBy { it.id }
        return inputs.favorites.map { favorite ->
            runCatchingCancellable {
                when (favorite.type) {
                    "friend" -> resolveFriend(favorite, inputs.friends[favorite.favoriteId])

                    "world", "vrcPlusWorld" ->
                        resolveWorld(favorite, worldsById[favorite.favoriteId])

                    "avatar" -> resolveAvatar(favorite, avatarsById[favorite.favoriteId])

                    else -> favorite.asUnresolved()
                }
            }.getOrElse { favorite.asUnresolved() }
        }
    }

    private suspend fun resolveFriend(favorite: Favorite, friend: FriendContext?): ResolvedFavorite {
        if (friend != null) {
            return ResolvedFavorite(
                favorite = favorite,
                name = friend.name,
                thumbnailUrl = friend.ref?.displayAvatarUrl().orEmpty(),
                subtitle = friend.ref?.statusDescription.orEmpty(),
                groupTags = favorite.tags,
                friendState = friend.state,
                friendStatus = friend.ref?.status,
            )
        }
        val user = userRepository.getUser(favorite.favoriteId)
        return ResolvedFavorite(
            favorite = favorite,
            name = user.displayName,
            thumbnailUrl = user.displayAvatarUrl(),
            subtitle = user.statusDescription,
            groupTags = favorite.tags,
        )
    }

    private fun resolveWorld(favorite: Favorite, world: World?): ResolvedFavorite = world?.let {
        ResolvedFavorite(
            favorite = favorite,
            name = it.name,
            thumbnailUrl = it.thumbnailImageUrl,
            subtitle = it.authorName,
            groupTags = favorite.tags,
        )
    } ?: favorite.asUnresolved()

    private fun resolveAvatar(favorite: Favorite, avatar: Avatar?): ResolvedFavorite = avatar?.let {
        ResolvedFavorite(
            favorite = favorite,
            name = it.name,
            thumbnailUrl = it.thumbnailImageUrl,
            subtitle = "by ${it.authorName}",
            groupTags = favorite.tags,
        )
    } ?: favorite.asUnresolved()

    private fun Favorite.asUnresolved() = ResolvedFavorite(
        favorite = this,
        name = favoriteId,
        groupTags = tags,
    )
}
