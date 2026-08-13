package com.vrcx.android.data.repository

import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.model.PlayerModeration
import com.vrcx.android.data.api.model.PlayerModerationRequest
import com.vrcx.android.data.api.model.UnPlayerModerationRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModerationRepository @Inject constructor(
    private val playerModerationApi: PlayerModerationApi,
    accountScope: AccountScope,
) : AccountScoped {
    private val account = accountScope.bindTo(this)

    private val _moderations = MutableStateFlow<List<PlayerModeration>>(emptyList())
    val moderations: StateFlow<List<PlayerModeration>> = _moderations.asStateFlow()

    suspend fun loadModerations() {
        val token = account.current()
        val moderations = playerModerationApi.getPlayerModerations()
        account.publishIfCurrent(token) { _moderations.value = moderations }
    }

    override fun clearRuntimeState() {
        _moderations.value = emptyList()
    }

    /**
     * Applies a moderation and publishes the created row, so screens observing
     * [moderations] see it. Callers must come through here rather than hitting
     * PlayerModerationApi directly — the profile screen bypassing this left the
     * Moderation screen showing stale rows after a block.
     *
     * The POST returns the created moderation, so this publishes that rather
     * than re-fetching the whole list, mirroring [deleteModeration].
     */
    suspend fun moderate(userId: String, apiValue: String) {
        val token = account.current()
        val created = playerModerationApi.sendPlayerModeration(
            PlayerModerationRequest(userId, apiValue)
        )
        account.publishIfCurrent(token) {
            _moderations.value = _moderations.value.filterNot { it.id == created.id } + created
        }
    }

    suspend fun deleteModeration(moderation: PlayerModeration) {
        val token = account.current()
        playerModerationApi.unmoderatePlayer(
            UnPlayerModerationRequest(
                moderated = moderation.targetUserId,
                type = moderation.type,
            )
        )
        account.publishIfCurrent(token) {
            _moderations.value = _moderations.value.filter { it.id != moderation.id }
        }
    }
}
