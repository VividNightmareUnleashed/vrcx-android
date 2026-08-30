package com.vrcx.android.data.repository

import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.websocket.PipelineEvent
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPipelineDecoderTest {
    private val decoder = NotificationPipelineDecoder(Json { ignoreUnknownKeys = true })

    @Test
    fun `recognized frames with the wrong nested shapes decode to safe no-ops`() {
        assertSame(
            DecodedNotificationEvent.Ignored,
            decoder.decode(PipelineEvent.Notification(JsonPrimitive("not an object"))),
        )
        assertSame(
            DecodedNotificationEvent.Ignored,
            decoder.decode(
                PipelineEvent.NotificationV2Update(
                    buildJsonObject {
                        put("id", "noty_1")
                        put("updates", JsonArray(emptyList()))
                    },
                ),
            ),
        )
        assertEquals(
            DecodedNotificationEvent.Remove(emptyList()),
            decoder.decode(PipelineEvent.NotificationV2Delete(JsonPrimitive("not an object"))),
        )
    }

    @Test
    fun `blank decoded ids are rejected before they reach inbox state`() {
        val decoded = decoder.decode(
            PipelineEvent.Notification(
                buildJsonObject {
                    put("id", "")
                    put("created_at", timestamp(1))
                },
            ),
        )

        assertSame(DecodedNotificationEvent.Ignored, decoded)
    }

    @Test
    fun `a v2 patch preserves omitted fields and replaces supplied fields`() {
        val existing = NotificationV2(
            id = "noty_1",
            type = "event.announcement",
            title = "Original",
            message = "Kept",
            createdAt = timestamp(1),
            updatedAt = timestamp(1),
            version = 1,
        )
        val decoded = decoder.decode(
            PipelineEvent.NotificationV2Update(
                buildJsonObject {
                    put("id", existing.id)
                    put("updates", buildJsonObject { put("title", "Updated") })
                },
            ),
        )

        assertTrue(decoded is DecodedNotificationEvent.UpdateV2)
        val updated = decoder.applyPatch(existing, decoded as DecodedNotificationEvent.UpdateV2)
        assertEquals("Updated", updated?.title)
        assertEquals("Kept", updated?.message)
        assertEquals(existing.id, updated?.id)
    }

    private fun timestamp(second: Int): String = Instant.parse("2026-03-19T00:00:00Z")
        .plusSeconds(second.toLong())
        .toString()
}
