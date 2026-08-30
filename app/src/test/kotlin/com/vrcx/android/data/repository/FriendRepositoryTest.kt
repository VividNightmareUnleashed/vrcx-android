package com.vrcx.android.data.repository

import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.model.Favorite
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.db.dao.FriendNotifyDao
import com.vrcx.android.data.db.entity.FriendNotifyEntity
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import com.vrcx.android.data.model.FriendTransition
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.DispatcherModule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal open class FriendRepositoryTestFixture {

    private val json = Json { ignoreUnknownKeys = true }

    protected data class Fixture(
        val repository: FriendRepository,
        val friendApi: FriendApi,
        val accountScope: AccountScope,
        val userRepository: UserRepository,
        val favoriteRepository: FavoriteRepository,
        val friendLogSynchronizer: FriendLogSynchronizer,
        val activityRecorder: FriendActivityRecorder,
    )

    protected fun buildRepository(
        favorites: MutableStateFlow<List<Favorite>> = MutableStateFlow(emptyList()),
        friendNotifyDao: FriendNotifyDao = mock(),
        ioDispatcher: CoroutineDispatcher = DispatcherModule.provideIoDispatcher(),
    ): Fixture {
        val friendApi = mock<FriendApi>()
        val accountScope = AccountScope()
        val userRepository = mock<UserRepository>()
        val favoriteRepository = mock<FavoriteRepository>().also {
            whenever(it.favorites).thenReturn(favorites)
        }
        val friendLogSynchronizer = mock<FriendLogSynchronizer>()
        val activityRecorder = mock<FriendActivityRecorder>()

        val snapshotCoordinator = FriendSnapshotCoordinator(
            snapshotLoader = FriendSnapshotLoader(friendApi),
            userRepository = userRepository,
            favoriteRepository = favoriteRepository,
            friendNotifyDao = friendNotifyDao,
            friendLogSynchronizer = friendLogSynchronizer,
            activityRecorder = activityRecorder,
        )
        val eventProcessor = FriendEventProcessor(
            userRepository = userRepository,
            friendLogSynchronizer = friendLogSynchronizer,
            activityRecorder = activityRecorder,
            json = json,
        )
        val repo = FriendRepository(
            snapshotCoordinator = snapshotCoordinator,
            eventProcessor = eventProcessor,
            favoriteRepository = favoriteRepository,
            friendNotifyDao = friendNotifyDao,
            friendLogSynchronizer = friendLogSynchronizer,
            accountScope = accountScope,
            ioDispatcher = ioDispatcher,
        )
        accountScope.bind("usr_owner")
        return Fixture(
            repo,
            friendApi,
            accountScope,
            userRepository,
            favoriteRepository,
            friendLogSynchronizer,
            activityRecorder,
        )
    }

    protected fun userPayload(id: String, displayName: String, location: String? = null) = buildJsonObject {
        put("id", id)
        put("displayName", displayName)
        put("currentAvatarImageUrl", "")
        put("currentAvatarThumbnailImageUrl", "")
        put("status", "active")
        put("statusDescription", "")
        put("bio", "")
        if (location != null) put("location", location)
    }

    protected fun friendOnlineEvent(userId: String) = PipelineEvent.FriendOnline(
        buildJsonObject {
            put("userId", userId)
            put("location", "wrld_test:1")
        },
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class FriendRepositoryTest : FriendRepositoryTestFixture() {
    @Test
    fun `resolveFriendUserId prefers camelCase and rejects missing ids`() {
        val both = buildJsonObject {
            put("userId", "usr_camel")
            put("userid", "usr_lower")
        }
        assertEquals("usr_camel", resolveFriendUserId(both))
        assertNull(resolveFriendUserId(buildJsonObject { put("displayName", "no id") }))
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
                repo.friendTransitions.collect { event ->
                    val transition = event.value
                    if (transition is FriendTransition.CameOffline) cameOffline.complete(transition)
                }
            }

            repo.handleEvent(
                PipelineEvent.FriendOffline(
                    buildJsonObject {
                        put("userId", "usr_target")
                    },
                ),
            )

            assertEquals("Target", cameOffline.await().displayName)
            collector.cancelAndJoin()

            val ctx = repo.friends.value.getValue("usr_target")
            assertEquals(FriendState.OFFLINE, ctx.state)
            assertEquals(emptySet<String>(), fixture.repository.pendingOfflineIds.toSet())
            verify(fixture.activityRecorder)
                .recordOnlineOffline(
                    fixture.accountScope.current(),
                    "usr_target",
                    "Target",
                    "offline",
                    "",
                )
        }
    }

    @Test
    fun `transition saturation backpressures without losing event order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = buildRepository(ioDispatcher = dispatcher)
        val releaseCollector = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.friendTransitions.collect { event ->
                received += event.value.userId
                if (received.size == 1) releaseCollector.await()
            }
        }
        val expected = List(100) { index -> "usr_$index" }
        val producer = async {
            expected.forEach { userId ->
                fixture.repository.handleEvent(friendOnlineEvent(userId))
            }
        }

        runCurrent()
        assertFalse(producer.isCompleted)

        releaseCollector.complete(Unit)
        runCurrent()
        producer.await()

        assertEquals(expected, received)
        collector.cancelAndJoin()
    }

    @Test
    fun `account change cancels an old transition suspended by saturation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = buildRepository(ioDispatcher = dispatcher)
        val oldToken = fixture.accountScope.current()
        val releaseCollector = CompletableDeferred<Unit>()
        val received = mutableListOf<AccountScopedEvent<FriendTransition>>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.repository.friendTransitions.collect { event ->
                received += event
                if (received.size == 1) releaseCollector.await()
            }
        }
        var attemptedUserId = ""
        val producer = async {
            repeat(100) { index ->
                attemptedUserId = "usr_old_$index"
                fixture.repository.handleEvent(friendOnlineEvent(attemptedUserId), oldToken)
            }
        }

        runCurrent()
        assertFalse(producer.isCompleted)
        val suspendedUserId = attemptedUserId

        fixture.accountScope.invalidate()
        fixture.accountScope.bind("usr_new")
        runCurrent()
        producer.join()
        assertTrue(producer.isCancelled)

        releaseCollector.complete(Unit)
        runCurrent()
        assertFalse(received.any { it.value.userId == suspendedUserId })

        val newToken = fixture.accountScope.current()
        fixture.repository.handleEvent(friendOnlineEvent("usr_new_friend"), newToken)
        runCurrent()

        assertEquals("usr_new_friend", received.last().value.userId)
        assertEquals(newToken, received.last().origin)
        assertTrue(received.dropLast(1).all { it.origin == oldToken })
        collector.cancelAndJoin()
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
            .recordGps(any(), any(), any(), any())
        verify(
            fixture.activityRecorder,
        ).markFilteredTransition(fixture.accountScope.current(), "usr_target")
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
            .recordGps(any(), any(), any(), any())
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
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class FriendRepositorySnapshotTest : FriendRepositoryTestFixture() {
    @Test
    fun `notify toggle from the previous account cannot publish or return into the next account`() = runBlocking {
        withTimeout(10_000) {
            val friendNotifyDao = mock<FriendNotifyDao>()
            val fixture = buildRepository(friendNotifyDao = friendNotifyDao)
            fixture.accountScope.invalidate()
            fixture.accountScope.bind("usr_old")

            val oldWriteStarted = CompletableDeferred<Unit>()
            val releaseOldWrite = CompletableDeferred<Unit>()
            whenever(friendNotifyDao.get(any())).thenReturn(null)
            whenever(friendNotifyDao.insert(any())).doSuspendableAnswer { invocation ->
                val entry = invocation.getArgument<FriendNotifyEntity>(0)
                if (entry.ownerUserId == "usr_old") {
                    oldWriteStarted.complete(Unit)
                    releaseOldWrite.await()
                }
                Unit
            }

            val oldToggle = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.toggleFriendNotify("usr_shared_friend")
            }
            oldWriteStarted.await()

            fixture.accountScope.invalidate()
            fixture.accountScope.bind("usr_new")
            assertTrue(fixture.repository.toggleFriendNotify("usr_new_friend"))
            assertEquals(setOf("usr_new_friend"), fixture.repository.notifyEnabledIds.value)

            releaseOldWrite.complete(Unit)
            val staleResultWasRejected = try {
                oldToggle.await()
                false
            } catch (_: AccountChangedException) {
                true
            }

            assertTrue(staleResultWasRejected)
            assertEquals(setOf("usr_new_friend"), fixture.repository.notifyEnabledIds.value)
        }
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
    fun `pipeline recovery starts a new snapshot after an active pre-gap load`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = buildRepository(ioDispatcher = dispatcher)
        val firstRequestsStarted = CompletableDeferred<Unit>()
        val releaseFirstRequests = CompletableDeferred<Unit>()
        val firstRequestCount = AtomicInteger()
        val onlineCalls = AtomicInteger()
        val offlineCalls = AtomicInteger()
        whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer { invocation ->
                val offset = invocation.getArgument<Int>(1)
                val offline = invocation.getArgument<Boolean>(2)
                if (offset > 0) {
                    emptyList()
                } else {
                    val call = if (offline) offlineCalls.incrementAndGet() else onlineCalls.incrementAndGet()
                    if (call == 1) {
                        if (firstRequestCount.incrementAndGet() == 2) firstRequestsStarted.complete(Unit)
                        releaseFirstRequests.await()
                    }
                    if (offline) {
                        emptyList()
                    } else {
                        val id = if (call == 1) "usr_before_gap" else "usr_after_gap"
                        listOf(VrcUser(id = id, displayName = id, location = "wrld_test:1"))
                    }
                }
            }

        val preGapLoad = async { runCatching { fixture.repository.loadFriendsList() } }
        runCurrent()
        firstRequestsStarted.await()

        val recovery = async { fixture.repository.resynchronize(fixture.accountScope.current()) }
        runCurrent()
        recovery.await()
        assertTrue(preGapLoad.await().isFailure)
        releaseFirstRequests.complete(Unit)

        assertEquals(setOf("usr_after_gap"), fixture.repository.friends.value.keys)
        assertEquals(2, onlineCalls.get())
        assertEquals(2, offlineCalls.get())
    }

    @Test
    fun `account change aborts friend pagination before the next page`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = buildRepository(ioDispatcher = dispatcher)
        val firstPageStarted = CompletableDeferred<Unit>()
        val releaseFirstPage = CompletableDeferred<Unit>()
        whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer { invocation ->
                val offset = invocation.getArgument<Int>(1)
                val offline = invocation.getArgument<Boolean>(2)
                when {
                    offline -> emptyList()

                    offset == 0 -> {
                        firstPageStarted.complete(Unit)
                        releaseFirstPage.await()
                        List(100) { index -> VrcUser(id = "usr_$index", displayName = "Friend $index") }
                    }

                    else -> emptyList()
                }
            }

        val load = async { runCatching { fixture.repository.loadFriendsList() } }
        runCurrent()
        firstPageStarted.await()

        fixture.accountScope.invalidate()
        fixture.accountScope.bind("usr_new")
        releaseFirstPage.complete(Unit)
        advanceTimeBy(200)
        runCurrent()

        assertTrue(load.await().exceptionOrNull() is AccountChangedException)
        verify(fixture.friendApi, never()).getFriends(n = any(), offset = eq(100), offline = eq(false))
    }

    @Test
    fun `snapshot loader normalizes presence and keeps the online duplicate`() = runBlocking {
        val friendApi = mock<FriendApi>()
        whenever(friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer { invocation ->
                val offset = invocation.getArgument<Int>(1)
                val offline = invocation.getArgument<Boolean>(2)
                when {
                    offset > 0 -> emptyList()

                    offline -> listOf(
                        VrcUser(id = "usr_offline", displayName = "Offline"),
                        VrcUser(id = "usr_duplicate", displayName = "Stale Duplicate"),
                    )

                    else -> listOf(
                        VrcUser(id = "usr_active", displayName = "Active"),
                        VrcUser(
                            id = "usr_online",
                            displayName = "Online",
                            location = "wrld_online:1",
                        ),
                        VrcUser(
                            id = "usr_duplicate",
                            displayName = "Current Duplicate",
                            location = "wrld_current:2",
                        ),
                    )
                }
            }

        val snapshot = FriendSnapshotLoader(friendApi).load { }

        assertEquals(FriendState.ACTIVE, snapshot.getValue("usr_active").state)
        assertEquals(FriendState.ONLINE, snapshot.getValue("usr_online").state)
        assertEquals(FriendState.OFFLINE, snapshot.getValue("usr_offline").state)
        assertEquals(FriendState.ONLINE, snapshot.getValue("usr_duplicate").state)
        assertEquals("Current Duplicate", snapshot.getValue("usr_duplicate").name)
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
    fun `account change after list commit clears cache and aborts remaining side effects`() = runBlocking {
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

            val cachedUserIds = mutableSetOf<String>()
            doAnswer { invocation ->
                invocation.getArgument<Iterable<VrcUser>>(0).mapTo(cachedUserIds, VrcUser::id)
                null
            }.whenever(fixture.userRepository).cacheUsers(any())
            doAnswer {
                cachedUserIds.clear()
                null
            }.whenever(fixture.userRepository).clearRuntimeState()
            fixture.accountScope.bindTo(fixture.userRepository)

            val synchronizationStarted = CompletableDeferred<Unit>()
            val releaseSynchronization = CompletableDeferred<Unit>()
            whenever(fixture.friendLogSynchronizer.synchronize(eq("usr_owner"), any()))
                .doSuspendableAnswer {
                    synchronizationStarted.complete(Unit)
                    releaseSynchronization.await()
                }
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
            synchronizationStarted.await()
            assertEquals(setOf("usr_old_friend"), cachedUserIds)

            fixture.accountScope.invalidate()
            assertTrue(fixture.repository.friends.value.isEmpty())
            assertTrue(cachedUserIds.isEmpty())

            releaseSynchronization.complete(Unit)
            val aborted = try {
                load.await()
                false
            } catch (_: AccountChangedException) {
                true
            }
            assertTrue(aborted)
            assertTrue(cachedUserIds.isEmpty())
            verify(fixture.favoriteRepository, never()).loadFavorites(type = "friend")
        }
    }

    @Test
    fun `late load from previous account cannot replace current account friends`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            fixture.accountScope.bind("usr_old")

            val oldRequestsStarted = CompletableDeferred<Unit>()
            val releaseOldRequests = CompletableDeferred<Unit>()
            stubAccountScopedLoads(fixture, oldRequestsStarted, releaseOldRequests)

            val oldLoad = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.loadFriendsList()
            }
            oldRequestsStarted.await()

            fixture.accountScope.invalidate()
            fixture.accountScope.bind("usr_new")
            fixture.repository.loadFriendsList()
            assertEquals(setOf("usr_new_friend"), fixture.repository.friends.value.keys)

            releaseOldRequests.complete(Unit)
            val oldLoadAborted = try {
                oldLoad.await()
                false
            } catch (_: AccountChangedException) {
                true
            }

            assertTrue(oldLoadAborted)
            assertEquals("usr_new", fixture.accountScope.ownerUserId)
            assertEquals(setOf("usr_new_friend"), fixture.repository.friends.value.keys)
            val cachedUsers = argumentCaptor<Iterable<VrcUser>>()
            verify(fixture.userRepository, times(1)).cacheUsers(cachedUsers.capture())
            assertEquals(setOf("usr_new_friend"), cachedUsers.firstValue.map { it.id }.toSet())
            verify(fixture.friendLogSynchronizer, never()).synchronize(eq("usr_old"), any())
            verify(fixture.friendLogSynchronizer, times(1)).synchronize(eq("usr_new"), any())
        }
    }

    private suspend fun stubAccountScopedLoads(
        fixture: Fixture,
        oldRequestsStarted: CompletableDeferred<Unit>,
        releaseOldRequests: CompletableDeferred<Unit>,
    ) {
        val oldRequestCount = AtomicInteger()
        val onlineCalls = AtomicInteger()
        val offlineCalls = AtomicInteger()
        whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer { invocation ->
                val offset = invocation.getArgument<Int>(1)
                val offline = invocation.getArgument<Boolean>(2)
                when {
                    offset > 0 -> emptyList()

                    offline -> {
                        awaitFirstOldRequest(
                            offlineCalls,
                            oldRequestCount,
                            oldRequestsStarted,
                            releaseOldRequests,
                        )
                        emptyList()
                    }

                    awaitFirstOldRequest(
                        onlineCalls,
                        oldRequestCount,
                        oldRequestsStarted,
                        releaseOldRequests,
                    ) -> listOf(friend("usr_old_friend", "Old Friend", "wrld_old:1"))

                    else -> listOf(friend("usr_new_friend", "New Friend", "wrld_new:1"))
                }
            }
    }

    private suspend fun awaitFirstOldRequest(
        callCount: AtomicInteger,
        oldRequestCount: AtomicInteger,
        oldRequestsStarted: CompletableDeferred<Unit>,
        releaseOldRequests: CompletableDeferred<Unit>,
    ): Boolean {
        val isFirstRequest = callCount.incrementAndGet() == 1
        if (isFirstRequest) {
            if (oldRequestCount.incrementAndGet() == 2) oldRequestsStarted.complete(Unit)
            releaseOldRequests.await()
        }
        return isFirstRequest
    }

    private fun friend(id: String, displayName: String, location: String) = VrcUser(
        id = id,
        displayName = displayName,
        location = location,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class FriendRepositoryAccountIsolationTest : FriendRepositoryTestFixture() {
    @Test
    fun `old removal cannot delete the same friend from the next account`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            fixture.accountScope.invalidate()
            fixture.accountScope.bind("usr_old")
            fixture.repository.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_shared_friend")
                        put("location", "wrld_old:1")
                    },
                ),
            )

            val removalStarted = CompletableDeferred<Unit>()
            val releaseRemoval = CompletableDeferred<Unit>()
            whenever(fixture.friendLogSynchronizer.recordRemoved("usr_old", "usr_shared_friend"))
                .doSuspendableAnswer {
                    removalStarted.complete(Unit)
                    releaseRemoval.await()
                }

            val oldRemoval = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.handleEvent(
                    PipelineEvent.FriendDelete(
                        buildJsonObject { put("userId", "usr_shared_friend") },
                    ),
                )
            }
            removalStarted.await()

            fixture.accountScope.invalidate()
            fixture.accountScope.bind("usr_new")
            fixture.repository.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_shared_friend")
                        put("location", "wrld_new:2")
                        put("user", userPayload("usr_shared_friend", "New Account Friend"))
                    },
                ),
            )

            releaseRemoval.complete(Unit)
            val aborted = try {
                oldRemoval.await()
                false
            } catch (_: AccountChangedException) {
                true
            }
            assertTrue(aborted)

            val current = fixture.repository.friends.value.getValue("usr_shared_friend")
            assertEquals("New Account Friend", current.name)
            assertEquals(FriendState.ONLINE, current.state)
        }
    }

    @Test
    fun `recovery snapshot cancels an older pending offline confirmation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = buildRepository(ioDispatcher = dispatcher)
        fixture.repository.offlineDelayMs = 1_000
        whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer { invocation ->
                val offset = invocation.getArgument<Int>(1)
                val offline = invocation.getArgument<Boolean>(2)
                if (offline || offset > 0) {
                    emptyList()
                } else {
                    listOf(
                        VrcUser(
                            id = "usr_target",
                            displayName = "Target",
                            location = "wrld_authoritative:1",
                        ),
                    )
                }
            }
        fixture.repository.handleEvent(
            PipelineEvent.FriendOnline(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", "wrld_before:1")
                    put("user", userPayload("usr_target", "Target"))
                },
            ),
        )
        clearInvocations(fixture.activityRecorder)
        fixture.repository.handleEvent(
            PipelineEvent.FriendOffline(
                buildJsonObject { put("userId", "usr_target") },
            ),
        )
        runCurrent()
        assertEquals(setOf("usr_target"), fixture.repository.pendingOfflineIds.toSet())

        fixture.repository.resynchronize(fixture.accountScope.current())

        assertTrue(fixture.repository.pendingOfflineIds.isEmpty())
        assertEquals(FriendState.ONLINE, fixture.repository.friends.value.getValue("usr_target").state)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(FriendState.ONLINE, fixture.repository.friends.value.getValue("usr_target").state)
        verify(fixture.activityRecorder, never())
            .recordOnlineOffline(any(), eq("usr_target"), any(), eq("offline"), any())
    }

    @Test
    fun `offline frame arriving during recovery remains newer than the snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = buildRepository(ioDispatcher = dispatcher)
        fixture.repository.offlineDelayMs = 1_000
        fixture.repository.handleEvent(
            PipelineEvent.FriendOnline(
                buildJsonObject {
                    put("userId", "usr_target")
                    put("location", "wrld_before:1")
                    put("user", userPayload("usr_target", "Target"))
                },
            ),
        )
        clearInvocations(fixture.activityRecorder)
        val onlineRequestStarted = CompletableDeferred<Unit>()
        val releaseOnlineRequest = CompletableDeferred<Unit>()
        whenever(fixture.friendApi.getFriends(n = any(), offset = any(), offline = any()))
            .doSuspendableAnswer { invocation ->
                val offset = invocation.getArgument<Int>(1)
                val offline = invocation.getArgument<Boolean>(2)
                if (offline || offset > 0) {
                    emptyList()
                } else {
                    onlineRequestStarted.complete(Unit)
                    releaseOnlineRequest.await()
                    listOf(
                        VrcUser(
                            id = "usr_target",
                            displayName = "Target",
                            location = "wrld_snapshot:1",
                        ),
                    )
                }
            }

        val recovery = async { fixture.repository.resynchronize(fixture.accountScope.current()) }
        runCurrent()
        onlineRequestStarted.await()
        fixture.repository.handleEvent(
            PipelineEvent.FriendOffline(
                buildJsonObject { put("userId", "usr_target") },
            ),
        )
        runCurrent()

        releaseOnlineRequest.complete(Unit)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(recovery.isCompleted)
        recovery.await()
        assertEquals(setOf("usr_target"), fixture.repository.pendingOfflineIds.toSet())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(FriendState.OFFLINE, fixture.repository.friends.value.getValue("usr_target").state)
        verify(fixture.activityRecorder)
            .recordOnlineOffline(any(), eq("usr_target"), any(), eq("offline"), eq(""))
    }

    @Test
    fun `logout cancels an unconfirmed offline transition and its side effects`() = runBlocking {
        withTimeout(10_000) {
            val fixture = buildRepository()
            fixture.repository.offlineDelayMs = 60_000
            fixture.repository.handleEvent(
                PipelineEvent.FriendOnline(
                    buildJsonObject {
                        put("userId", "usr_target")
                        put("location", "wrld_a:1")
                        put("user", userPayload("usr_target", "Target"))
                    },
                ),
            )
            clearInvocations(fixture.activityRecorder)

            val cameOffline = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.repository.friendTransitions.collect { event ->
                    if (event.value is FriendTransition.CameOffline) cameOffline.complete(Unit)
                }
            }
            fixture.repository.handleEvent(
                PipelineEvent.FriendOffline(
                    buildJsonObject { put("userId", "usr_target") },
                ),
            )
            assertEquals(setOf("usr_target"), fixture.repository.pendingOfflineIds.toSet())

            fixture.accountScope.invalidate()

            assertTrue(fixture.repository.pendingOfflineIds.isEmpty())
            assertTrue(fixture.repository.friends.value.isEmpty())
            assertFalse(cameOffline.isCompleted)
            verify(
                fixture.activityRecorder,
                never(),
            ).markFilteredTransition(any(), eq("usr_target"))
            verify(fixture.activityRecorder, never())
                .recordOnlineOffline(any(), eq("usr_target"), any(), eq("offline"), any())
            collector.cancelAndJoin()
        }
    }

    @Test
    fun `a queued frame from the previous account cannot mutate the new account`() = runBlocking {
        val fixture = buildRepository()
        val oldSocketOrigin = fixture.accountScope.current()
        fixture.accountScope.invalidate()
        fixture.accountScope.bind("usr_new")

        fixture.repository.loadFriendsList(oldSocketOrigin)
        fixture.repository.handleEvent(
            PipelineEvent.FriendOnline(
                buildJsonObject {
                    put("userId", "usr_old_friend")
                    put("location", "wrld_old:1")
                    put("user", userPayload("usr_old_friend", "Old Friend"))
                },
            ),
            oldSocketOrigin,
        )

        assertTrue(fixture.repository.friends.value.isEmpty())
        verify(fixture.friendApi, never()).getFriends(any(), any(), any())
        verify(fixture.userRepository, never()).cacheUser(any())
        verify(
            fixture.activityRecorder,
            never(),
        ).recordOnlineOffline(any(), any(), any(), any(), any())
    }
}
