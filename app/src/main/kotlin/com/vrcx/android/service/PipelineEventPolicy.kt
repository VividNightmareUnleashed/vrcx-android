package com.vrcx.android.service

import android.util.Log
import com.vrcx.android.data.repository.AccountChangedException
import com.vrcx.android.data.repository.AccountScope
import com.vrcx.android.data.repository.AccountScopedEvent
import kotlinx.coroutines.CancellationException

internal inline fun <T> consumeAccountScopedPipelineEvent(
    accountScope: AccountScope,
    pipelineAccount: AccountScope.Token,
    event: AccountScopedEvent<T>,
    crossinline consume: (T) -> Unit,
): Boolean {
    if (event.origin != pipelineAccount) return false
    return accountScope.publishIfCurrent(event.origin) { consume(event.value) }
}

// A capability boundary contains malformed payloads so one event cannot cancel the shared collector.
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal inline fun <T> containPipelineFailure(capability: String, block: () -> T): T? = try {
    block()
} catch (_: AccountChangedException) {
    Log.d(SERVICE_LOG_TAG, "Discarded stale $capability work after an account change")
    null
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Log.w(SERVICE_LOG_TAG, "Pipeline event dropped by $capability", error)
    null
}
