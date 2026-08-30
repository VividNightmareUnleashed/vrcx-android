package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost

interface GroupQueries {
    suspend fun getUserGroups(userId: String): List<Group>

    suspend fun getGroup(groupId: String): Group

    suspend fun getGroupMembersPage(
        groupId: String,
        offset: Int = 0,
        count: Int = GROUP_PAGE_SIZE,
    ): GroupPage<GroupMember>

    suspend fun getGroupInstances(groupId: String): List<GroupInstance>

    suspend fun getGroupPostsPage(groupId: String, offset: Int = 0, count: Int = GROUP_PAGE_SIZE): GroupPage<GroupPost>
}

interface GroupMembershipOperations {
    suspend fun joinGroup(groupId: String): Group

    suspend fun leaveGroup(groupId: String): Group

    suspend fun kickGroupMember(groupId: String, userId: String): Boolean
}
