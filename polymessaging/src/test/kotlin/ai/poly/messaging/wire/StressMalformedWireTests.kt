// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.wire.WireDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Robustness / stress probes for [WireDecoder] against malformed wire payloads. Every assertion
 * captures the decoder's CURRENT behaviour (no source changes). Pure synchronous decoder probes:
 * no fakes or virtual time.
 *
 * The primitive-in-events-array whole-batch-drop pin is intentionally not duplicated here: it is
 * already covered verbatim by `WireTest.decode_batch_withNonObjectElement_dropsEntireBatch`.
 */
class StressMalformedWireTests {
    private val decoder = WireDecoder()

    @Test
    fun decode_batchWithNullPayload_dropsToZeroEvents() {
        // objOrNull("payload") treats JSONObject.NULL as absent, so the batch guard bails.
        val json = """{"type":"EVENT_TYPE_EVENT_BATCH","payload":null}"""
        val events = decoder.decode(json)
        assertEquals(0, events.size, "Null batch payload must drop to exactly zero events, not crash")
    }

    @Test
    fun decode_emptyIdAndNullSequence_stillDecodes() {
        // stringOrNull preserves a present-but-empty string, so the non-heartbeat id/timestamp
        // presence guard passes (empty != missing).
        val json = """{"id":"","timestamp":"2026-05-07T12:00:00Z",
            "type":"EVENT_TYPE_POLY_AGENT_MESSAGE",
            "payload":{"message_id":"m1","text":"Hi"}}"""
        val events = decoder.decode(json)
        assertEquals(1, events.size, "Empty id with valid timestamp should still decode")
        val e = events.single() as MessagingEvent.AgentMessage
        assertEquals("", e.env.id, "Empty wire id is preserved verbatim")
        assertEquals(null, e.env.sequence, "Missing sequence stays null")
        assertEquals("Hi", e.payload.text)
    }

    @Test
    fun decode_lowercaseTypeString_dropped() {
        // WireEventType.fromRaw is an exact (case-sensitive) map lookup: lowercase cannot match
        // the EVENT_TYPE_* raw values, so the frame drops to exactly zero events.
        val json = """{"id":"evt_lc","sequence":1,"timestamp":"2026-05-07T12:00:00Z",
            "type":"event_type_poly_agent_message",
            "payload":{"message_id":"m1","text":"Hi"}}"""
        val events = decoder.decode(json)
        assertTrue(events.isEmpty(), "Wrong-case event type must not match a known type; drops to exactly zero events")
    }
}
