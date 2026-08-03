package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.websocket.PipelineEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FriendRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private data class Fixture(
        val repository: FriendRepository,
        val friendApi: FriendApi,
        val authRepository: AuthRepository,
        val userRepository: UserRepository,
        val friendLogSynchronizer: FriendLogSynchronizer,
    )

    private fun buildRepository(): Fixture {
        val friendApi = mock<FriendApi>()
        val authRepository = mock<AuthRepository>()
        val userRepository = mock<UserRepository>()
        val favoriteRepository = mock<FavoriteRepository>().also {
            whenever(it.favorites).thenReturn(MutableStateFlow(emptyList()))
        }
        val friendNotifyDao = mock<FriendNotifyDao>()
        val friendLogSynchronizer = mock<FriendLogSynchronizer>()
        val activityRecorder = mock<FriendActivityRecorder>()

        val repo = FriendRepository(
            friendApi = friendApi,
            authRepository = authRepository,
            userRepository = userRepository,
            favoriteRepository = favoriteRepository,
            friendNotifyDao = friendNotifyDao,
            friendLogSynchronizer = friendLogSynchronizer,
            activityRecorder = activityRecorder,
            json = json,
        )
        repo.ownerUserId = "usr_owner"
        return Fixture(repo, friendApi, authRepository, userRepository, friendLogSynchronizer)
    }

    @Test
    fun `resolveFriendUserId prefers camelCase and rejects missing ids`() {
        val repo = buildRepository().repository
        val both = buildJsonObject {
            put("userId", "usr_camel")
            put("userid", "usr_lower")
        }
        assertEquals("usr_camel", repo.resolveFriendUserId(both))
        assertNull(repo.resolveFriendUserId(buildJsonObject { put("displayName", "no id") }))
    }

    @Test
    fun `handleFriendOffline caches the embedded content user payload`() = runBlocking {
        val fixture = buildRepository()
        val repo = fixture.repository
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
        verify(fixture.userRepository).cacheUser(captor.capture())
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
        val repo = buildRepository().repository
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

    @Test
    fun `concurrent loads coalesce even when the first waiter is cancelled`() = runBlocking {
        val fixture = buildRepository()
        val requestCount = AtomicInteger()
        val bothRequestsStarted = CompletableDeferred<Unit>()
        val releaseRequests = CompletableDeferred<Unit>()
        whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer {
                if (requestCount.incrementAndGet() == 2) bothRequestsStarted.complete(Unit)
                releaseRequests.await()
                emptyList()
            }

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.loadFriendsList()
        }
        bothRequestsStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.loadFriendsList()
        }

        first.cancelAndJoin()
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.loadFriendsList()
        }

        assertEquals(2, requestCount.get())
        releaseRequests.complete(Unit)
        second.await()
        third.await()

        verify(fixture.friendApi, times(1)).getFriends(n = 100, offset = 0, offline = false)
        verify(fixture.friendApi, times(1)).getFriends(n = 100, offset = 0, offline = true)
        Unit
    }

    @Test
    fun `late load from previous account cannot replace current account friends`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            var currentUser = CurrentUser(id = "usr_old")
            whenever(fixture.authRepository.currentUser).thenAnswer { currentUser }
            fixture.repository.ownerUserId = "usr_old"

            val oldRequestsStarted = CompletableDeferred<Unit>()
            val releaseOldRequests = CompletableDeferred<Unit>()
            val oldRequestCount = AtomicInteger()
            val onlineCalls = AtomicInteger()
            val offlineCalls = AtomicInteger()

            whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
                .doSuspendableAnswer { invocation ->
                    if (invocation.getArgument<Boolean>(2)) {
                        if (offlineCalls.incrementAndGet() == 1) {
                            if (oldRequestCount.incrementAndGet() == 2) oldRequestsStarted.complete(Unit)
                            releaseOldRequests.await()
                        }
                        emptyList()
                    } else if (onlineCalls.incrementAndGet() == 1) {
                        if (oldRequestCount.incrementAndGet() == 2) oldRequestsStarted.complete(Unit)
                        releaseOldRequests.await()
                        listOf(VrcUser(id = "usr_old_friend", displayName = "Old Friend", location = "wrld_old:1"))
                    } else {
                        listOf(VrcUser(id = "usr_new_friend", displayName = "New Friend", location = "wrld_new:1"))
                    }
                }

            val oldLoad = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.loadFriendsList()
            }
            oldRequestsStarted.await()

            currentUser = CurrentUser(id = "usr_new")
            fixture.repository.clearRuntimeState()
            fixture.repository.loadFriendsList()
            assertEquals(setOf("usr_new_friend"), fixture.repository.friends.value.keys)

            releaseOldRequests.complete(Unit)
            oldLoad.await()

            assertEquals("usr_new", fixture.repository.ownerUserId)
            assertEquals(setOf("usr_new_friend"), fixture.repository.friends.value.keys)
            val cachedUsers = argumentCaptor<Iterable<VrcUser>>()
            verify(fixture.userRepository, times(1)).cacheUsers(cachedUsers.capture())
            assertEquals(setOf("usr_new_friend"), cachedUsers.firstValue.map { it.id }.toSet())
            verify(fixture.friendLogSynchronizer, never()).synchronize(eq("usr_old"), any())
            verify(fixture.friendLogSynchronizer, times(1)).synchronize(eq("usr_new"), any())
        }
    }
}
