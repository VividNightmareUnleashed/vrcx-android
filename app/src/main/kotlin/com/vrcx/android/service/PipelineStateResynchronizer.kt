package com.vrcx.android.service

import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.FriendRepository
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.repository.NotificationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope

/** Reconciles every repository whose authoritative state can outlive a pipeline gap. */
@Singleton
class PipelineStateResynchronizer @Inject constructor(
    private val authRepository: AuthRepository,
    private val friendRepository: FriendRepository,
    private val notificationRepository: NotificationRepository,
    private val groupRepository: GroupRepository,
    private val galleryRepository: GalleryRepository,
) {
    suspend fun resynchronizeCore(origin: AccountScope.Token): Boolean {
        val sessionRecovered = containPipelineFailure("session resync") {
            authRepository.resynchronizePipelineState(origin)
            authRepository.pipelineSession()?.account == origin
        } == true
        if (!sessionRecovered) return false

        return supervisorScope {
            listOf(
                async {
                    containPipelineFailure("friend resync") {
                        friendRepository.resynchronize(origin)
                    } != null
                },
                async {
                    containPipelineFailure("notification resync") {
                        notificationRepository.resynchronize(origin)
                    } != null
                },
                async {
                    containPipelineFailure("group resync") {
                        groupRepository.loadMyGroups(origin)
                    } != null
                },
            ).awaitAll().all { it }
        }
    }

    suspend fun resynchronizeGallery(origin: AccountScope.Token): Boolean = containPipelineFailure("gallery resync") {
        galleryRepository.resynchronize(origin)
    } != null
}

/** Retries core snapshots with bounded waits while the owning pipeline is current. */
internal suspend fun retryPipelineStateRecovery(
    isCurrent: () -> Boolean,
    recoverCore: suspend () -> Boolean,
    delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    var attempt = 0
    while (isCurrent()) {
        val recovered = recoverCore()
        val stillCurrent = isCurrent()
        if (recovered || !stillCurrent) return recovered && stillCurrent
        attempt++
        delayBeforeRetry(pipelineRecoveryRetryDelayMs(attempt))
    }
    return false
}

internal fun pipelineRecoveryRetryDelayMs(attempt: Int): Long {
    val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(PIPELINE_RECOVERY_MAX_EXPONENT)
    return minOf(PIPELINE_RECOVERY_BASE_DELAY_MS shl exponent, PIPELINE_RECOVERY_MAX_DELAY_MS)
}

private const val PIPELINE_RECOVERY_BASE_DELAY_MS = 1_000L
private const val PIPELINE_RECOVERY_MAX_DELAY_MS = 30_000L
private const val PIPELINE_RECOVERY_MAX_EXPONENT = 5
