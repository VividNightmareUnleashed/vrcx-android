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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val GROUP_PAGE_SIZE = 100
private const val GROUP_LOG_TAG = "GroupRepository"

internal data class GroupRefreshRevision(val group: Long, val snapshot: Long)

internal class GroupRepositoryComponents(
    groupApi: GroupApi,
    dedup: RequestDeduplicator,
    val account: AccountScope,
    scope: CoroutineScope,
) {
    val state = GroupRuntimeState()
    val revisions = GroupRevisionGate(account)
    val reader = GroupReader(groupApi, dedup, account, state, revisions)
    val snapshot = GroupSnapshotLoader(account, state, revisions, reader)
    val memberships = GroupMembershipManager(groupApi, account, state, revisions)
    val events = GroupEventCoordinator(groupApi, account, state, revisions, snapshot, scope)
}

internal class GroupRuntimeState {
    private val cache = ConcurrentHashMap<String, Group>()
    private val groups = MutableStateFlow<List<Group>>(emptyList())

    val userGroups: StateFlow<List<Group>> = groups.asStateFlow()

    fun clear() {
        cache.clear()
        groups.value = emptyList()
    }

    fun findKnown(groupId: String): Group? = cache[groupId]
        ?: groups.value.firstOrNull { it.canonicalGroupId() == groupId }

    fun findCached(groupId: String): Group? = cache[groupId]

    fun invalidate(groupId: String) {
        cache.remove(groupId)
    }

    fun cache(group: Group) {
        cache[group.canonicalGroupId()] = group
    }

    fun replaceSnapshot(snapshot: List<Group>) {
        cache.clear()
        snapshot.forEach(::cache)
        groups.value = snapshot
    }

    fun upsert(groupId: String, group: Group) {
        groups.update { it.upsertGroup(groupId, group) }
    }

    fun remove(groupId: String) {
        groups.update { current -> current.filterNot { it.canonicalGroupId() == groupId } }
    }

    fun replaceExisting(groupId: String, group: Group) {
        groups.update { current ->
            current.map { if (it.canonicalGroupId() == groupId) group else it }
        }
    }

    fun publishMembership(groupId: String, group: Group, joined: Boolean) {
        cache(group)
        if (joined) upsert(groupId, group) else remove(groupId)
    }
}

internal class GroupRevisionGate(private val account: AccountScope) {
    private val listRevision = AtomicLong()
    private val snapshotGeneration = AtomicLong()
    private val refreshRevisions = ConcurrentHashMap<String, Long>()

    fun clear() {
        listRevision.incrementAndGet()
        snapshotGeneration.incrementAndGet()
        refreshRevisions.clear()
    }

    fun current(groupId: String): GroupRefreshRevision = GroupRefreshRevision(
        group = refreshRevisions[groupId] ?: 0L,
        snapshot = snapshotGeneration.get(),
    )

    fun beginSnapshot(token: AccountScope.Token, mutation: () -> Unit = {}): Long? {
        var revision: Long? = null
        account.publishIfCurrent(token) {
            revision = listRevision.incrementAndGet()
            mutation()
        }
        return revision
    }

    fun beginMutation(token: AccountScope.Token, groupId: String, mutation: () -> Unit = {}): GroupRefreshRevision? {
        var revision: GroupRefreshRevision? = null
        account.publishIfCurrent(token) {
            listRevision.incrementAndGet()
            val groupRevision = refreshRevisions.merge(groupId, 1L) { current, increment ->
                current + increment
            } ?: 1L
            revision = GroupRefreshRevision(groupRevision, snapshotGeneration.get())
            mutation()
        }
        return revision
    }

    fun publishSnapshot(token: AccountScope.Token, revision: Long, publish: () -> Unit) {
        account.publishIfCurrent(token) {
            if (revision == listRevision.get()) {
                snapshotGeneration.incrementAndGet()
                publish()
            }
        }
    }

    fun publishMutation(
        token: AccountScope.Token,
        groupId: String,
        revision: GroupRefreshRevision?,
        publish: () -> Unit,
    ) {
        if (revision == null) return
        account.publishIfCurrent(token) {
            if (revision == current(groupId)) publish()
        }
    }
}

internal class GroupReader(
    private val groupApi: GroupApi,
    private val dedup: RequestDeduplicator,
    private val account: AccountScope,
    private val state: GroupRuntimeState,
    private val revisions: GroupRevisionGate,
) : GroupQueries {
    override suspend fun getUserGroups(userId: String): List<Group> =
        BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            groupApi.getUserGroups(userId, n = count, offset = offset)
        }

    suspend fun getCurrentUserGroups(token: AccountScope.Token): List<Group> =
        BulkPaginator.fetchAll(pageSize = GROUP_PAGE_SIZE) { offset, count ->
            account.ensureCurrent(token)
            groupApi.getUserGroups(token.ownerUserId, n = count, offset = offset)
        }

    override suspend fun getGroup(groupId: String): Group {
        state.findCached(groupId)?.let { return it }
        val token = account.current()
        val revision = revisions.current(groupId)
        val group = dedup.dedupGet("group:$groupId") { groupApi.getGroup(groupId) }
        revisions.publishMutation(token, groupId, revision) { state.cache(group) }
        return group
    }

    override suspend fun getGroupMembersPage(groupId: String, offset: Int, count: Int): GroupPage<GroupMember> {
        val members = groupApi.getGroupMembers(groupId, n = count, offset = offset)
        return GroupPage(
            items = members,
            nextOffset = offset + members.size,
            hasMore = members.size == count,
        )
    }

    override suspend fun getGroupInstances(groupId: String): List<GroupInstance> = groupApi.getGroupInstances(groupId)

    override suspend fun getGroupPostsPage(groupId: String, offset: Int, count: Int): GroupPage<GroupPost> {
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
}

internal class GroupSnapshotLoader(
    private val account: AccountScope,
    private val state: GroupRuntimeState,
    private val revisions: GroupRevisionGate,
    private val reader: GroupReader,
) {
    suspend fun load(token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty()) return
        val revision = revisions.beginSnapshot(token) ?: return
        val groups = reader.getCurrentUserGroups(token)
        revisions.publishSnapshot(token, revision) { state.replaceSnapshot(groups) }
    }

    suspend fun refresh(token: AccountScope.Token, revision: Long) {
        if (!account.isCurrent(token)) return
        val groups = reader.getCurrentUserGroups(token)
        revisions.publishSnapshot(token, revision) { state.replaceSnapshot(groups) }
    }
}

internal class GroupMembershipManager(
    private val groupApi: GroupApi,
    private val account: AccountScope,
    private val state: GroupRuntimeState,
    private val revisions: GroupRevisionGate,
) : GroupMembershipOperations {
    override suspend fun joinGroup(groupId: String): Group {
        val token = account.current()
        val currentGroup = state.findKnown(groupId)
        groupApi.joinGroup(groupId)
        val revision = revisions.beginMutation(token, groupId)
        return runCatchingCancellable { refresh(groupId, token, revision) }.getOrElse {
            val status = inferJoinedMembershipStatus(currentGroup)
            val optimistic = buildOptimisticGroup(groupId, currentGroup, status)
            revisions.publishMutation(token, groupId, revision) {
                state.invalidate(groupId)
                if (status == "member") state.upsert(groupId, optimistic)
            }
            optimistic
        }
    }

    override suspend fun leaveGroup(groupId: String): Group {
        val token = account.current()
        val currentGroup = state.findKnown(groupId)
        groupApi.leaveGroup(groupId)
        val revision = revisions.beginMutation(token, groupId)
        return runCatchingCancellable { refresh(groupId, token, revision) }.getOrElse {
            revisions.publishMutation(token, groupId, revision) {
                state.invalidate(groupId)
                state.remove(groupId)
            }
            buildOptimisticGroup(groupId, currentGroup, membershipStatus = "")
        }
    }

    override suspend fun kickGroupMember(groupId: String, userId: String): Boolean =
        runCatchingCancellable { groupApi.kickGroupMember(groupId, userId) }.isSuccess

    private suspend fun refresh(groupId: String, token: AccountScope.Token, revision: GroupRefreshRevision?): Group {
        val updated = groupApi.getGroup(groupId)
        revisions.publishMutation(token, groupId, revision) {
            state.publishMembership(groupId, updated, isJoinedGroup(updated))
        }
        return updated
    }

    private fun inferJoinedMembershipStatus(group: Group?): String = when {
        group == null -> "requested"
        membershipStatus(group) == "invited" -> "member"
        group.privacy.equals("public", ignoreCase = true) -> "member"
        else -> "requested"
    }

    private fun buildOptimisticGroup(groupId: String, previousGroup: Group?, membershipStatus: String): Group {
        val baseGroup = previousGroup ?: Group(id = groupId, groupId = groupId)
        val optimisticMember = when {
            baseGroup.myMember != null -> baseGroup.myMember.copy(membershipStatus = membershipStatus)

            membershipStatus.isBlank() -> null

            else -> GroupMember(
                groupId = baseGroup.groupId.ifEmpty { baseGroup.id.ifEmpty { groupId } },
                membershipStatus = membershipStatus,
            )
        }
        return baseGroup.copy(membershipStatus = membershipStatus, myMember = optimisticMember)
    }
}

internal class GroupEventCoordinator(
    private val groupApi: GroupApi,
    private val account: AccountScope,
    private val state: GroupRuntimeState,
    private val revisions: GroupRevisionGate,
    private val snapshot: GroupSnapshotLoader,
    private val scope: CoroutineScope,
) {
    fun handle(event: PipelineEvent, token: AccountScope.Token) {
        if (token.ownerUserId.isEmpty() || !account.isCurrent(token)) return
        when (event) {
            is PipelineEvent.GroupJoined -> handleJoined(event, token)
            is PipelineEvent.GroupLeft -> handleLeft(event, token)
            is PipelineEvent.GroupRoleUpdated -> handleUpdate(event.content, "role", token)
            is PipelineEvent.GroupMemberUpdated -> handleUpdate(event.content, "member", token)
            else -> Unit
        }
    }

    private fun handleJoined(event: PipelineEvent.GroupJoined, token: AccountScope.Token) {
        val groupId = event.content.stringOrNull("groupId")
        val revision = revisions.beginSnapshot(token) {
            if (groupId != null) state.invalidate(groupId)
        } ?: return
        scope.launch { runIgnoringFailure { snapshot.refresh(token, revision) } }
    }

    private fun handleLeft(event: PipelineEvent.GroupLeft, token: AccountScope.Token) {
        val groupId = event.content.stringOrNull("groupId") ?: return
        revisions.beginMutation(token, groupId) {
            state.invalidate(groupId)
            state.remove(groupId)
        }
    }

    private fun handleUpdate(content: JsonElement?, payloadKey: String, token: AccountScope.Token) {
        val groupId = content.objectOrNull(payloadKey).stringOrNull("groupId") ?: return
        val revision = revisions.beginMutation(token, groupId) ?: return
        scope.launch {
            val failure = captureFailure {
                // Each event needs a read started after that event; an older deduplicated read may be stale.
                val updated = groupApi.getGroup(groupId)
                revisions.publishMutation(token, groupId, revision) {
                    state.cache(updated)
                    state.replaceExisting(groupId, updated)
                }
            }
            if (failure != null) {
                Log.d(GROUP_LOG_TAG, "Failed to refresh group on $payloadKey update: ${failure.message}")
            }
        }
    }
}

private fun List<Group>.upsertGroup(groupId: String, group: Group): List<Group> =
    if (any { it.canonicalGroupId() == groupId }) {
        map { if (it.canonicalGroupId() == groupId) group else it }
    } else {
        this + group
    }

private fun membershipStatus(group: Group): String = group.myMember?.membershipStatus
    ?.takeIf(String::isNotBlank)
    ?: group.membershipStatus

private fun isJoinedGroup(group: Group): Boolean = membershipStatus(group) == "member"

private fun JsonElement?.objectOrNull(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private fun JsonElement?.stringOrNull(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
