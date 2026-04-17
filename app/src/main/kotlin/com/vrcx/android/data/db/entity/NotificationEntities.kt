package com.vrcx.android.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [Index(value = ["ownerUserId"])],
)
data class NotificationEntity(
    @PrimaryKey val id: String = "",
    val ownerUserId: String = "",
    val type: String = "",
    val senderUserId: String = "",
    val senderUsername: String = "",
    val receiverUserId: String = "",
    val message: String = "",
    val details: String = "",
    val seen: Boolean = false,
    val createdAt: String = "",
)

@Entity(
    tableName = "notifications_v2",
    indices = [Index(value = ["ownerUserId"])],
)
data class NotificationV2Entity(
    @PrimaryKey val id: String = "",
    val ownerUserId: String = "",
    val version: Int = 0,
    val type: String = "",
    val category: String = "",
    val isSystem: Boolean = false,
    val ignoreDND: Boolean = false,
    val senderUserId: String = "",
    val senderUsername: String = "",
    val receiverUserId: String = "",
    val relatedNotificationsId: String = "",
    val title: String = "",
    val message: String = "",
    val seen: Boolean = false,
    val responsesJson: String = "",
    val responseDataJson: String = "",
    val expiresAt: String = "",
    val expiryAfterSeen: Int? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)
