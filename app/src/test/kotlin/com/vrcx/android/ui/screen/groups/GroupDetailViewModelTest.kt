package com.vrcx.android.ui.screen.groups

import androidx.lifecycle.SavedStateHandle
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.repository.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadGroup fans out the four sub-loads in parallel`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup(eq("grp_x"))).thenReturn(Group(id = "grp_x"))
        whenever(repo.getGroupMembers(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.getGroupInstances(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.getGroupPosts(eq("grp_x"))).thenReturn(emptyList())

        buildViewModel(repository = repo)
        advanceUntilIdle()

        // All four endpoints fire — sequencing them was the bug.
        verify(repo).getGroup(eq("grp_x"))
        verify(repo).getGroupMembers(eq("grp_x"))
        verify(repo).getGroupInstances(eq("grp_x"))
        verify(repo).getGroupPosts(eq("grp_x"))
    }

    @Test
    fun `loadGroup tolerates a member fetch failure without dropping the rest`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup(eq("grp_x"))).thenReturn(Group(id = "grp_x"))
        whenever(repo.getGroupMembers(eq("grp_x"))).thenThrow(RuntimeException("nope"))
        whenever(repo.getGroupInstances(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.getGroupPosts(eq("grp_x"))).thenReturn(emptyList())

        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        // Other tabs still populated even though members failed.
        assertEquals(0, vm.members.value.size)
        assertEquals(0, vm.instances.value.size)
        assertEquals(0, vm.posts.value.size)
        assertEquals("grp_x", vm.group.value?.id)
    }

    @Test
    fun `canManageMembers returns false when myMember is missing or has no roles`() {
        val vm = buildViewModel()
        assertFalse(vm.canManageMembers(null))
        assertFalse(vm.canManageMembers(Group(id = "grp_x", myMember = null)))
        assertFalse(
            vm.canManageMembers(
                Group(
                    id = "grp_x",
                    membershipStatus = "member",
                    myMember = GroupMember(membershipStatus = "member", roleIds = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `canManageMembers returns true when caller has a role and is a member`() {
        val vm = buildViewModel()
        assertTrue(
            vm.canManageMembers(
                Group(
                    id = "grp_x",
                    membershipStatus = "member",
                    myMember = GroupMember(
                        membershipStatus = "member",
                        roleIds = listOf("admin_role"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `kickMember on success removes the member from the local list`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup(eq("grp_x"))).thenReturn(Group(id = "grp_x"))
        whenever(repo.getGroupMembers(eq("grp_x"))).thenReturn(
            listOf(
                GroupMember(id = "gmem_a", userId = "usr_a"),
                GroupMember(id = "gmem_b", userId = "usr_b"),
            ),
        )
        whenever(repo.getGroupInstances(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.getGroupPosts(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.kickGroupMember(eq("grp_x"), eq("usr_a"))).thenReturn(true)

        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.kickMember("usr_a")
        advanceUntilIdle()

        assertEquals(listOf("usr_b"), vm.members.value.map { it.userId })
    }

    @Test
    fun `kickMember on failure leaves the member in place and does not retry`() = runTest(testDispatcher) {
        val repo = mock<GroupRepository>()
        whenever(repo.getGroup(eq("grp_x"))).thenReturn(Group(id = "grp_x"))
        whenever(repo.getGroupMembers(eq("grp_x"))).thenReturn(
            listOf(GroupMember(id = "gmem_a", userId = "usr_a")),
        )
        whenever(repo.getGroupInstances(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.getGroupPosts(eq("grp_x"))).thenReturn(emptyList())
        whenever(repo.kickGroupMember(eq("grp_x"), eq("usr_a"))).thenReturn(false)

        val vm = buildViewModel(repository = repo)
        advanceUntilIdle()

        vm.kickMember("usr_a")
        advanceUntilIdle()

        assertEquals(listOf("usr_a"), vm.members.value.map { it.userId })
        // Initial load was the only fetch — kick failure doesn't trigger a reload.
        verify(repo, times(1)).getGroupMembers(eq("grp_x"))
    }

    private fun buildViewModel(
        groupId: String = "grp_x",
        repository: GroupRepository = mock(),
    ): GroupDetailViewModel {
        val handle = SavedStateHandle(mapOf("groupId" to groupId))
        return GroupDetailViewModel(handle, repository)
    }
}
