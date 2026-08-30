package com.vrcx.android.ui.screen.search

fun SearchViewModel.nextPage() = handle(SearchCommand.NextPage)

fun SearchViewModel.previousPage() = handle(SearchCommand.PreviousPage)

fun SearchViewModel.retry() = handle(SearchCommand.Retry)
