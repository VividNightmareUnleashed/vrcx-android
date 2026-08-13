package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.websocket.PipelineEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
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
        val accountScope: AccountScope,
        val userRepository: UserRepository,
        val friendLogSynchronizer: FriendLogSynchronizer,
        val activityRecorder: FriendActivityRecorder,
    )

    private fun buildRepository(
        favorites: MutableStateFlow<List<Favorite>> = MutableStateFlow(emptyList()),
        friendNotifyDao: FriendNotifyDao = mock(),
    ): Fixture {
        val friendApi = mock<FriendApi>()
        val accountScope = AccountScope()
        val userRepository = mock<UserRepository>()
        val favoriteRepository = mock<FavoriteRepository>().also {
            whenever(it.favorites).thenReturn(favorites)
        }
        val friendLogSynchronizer = mock<FriendLogSynchronizer>()
        val activityRecorder = mock<FriendActivityRecorder>()

        val repo = FriendRepository(
            friendApi = friendApi,
            userRepository = userRepository,
            favoriteRepository = favoriteRepository,
            friendNotifyDao = friendNotifyDao,
            friendLogSynchronizer = friendLogSynchronizer,
            activityRecorder = activityRecorder,
            json = json,
            accountScope = accountScope,
        )
        accountScope.bind("usr_owner")
        return Fixture(
            repo,
            friendApi,
            accountScope,
            userRepository,
            friendLogSynchronizer,
            activityRecorder,
        )
    }

    private fun userPayload(id: String, displayName: String, location: String? = null) = buildJsonObject {
        put("id", id)
        put("displayName", displayName)
        put("currentAvatarImageUrl", "")
        put("currentAvatarThumbnailImageUrl", "")
        put("status", "active")
        put("statusDescription", "")
        put("bio", "")
        if (location != null) put("location", location)
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
        repo.handleEvent(
            PipelineEvent.FriendOnline(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", "wrld_a:1")
                    put("user", userPayload("usr_target", "Target"))
                },
            ),
        )
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
        verify(fixture.userRepository, times(2)).cacheUser(captor.capture())
        assertEquals("usr_target", captor.lastValue.id)
        assertEquals("Updated Name", captor.lastValue.displayName)

        val ctx: FriendContext? = repo.friends.value["usr_target"]
        assertNotNull(ctx)
        assertEquals("Updated Name", ctx!!.name)
        // The friend stays online until the confirmation delay elapses.
        assertEquals(setOf("usr_target"), repo.pendingOfflineIds.toSet())
        assertEquals(FriendState.ONLINE, ctx.state)
    }

    @Test
    fun `a confirmed friend-offline flips the state and reports the transition`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            val repo = fixture.repository
            repo.offlineDelayMs = 0
            repo.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_target")
                        put("location", "wrld_a:1")
                        put("user", userPayload("usr_target", "Target"))
                    },
                ),
            )

            val cameOffline = CompletableDeferred<FriendTransition.CameOffline>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repo.friendTransitions.collect {
                    if (it is FriendTransition.CameOffline) cameOffline.complete(it)
                }
            }

            repo.handleEvent(PipelineEvent.FriendOffline(buildJsonObject { put("userId", "usr_target") }))

            assertEquals("Target", cameOffline.await().displayName)
            collector.cancelAndJoin()

            val ctx = repo.friends.value.getValue("usr_target")
            assertEquals(FriendState.OFFLINE, ctx.state)
            assertEquals(emptySet<String>(), fixture.repository.pendingOfflineIds.toSet())
            verify(fixture.activityRecorder)
                .recordOnlineOffline("usr_owner", "usr_target", "Target", "offline", "")
        }
    }

    @Test
    fun `friend-update keeps the presence the location handler resolved`() = runBlocking {
        val fixture = buildRepository()
        val repo = fixture.repository
        repo.handleEvent(
            PipelineEvent.FriendLocation(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", "wrld_here:42")
                    put("worldName", "Here")
                    put("user", userPayload("usr_target", "Target"))
                },
            ),
        )
        assertEquals(FriendState.ONLINE, repo.friends.value.getValue("usr_target").state)

        // friend-update fires for bio / status / avatar edits and its user object
        // reports no usable location.
        repo.handleEvent(
            PipelineEvent.FriendUpdate(
                buildJsonObject {
                    put("userId", "usr_target")
                    put(
                        "user",
                        buildJsonObject {
                            put("id", "usr_target")
                            put("displayName", "Target")
                            put("currentAvatarImageUrl", "")
                            put("currentAvatarThumbnailImageUrl", "")
                            put("status", "join me")
                            put("statusDescription", "")
                            put("bio", "")
                            put("location", "offline")
                        },
                    )
                },
            ),
        )

        val ctx = repo.friends.value.getValue("usr_target")
        assertEquals(FriendState.ONLINE, ctx.state)
        assertEquals("wrld_here:42", ctx.ref?.location)
        assertEquals("42", ctx.ref?.instanceId)
        // The profile fields the event exists to deliver still land.
        assertEquals("join me", ctx.ref?.status)
    }

    @Test
    fun `a traveling location is not a destination`() = runBlocking {
        val fixture = buildRepository()
        fixture.repository.handleEvent(
            PipelineEvent.FriendLocation(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", "traveling")
                    put("travelingToLocation", "wrld_dest:7")
                    put("user", userPayload("usr_target", "Target"))
                },
            ),
        )

        verify(fixture.activityRecorder, never())
            .recordGps(any(), any(), any(), any(), any(), any())
        verify(fixture.activityRecorder).markFilteredTransition("usr_target")
    }

    @Test
    fun `pipeline payloads of the wrong shape degrade instead of throwing`() = runBlocking {
        val fixture = buildRepository()
        val repo = fixture.repository
        val malformed = listOf(
            PipelineEvent.FriendOnline(JsonNull),
            PipelineEvent.FriendOnline(buildJsonArray { add(JsonPrimitive("nope")) }),
            PipelineEvent.FriendUpdate(buildJsonObject { put("userId", JsonNull) }),
            PipelineEvent.FriendDelete(
                buildJsonObject { put("userId", buildJsonObject { put("id", "usr_target") }) },
            ),
            PipelineEvent.FriendLocation(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", buildJsonArray { })
                    put("world", JsonPrimitive("A World"))
                },
            ),
        )

        malformed.forEach { repo.handleEvent(it) }

        // Only the last frame carries a usable id, and its location came back as
        // the wrong shape, so it must not be mistaken for a real destination.
        assertEquals(setOf("usr_target"), repo.friends.value.keys)
        verify(fixture.activityRecorder, never())
            .recordGps(any(), any(), any(), any(), any(), any())
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
    fun `the favourite and notify id sets are published for screens to read`() = runBlocking {
        val favorites = MutableStateFlow(emptyList<Favorite>())
        val friendNotifyDao = mock<FriendNotifyDao>()
        val fixture = buildRepository(favorites = favorites, friendNotifyDao = friendNotifyDao)
        val repo = fixture.repository
        repo.handleEvent(
            PipelineEvent.FriendOnline(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", "wrld_a:1")
                },
            ),
        )

        favorites.value = listOf(Favorite(id = "fav_1", favoriteId = "usr_target", type = "friend"))
        withTimeout(10_000) {
            while (repo.favoriteFriendIds.value.isEmpty()) delay(5)
        }

        // The sets the screens need. Stamping the same fact onto every entry of
        // the friend map would republish the whole map on each favourite change.
        assertEquals(setOf("usr_target"), repo.favoriteFriendIds.value)

        whenever(friendNotifyDao.get("usr_owner:usr_target")).thenReturn(null)
        assertEquals(true, repo.toggleFriendNotify("usr_target"))
        assertEquals(setOf("usr_target"), repo.notifyEnabledIds.value)
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
    fun `pipeline frames during the sweep do not discard the fetched friend list`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            val onlineSweepStarted = CompletableDeferred<Unit>()
            val releaseOnlineSweep = CompletableDeferred<Unit>()
            whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
                .doSuspendableAnswer { invocation ->
                    val offset = invocation.getArgument<Int>(1)
                    val offline = invocation.getArgument<Boolean>(2)
                    when {
                        offset > 0 -> emptyList()
                        offline -> listOf(VrcUser(id = "usr_moving", displayName = "Moving Friend"))
                        else -> {
                            onlineSweepStarted.complete(Unit)
                            releaseOnlineSweep.await()
                            listOf(
                                VrcUser(
                                    id = "usr_fetched",
                                    displayName = "Fetched Friend",
                                    location = "wrld_a:1",
                                ),
                            )
                        }
                    }
                }

            val load = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.loadFriendsList()
            }
            onlineSweepStarted.await()

            // One frame for a friend the sweep is about to report as offline,
            // one for a friend it never sees at all.
            fixture.repository.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_moving")
                        put("location", "wrld_b:2")
                    },
                ),
            )
            fixture.repository.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_live")
                        put("location", "wrld_c:3")
                    },
                ),
            )

            releaseOnlineSweep.complete(Unit)
            load.await()

            val friends = fixture.repository.friends.value
            assertEquals(setOf("usr_fetched", "usr_moving", "usr_live"), friends.keys)
            // The frame wins for the friend it touched...
            assertEquals(FriendState.ONLINE, friends.getValue("usr_moving").state)
            // ...without costing the rest of the fetched list, the friend-log
            // sync, or the follow-on loads.
            assertEquals("Fetched Friend", friends.getValue("usr_fetched").name)
            verify(fixture.friendLogSynchronizer).synchronize(eq("usr_owner"), any())
        }
    }

    @Test
    fun `clearRuntimeState un-publishes the friend map before it returns`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            fixture.repository.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_old_friend")
                        put("location", "wrld_a:1")
                    },
                ),
            )
            assertTrue(fixture.repository.friends.value.isNotEmpty())

            // Block the load inside its commit so the map lock is held while the
            // account changes underneath it.
            val commitReached = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            doAnswer {
                commitReached.countDown()
                releaseCommit.await()
                null
            }.whenever(fixture.userRepository).cacheUsers(any())
            whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
                .doSuspendableAnswer { invocation ->
                    if (invocation.getArgument<Int>(1) > 0) {
                        emptyList()
                    } else {
                        listOf(VrcUser(id = "usr_old_friend", displayName = "Old Friend"))
                    }
                }

            val load = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.loadFriendsList()
            }
            commitReached.await()

            fixture.accountScope.invalidate()
            assertTrue(fixture.repository.friends.value.isEmpty())

            releaseCommit.countDown()
            load.await()
        }
    }

    @Test
    fun `late load from previous account cannot replace current account friends`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            fixture.accountScope.bind("usr_old")

            val oldRequestsStarted = CompletableDeferred<Unit>()
            val releaseOldRequests = CompletableDeferred<Unit>()
            val oldRequestCount = AtomicInteger()
            val onlineCalls = AtomicInteger()
            val offlineCalls = AtomicInteger()

            whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
                .doSuspendableAnswer { invocation ->
                    if (invocation.getArgument<Int>(1) > 0) {
                        emptyList()
                    } else if (invocation.getArgument<Boolean>(2)) {
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

            fixture.accountScope.invalidate()
            fixture.accountScope.bind("usr_new")
            fixture.repository.loadFriendsList()
            assertEquals(setOf("usr_new_friend"), fixture.repository.friends.value.keys)

            releaseOldRequests.complete(Unit)
            oldLoad.await()

            assertEquals("usr_new", fixture.accountScope.ownerUserId)
            assertEquals(setOf("usr_new_friend"), fixture.repository.friends.value.keys)
            val cachedUsers = argumentCaptor<Iterable<VrcUser>>()
            verify(fixture.userRepository, times(1)).cacheUsers(cachedUsers.capture())
            assertEquals(setOf("usr_new_friend"), cachedUsers.firstValue.map { it.id }.toSet())
            verify(fixture.friendLogSynchronizer, never()).synchronize(eq("usr_old"), any())
            verify(fixture.friendLogSynchronizer, times(1)).synchronize(eq("usr_new"), any())
        }
    }
}
