// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.wire.WireEncoder
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function tests over the internal `WireEncoder` — no fakes, virtual time, or Turbine.
 * The remaining encoder cases (user message local_id, end-conversation/left collapse) are
 * already covered in `WireTest.kt`.
 */
class WireEncoderTests {

    @Test
    fun encode_heartbeat_typeWithEmptyPayloadObject() {
        // Backend `poly_agent_processor.go:341` touchSession's + echoes heartbeats, so clients
        // send them rather than silently dropping. Empty payload object per
        // `HeartbeatPayload {}` in events.proto.
        val obj = JSONObject(WireEncoder.encode(OutgoingEvent.Heartbeat))
        assertEquals("EVENT_TYPE_HEARTBEAT", obj.getString("type"))
        val payload = obj.getJSONObject("payload") // present (throws if absent), and empty
        assertEquals(0, payload.length())
    }

    @Test
    fun encode_userTyping_started_stateRaw() {
        val obj = JSONObject(WireEncoder.encode(OutgoingEvent.UserTyping(TypingState.STARTED)))
        assertEquals("EVENT_TYPE_USER_TYPING", obj.getString("type"))
        assertEquals("TYPING_STATE_STARTED", obj.getJSONObject("payload").getString("state"))
    }

    @Test
    fun encode_userTyping_stopped_stateRaw() {
        val obj = JSONObject(WireEncoder.encode(OutgoingEvent.UserTyping(TypingState.STOPPED)))
        assertEquals("EVENT_TYPE_USER_TYPING", obj.getString("type"))
        assertEquals("TYPING_STATE_STOPPED", obj.getJSONObject("payload").getString("state"))
    }

    @Test
    fun encode_userMessage_emoji_roundTripsAsUtf8() {
        // WireEncoder returns a Kotlin String, so we round-trip through UTF-8 bytes and re-parse.
        val frame = WireEncoder.encode(OutgoingEvent.UserMessage("test 🎉"))
        val roundTripped = String(frame.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(frame, roundTripped)
        assertTrue(roundTripped.contains("test"))
        val obj = JSONObject(roundTripped)
        assertEquals("test 🎉", obj.getJSONObject("payload").getString("text")) // emoji preserved
    }
}
