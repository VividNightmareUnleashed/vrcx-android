package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.util.captureFailure
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.util.runIgnoringFailure
import com.vrcx.android.data.websocket.PipelineEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `grp_…` id this group denotes. `GET users/{userId}/groups` returns
 * membership objects whose `id` is `gmem_…` and whose `groupId` is the group;
 * every other endpoint returns the group itself with a `grp_…` `id` and no
 * `groupId`.
 */
fun Group.canonicalGroupId(): String = groupId.ifEmpty { id }

@Singleton
class GroupRepository(
    private val groupApi: GroupApi,
    private val dedup: RequestDeduplicator,
    accountScope: AccountScope,
    private val scope: CoroutineScope,
) : AccountScoped {
    @Inject
    constructor(groupApi: GroupApi, dedup: RequestDeduplicator, accountScope: AccountScope) : this(
        groupApi = groupApi,
        dedup = dedup,
        accountScope = accountScope,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val TAG = "GroupRepository"
    private val account = accountScope.bindTo(this)
    private val groupCache = ConcurrentHashMap<String, Group>()

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    /**
     * Loads the signed-in user's groups into [userGroups], the flow backing the
     * Groups tab. It takes no id: the account it publishes for is the one the
     * scope holds, so no caller can point this flow at somebody else's groups.
     * For another user's profile use [getUserGroups].
     */
    suspend fun loadMyGroups() {
        val token = account.current()
        if (token.ownerUserId.isEmpty()) return
        val groups = getUserGroups(token.ownerUserId)
        account.publishIfCurrent(token) { _userGroups.value = groups }
    }

    /**
     * All of [userId]'s groups, paginated. Read-only: it publishes to nothing,
     * so it is safe to call for someone else's profile.
     */
    suspend fun getUserGroups(userId: String): List<Group> =
        BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            groupApi.getUserGroups(userId, n = count, offset = offset)
        }

    override fun clearRuntimeState() {
        groupCache.clear()
        _userGroups.value = emptyList()
    }

    suspend fun getGroup(groupId: String): Group {
        groupCache[groupId]?.let { return it }
        val token = account.current()
        val group = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
        account.publishIfCurrent(token) { groupCache[group.canonicalGroupId()] = group }
        return group
    }
    suspend fun getGroupMembersPage(
        groupId: String,
        offset: Int = 0,
        count: Int = GROUP_PAGE_SIZE,
    ): GroupPage<GroupMember> {
        val members = groupApi.getGroupMembers(groupId, n = count, offset = offset)
        return GroupPage(
            items = members,
            nextOffset = offset + members.size,
            hasMore = members.size == count,
        )
    }

    suspend fun getGroupInstances(groupId: String): List<GroupInstance> = groupApi.getGroupInstances(groupId)

    suspend fun getGroupPostsPage(
        groupId: String,
        offset: Int = 0,
        count: Int = GROUP_PAGE_SIZE,
    ): GroupPage<GroupPost> {
        val response = groupApi.getGroupPosts(groupId = groupId, n = count, offset = offset)
        val nextOffset = offset + response.posts.size
        val authoritativeTotal = response.total.takeIf { it >= nextOffset }
        return GroupPage(
            items = response.posts,
            nextOffset = nextOffset,
            total = authoritativeTotal,
            hasMore = authoritativeTotal?.let { nextOffset < it } ?: (response.posts.size == count),
        )
    }

    suspend fun joinGroup(groupId: String): Group {
        val token = account.current()
        val currentGroup = findKnownGroup(groupId)
        groupApi.joinGroup(groupId)
        return runCatchingCancellable { refreshGroup(groupId, token) }.getOrElse {
            invalidateCachedGroup(groupId)
            val optimisticMembershipStatus = inferJoinedMembershipStatus(currentGroup)
            val optimisticGroup = buildOptimisticGroup(
                groupId = groupId,
                previousGroup = currentGroup,
                membershipStatus = optimisticMembershipStatus,
            )
            if (optimisticMembershipStatus == "member") {
                updateUserGroupsIfCurrent(token) { groups ->
                    groups.upsertGroup(groupId, optimisticGroup)
                }
            }
            optimisticGroup
        }
    }

    suspend fun leaveGroup(groupId: String): Group {
        val token = account.current()
        val currentGroup = findKnownGroup(groupId)
        groupApi.leaveGroup(groupId)
        return runCatchingCancellable { refreshGroup(groupId, token) }.getOrElse {
            invalidateCachedGroup(groupId)
            updateUserGroupsIfCurrent(token) { groups ->
                groups.filterNot { it.canonicalGroupId() == groupId }
            }
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
    suspend fun kickGroupMember(groupId: String, userId: String): Boolean =
        runCatchingCancellable { groupApi.kickGroupMember(groupId, userId) }.isSuccess

    fun handleEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.GroupJoined -> {
                val groupId = event.content.stringOrNull("groupId")
                if (groupId != null) invalidateCachedGroup(groupId)
                val token = account.current()
                if (token.ownerUserId.isNotEmpty()) {
                    scope.launch { runIgnoringFailure { refreshUserGroupsIfCurrent(token) } }
                }
            }
            is PipelineEvent.GroupLeft -> {
                val groupId = event.content.stringOrNull("groupId") ?: return
                val token = account.current()
                invalidateCachedGroup(groupId)
                updateUserGroupsIfCurrent(token) { groups ->
                    groups.filterNot { it.canonicalGroupId() == groupId }
                }
            }
            is PipelineEvent.GroupRoleUpdated -> {
                val groupId = event.content.objectOrNull("role").stringOrNull("groupId") ?: return
                refreshGroupAfterUpdate(groupId, updateType = "role")
            }
            is PipelineEvent.GroupMemberUpdated -> {
                val groupId = event.content.objectOrNull("member").stringOrNull("groupId") ?: return
                refreshGroupAfterUpdate(groupId, updateType = "member")
            }
            else -> {}
        }
    }

    private fun refreshGroupAfterUpdate(groupId: String, updateType: String) {
        val token = account.current()
        scope.launch {
            val failure = captureFailure {
                val updated = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
                account.publishIfCurrent(token) {
                    groupCache[updated.canonicalGroupId()] = updated
                    _userGroups.update { groups ->
                        groups.map { group ->
                            if (group.canonicalGroupId() == groupId) updated else group
                        }
                    }
                }
            }
            if (failure != null) {
                Log.d(TAG, "Failed to refresh group on $updateType update: ${failure.message}")
            }
        }
    }

    private suspend fun refreshGroup(groupId: String, token: AccountScope.Token): Group {
        val updated = dedup.dedupGet("group:$groupId:refresh") { groupApi.getGroup(groupId) }
        account.publishIfCurrent(token) {
            groupCache[updated.canonicalGroupId()] = updated
            _userGroups.update { groups ->
                if (isJoinedGroup(updated)) {
                    groups.upsertGroup(groupId, updated)
                } else {
                    groups.filterNot { it.canonicalGroupId() == groupId }
                }
            }
        }
        return updated
    }

    /**
     * Applies [transform] to the published group list, skipping it if the
     * account moved on since [token] was captured.
     */
    private fun updateUserGroupsIfCurrent(
        token: AccountScope.Token,
        transform: (List<Group>) -> List<Group>,
    ) {
        account.publishIfCurrent(token) { _userGroups.update(transform) }
    }

    private fun List<Group>.upsertGroup(groupId: String, group: Group): List<Group> {
        return if (any { it.canonicalGroupId() == groupId }) {
            map { if (it.canonicalGroupId() == groupId) group else it }
        } else {
            this + group
        }
    }

    private suspend fun refreshUserGroupsIfCurrent(token: AccountScope.Token) {
        if (!account.isCurrent(token)) return
        val groups = getUserGroups(token.ownerUserId)
        account.publishIfCurrent(token) { _userGroups.value = groups }
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
            ?: _userGroups.value.firstOrNull { it.canonicalGroupId() == groupId }
    }

    private fun invalidateCachedGroup(groupId: String) {
        groupCache.remove(groupId)
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

    /**
     * VRChat sends fields of inconsistent shape, so read them by cast rather
     * than through the throwing kotlinx accessors: an unexpected shape becomes
     * a missing field instead of an exception out of the pipeline collector.
     */
    private fun JsonElement?.objectOrNull(key: String): JsonElement? =
        (this as? JsonObject)?.get(key)

    private fun JsonElement?.stringOrNull(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val GROUP_PAGE_SIZE = 100
    }
}

data class GroupPage<T>(
    val items: List<T>,
    val nextOffset: Int,
    val total: Int? = null,
    val hasMore: Boolean,
)
