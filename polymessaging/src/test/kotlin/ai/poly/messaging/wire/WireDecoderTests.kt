// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.wire.WireDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decoder-only tests over the real `org.json`, covering cases not already covered by `WireTest`.
 * All pure JSON-in/events-out: no fakes, no coroutines, no virtual time. Avatar/content URLs are
 * `java.net.URI` (asserted via `toString()`).
 */
class WireDecoderTests {
    private val decoder = WireDecoder(now = { FIXED_NOW })

    // ---- Session events ----

    @Test
    fun decode_sessionStart_capabilities() {
        val json = """{
            "id": "evt_001",
            "sequence": 1,
            "timestamp": "2026-05-07T12:00:00Z",
            "type": "EVENT_TYPE_SESSION_START",
            "payload": {
                "capabilities": {
                    "streaming": true,
                    "max_message_size_bytes": 65536,
                    "heartbeat_interval_seconds": 30,
                    "max_reconnect_attempts": 10
                }
            }
        }"""
        val events = decoder.decode(json)
        assertEquals(1, events.size)
        val e = events[0] as MessagingEvent.SessionStart
        assertEquals("evt_001", e.env.id)
        assertEquals(1, e.env.sequence)
        assertTrue(e.payload.capabilities.streaming)
        assertEquals(65536, e.payload.capabilities.maxMessageSize)
        assertEquals(30, e.payload.capabilities.heartbeatIntervalSeconds)
        assertEquals(10, e.payload.capabilities.maxReconnectAttempts)
    }

    @Test
    fun decode_sessionEnd_reason() {
        val json = """{
            "id": "evt_002",
            "sequence": 50,
            "timestamp": "2026-05-07T12:30:00Z",
            "type": "EVENT_TYPE_SESSION_END",
            "payload": { "reason": "agent_ended" }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.SessionEnd
        assertEquals("agent_ended", e.payload.reason)
    }

    // ---- Agent messages ----

    @Test
    fun decode_agentMessage_fullPayload_attachmentsAndAgentName() {
        val json = """{
            "id": "evt_010",
            "sequence": 10,
            "timestamp": "2026-05-07T12:01:00Z",
            "type": "EVENT_TYPE_POLY_AGENT_MESSAGE",
            "payload": {
                "message_id": "msg_abc",
                "text": "Hello there!",
                "agent_name": "Poly",
                "attachments": [
                    {
                        "content_type": "ATTACHMENT_CONTENT_TYPE_IMAGE",
                        "content_url": "https://example.com/img.png",
                        "title": "Screenshot"
                    }
                ],
                "response_suggestions": [
                    { "message_text": "Yes", "payload": "yes_intent" }
                ]
            }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.AgentMessage
        assertEquals("msg_abc", e.payload.messageId)
        assertEquals("Hello there!", e.payload.text)
        assertEquals("Poly", e.payload.agentName)
        assertEquals(1, e.payload.attachments.size)
        assertEquals(AttachmentContentType.IMAGE, e.payload.attachments[0].contentType)
        // contentUrl is java.net.URI — assert via toString().
        assertEquals("https://example.com/img.png", e.payload.attachments[0].contentUrl?.toString())
        assertEquals(1, e.payload.responseSuggestions.size)
        assertEquals("Yes", e.payload.responseSuggestions[0].messageText)
    }

    @Test
    fun decode_agentMessageChunk_fields() {
        val json = """{
            "id": "evt_011",
            "sequence": 11,
            "timestamp": "2026-05-07T12:01:01Z",
            "type": "EVENT_TYPE_POLY_AGENT_MESSAGE_CHUNK",
            "payload": {
                "message_id": "msg_chunk_1",
                "chunk_index": 2,
                "is_complete": false,
                "text": "partial response"
            }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.AgentMessageChunk
        assertEquals("msg_chunk_1", e.payload.messageId)
        assertEquals(2, e.payload.chunkIndex)
        assertFalse(e.payload.isComplete)
        assertEquals("partial response", e.payload.text)
    }

    // ---- Live agent typing (state) ----

    // STOPPED sibling lives in WireTest.decode_liveAgentTyping_state.
    @Test
    fun decode_liveAgentTyping_startedState() {
        val json = """{
            "id": "evt_t1",
            "type": "EVENT_TYPE_LIVE_AGENT_TYPING",
            "timestamp": "2026-05-07T12:00:00Z",
            "payload": { "state": "TYPING_STATE_STARTED", "agent_id": "agent-42" }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.LiveAgentTyping
        assertEquals(TypingState.STARTED, e.payload.state)
        assertEquals("agent-42", e.payload.agentId)
    }

    // Forward-compat: legacy senders that omit `state` still light up the indicator instead of
    // rendering nothing.
    @Test
    fun decode_liveAgentTyping_missingState_defaultsToStarted() {
        val json = """{
            "id": "evt_t3",
            "type": "EVENT_TYPE_LIVE_AGENT_TYPING",
            "timestamp": "2026-05-07T12:00:00Z",
            "payload": { "agent_id": "agent-42" }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.LiveAgentTyping
        assertEquals(TypingState.STARTED, e.payload.state)
    }

    // ---- Batch ----

    // SESSION_START + POLY_AGENT_MESSAGE + USER_MESSAGE in sequence order.
    @Test
    fun decode_batch_mixedEventTypes_inOrder() {
        val json = """{
            "type": "EVENT_TYPE_EVENT_BATCH",
            "payload": {
                "events": [
                    {
                        "id": "evt_b1",
                        "sequence": 1,
                        "timestamp": "2026-05-07T12:00:00Z",
                        "type": "EVENT_TYPE_SESSION_START",
                        "payload": { "capabilities": { "streaming": true, "max_message_size_bytes": 65536 } }
                    },
                    {
                        "id": "evt_b2",
                        "sequence": 2,
                        "timestamp": "2026-05-07T12:00:01Z",
                        "type": "EVENT_TYPE_POLY_AGENT_MESSAGE",
                        "payload": { "message_id": "m1", "text": "Hi" }
                    },
                    {
                        "id": "evt_b3",
                        "sequence": 3,
                        "timestamp": "2026-05-07T12:00:02Z",
                        "type": "EVENT_TYPE_USER_MESSAGE",
                        "payload": { "message_id": "m2", "text": "Hello" }
                    }
                ]
            }
        }"""
        val events = decoder.decode(json)
        assertEquals(3, events.size)
        assertTrue(events[0] is MessagingEvent.SessionStart)
        assertTrue(events[1] is MessagingEvent.AgentMessage)
        assertTrue(events[2] is MessagingEvent.UserMessage)
    }

    // ---- Edge cases ----

    // Valid JSON without a `type` key drops silently (distinct from the unknown-type-string
    // case covered in WireTest).
    @Test
    fun decode_missingTypeField_returnsEmpty() {
        val events = decoder.decode("""{ "id": "evt_notype", "payload": {} }""")
        assertTrue(events.isEmpty())
    }

    // Heartbeat is exempt from the non-heartbeat id+timestamp guard.
    @Test
    fun decode_heartbeat_withoutIdOrTimestamp_decodes() {
        val events = decoder.decode("""{ "type": "EVENT_TYPE_HEARTBEAT" }""")
        assertEquals(1, events.size)
        assertTrue(events[0] is MessagingEvent.Heartbeat)
    }

    // ---- Poly agent joined ----

    @Test
    fun decode_polyAgentJoined_canonicalAgentAvatarUrl() {
        val json = """{
            "id": "evt_aj",
            "sequence": 5,
            "timestamp": "2026-05-07T12:00:00Z",
            "type": "EVENT_TYPE_POLY_AGENT_JOINED",
            "payload": {
                "agent_name": "Ada",
                "agent_avatar_url": "https://example.com/ada.png"
            }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.AgentJoined
        assertEquals("Ada", e.payload.agentName)
        // avatarUrl is java.net.URI — assert via toString().
        assertEquals("https://example.com/ada.png", e.payload.avatarUrl?.toString())
    }

    // Legacy `avatar_url` fallback.
    @Test
    fun decode_polyAgentJoined_fallsBackToLegacyAvatarUrl() {
        val json = """{
            "id": "evt_aj2",
            "sequence": 6,
            "timestamp": "2026-05-07T12:00:00Z",
            "type": "EVENT_TYPE_POLY_AGENT_JOINED",
            "payload": {
                "agent_name": "Ada",
                "avatar_url": "https://example.com/ada-legacy.png"
            }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.AgentJoined
        assertEquals("https://example.com/ada-legacy.png", e.payload.avatarUrl?.toString())
    }

    // ---- Live agent ----

    // Name AND avatar_url from the nested `agent` object fallback (nested id/name is covered
    // in WireTest).
    @Test
    fun decode_liveAgentJoined_nestedAgent_nameAndAvatar() {
        val json = """{
            "id": "evt_la",
            "sequence": 20,
            "timestamp": "2026-05-07T12:10:00Z",
            "type": "EVENT_TYPE_LIVE_AGENT_JOINED",
            "payload": {
                "agent": {
                    "name": "Sarah",
                    "avatar_url": "https://example.com/avatar.png"
                }
            }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.LiveAgentJoined
        assertEquals("Sarah", e.payload.agentName)
        // avatarUrl is java.net.URI — assert via toString().
        assertEquals("https://example.com/avatar.png", e.payload.avatarUrl?.toString())
    }

    // ---- System ----

    @Test
    fun decode_systemMessage_messageAndErrorLevel() {
        val json = """{
            "id": "evt_sys",
            "sequence": 30,
            "timestamp": "2026-05-07T12:15:00Z",
            "type": "EVENT_TYPE_SYSTEM_MESSAGE",
            "payload": {
                "message": "Something went wrong",
                "level": "SYSTEM_MESSAGE_LEVEL_ERROR"
            }
        }"""
        val e = decoder.decode(json).single() as MessagingEvent.SystemMessage
        assertEquals("Something went wrong", e.payload.message)
        assertEquals(SystemMessageLevel.ERROR, e.payload.level)
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
