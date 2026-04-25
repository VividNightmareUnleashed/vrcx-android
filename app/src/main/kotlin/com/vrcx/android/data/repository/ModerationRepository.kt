package com.vrcx.android.data.repository

import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.api.model.PlayerModerationRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModerationRepository @Inject constructor(
    private val playerModerationApi: PlayerModerationApi,
) {
    private val _moderations = MutableStateFlow<List<PlayerModeration>>(emptyList())
    val moderations: StateFlow<List<PlayerModeration>> = _moderations.asStateFlow()

    suspend fun loadModerations() {
        _moderations.value = playerModerationApi.getPlayerModerations()
    }

    fun clearRuntimeState() {
        _moderations.value = emptyList()
    }

    suspend fun blockUser(userId: String) {
        playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "block"))
        loadModerations()
    }

    suspend fun muteUser(userId: String) {
        playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "mute"))
        loadModerations()
    }

    suspend fun deleteModeration(moderationId: String) {
        playerModerationApi.deletePlayerModeration(moderationId)
        _moderations.value = _moderations.value.filter { it.id != moderationId }
    }

    suspend fun interactOn(userId: String) {
        playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "interactOn"))
        loadModerations()
    }

    suspend fun interactOff(userId: String) {
        playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "interactOff"))
        loadModerations()
    }

    suspend fun showAvatar(userId: String) {
        playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "showAvatar"))
        loadModerations()
    }

    suspend fun hideAvatar(userId: String) {
        playerModerationApi.sendPlayerModeration(PlayerModerationRequest(userId, "hideAvatar"))
        loadModerations()
    }
}
