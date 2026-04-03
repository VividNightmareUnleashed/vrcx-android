package com.vrcx.android.data.repository

import com.vrcx.android.data.api.InviteMessageApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class InviteMessageType(val apiValue: String) {
    MESSAGE("message"),
    REQUEST("request"),
    RESPONSE("response"),
    REQUEST_RESPONSE("requestResponse"),
}

data class InviteMessageTemplate(
    val slot: Int,
    val message: String,
    val updatedAt: String,
    val messageType: InviteMessageType,
)

@Serializable
private data class InviteMessageTemplateDto(
    val slot: Int = 0,
    val message: String = "",
    val updatedAt: String = "",
)

@Singleton
class InviteMessageRepository @Inject constructor(
    private val inviteMessageApi: InviteMessageApi,
    private val authRepository: AuthRepository,
    private val json: Json,
) {
    suspend fun getMessages(messageType: InviteMessageType): List<InviteMessageTemplate> {
        val userId = authRepository.currentUser?.id ?: error("Current user is not available")
        val response = inviteMessageApi.getInviteMessages(userId, messageType.apiValue)
        return json.decodeFromJsonElement(ListSerializer(InviteMessageTemplateDto.serializer()), response)
            .sortedBy { it.slot }
            .map { template ->
                InviteMessageTemplate(
                    slot = template.slot,
                    message = template.message,
                    updatedAt = template.updatedAt,
                    messageType = messageType,
                )
            }
    }
}
