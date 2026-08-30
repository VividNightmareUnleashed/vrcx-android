package com.vrcx.android.ui.screen.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.repository.GroupPage
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `initial load fetches metadata and one member page only`() = runTest(testDispatcher) {
        val repo = initialRepository(group = Group(id = "grp_x", memberCount = 25))

        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        verify(repo).getGroup("grp_x")
        verify(repo).getGroupMembersPage(eq("grp_x"), eq(0), any())
        verify(repo, never()).getGroupInstances(any())
        verify(repo, never()).getGroupPostsPage(any(), any(), any())
        assertEquals(25, ready(vm.state.value.members).totalCount)
        assertTrue(vm.state.value.instances == LoadState.NotLoaded)
        assertTrue(vm.state.value.posts == LoadState.NotLoaded)
    }

    @Test
    fun `tabs load their resources once on first selection`() = runTest(testDispatcher) {
        val repo = initialRepository()
        whenever(repo.getGroupInstances("grp_x")).thenReturn(
            listOf(GroupInstance(instanceId = "instance_1")),
        )
        whenever(repo.getGroupPostsPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(
                items = listOf(GroupPost(id = "post_1")),
                nextOffset = 1,
                total = 1,
                hasMore = false,
            ),
        )
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.onTabSelected(GroupTab.INSTANCES)
        vm.onTabSelected(GroupTab.INSTANCES)
        vm.onTabSelected(GroupTab.POSTS)
        vm.onTabSelected(GroupTab.POSTS)
        advanceUntilIdle()
        vm.onTabSelected(GroupTab.INSTANCES)
        vm.onTabSelected(GroupTab.POSTS)
        advanceUntilIdle()

        verify(repo, times(1)).getGroupInstances("grp_x")
        verify(repo, times(1)).getGroupPostsPage(eq("grp_x"), eq(0), any())
        assertEquals(GroupTab.POSTS, vm.state.value.selectedTab)
        assertEquals("instance_1", ready(vm.state.value.instances).single().instanceId)
        assertEquals("post_1", ready(vm.state.value.posts).items.single().id)
    }

    @Test
    fun `members append by offset and duplicate load requests are ignored`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x", memberCount = 3))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(
                items = listOf(member("a"), member("b")),
                nextOffset = 2,
                hasMore = true,
            ),
        )
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(2), any())).thenReturn(
            GroupPage(
                items = listOf(member("c")),
                nextOffset = 3,
                hasMore = false,
            ),
        )
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.loadMoreMembers()
        vm.loadMoreMembers()
        advanceUntilIdle()

        val page = ready(vm.state.value.members)
        assertEquals(listOf("usr_a", "usr_b", "usr_c"), page.items.map { it.userId })
        assertEquals(3, page.totalCount)
        assertFalse(page.hasMore)
        verify(repo, times(1)).getGroupMembersPage(eq("grp_x"), eq(2), any())
    }

    @Test
    fun `posts use server total and append only when requested`() = runTest(testDispatcher) {
        val repo = initialRepository()
        whenever(repo.getGroupPostsPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(
                items = listOf(GroupPost(id = "post_1")),
                nextOffset = 1,
                total = 2,
                hasMore = true,
            ),
        )
        whenever(repo.getGroupPostsPage(eq("grp_x"), eq(1), any())).thenReturn(
            GroupPage(
                items = listOf(GroupPost(id = "post_2")),
                nextOffset = 2,
                total = 2,
                hasMore = false,
            ),
        )
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.onTabSelected(GroupTab.POSTS)
        advanceUntilIdle()
        assertEquals(listOf("post_1"), ready(vm.state.value.posts).items.map { it.id })

        vm.loadMorePosts()
        advanceUntilIdle()

        val page = ready(vm.state.value.posts)
        assertEquals(listOf("post_1", "post_2"), page.items.map { it.id })
        assertEquals(2, page.totalCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun `member failure is independent and retry replaces the failed state`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x"))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any()))
            .thenThrow(RuntimeException("members unavailable"))
            .thenReturn(
                GroupPage(
                    items = listOf(member("a")),
                    nextOffset = 1,
                    hasMore = false,
                ),
            )
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        assertEquals("grp_x", ready(vm.state.value.group).id)
        assertEquals("members unavailable", (vm.state.value.members as LoadState.Failed).message)
        assertTrue(vm.state.value.instances == LoadState.NotLoaded)
        assertTrue(vm.state.value.posts == LoadState.NotLoaded)

        vm.retryMembers()
        advanceUntilIdle()

        assertEquals(listOf("usr_a"), ready(vm.state.value.members).items.map { it.userId })
        verify(repo, times(2)).getGroupMembersPage(eq("grp_x"), eq(0), any())
    }

    @Test
    fun `append failure retains items and retries the same offset`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x", memberCount = 2))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(listOf(member("a")), nextOffset = 1, hasMore = true),
        )
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(1), any()))
            .thenThrow(RuntimeException("next page failed"))
            .thenReturn(GroupPage(listOf(member("b")), nextOffset = 2, hasMore = false))
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.loadMoreMembers()
        advanceUntilIdle()
        val failedPage = ready(vm.state.value.members)
        assertEquals(listOf("usr_a"), failedPage.items.map { it.userId })
        assertEquals("next page failed", (failedPage.appendState as GroupAppendState.Error).message)

        vm.loadMoreMembers()
        advanceUntilIdle()

        assertEquals(listOf("usr_a", "usr_b"), ready(vm.state.value.members).items.map { it.userId })
        verify(repo, times(2)).getGroupMembersPage(eq("grp_x"), eq(1), any())
    }

    @Test
    fun `failed first-page refresh keeps the loaded members as stale data`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x", memberCount = 1))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any()))
            .thenReturn(GroupPage(listOf(member("a")), nextOffset = 1, hasMore = false))
            .thenThrow(RuntimeException("refresh failed"))
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.retryMembers()
        advanceUntilIdle()

        val members = vm.state.value.members as LoadState.Loaded<GroupPagedData<GroupMember>>
        assertEquals(listOf("usr_a"), members.value.items.map { it.userId })
        assertEquals("refresh failed", members.staleError)
        assertFalse(members.isRefreshing)
    }

    @Test
    fun `clearing one displayed message preserves unseen resource failures`() = runTest(testDispatcher) {
        val repo = initialRepository()
        whenever(repo.getGroup("grp_x"))
            .thenReturn(Group(id = "grp_x"))
            .thenThrow(RuntimeException("group refresh failed"))
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.retryGroup()
        advanceUntilIdle()

        val failedRefresh = vm.state.value.group as LoadState.Loaded<Group>
        assertEquals("group refresh failed", failedRefresh.staleError)

        vm.clearMessage(GroupDetailMessageSource.ACTION)
        assertEquals(
            "group refresh failed",
            (vm.state.value.group as LoadState.Loaded<Group>).staleError,
        )

        vm.clearMessage(GroupDetailMessageSource.GROUP)
        assertNull((vm.state.value.group as LoadState.Loaded<Group>).staleError)
    }

    @Test
    fun `cancelling the view model settles its loading state without publishing an error`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        val memberLoadStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<GroupPage<GroupMember>>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x"))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).doSuspendableAnswer {
            memberLoadStarted.complete(Unit)
            neverCompletes.await()
        }
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()
        memberLoadStarted.await()

        vm.viewModelScope.cancel()
        advanceUntilIdle()

        assertTrue(vm.state.value.members == LoadState.NotLoaded)
    }

    @Test
    fun `canManageMembers requires the explicit permission or wildcard`() {
        val vm = buildViewModel()
        assertFalse(vm.canManageMembers(null))
        assertFalse(
            vm.canManageMembers(
                memberGroup(permissions = listOf("group-announcements-manage")),
            ),
        )
        assertTrue(vm.canManageMembers(memberGroup(permissions = listOf("group-members-manage"))))
        assertTrue(vm.canManageMembers(memberGroup(permissions = listOf("*"))))
    }

    @Test
    fun `member removal policy follows owner and current-member identities`() {
        val vm = buildViewModel()
        val group = memberGroup(permissions = listOf("group-members-manage")).copy(
            ownerId = "usr_owner",
            myMember = memberGroup(listOf("group-members-manage")).myMember?.copy(userId = "usr_me"),
        )

        assertFalse(vm.canRemoveMember(group, member("owner").copy(userId = "usr_owner")))
        assertFalse(vm.canRemoveMember(group, member("me").copy(userId = "usr_me")))
        assertTrue(vm.canRemoveMember(group, member("other").copy(userId = "usr_other")))

        val changed = group.copy(
            ownerId = "usr_other",
            myMember = group.myMember?.copy(userId = "usr_owner"),
        )
        assertFalse(vm.canRemoveMember(changed, member("other").copy(userId = "usr_other")))
        assertFalse(vm.canRemoveMember(changed, member("owner").copy(userId = "usr_owner")))
    }

    @Test
    fun `kick state identifies the member in flight and rejects a second action`() = runTest(testDispatcher) {
        val kickStarted = CompletableDeferred<Unit>()
        val releaseKick = CompletableDeferred<Unit>()
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x", memberCount = 2))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(listOf(member("a"), member("b")), nextOffset = 2, hasMore = false),
        )
        whenever(repo.kickGroupMember("grp_x", "usr_a")).doSuspendableAnswer {
            kickStarted.complete(Unit)
            releaseKick.await()
            true
        }
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.kickMember("usr_a")
        runCurrent()
        kickStarted.await()

        assertTrue(vm.state.value.isActionLoading)
        assertEquals("usr_a", vm.state.value.removingMemberUserId)
        vm.kickMember("usr_b")
        runCurrent()
        verify(repo, never()).kickGroupMember("grp_x", "usr_b")

        releaseKick.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.state.value.isActionLoading)
        assertNull(vm.state.value.removingMemberUserId)
    }

    @Test
    fun `kick success removes member and updates authoritative count`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x", memberCount = 2))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(listOf(member("a"), member("b")), nextOffset = 2, hasMore = false),
        )
        whenever(repo.kickGroupMember("grp_x", "usr_a")).thenReturn(true)
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.kickMember("usr_a")
        advanceUntilIdle()

        assertEquals(listOf("usr_b"), ready(vm.state.value.members).items.map { it.userId })
        assertEquals(1, ready(vm.state.value.members).totalCount)
        assertEquals(1, ready(vm.state.value.group).memberCount)
    }

    @Test
    fun `kick cannot shift the server page while a member append is in flight`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        val appendStarted = CompletableDeferred<Unit>()
        val releaseAppend = CompletableDeferred<Unit>()
        whenever(repo.getGroup("grp_x")).thenReturn(Group(id = "grp_x", memberCount = 3))
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(listOf(member("a"), member("b")), nextOffset = 2, hasMore = true),
        )
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(2), any())).doSuspendableAnswer {
            appendStarted.complete(Unit)
            releaseAppend.await()
            GroupPage(listOf(member("c")), nextOffset = 3, hasMore = false)
        }
        whenever(repo.kickGroupMember("grp_x", "usr_a")).thenReturn(true)
        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.loadMoreMembers()
        advanceUntilIdle()
        appendStarted.await()

        vm.kickMember("usr_a")
        runCurrent()

        verify(repo, never()).kickGroupMember("grp_x", "usr_a")
        assertEquals(listOf("usr_a", "usr_b"), ready(vm.state.value.members).items.map { it.userId })

        releaseAppend.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("usr_a", "usr_b", "usr_c"),
            ready(vm.state.value.members).items.map { it.userId },
        )

        vm.kickMember("usr_a")
        advanceUntilIdle()

        val page = ready(vm.state.value.members)
        assertEquals(listOf("usr_b", "usr_c"), page.items.map { it.userId })
        assertEquals(2, page.nextOffset)
        assertEquals(2, page.totalCount)
        assertFalse(page.hasMore)
        verify(repo, times(1)).kickGroupMember("grp_x", "usr_a")
    }

    private suspend fun initialRepository(group: Group = Group(id = "grp_x")): GroupRepository {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup("grp_x")).thenReturn(group)
        whenever(repo.getGroupMembersPage(eq("grp_x"), eq(0), any())).thenReturn(
            GroupPage(items = emptyList(), nextOffset = 0, hasMore = false),
        )
        return repo
    }

    private fun member(suffix: String) = GroupMember(id = "gmem_$suffix", userId = "usr_$suffix")

    private fun memberGroup(permissions: List<String>) = Group(
        id = "grp_x",
        membershipStatus = "member",
        myMember = GroupMember(
            membershipStatus = "member",
            roleIds = listOf("role"),
            permissions = permissions,
        ),
    )

    private fun buildViewModel(groupId: String = "grp_x", repository: GroupRepository = mock()): GroupDetailViewModel {
        val handle = SavedStateHandle(mapOf("groupId" to groupId))
        return GroupDetailViewModel(handle, repository)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ready(state: LoadState<T>): T = (state as LoadState.Loaded<T>).value
}
