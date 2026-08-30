package com.vrcx.android.ui.screen.search

fun SearchViewModel.updateQuery(query: String) = handle(SearchCommand.UpdateQuery(query))

fun SearchViewModel.selectTab(tab: SearchTab) = handle(SearchCommand.SelectTab(tab))

fun SearchViewModel.setSearchUsersByBio(enabled: Boolean) = handle(SearchCommand.SearchUsersByBio(enabled))

fun SearchViewModel.setSortUsersByLastLogin(enabled: Boolean) = handle(SearchCommand.SortUsersByLastLogin(enabled))

fun SearchViewModel.setWorldMode(mode: WorldSearchMode) = handle(SearchCommand.SetWorldMode(mode))

fun SearchViewModel.setIncludeWorldLabs(enabled: Boolean) = handle(SearchCommand.IncludeWorldLabs(enabled))

fun SearchViewModel.setWorldTag(tag: String) = handle(SearchCommand.SetWorldTag(tag))

fun SearchViewModel.setAvatarSearchSource(source: AvatarSearchSource) = handle(SearchCommand.SetAvatarSource(source))

fun SearchViewModel.setAvatarProviderUrl(url: String) = handle(SearchCommand.SetAvatarProviderUrl(url))
