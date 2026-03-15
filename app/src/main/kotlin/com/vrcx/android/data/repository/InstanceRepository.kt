package com.vrcx.android.data.repository

import com.vrcx.android.data.api.InstanceApi
import com.vrcx.android.data.api.model.Instance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstanceRepository @Inject constructor(
    private val instanceApi: InstanceApi,
) {
    data class QueueState(
        val instanceLocation: String,
        val position: Int = 0,
        val queueSize: Int = 0,
        val isReady: Boolean = false,
    )

    private val _queueState = MutableStateFlow<QueueState?>(null)
    val queueState: StateFlow<QueueState?> = _queueState.asStateFlow()

    fun handleQueueUpdate(instanceLocation: String, position: Int, queueSize: Int) {
        _queueState.value = QueueState(instanceLocation, position, queueSize)
    }

    fun handleQueueReady(instanceLocation: String) {
        _queueState.value = _queueState.value?.copy(isReady = true)
            ?: QueueState(instanceLocation, isReady = true)
    }

    fun handleQueueLeft(instanceLocation: String) {
        if (_queueState.value?.instanceLocation == instanceLocation) {
            _queueState.value = null
        }
    }

    suspend fun getInstance(worldId: String, instanceId: String): Instance =
        instanceApi.getInstance(worldId, instanceId)
}
