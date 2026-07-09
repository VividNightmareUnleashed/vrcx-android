package com.vrcx.android.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Instance(
    val active: Boolean = true,
    val canRequestInvite: Boolean = false,
    val capacity: Int = 0,
    val closedAt: String? = null,
    val displayName: String = "",
    @SerialName("full") val isFull: Boolean = false,
    val gameServerVersion: Int = 0,
    val id: String = "",
    val instanceId: String = "",
    val instancePersistenceEnabled: String = "",
    val location: String = "",
    @SerialName("n_users") val nUsers: Int = 0,
    val name: String = "",
    val ownerId: String? = null,
    val permanent: Boolean = false,
    val photonRegion: String = "",
    val platforms: InstancePlatforms = InstancePlatforms(),
    val queueEnabled: Boolean = false,
    val queueSize: Int = 0,
    val recommendedCapacity: Int = 0,
    val region: String = "",
    val secureName: String = "",
    val shortName: String? = null,
    val strict: Boolean = false,
    val tags: List<String> = emptyList(),
    val type: String = "",
    val userCount: Int = 0,
    val users: List<VrcUser> = emptyList(),
    val world: World? = null,
    val worldId: String = "",
)

@Serializable
data class InstancePlatforms(
    val android: Int = 0,
    val ios: Int = 0,
    val standalonewindows: Int = 0,
)

@Serializable
data class InstanceShortName(
    val secureName: String = "",
    val shortName: String = "",
)
