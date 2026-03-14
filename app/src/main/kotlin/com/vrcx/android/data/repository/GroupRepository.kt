package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupApi: GroupApi,
) {
    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    suspend fun loadUserGroups(userId: String) {
        _userGroups.value = groupApi.getUserGroups(userId)
    }

    suspend fun getGroup(groupId: String): Group = groupApi.getGroup(groupId)
    suspend fun getGroupMembers(groupId: String): List<GroupMember> = groupApi.getGroupMembers(groupId)
    suspend fun getGroupInstances(groupId: String): List<GroupInstance> = groupApi.getGroupInstances(groupId)
}
