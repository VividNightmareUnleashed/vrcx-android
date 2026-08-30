package com.vrcx.android

import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** Runs background work inline when a test does not need scheduler control. */
internal val directTestDispatcher: CoroutineDispatcher = Executor { command -> command.run() }.asCoroutineDispatcher()
