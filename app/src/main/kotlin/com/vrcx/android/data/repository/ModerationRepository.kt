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
) {
    private val stateLock = Any()
    private var accountGeneration = 0L

    private val _moderations = MutableStateFlow<List<PlayerModeration>>(emptyList())
    val moderations: StateFlow<List<PlayerModeration>> = _moderations.asStateFlow()

    suspend fun loadModerations() {
        val generation = currentGeneration()
        val moderations = playerModerationApi.getPlayerModerations()
        publishIfCurrent(generation) { _moderations.value = moderations }
    }

    fun clearRuntimeState() {
        synchronized(stateLock) {
            accountGeneration++
            _moderations.value = emptyList()
        }
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
        val generation = currentGeneration()
        val created = playerModerationApi.sendPlayerModeration(
            PlayerModerationRequest(userId, apiValue)
        )
        publishIfCurrent(generation) {
            _moderations.value = _moderations.value.filterNot { it.id == created.id } + created
        }
    }

    suspend fun deleteModeration(moderation: PlayerModeration) {
        val generation = currentGeneration()
        playerModerationApi.unmoderatePlayer(
            UnPlayerModerationRequest(
                moderated = moderation.targetUserId,
                type = moderation.type,
            )
        )
        publishIfCurrent(generation) {
            _moderations.value = _moderations.value.filter { it.id != moderation.id }
        }
    }

    private fun currentGeneration(): Long = synchronized(stateLock) { accountGeneration }

    private inline fun publishIfCurrent(generation: Long, publish: () -> Unit) {
        synchronized(stateLock) {
            if (generation == accountGeneration) publish()
        }
    }
}
