package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.websocket.PipelineEvent
import java.util.concurrent.ConcurrentHashMap
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
    private val dedup: RequestDeduplicator,
) {
    private val TAG = "GroupRepository"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val groupCache = ConcurrentHashMap<String, Group>()

    var ownerUserId: String = ""

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    suspend fun loadUserGroups(userId: String) {
        ownerUserId = userId
        _userGroups.value = groupApi.getUserGroups(userId)
    }

    suspend fun getGroup(groupId: String): Group {
        groupCache[groupId]?.let { return it }
        val group = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
        groupCache[groupId] = group
        return group
    }
    suspend fun getGroupMembers(groupId: String): List<GroupMember> = groupApi.getGroupMembers(groupId)
    suspend fun getGroupInstances(groupId: String): List<GroupInstance> = groupApi.getGroupInstances(groupId)

    suspend fun getGroupPosts(groupId: String): List<GroupPost> {
        val pageSize = 100
        val maxPages = 50
        val posts = mutableListOf<GroupPost>()
        var offset = 0
        var pages = 0
        var total: Int? = null

        while (pages < maxPages) {
            val response = groupApi.getGroupPosts(groupId = groupId, n = pageSize, offset = offset)
            val pagePosts = response.posts
            if (response.total > 0) {
                total = response.total
            }
            if (pagePosts.isEmpty()) {
                break
            }
            posts += pagePosts
            offset += pagePosts.size
            pages += 1
            if (total != null && offset >= total) {
                break
            }
        }

        return posts
    }

    suspend fun joinGroup(groupId: String): Group {
        groupApi.joinGroup(groupId)
        return refreshGroup(groupId)
    }

    suspend fun leaveGroup(groupId: String): Group {
        groupApi.leaveGroup(groupId)
        val updated = refreshGroup(groupId)
        if (!isJoinedGroup(updated)) {
            _userGroups.value = _userGroups.value.filterNot { it.matchesGroupId(groupId) }
        }
        return updated
    }

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
                _userGroups.value = _userGroups.value.filterNot { it.matchesGroupId(groupId) }
            }
            is PipelineEvent.GroupRoleUpdated -> {
                val groupId = event.content?.jsonObject?.get("role")
                    ?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                scope.launch {
                    try {
                        val updated = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
                        groupCache[groupId] = updated
                        _userGroups.value = _userGroups.value.map { if (it.matchesGroupId(groupId)) updated else it }
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
                        val updated = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
                        groupCache[groupId] = updated
                        _userGroups.value = _userGroups.value.map { if (it.matchesGroupId(groupId)) updated else it }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to refresh group on member update: ${e.message}")
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun refreshGroup(groupId: String): Group {
        val updated = dedup.dedupGet("group:$groupId:refresh") { groupApi.getGroup(groupId) }
        groupCache[groupId] = updated
        _userGroups.value = if (isJoinedGroup(updated)) {
            if (_userGroups.value.any { it.matchesGroupId(groupId) }) {
                _userGroups.value.map { group ->
                    if (group.matchesGroupId(groupId)) updated else group
                }
            } else {
                _userGroups.value + updated
            }
        } else {
            _userGroups.value.filterNot { it.matchesGroupId(groupId) }
        }
        return updated
    }

    private fun isJoinedGroup(group: Group): Boolean {
        return membershipStatus(group) == "member"
    }

    private fun membershipStatus(group: Group): String {
        return group.myMember?.membershipStatus
            ?.takeIf { it.isNotBlank() }
            ?: group.membershipStatus
    }

    private fun Group.matchesGroupId(groupId: String): Boolean {
        return id == groupId || groupId == this.groupId
    }
}
