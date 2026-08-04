package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.api.model.GroupPostsResponse
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GroupRepositoryTest {
    private val groupApi = mock<GroupApi>()
    private val repository = GroupRepository(
        groupApi = groupApi,
        dedup = RequestDeduplicator(),
    )

    @Test
    fun `member page preserves requested offset and reports a full page as having more`() {
        runBlocking {
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
    }

    @Test
    fun `post page uses server total to determine completion`() {
        runBlocking {
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
    }

    @Test
    fun `join fallback invalidates cached group before later reads`() {
        runBlocking {
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
    }

    @Test
    fun `loadMyGroups ignores result when runtime state is cleared mid-request`() {
        runBlocking {
            val staleGroups = listOf(Group(id = "grp_old", groupId = "grp_old"))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            whenever(groupApi.getUserGroups("usr_old")).thenAnswer {
                runBlocking {
                    started.complete(Unit)
                    release.await()
                    staleGroups
                }
            }

            val loadJob = async { repository.loadMyGroups("usr_old") }
            started.await()

            repository.clearRuntimeState()
            release.complete(Unit)
            loadJob.await()

            assertEquals("", repository.ownerUserId)
            assertEquals(emptyList<Group>(), repository.userGroups.value)
        }
    }

    @Test
    fun `group joined refresh ignores stale owner after runtime clear`() {
        runBlocking {
            val staleGroups = listOf(Group(id = "grp_old", groupId = "grp_old"))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            whenever(groupApi.getUserGroups("usr_old")).thenAnswer {
                runBlocking {
                    started.complete(Unit)
                    release.await()
                    staleGroups
                }
            }

            repository.ownerUserId = "usr_old"
            repository.handleEvent(PipelineEvent.GroupJoined(null))
            started.await()

            repository.clearRuntimeState()
            release.complete(Unit)
            delay(100)

            assertEquals("", repository.ownerUserId)
            assertEquals(emptyList<Group>(), repository.userGroups.value)
        }
    }
}
