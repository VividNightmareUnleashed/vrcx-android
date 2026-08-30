package com.vrcx.android.ui.common

import kotlinx.coroutines.flow.SharingStarted

/** Keeps derived screen state warm across brief navigation and configuration gaps. */
internal val whileUiSubscribed = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
