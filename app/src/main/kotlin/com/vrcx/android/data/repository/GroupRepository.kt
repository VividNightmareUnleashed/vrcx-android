package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.websocket.PipelineEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong

@Singleton
class GroupRepository @Inject constructor(
    private val groupApi: GroupApi,
    private val dedup: RequestDeduplicator,
) {
    private val TAG = "GroupRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupCache = ConcurrentHashMap<String, Group>()
    private val accountGeneration = AtomicLong(0)

    @Volatile
    var ownerUserId: String = ""

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    suspend fun loadUserGroups(userId: String) {
        val generation = accountGeneration.get()
        ownerUserId = userId
        val groups = BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            groupApi.getUserGroups(userId, n = count, offset = offset)
        }
        if (ownerUserId == userId && generation == accountGeneration.get()) {
            _userGroups.value = groups
        }
    }

    fun clearRuntimeState() {
        accountGeneration.incrementAndGet()
        ownerUserId = ""
        groupCache.clear()
        _userGroups.value = emptyList()
    }

    suspend fun getGroup(groupId: String): Group {
        groupCache[groupId]?.let { return it }
        val generation = accountGeneration.get()
        val group = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
        if (generation == accountGeneration.get()) groupCache[groupId] = group
        return group
    }
    suspend fun getGroupMembers(groupId: String): List<GroupMember> =
        BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            groupApi.getGroupMembers(groupId, n = count, offset = offset)
        }
    suspend fun getGroupInstances(groupId: String): List<GroupInstance> = groupApi.getGroupInstances(groupId)

    suspend fun getGroupPosts(groupId: String): List<GroupPost> {
        var total = 0
        return BulkPaginator.fetchAll(
            pageSize = 100,
            maxPages = 50,
            stopOnShortPage = false,
            stopWhen = { fetched -> total in 1..fetched },
        ) { offset, count ->
            val response = groupApi.getGroupPosts(groupId = groupId, n = count, offset = offset)
            if (response.total > 0) total = response.total
            response.posts
        }
    }

    suspend fun joinGroup(groupId: String): Group {
        val currentGroup = findKnownGroup(groupId)
        groupApi.joinGroup(groupId)
        return runCatching { refreshGroup(groupId) }
            .getOrElse {
                invalidateCachedGroup(groupId)
                val optimisticMembershipStatus = inferJoinedMembershipStatus(currentGroup)
                val optimisticGroup = buildOptimisticGroup(
                    groupId = groupId,
                    previousGroup = currentGroup,
                    membershipStatus = optimisticMembershipStatus,
                )
                if (optimisticMembershipStatus == "member") {
                    _userGroups.value = if (_userGroups.value.any { it.matchesGroupId(groupId) }) {
                        _userGroups.value.map { group ->
                            if (group.matchesGroupId(groupId)) optimisticGroup else group
                        }
                    } else {
                        _userGroups.value + optimisticGroup
                    }
                }
                optimisticGroup
            }
    }

    suspend fun leaveGroup(groupId: String): Group {
        val currentGroup = findKnownGroup(groupId)
        groupApi.leaveGroup(groupId)
        return runCatching { refreshGroup(groupId) }
            .getOrElse {
                invalidateCachedGroup(groupId)
                _userGroups.value = _userGroups.value.filterNot { it.matchesGroupId(groupId) }
                buildOptimisticGroup(
                    groupId = groupId,
                    previousGroup = currentGroup,
                    membershipStatus = "",
                )
            }
    }

    /**
     * Removes the named user from the group. Returns true on success, false if
     * the API rejects the request (most commonly because the caller doesn't have
     * the permission). Callers should treat false as "not your call to make".
     */
    suspend fun kickGroupMember(groupId: String, userId: String): Boolean {
        return runCatching { groupApi.kickGroupMember(groupId, userId) }.isSuccess
    }

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.GroupJoined -> {
                val groupId = event.content?.jsonObject?.get("groupId")?.jsonPrimitive?.content
                if (groupId != null) invalidateCachedGroup(groupId)
                val ownerId = ownerUserId
                val generation = accountGeneration.get()
                if (ownerId.isNotEmpty()) {
                    scope.launch {
                        try {
                            refreshUserGroupsIfCurrent(ownerId, generation)
                        } catch (_: Exception) {}
                    }
                }
            }
            is PipelineEvent.GroupLeft -> {
                val groupId = event.content?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                invalidateCachedGroup(groupId)
                _userGroups.value = _userGroups.value.filterNot { it.matchesGroupId(groupId) }
            }
            is PipelineEvent.GroupRoleUpdated -> {
                val groupId = event.content?.jsonObject?.get("role")
                    ?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                val ownerId = ownerUserId
                val generation = accountGeneration.get()
                scope.launch {
                    try {
                        val updated = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
                        if (ownerUserId == ownerId && generation == accountGeneration.get()) {
                            groupCache[groupId] = updated
                            _userGroups.value = _userGroups.value.map { if (it.matchesGroupId(groupId)) updated else it }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to refresh group on role update: ${e.message}")
                    }
                }
            }
            is PipelineEvent.GroupMemberUpdated -> {
                val groupId = event.content?.jsonObject?.get("member")
                    ?.jsonObject?.get("groupId")?.jsonPrimitive?.content ?: return
                val ownerId = ownerUserId
                val generation = accountGeneration.get()
                scope.launch {
                    try {
                        val updated = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
                        if (ownerUserId == ownerId && generation == accountGeneration.get()) {
                            groupCache[groupId] = updated
                            _userGroups.value = _userGroups.value.map { if (it.matchesGroupId(groupId)) updated else it }
                        }
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

    private suspend fun refreshUserGroupsIfCurrent(ownerId: String, generation: Long) {
        if (ownerUserId != ownerId || generation != accountGeneration.get()) {
            return
        }
        val groups = BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            groupApi.getUserGroups(ownerId, n = count, offset = offset)
        }
        if (ownerUserId == ownerId && generation == accountGeneration.get()) {
            _userGroups.value = groups
        }
    }

    private fun isJoinedGroup(group: Group): Boolean {
        return membershipStatus(group) == "member"
    }

    private fun membershipStatus(group: Group): String {
        return group.myMember?.membershipStatus
            ?.takeIf { it.isNotBlank() }
            ?: group.membershipStatus
    }

    private fun inferJoinedMembershipStatus(group: Group?): String {
        return when {
            group == null -> "requested"
            membershipStatus(group) == "invited" -> "member"
            group.privacy.equals("public", ignoreCase = true) -> "member"
            else -> "requested"
        }
    }

    private fun findKnownGroup(groupId: String): Group? {
        return groupCache[groupId]
            ?: groupCache.values.firstOrNull { it.matchesGroupId(groupId) }
            ?: _userGroups.value.firstOrNull { it.matchesGroupId(groupId) }
    }

    private fun invalidateCachedGroup(groupId: String) {
        val matchingKeys = groupCache.entries
            .filter { (_, group) -> group.matchesGroupId(groupId) }
            .map { (key, _) -> key }

        matchingKeys.forEach { key -> groupCache.remove(key) }
    }

    private fun buildOptimisticGroup(
        groupId: String,
        previousGroup: Group?,
        membershipStatus: String,
    ): Group {
        val baseGroup = previousGroup ?: Group(
            id = groupId,
            groupId = groupId,
        )
        val optimisticMember = when {
            baseGroup.myMember != null -> baseGroup.myMember.copy(membershipStatus = membershipStatus)
            membershipStatus.isBlank() -> null
            else -> GroupMember(
                groupId = baseGroup.groupId.ifEmpty { baseGroup.id.ifEmpty { groupId } },
                membershipStatus = membershipStatus,
            )
        }
        return baseGroup.copy(
            membershipStatus = membershipStatus,
            myMember = optimisticMember,
        )
    }

    private fun Group.matchesGroupId(groupId: String): Boolean {
        return id == groupId || groupId == this.groupId
    }

    private companion object {
        const val GROUP_PAGE_SIZE = 100
    }
}
