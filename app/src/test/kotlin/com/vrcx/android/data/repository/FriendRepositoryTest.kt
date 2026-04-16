package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendLogDao
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FriendRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildRepository(): Pair<FriendRepository, UserRepository> {
        val friendApi = mock<FriendApi>()
        val authRepository = mock<AuthRepository>()
        val userRepository = mock<UserRepository>()
        val feedRepository = mock<FeedRepository>()
        val favoriteRepository = mock<FavoriteRepository>().also {
            whenever(it.favorites).thenReturn(MutableStateFlow(emptyList()))
        }
        val friendLogDao = mock<FriendLogDao>()
        val friendNotifyDao = mock<FriendNotifyDao>()

        val repo = FriendRepository(
            friendApi = friendApi,
            authRepository = authRepository,
            userRepository = userRepository,
            feedRepository = feedRepository,
            favoriteRepository = favoriteRepository,
            friendLogDao = friendLogDao,
            friendNotifyDao = friendNotifyDao,
            json = json,
        )
        repo.ownerUserId = "usr_owner"
        return repo to userRepository
    }

    @Test
    fun `resolveFriendUserId accepts both userId and userid keys`() {
        val (repo, _) = buildRepository()
        val withCamel = buildJsonObject { put("userId", "usr_camel") }
        val withLower = buildJsonObject { put("userid", "usr_lower") }
        val withNeither = buildJsonObject { put("displayName", "no id") }

        assertEquals("usr_camel", repo.resolveFriendUserId(withCamel))
        assertEquals("usr_lower", repo.resolveFriendUserId(withLower))
        assertNull(repo.resolveFriendUserId(withNeither))
    }

    @Test
    fun `resolveFriendUserId prefers camelCase when both keys exist`() {
        val (repo, _) = buildRepository()
        val both = buildJsonObject {
            put("userId", "usr_camel")
            put("userid", "usr_lower")
        }
        assertEquals("usr_camel", repo.resolveFriendUserId(both))
    }

    @Test
    fun `handleFriendOffline caches the embedded content user payload`() = runBlocking {
        val (repo, userRepository) = buildRepository()
        val payload = buildJsonObject {
            put("userId", "usr_target")
            put(
                "user",
                buildJsonObject {
                    put("id", "usr_target")
                    put("displayName", "Updated Name")
                    put("currentAvatarImageUrl", "")
                    put("currentAvatarThumbnailImageUrl", "")
                    put("status", "offline")
                    put("statusDescription", "")
                    put("bio", "")
                    put("location", "offline")
                },
            )
        }
        val event = PipelineEvent.FriendOffline(content = payload)

        repo.handleEvent(event)

        // The cached user from the offline payload should be propagated to UserRepository,
        // matching the behavior of FriendOnline / FriendActive / FriendUpdate.
        val captor = argumentCaptor<VrcUser>()
        verify(userRepository).cacheUser(captor.capture())
        assertEquals("usr_target", captor.firstValue.id)
        assertEquals("Updated Name", captor.firstValue.displayName)

        val ctx: FriendContext? = repo.friends.value["usr_target"]
        assertNotNull(ctx)
        assertEquals("Updated Name", ctx!!.name)
        // Friend is in pending-offline state until the 5s delay completes.
        assertEquals(true, ctx.pendingOffline)
        assertEquals(FriendState.OFFLINE, ctx.state)
    }

    @Test
    fun `handleFriendOnline accepts lowercase userid payload`() = runBlocking {
        val (repo, _) = buildRepository()
        val payload = buildJsonObject {
            put("userid", "usr_lower")
            put("location", "wrld_xyz:1234")
        }
        val event = PipelineEvent.FriendOnline(content = payload)

        repo.handleEvent(event)

        val ctx = repo.friends.value["usr_lower"]
        assertNotNull(ctx)
        assertEquals(FriendState.ONLINE, ctx!!.state)
    }
}
