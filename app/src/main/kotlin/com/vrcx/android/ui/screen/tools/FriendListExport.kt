package com.vrcx.android.ui.screen.tools

import com.vrcx.android.data.model.FriendContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class FriendExportRow(
    val userId: String,
    val displayName: String,
    val memo: String,
)

object FriendListExport {
    private val json = Json { prettyPrint = true }

    fun rows(
        currentUserFriends: JsonElement?,
        friends: Map<String, FriendContext>,
        memos: Map<String, String>,
    ): List<FriendExportRow> {
        val preferredIds = (currentUserFriends as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val seen = mutableSetOf<String>()
        val orderedIds = buildList {
            preferredIds.forEach { userId ->
                if (userId in friends && seen.add(userId)) add(userId)
            }
            friends.keys
                .asSequence()
                .filterNot { it in seen }
                .sorted()
                .forEach { userId ->
                    seen.add(userId)
                    add(userId)
                }
        }

        return orderedIds.map { userId ->
            val friend = friends.getValue(userId)
            FriendExportRow(
                userId = userId,
                displayName = friend.name,
                memo = memos[userId]
                    .orEmpty()
                    .replace("\r\n", " ")
                    .replace('\r', ' ')
                    .replace('\n', ' '),
            )
        }
    }

    fun toCsv(rows: List<FriendExportRow>): String {
        return buildString {
            append("UserID,DisplayName,Memo")
            rows.forEach { row ->
                append('\n')
                append(csvField(row.userId))
                append(',')
                append(csvField(row.displayName))
                append(',')
                append(csvField(row.memo))
            }
        }
    }

    fun toJson(rows: List<FriendExportRow>): String {
        return json.encodeToString(mapOf("friends" to rows.map { it.userId }))
    }

    private fun csvField(value: String): String {
        val safeValue = if (value.firstOrNull() in FORMULA_PREFIXES) "'$value" else value
        return if (safeValue.any { it.code in 0x00..0x1f || it == ',' || it == '"' }) {
            "\"${safeValue.replace("\"", "\"\"")}\""
        } else {
            safeValue
        }
    }

    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
}
