package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.websocket.PipelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupApi: GroupApi,
) {
    private val TAG = "GroupRepository"
    private val scope = CoroutineScope(Dispatchers.IO)

    var ownerUserId: String = ""

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    suspend fun loadUserGroups(userId: String) {
        _userGroups.value = groupApi.getUserGroups(userId)
    }

    suspend fun getGroup(groupId: String): Group = groupApi.getGroup(groupId)
    suspend fun getGroupMembers(groupId: String): List<GroupMember> = groupApi.getGroupMembers(groupId)
    suspend fun getGroupInstances(groupId: String): List<GroupInstance> = groupApi.getGroupInstances(groupId)

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.GroupJoined -> {
                if (ownerUserId.isNotEmpty()) {
                    scope.launch {
                        try { loadUserGroups(ownerUserId) } catch (_: Exception) {}
                    }
                }
            }
            is PipelineEvent.GroupLeft -> {
                val groupId = event.content?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                _userGroups.value = _userGroups.value.filter { it.id != groupId }
            }
            is PipelineEvent.GroupRoleUpdated -> {
                val groupId = event.content?.jsonObject?.get("role")
                    ?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                scope.launch {
                    try {
                        val updated = groupApi.getGroup(groupId)
                        _userGroups.value = _userGroups.value.map { if (it.id == groupId) updated else it }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to refresh group on role update: ${e.message}")
                    }
                }
            }
            is PipelineEvent.GroupMemberUpdated -> {
                val groupId = event.content?.jsonObject?.get("member")
                    ?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                scope.launch {
                    try {
                        val updated = groupApi.getGroup(groupId)
                        _userGroups.value = _userGroups.value.map { if (it.id == groupId) updated else it }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to refresh group on member update: ${e.message}")
                    }
                }
            }
            else -> {}
        }
    }
}
