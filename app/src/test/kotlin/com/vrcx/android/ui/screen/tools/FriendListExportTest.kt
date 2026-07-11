package com.vrcx.android.ui.screen.tools

import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.data.model.FriendContext
import com.vrcx.android.data.model.FriendState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendListExportTest {
    @Test
    fun `rows follow current user friend order and include memo`() {
        val rows = FriendListExport.rows(
            currentUserFriends = JsonArray(listOf(JsonPrimitive("usr_2"), JsonPrimitive("usr_1"))),
            friends = mapOf(
                "usr_1" to friend("usr_1", "Alice"),
                "usr_2" to friend("usr_2", "Bob"),
            ),
            memos = mapOf("usr_1" to "hello", "usr_2" to "line one\nline two"),
        )

        assertEquals(
            listOf(
                FriendExportRow("usr_2", "Bob", "line one line two"),
                FriendExportRow("usr_1", "Alice", "hello"),
            ),
            rows,
        )
    }

    @Test
    fun `csv matches desktop columns and escaping`() {
        val csv = FriendListExport.toCsv(
            listOf(
                FriendExportRow("usr_1", "Alice", "plain"),
                FriendExportRow("usr_2", "Bob, Jr.", "says \"hi\""),
            ),
        )

        val expected = listOf(
            "UserID,DisplayName,Memo",
            "usr_1,Alice,plain",
            "usr_2,\"Bob, Jr.\",\"says \"\"hi\"\"\"",
        ).joinToString("\n")
        assertEquals(expected, csv)
    }

    @Test
    fun `rows drop stale ids append live friends and use local memos`() {
        val rows = FriendListExport.rows(
            currentUserFriends = JsonArray(listOf(JsonPrimitive("usr_stale"), JsonPrimitive("usr_2"))),
            friends = mapOf(
                "usr_1" to friend("usr_1", "Alice"),
                "usr_2" to friend("usr_2", "Bob"),
            ),
            memos = mapOf("usr_1" to "local memo"),
        )

        assertEquals(listOf("usr_2", "usr_1"), rows.map { it.userId })
        assertEquals("local memo", rows.last().memo)
    }

    @Test
    fun `csv neutralizes spreadsheet formula prefixes`() {
        val csv = FriendListExport.toCsv(
            listOf(FriendExportRow("usr_1", "=HYPERLINK(\"bad\")", "+SUM(1,1)")),
        )

        assertEquals(
            "UserID,DisplayName,Memo\nusr_1,\"'=HYPERLINK(\"\"bad\"\")\",\"'+SUM(1,1)\"",
            csv,
        )
    }

    @Test
    fun `json matches desktop friend id container`() {
        val export = FriendListExport.toJson(
            listOf(
                FriendExportRow("usr_2", "Bob", ""),
                FriendExportRow("usr_1", "Alice", ""),
            ),
        )

        assertEquals(
            """
                {
                    "friends": [
                        "usr_2",
                        "usr_1"
                    ]
                }
            """.trimIndent(),
            export,
        )
    }

    private fun friend(
        id: String,
        name: String,
    ): FriendContext {
        return FriendContext(
            id = id,
            name = name,
            state = FriendState.OFFLINE,
            ref = VrcUser(
                id = id,
                displayName = name,
            ),
        )
    }
}
