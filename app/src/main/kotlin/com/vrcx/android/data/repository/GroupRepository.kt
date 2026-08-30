package com.vrcx.android.data.repository

import android.util.Log
import com.vrcx.android.data.api.BulkPaginator
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupInstance
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.util.captureFailure
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.data.util.runIgnoringFailure
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.IoDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    private data class GroupRefreshRevision(val group: Long, val snapshot: Long)

    @Inject
    constructor(
        groupApi: GroupApi,
        dedup: RequestDeduplicator,
        accountScope: AccountScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        groupApi = groupApi,
        dedup = dedup,
        accountScope = accountScope,
        scope = CoroutineScope(SupervisorJob() + ioDispatcher),
    )

    private val account = accountScope.bindTo(this)
    private val groupCache = ConcurrentHashMap<String, Group>()
    private val groupListRevision = AtomicLong()
    private val groupSnapshotGeneration = AtomicLong()
    private val groupRefreshRevisions = ConcurrentHashMap<String, Long>()

    private val _userGroups = MutableStateFlow<List<Group>>(emptyList())
    val userGroups: StateFlow<List<Group>> = _userGroups.asStateFlow()

    /**
     * Loads the signed-in user's groups into [userGroups], the flow backing the
     * Groups tab. It takes no id: the account it publishes for is the one the
     * scope holds, so no caller can point this flow at somebody else's groups.
     * For another user's profile use [getUserGroups].
     */
    suspend fun loadMyGroups() {
        loadMyGroups(account.current())
    }

    internal suspend fun loadMyGroups(token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty()) return
        val revision = beginGroupSnapshot(token) ?: return
        val groups = getCurrentUserGroups(token)
        publishGroupSnapshot(token, revision, groups)
    }

    /**
     * All of [userId]'s groups, paginated. Read-only: it publishes to nothing,
     * so it is safe to call for someone else's profile.
     */
    suspend fun getUserGroups(userId: String): List<Group> =
        BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            groupApi.getUserGroups(userId, n = count, offset = offset)
        }

    private suspend fun getCurrentUserGroups(token: AccountScope.Token): List<Group> =
        BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            account.ensureCurrent(token)
            groupApi.getUserGroups(token.ownerUserId, n = count, offset = offset)
        }

    override fun clearRuntimeState() {
        groupListRevision.incrementAndGet()
        groupSnapshotGeneration.incrementAndGet()
        groupRefreshRevisions.clear()
        groupCache.clear()
        _userGroups.value = emptyList()
    }

    suspend fun getGroup(groupId: String): Group {
        groupCache[groupId]?.let { return it }
        val token = account.current()
        val revision = currentGroupRefreshRevision(groupId)
        val group = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
        account.publishIfCurrent(token) {
            if (revision == currentGroupRefreshRevision(groupId)) {
                groupCache[group.canonicalGroupId()] = group
            }
        }
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
        val revision = beginGroupMutation(token, groupId)
        return runCatchingCancellable { refreshGroup(groupId, token, revision) }.getOrElse {
            val optimisticMembershipStatus = inferJoinedMembershipStatus(currentGroup)
            val optimisticGroup = buildOptimisticGroup(
                groupId = groupId,
                previousGroup = currentGroup,
                membershipStatus = optimisticMembershipStatus,
            )
            publishGroupMutation(token, groupId, revision) {
                invalidateCachedGroup(groupId)
                if (optimisticMembershipStatus == "member") {
                    _userGroups.update { groups ->
                        groups.upsertGroup(groupId, optimisticGroup)
                    }
                }
            }
            optimisticGroup
        }
    }

    suspend fun leaveGroup(groupId: String): Group {
        val token = account.current()
        val currentGroup = findKnownGroup(groupId)
        groupApi.leaveGroup(groupId)
        val revision = beginGroupMutation(token, groupId)
        return runCatchingCancellable { refreshGroup(groupId, token, revision) }.getOrElse {
            publishGroupMutation(token, groupId, revision) {
                invalidateCachedGroup(groupId)
                _userGroups.update { groups ->
                    groups.filterNot { it.canonicalGroupId() == groupId }
                }
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

    fun handleEvent(event: PipelineEvent, token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        when (event) {
            is PipelineEvent.GroupJoined -> handleGroupJoined(event, token)
            is PipelineEvent.GroupLeft -> handleGroupLeft(event, token)
            is PipelineEvent.GroupRoleUpdated -> handleGroupUpdate(event.content, "role", token)
            is PipelineEvent.GroupMemberUpdated -> handleGroupUpdate(event.content, "member", token)
            else -> {}
        }
    }

    internal fun handleEvent(event: PipelineEvent) {
        handleEvent(event, account.current())
    }

    private fun handleGroupJoined(event: PipelineEvent.GroupJoined, token: AccountScope.Token) {
        val groupId = event.content.stringOrNull("groupId")
        val revision = beginGroupSnapshot(token) {
            if (groupId != null) invalidateCachedGroup(groupId)
        } ?: return
        scope.launch { runIgnoringFailure { refreshUserGroupsIfCurrent(token, revision) } }
    }

    private fun handleGroupLeft(event: PipelineEvent.GroupLeft, token: AccountScope.Token) {
        val groupId = event.content.stringOrNull("groupId") ?: return
        beginGroupMutation(token, groupId) {
            invalidateCachedGroup(groupId)
            _userGroups.update { groups ->
                groups.filterNot { it.canonicalGroupId() == groupId }
            }
        }
    }

    private fun handleGroupUpdate(content: JsonElement?, payloadKey: String, token: AccountScope.Token) {
        val groupId = content.objectOrNull(payloadKey).stringOrNull("groupId") ?: return
        val revision = beginGroupMutation(token, groupId) ?: return
        refreshGroupAfterUpdate(groupId, updateType = payloadKey, token = token, revision = revision)
    }

    private fun refreshGroupAfterUpdate(
        groupId: String,
        updateType: String,
        token: AccountScope.Token,
        revision: GroupRefreshRevision,
    ) {
        scope.launch {
            val failure = captureFailure {
                // Each event needs a response started after that event. Sharing an
                // older in-flight read would let the newer revision publish stale data.
                val updated = groupApi.getGroup(groupId)
                publishGroupMutation(token, groupId, revision) {
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

    private suspend fun refreshGroup(
        groupId: String,
        token: AccountScope.Token,
        revision: GroupRefreshRevision?,
    ): Group {
        // A mutation confirmation cannot share a read started before that mutation.
        val updated = groupApi.getGroup(groupId)
        publishGroupMutation(token, groupId, revision) {
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

    private fun List<Group>.upsertGroup(groupId: String, group: Group): List<Group> = if (any {
            it.canonicalGroupId() == groupId
        }
    ) {
        map { if (it.canonicalGroupId() == groupId) group else it }
    } else {
        this + group
    }

    private suspend fun refreshUserGroupsIfCurrent(token: AccountScope.Token, revision: Long) {
        if (!account.isCurrent(token)) return
        val groups = getCurrentUserGroups(token)
        publishGroupSnapshot(token, revision, groups)
    }

    private fun publishGroupSnapshot(token: AccountScope.Token, revision: Long, groups: List<Group>) {
        account.publishIfCurrent(token) {
            if (revision != groupListRevision.get()) return@publishIfCurrent
            groupSnapshotGeneration.incrementAndGet()
            groupCache.clear()
            groups.forEach { group -> groupCache[group.canonicalGroupId()] = group }
            _userGroups.value = groups
        }
    }

    private fun beginGroupSnapshot(token: AccountScope.Token, mutation: () -> Unit = {}): Long? {
        var revision: Long? = null
        account.publishIfCurrent(token) {
            revision = groupListRevision.incrementAndGet()
            mutation()
        }
        return revision
    }

    private fun beginGroupMutation(
        token: AccountScope.Token,
        groupId: String,
        mutation: () -> Unit = {},
    ): GroupRefreshRevision? {
        var revision: GroupRefreshRevision? = null
        account.publishIfCurrent(token) {
            groupListRevision.incrementAndGet()
            val groupRevision = groupRefreshRevisions.merge(groupId, 1L) { current, increment ->
                current + increment
            } ?: 1L
            revision = GroupRefreshRevision(groupRevision, groupSnapshotGeneration.get())
            mutation()
        }
        return revision
    }

    private fun currentGroupRefreshRevision(groupId: String): GroupRefreshRevision = GroupRefreshRevision(
        group = groupRefreshRevisions[groupId] ?: 0L,
        snapshot = groupSnapshotGeneration.get(),
    )

    private fun publishGroupMutation(
        token: AccountScope.Token,
        groupId: String,
        revision: GroupRefreshRevision?,
        publish: () -> Unit,
    ) {
        if (revision == null) return
        account.publishIfCurrent(token) {
            if (revision == currentGroupRefreshRevision(groupId)) publish()
        }
    }

    private fun isJoinedGroup(group: Group): Boolean = membershipStatus(group) == "member"

    private fun membershipStatus(group: Group): String = group.myMember?.membershipStatus
        ?.takeIf { it.isNotBlank() }
        ?: group.membershipStatus

    private fun inferJoinedMembershipStatus(group: Group?): String = when {
        group == null -> "requested"
        membershipStatus(group) == "invited" -> "member"
        group.privacy.equals("public", ignoreCase = true) -> "member"
        else -> "requested"
    }

    private fun findKnownGroup(groupId: String): Group? = groupCache[groupId]
        ?: _userGroups.value.firstOrNull { it.canonicalGroupId() == groupId }

    private fun invalidateCachedGroup(groupId: String) {
        groupCache.remove(groupId)
    }

    private fun buildOptimisticGroup(groupId: String, previousGroup: Group?, membershipStatus: String): Group {
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
    private fun JsonElement?.objectOrNull(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement?.stringOrNull(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "GroupRepository"
        const val GROUP_PAGE_SIZE = 100
    }
}

data class GroupPage<T>(val items: List<T>, val nextOffset: Int, val total: Int? = null, val hasMore: Boolean)
