package com.vrcx.android.ui.screen.search

internal sealed interface SearchCommand {
    sealed interface Criteria : SearchCommand

    sealed interface Paging : SearchCommand

    data class UpdateQuery(val query: String) : Criteria

    data class SelectTab(val tab: SearchTab) : Criteria

    data class SearchUsersByBio(val enabled: Boolean) : Criteria

    data class SortUsersByLastLogin(val enabled: Boolean) : Criteria

    data class SetWorldMode(val mode: WorldSearchMode) : Criteria

    data class IncludeWorldLabs(val enabled: Boolean) : Criteria

    data class SetWorldTag(val tag: String) : Criteria

    data class SetAvatarSource(val source: AvatarSearchSource) : Criteria

    data class SetAvatarProviderUrl(val url: String) : Criteria

    data object NextPage : Paging

    data object PreviousPage : Paging

    data object Retry : Paging
}
