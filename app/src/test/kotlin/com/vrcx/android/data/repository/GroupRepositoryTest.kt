package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
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
}
