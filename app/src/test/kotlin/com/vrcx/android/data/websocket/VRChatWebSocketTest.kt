package com.vrcx.android.data.websocket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VRChatWebSocketTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every pipeline event type from the desktop spec parses to its sealed subclass`() {
        // Map each desktop pipeline type name to the expected typed event class.
        val cases = listOf(
            "friend-online" to PipelineEvent.FriendOnline::class.java,
            "friend-offline" to PipelineEvent.FriendOffline::class.java,
            "friend-active" to PipelineEvent.FriendActive::class.java,
            "friend-update" to PipelineEvent.FriendUpdate::class.java,
            "friend-location" to PipelineEvent.FriendLocation::class.java,
            "friend-add" to PipelineEvent.FriendAdd::class.java,
            "friend-delete" to PipelineEvent.FriendDelete::class.java,
            "user-update" to PipelineEvent.UserUpdate::class.java,
            "user-location" to PipelineEvent.UserLocation::class.java,
            "notification" to PipelineEvent.Notification::class.java,
            "notification-v2" to PipelineEvent.NotificationV2::class.java,
            "notification-v2-delete" to PipelineEvent.NotificationV2Delete::class.java,
            "notification-v2-update" to PipelineEvent.NotificationV2Update::class.java,
            "see-notification" to PipelineEvent.SeeNotification::class.java,
            "hide-notification" to PipelineEvent.HideNotification::class.java,
            "response-notification" to PipelineEvent.ResponseNotification::class.java,
            "group-joined" to PipelineEvent.GroupJoined::class.java,
            "group-left" to PipelineEvent.GroupLeft::class.java,
            "group-role-updated" to PipelineEvent.GroupRoleUpdated::class.java,
            "group-member-updated" to PipelineEvent.GroupMemberUpdated::class.java,
            "instance-queue-joined" to PipelineEvent.InstanceQueueJoined::class.java,
            "instance-queue-position" to PipelineEvent.InstanceQueuePosition::class.java,
            "instance-queue-ready" to PipelineEvent.InstanceQueueReady::class.java,
            "instance-queue-left" to PipelineEvent.InstanceQueueLeft::class.java,
            "content-refresh" to PipelineEvent.ContentRefresh::class.java,
            "instance-closed" to PipelineEvent.InstanceClosed::class.java,
        )

        for ((typeName, expectedClass) in cases) {
            val frame = """{"type":"$typeName","content":{}}"""
            val event = parsePipelineMessage(json, frame)
            assertNotNull("type=$typeName should parse", event)
            assertEquals("type=$typeName parsed to wrong class", expectedClass, event!!::class.java)
        }
    }

    @Test
    fun `parses content delivered as an inline JSON object`() {
        val frame = """{"type":"friend-online","content":{"userId":"usr_xyz"}}"""
        val event = parsePipelineMessage(json, frame) as? PipelineEvent.FriendOnline
        assertNotNull(event)
        assertEquals("usr_xyz", event!!.content!!.jsonObject["userId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses content delivered as a JSON string requiring double-decode`() {
        // VRChat sends some frames with content as a *string* of JSON rather than
        // an inline object — the parser must double-decode in that case.
        val frame = """{"type":"friend-online","content":"{\"userId\":\"usr_xyz\"}"}"""
        val event = parsePipelineMessage(json, frame) as? PipelineEvent.FriendOnline
        assertNotNull(event)
        assertEquals("usr_xyz", event!!.content!!.jsonObject["userId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses null content as null`() {
        val frame = """{"type":"friend-add"}"""
        val event = parsePipelineMessage(json, frame)
        assertNotNull(event)
        assertNull(event!!.content)
    }

    @Test
    fun `parses unknown event types into Unknown carrying the type name`() {
        val frame = """{"type":"some-future-event","content":{"foo":"bar"}}"""
        val event = parsePipelineMessage(json, frame) as? PipelineEvent.Unknown
        assertNotNull(event)
        assertEquals("some-future-event", event!!.type)
        assertEquals("bar", event.content!!.jsonObject["foo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `returns null for malformed JSON`() {
        assertNull(parsePipelineMessage(json, "not json"))
        assertNull(parsePipelineMessage(json, "{ broken"))
        assertNull(parsePipelineMessage(json, ""))
    }

    @Test
    fun `returns null for messages with no type field`() {
        val frame = """{"content":{"foo":"bar"}}"""
        assertNull(parsePipelineMessage(json, frame))
    }

    @Test
    fun `gracefully drops content that fails the inner string-parse`() {
        // A non-JSON string in `content` shouldn't crash; content becomes null
        // but the typed event still emits.
        val frame = """{"type":"friend-online","content":"not really json"}"""
        val event = parsePipelineMessage(json, frame)
        assertTrue(event is PipelineEvent.FriendOnline)
        assertNull(event!!.content)
    }
}
