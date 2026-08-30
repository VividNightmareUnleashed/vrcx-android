package com.vrcx.android.data.repository

import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Fetches and normalizes one complete REST snapshot without publishing account state. */
internal class FriendSnapshotLoader @Inject constructor(private val friendApi: FriendApi) {
    suspend fun load(validateAccount: () -> Unit): Map<String, FriendContext> {
        val (onlineFriends, offlineFriends) = coroutineScope {
            val online = async { fetchFriends(offline = false, validateAccount = validateAccount) }
            val offline = async { fetchFriends(offline = true, validateAccount = validateAccount) }
            online.await() to offline.await()
        }

        return buildMap {
            onlineFriends.forEach { user ->
                val state = if (user.location.isNullOrEmpty() || user.location == "offline") {
                    FriendState.ACTIVE
                } else {
                    FriendState.ONLINE
                }
                put(user.id, user.toFriendContext(state))
            }
            offlineFriends.forEach { user ->
                putIfAbsent(user.id, user.toFriendContext(FriendState.OFFLINE))
            }
        }
    }

    // These endpoints can return short pages before the final page.
    private suspend fun fetchFriends(offline: Boolean, validateAccount: () -> Unit): List<VrcUser> =
        BulkPaginator.fetchAll(pageSize = PAGE_SIZE, stopOnShortPage = false) { offset, count ->
            validateAccount()
            friendApi.getFriends(n = count, offset = offset, offline = offline)
        }

    private fun VrcUser.toFriendContext(state: FriendState): FriendContext = FriendContext(
        id = id,
        name = displayName,
        state = state,
        ref = this,
    )

    private companion object {
        const val PAGE_SIZE = 100
    }
}
