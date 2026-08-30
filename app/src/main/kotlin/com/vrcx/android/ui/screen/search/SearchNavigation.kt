package com.vrcx.android.ui.screen.search

internal data class SearchNavigation(
    val onUserClick: (String) -> Unit,
    val onWorldClick: (String) -> Unit,
    val onAvatarClick: (String) -> Unit,
    val onGroupClick: (String) -> Unit,
)
