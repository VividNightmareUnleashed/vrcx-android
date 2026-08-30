package com.vrcx.android.data.repository

import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.websocket.PipelineEvent
import com.vrcx.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/** Resolves membership-shaped `gmem_` rows to the `grp_` id used by group endpoints. */
fun Group.canonicalGroupId(): String = groupId.ifEmpty { id }

@Singleton
class GroupRepository private constructor(
    private val components: GroupRepositoryComponents,
    accountScope: AccountScope,
) : AccountScoped,
    GroupQueries by components.reader,
    GroupMembershipOperations by components.memberships {

    constructor(
        groupApi: GroupApi,
        dedup: RequestDeduplicator,
        accountScope: AccountScope,
        scope: CoroutineScope,
    ) : this(
        components = GroupRepositoryComponents(groupApi, dedup, accountScope, scope),
        accountScope = accountScope,
    )

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

    init {
        accountScope.bindTo(this)
    }

    val userGroups: StateFlow<List<Group>> = components.state.userGroups

    suspend fun loadMyGroups() {
        components.snapshot.load(components.account.current())
    }

    internal suspend fun loadMyGroups(token: AccountScope.Token) {
        components.snapshot.load(token)
    }

    override fun clearRuntimeState() {
        components.revisions.clear()
        components.state.clear()
    }

    fun handleEvent(event: PipelineEvent, token: AccountScope.Token) {
        components.events.handle(event, token)
    }

    internal fun handleEvent(event: PipelineEvent) {
        components.events.handle(event, components.account.current())
    }
}

data class GroupPage<T>(val items: List<T>, val nextOffset: Int, val total: Int? = null, val hasMore: Boolean)
