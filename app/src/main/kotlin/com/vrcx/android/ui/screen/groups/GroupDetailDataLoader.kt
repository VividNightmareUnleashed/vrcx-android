package com.vrcx.android.ui.screen.groups

import com.vrcx.android.data.api.model.Group
import com.vrcx.android.data.api.model.GroupMember
import com.vrcx.android.data.api.model.GroupPost
import com.vrcx.android.data.repository.GroupPage
import com.vrcx.android.data.repository.GroupRepository
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.LoadState
import com.vrcx.android.ui.common.completeLoad
import com.vrcx.android.ui.common.failLoad
import com.vrcx.android.ui.common.isBusy
import com.vrcx.android.ui.common.settleLoad
import com.vrcx.android.ui.common.startLoad
import com.vrcx.android.ui.common.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class GroupDetailDataLoader(
    private val groupId: String,
    private val groupRepository: GroupRepository,
    private val state: MutableStateFlow<GroupDetailState>,
    private val scope: CoroutineScope,
) {
    private val resourceLoader = GroupDetailResourceLoader(scope)
    private val membersPager = GroupDetailPager(
        source = GroupDetailPageSource(
            current = { state.value.members },
            update = { members -> state.update { it.copy(members = members) } },
            fallbackError = "Failed to load members",
            fetch = { offset -> groupRepository.getGroupMembersPage(groupId, offset = offset) },
            total = { _, itemCount ->
                state.value.group.valueOrNull?.memberCount?.takeIf { it >= itemCount }
            },
            key = { member: GroupMember -> member.id.ifBlank { member.userId } },
        ),
        scope = scope,
    )
    private val postsPager = GroupDetailPager(
        source = GroupDetailPageSource(
            current = { state.value.posts },
            update = { posts -> state.update { it.copy(posts = posts) } },
            fallbackError = "Failed to load posts",
            fetch = { offset -> groupRepository.getGroupPostsPage(groupId, offset = offset) },
            total = { page, _ -> page.total },
            key = GroupPost::id,
        ),
        scope = scope,
    )

    fun dispatch(intent: GroupDetailIntent.Load) {
        when (intent) {
            GroupDetailIntent.RetryGroup -> loadGroup()

            GroupDetailIntent.RetryMembers -> if (!state.value.isActionLoading) {
                loadMembers(reset = true)
            }

            GroupDetailIntent.RetryInstances -> loadInstances()

            GroupDetailIntent.RetryPosts -> loadPosts(reset = true)

            GroupDetailIntent.LoadMoreMembers -> if (!state.value.isActionLoading) {
                loadMembers(reset = false)
            }

            GroupDetailIntent.LoadMorePosts -> loadPosts(reset = false)
        }
    }

    private fun loadGroup() {
        resourceLoader.load(
            current = { state.value.group },
            update = { group -> state.update { it.copy(group = group) } },
            fallbackError = "Failed to load group",
            fetch = { groupRepository.getGroup(groupId) },
            onLoaded = { group -> applyAuthoritativeMemberCount(group.memberCount) },
        )
    }

    private fun loadInstances() {
        resourceLoader.load(
            current = { state.value.instances },
            update = { instances -> state.update { it.copy(instances = instances) } },
            fallbackError = "Failed to load instances",
            fetch = { groupRepository.getGroupInstances(groupId) },
        )
    }

    private fun loadMembers(reset: Boolean) {
        if (reset) membersPager.loadFirstPage() else membersPager.appendNextPage()
    }

    private fun loadPosts(reset: Boolean) {
        if (reset) postsPager.loadFirstPage() else postsPager.appendNextPage()
    }

    private fun applyAuthoritativeMemberCount(count: Int) {
        state.update { current ->
            current.copy(
                members = current.members.mapLoaded { members ->
                    if (count < members.items.size) {
                        members
                    } else {
                        members.copy(
                            totalCount = count,
                            hasMore = members.items.isNotEmpty() && members.nextOffset < count,
                        )
                    }
                },
            )
        }
    }
}

private class GroupDetailResourceLoader(private val scope: CoroutineScope) {
    fun <T> load(
        current: () -> LoadState<T>,
        update: (LoadState<T>) -> Unit,
        fallbackError: String,
        fetch: suspend () -> T,
        onLoaded: (T) -> Unit = {},
    ) {
        if (current().isBusy) return
        update(current().startLoad())
        scope.launch {
            try {
                runCatchingCancellable { fetch() }
                    .fold(
                        onSuccess = { value ->
                            update(current().completeLoad(value))
                            onLoaded(value)
                        },
                        onFailure = { failure ->
                            update(current().failLoad(failure.message ?: fallbackError))
                        },
                    )
            } finally {
                update(current().settleLoad())
            }
        }
    }
}

private class GroupDetailPageSource<T>(
    val current: () -> LoadState<GroupPagedData<T>>,
    val update: (LoadState<GroupPagedData<T>>) -> Unit,
    val fallbackError: String,
    val fetch: suspend (offset: Int) -> GroupPage<T>,
    val total: (page: GroupPage<T>, itemCount: Int) -> Int?,
    val key: (T) -> String,
)

private class GroupDetailPager<T>(private val source: GroupDetailPageSource<T>, private val scope: CoroutineScope) {
    fun loadFirstPage() {
        if (source.current().isBusy) return
        source.update(source.current().startLoad())
        scope.launch {
            try {
                runCatchingCancellable { source.fetch(FIRST_PAGE_OFFSET) }
                    .fold(
                        onSuccess = { page -> publishFirstPage(page) },
                        onFailure = { failure ->
                            source.update(
                                source.current().failLoad(
                                    failure.message ?: source.fallbackError,
                                ),
                            )
                        },
                    )
            } finally {
                source.update(source.current().settleLoad())
            }
        }
    }

    fun appendNextPage() {
        val initial = source.current()
        val previous = initial.valueOrNull
        if (initial.isBusy || previous == null) return
        if (!previous.hasMore || previous.appendState == GroupAppendState.Loading) return

        source.update(initial.completeLoad(previous.copy(appendState = GroupAppendState.Loading)))
        scope.launch {
            runCatchingCancellable { source.fetch(previous.nextOffset) }
                .fold(
                    onSuccess = { page -> publishAppendedPage(page, previous) },
                    onFailure = { failure -> publishAppendFailure(failure, previous) },
                )
        }
    }

    private fun publishFirstPage(page: GroupPage<T>) {
        val items = page.items.distinctBy(source.key)
        source.update(
            source.current().completeLoad(
                page.toPagedData(items, source.total(page, items.size), page.nextOffset),
            ),
        )
    }

    private fun publishAppendedPage(page: GroupPage<T>, previous: GroupPagedData<T>) {
        val latest = source.current().valueOrNull ?: previous
        val items = (latest.items + page.items).distinctBy(source.key)
        val authoritativeTotal = source.total(page, items.size) ?: latest.totalCount
        // A concurrent refresh can advance the live page while this append is in flight.
        val nextOffset = (page.nextOffset + (latest.nextOffset - previous.nextOffset))
            .coerceAtLeast(FIRST_PAGE_OFFSET)
        source.update(
            source.current().completeLoad(
                page.toPagedData(items, authoritativeTotal, nextOffset),
            ),
        )
    }

    private fun publishAppendFailure(failure: Throwable, previous: GroupPagedData<T>) {
        val latest = source.current().valueOrNull ?: previous
        source.update(
            source.current().completeLoad(
                latest.copy(
                    appendState = GroupAppendState.Error(
                        failure.message ?: source.fallbackError,
                    ),
                ),
            ),
        )
    }

    private fun GroupPage<T>.toPagedData(items: List<T>, authoritativeTotal: Int?, nextOffset: Int) = GroupPagedData(
        items = items,
        nextOffset = nextOffset,
        totalCount = authoritativeTotal,
        hasMore = this.items.isNotEmpty() &&
            (authoritativeTotal?.let { nextOffset < it } ?: hasMore),
    )
}

internal fun <T> LoadState<T>.mapLoaded(transform: (T) -> T): LoadState<T> =
    if (this is LoadState.Loaded) copy(value = transform(value)) else this

private const val FIRST_PAGE_OFFSET = 0
