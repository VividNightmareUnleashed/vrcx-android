package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.api.model.GroupPostsResponse
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.directTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GroupRepositoryTest {
    private val groupApi = mock<GroupApi>()
    private val testScope = TestScope()
    private val accountScope = AccountScope()
    private val repository = GroupRepository(
        groupApi = groupApi,
        dedup = RequestDeduplicator(directTestDispatcher),
        accountScope = accountScope,
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler)),
    )

    private fun repositoryTest(body: suspend TestScope.() -> Unit) = testScope.runTest { body() }

    @Test
    fun `member page preserves requested offset and reports a full page as having more`() = repositoryTest {
        val members = listOf(
            GroupMember(id = "gmem_1"),
            GroupMember(id = "gmem_2"),
        )
        whenever(groupApi.getGroupMembers("grp_1", n = 2, offset = 4)).thenReturn(members)

        val page = repository.getGroupMembersPage("grp_1", offset = 4, count = 2)

        assertEquals(members, page.items)
        assertEquals(6, page.nextOffset)
        assertEquals(true, page.hasMore)
        verify(groupApi).getGroupMembers("grp_1", n = 2, offset = 4)
    }

    @Test
    fun `post page uses server total to determine completion`() = repositoryTest {
        val posts = listOf(
            GroupPost(id = "post_3"),
            GroupPost(id = "post_4"),
        )
        whenever(groupApi.getGroupPosts("grp_1", n = 2, offset = 2)).thenReturn(
            GroupPostsResponse(posts = posts, total = 5),
        )

        val page = repository.getGroupPostsPage("grp_1", offset = 2, count = 2)

        assertEquals(posts, page.items)
        assertEquals(4, page.nextOffset)
        assertEquals(5, page.total)
        assertEquals(true, page.hasMore)
    }

    @Test
    fun `join fallback invalidates cached group before later reads`() = repositoryTest {
        val cachedGroup = Group(
            id = "grp_1",
            groupId = "grp_1",
            privacy = "public",
        )
        val refreshedGroup = cachedGroup.copy(membershipStatus = "member")

        whenever(groupApi.getGroup("grp_1"))
            .thenReturn(cachedGroup)
            .thenThrow(RuntimeException("timeout"))
            .thenReturn(refreshedGroup)
        whenever(groupApi.joinGroup("grp_1")).thenReturn(buildJsonObject { })

        repository.getGroup("grp_1")

        val optimisticGroup = repository.joinGroup("grp_1")
        assertEquals("member", optimisticGroup.membershipStatus)

        val fetchedGroup = repository.getGroup("grp_1")

        assertEquals(refreshedGroup, fetchedGroup)
        verify(groupApi, times(3)).getGroup("grp_1")
    }

    @Test
    fun `loadMyGroups ignores result when runtime state is cleared mid-request`() = repositoryTest {
        val staleGroups = listOf(Group(id = "grp_old", groupId = "grp_old"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        whenever(groupApi.getUserGroups("usr_old", 100, 0)).doSuspendableAnswer {
            started.complete(Unit)
            release.await()
            staleGroups
        }

        accountScope.bind("usr_old")
        val loadJob = async(start = CoroutineStart.UNDISPATCHED) { repository.loadMyGroups() }
        started.await()

        accountScope.invalidate()
        release.complete(Unit)
        loadJob.await()

        assertEquals("", accountScope.ownerUserId)
        assertEquals(emptyList<Group>(), repository.userGroups.value)
    }

    @Test
    fun `group joined refresh ignores stale owner after runtime clear`() = repositoryTest {
        val staleGroups = listOf(Group(id = "grp_old", groupId = "grp_old"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        whenever(groupApi.getUserGroups("usr_old", 100, 0)).doSuspendableAnswer {
            started.complete(Unit)
            release.await()
            staleGroups
        }

        accountScope.bind("usr_old")
        repository.handleEvent(PipelineEvent.GroupJoined(null))
        advanceUntilIdle()
        started.await()

        accountScope.invalidate()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("", accountScope.ownerUserId)
        assertEquals(emptyList<Group>(), repository.userGroups.value)
    }

    @Test
    fun `join refresh landing after an account change publishes nothing`() = repositoryTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        whenever(groupApi.joinGroup("grp_1")).thenReturn(buildJsonObject { })
        whenever(groupApi.getGroup("grp_1")).doSuspendableAnswer {
            started.complete(Unit)
            release.await()
            Group(id = "grp_1", membershipStatus = "member")
        }

        val joinJob = async(start = CoroutineStart.UNDISPATCHED) { repository.joinGroup("grp_1") }
        started.await()

        accountScope.invalidate()
        release.complete(Unit)
        joinJob.await()

        assertEquals(emptyList<Group>(), repository.userGroups.value)
        // The refreshed group must not have been cached either, so the next
        // read of it goes back to the network under the new account.
        repository.getGroup("grp_1")
        verify(groupApi, times(2)).getGroup("grp_1")
    }

    @Test
    fun `optimistic join fallback after an account change publishes nothing`() = repositoryTest {
        val known = Group(id = "grp_1", groupId = "grp_1", privacy = "public")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var call = 0

        whenever(groupApi.joinGroup("grp_1")).thenReturn(buildJsonObject { })
        whenever(groupApi.getGroup("grp_1")).doSuspendableAnswer {
            if (call++ == 0) {
                known
            } else {
                started.complete(Unit)
                release.await()
                throw RuntimeException("timeout")
            }
        }
        // A known public group is what makes the fallback publish a member
        // entry rather than a pending request.
        repository.getGroup("grp_1")

        val joinJob = async(start = CoroutineStart.UNDISPATCHED) { repository.joinGroup("grp_1") }
        started.await()

        accountScope.invalidate()
        release.complete(Unit)
        assertEquals("member", joinJob.await().membershipStatus)

        assertEquals(emptyList<Group>(), repository.userGroups.value)
    }

    @Test
    fun `malformed group frames leave the published groups untouched`() = repositoryTest {
        whenever(groupApi.getUserGroups("usr_1", 100, 0)).thenReturn(
            listOf(Group(id = "gmem_1", groupId = "grp_1")),
        )
        accountScope.bind("usr_1")
        repository.loadMyGroups()

        listOf(
            PipelineEvent.GroupLeft(JsonNull),
            PipelineEvent.GroupLeft(JsonPrimitive("grp_1")),
            PipelineEvent.GroupLeft(buildJsonObject { put("groupId", JsonNull) }),
            PipelineEvent.GroupLeft(buildJsonObject { put("groupId", buildJsonArray { }) }),
            PipelineEvent.GroupRoleUpdated(buildJsonObject { put("role", "moderator") }),
            PipelineEvent.GroupMemberUpdated(buildJsonObject { put("member", buildJsonArray { }) }),
            PipelineEvent.GroupJoined(JsonPrimitive("grp_1")),
        ).forEach(repository::handleEvent)
        advanceUntilIdle()

        assertEquals(listOf("grp_1"), repository.userGroups.value.map { it.canonicalGroupId() })
    }

    @Test
    fun `group left removes the membership entry the profile endpoint returned`() = repositoryTest {
        whenever(groupApi.getUserGroups("usr_1", 100, 0)).thenReturn(
            listOf(Group(id = "gmem_1", groupId = "grp_1")),
        )
        accountScope.bind("usr_1")
        repository.loadMyGroups()

        repository.handleEvent(
            PipelineEvent.GroupLeft(buildJsonObject { put("groupId", "grp_1") }),
        )

        assertEquals(emptyList<Group>(), repository.userGroups.value)
    }

    @Test
    fun `an older joined refresh cannot undo a later group-left event`() = repositoryTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        whenever(groupApi.getUserGroups("usr_1", 100, 0)).doSuspendableAnswer {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            listOf(Group(id = "gmem_1", groupId = "grp_1"))
        }
        accountScope.bind("usr_1")

        repository.handleEvent(
            PipelineEvent.GroupJoined(buildJsonObject { put("groupId", "grp_1") }),
        )
        advanceUntilIdle()
        refreshStarted.await()
        repository.handleEvent(
            PipelineEvent.GroupLeft(buildJsonObject { put("groupId", "grp_1") }),
        )
        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<Group>(), repository.userGroups.value)
    }

    @Test
    fun `updates for different groups complete independently`() = repositoryTest {
        whenever(groupApi.getUserGroups("usr_1", 100, 0)).thenReturn(
            listOf(
                Group(id = "grp_a", name = "A before"),
                Group(id = "grp_b", name = "B before"),
            ),
        )
        val firstUpdateStarted = CompletableDeferred<Unit>()
        val releaseFirstUpdate = CompletableDeferred<Unit>()
        whenever(groupApi.getGroup("grp_a")).doSuspendableAnswer {
            firstUpdateStarted.complete(Unit)
            releaseFirstUpdate.await()
            Group(id = "grp_a", name = "A after")
        }
        whenever(groupApi.getGroup("grp_b")).thenReturn(Group(id = "grp_b", name = "B after"))
        accountScope.bind("usr_1")
        repository.loadMyGroups()

        repository.handleEvent(groupRoleUpdated("grp_a"))
        runCurrent()
        firstUpdateStarted.await()
        repository.handleEvent(groupRoleUpdated("grp_b"))
        runCurrent()

        releaseFirstUpdate.complete(Unit)
        advanceUntilIdle()

        val groupsById = repository.userGroups.value.associateBy { it.canonicalGroupId() }
        assertEquals("A after", groupsById.getValue("grp_a").name)
        assertEquals("B after", groupsById.getValue("grp_b").name)
    }

    @Test
    fun `a later update for the same group uses its own response`() = repositoryTest {
        whenever(groupApi.getUserGroups("usr_1", 100, 0)).thenReturn(
            listOf(Group(id = "grp_a", name = "Before")),
        )
        val firstUpdateStarted = CompletableDeferred<Unit>()
        val releaseFirstUpdate = CompletableDeferred<Unit>()
        var requestCount = 0
        whenever(groupApi.getGroup("grp_a")).doSuspendableAnswer {
            if (++requestCount == 1) {
                firstUpdateStarted.complete(Unit)
                releaseFirstUpdate.await()
                Group(id = "grp_a", name = "After first update")
            } else {
                Group(id = "grp_a", name = "After second update")
            }
        }
        accountScope.bind("usr_1")
        repository.loadMyGroups()

        repository.handleEvent(groupRoleUpdated("grp_a"))
        runCurrent()
        firstUpdateStarted.await()
        repository.handleEvent(groupRoleUpdated("grp_a"))
        runCurrent()

        assertEquals("After second update", repository.userGroups.value.single().name)

        releaseFirstUpdate.complete(Unit)
        advanceUntilIdle()

        assertEquals("After second update", repository.userGroups.value.single().name)
    }

    @Test
    fun `my groups follow the signed-in account, not a caller-supplied id`() = repositoryTest {
        whenever(groupApi.getUserGroups("usr_me", 100, 0)).thenReturn(
            listOf(Group(id = "gmem_mine", groupId = "grp_mine")),
        )
        accountScope.bind("usr_me")

        repository.loadMyGroups()

        // Nothing a caller passes can repoint the Groups tab at another user.
        assertEquals(listOf("grp_mine"), repository.userGroups.value.map { it.canonicalGroupId() })
        verify(groupApi).getUserGroups("usr_me", 100, 0)
    }

    @Test
    fun `canonical group id prefers the group id over a membership id`() {
        assertEquals("grp_x", Group(id = "gmem_x", groupId = "grp_x").canonicalGroupId())
        assertEquals("grp_y", Group(id = "grp_y").canonicalGroupId())
    }

    private fun groupRoleUpdated(groupId: String) = PipelineEvent.GroupRoleUpdated(
        buildJsonObject {
            put(
                "role",
                buildJsonObject { put("groupId", groupId) },
            )
        },
    )
}
