// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.JwtValidator
import ai.poly.messaging.internal.wire.WireDecoder
import ai.poly.messaging.internal.wire.WireEncoder
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-layer tests over the real `org.json`. Decoder tolerates unknown/malformed input.
 */
class WireTest {
    private val decoder = WireDecoder(now = { FIXED_NOW })

    @Test
    fun decode_agentMessage_withSuggestions() {
        val json = """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e1","sequence":3,
            "timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m1","text":"hi",
            "response_suggestions":[{"message_text":"yes"},{"message_text":"no"}]}}"""
        val events = decoder.decode(json)
        assertEquals(1, events.size)
        val e = events[0] as MessagingEvent.AgentMessage
        assertEquals("m1", e.payload.messageId)
        assertEquals("hi", e.payload.text)
        assertEquals(listOf("yes", "no"), e.payload.responseSuggestions.map { it.messageText })
        assertEquals(3, e.env.sequence)
    }

    @Test
    fun decode_batch_sortsBySequenceNullsLast() {
        val json = """{"type":"EVENT_TYPE_EVENT_BATCH","payload":{"events":[
            {"type":"EVENT_TYPE_HEARTBEAT","id":"h","timestamp":"2026-06-08T10:00:00Z"},
            {"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"b","sequence":2,"timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m2","text":"two"}},
            {"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"a","sequence":1,"timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m1","text":"one"}}
        ]}}"""
        val events = decoder.decode(json)
        assertEquals(3, events.size)
        assertEquals(1, events[0].envelope?.sequence)
        assertEquals(2, events[1].envelope?.sequence)
        assertEquals(null, events[2].envelope?.sequence) // heartbeat (no sequence) sorts last
    }

    @Test
    fun decode_unknownAndSpecOnlyTypes_dropped() {
        assertTrue(decoder.decode("""{"type":"EVENT_TYPE_SHOW_CSAT_REQUEST","id":"x","timestamp":"2026-06-08T10:00:00Z"}""").isEmpty())
        assertTrue(decoder.decode("""{"type":"EVENT_TYPE_TOTALLY_UNKNOWN","id":"x"}""").isEmpty())
    }

    @Test
    fun decode_malformedJson_returnsEmpty() {
        assertTrue(decoder.decode("not json at all {{{").isEmpty())
    }

    @Test
    fun decode_liveAgentTyping_state() {
        // The decoder reads the canonical `state` key only; agent_id/agent_name are decoded too.
        val json = """{"type":"EVENT_TYPE_LIVE_AGENT_TYPING","id":"t","sequence":1,"timestamp":"2026-06-08T10:00:00Z","payload":{"state":"TYPING_STATE_STOPPED","agent_id":"a1"}}"""
        val e = decoder.decode(json).single() as MessagingEvent.LiveAgentTyping
        assertEquals(TypingState.STOPPED, e.payload.state)
        assertEquals("a1", e.payload.agentId)
    }

    @Test
    fun decode_liveAgentMessage_ignoresNestedAgentObject() {
        // decodeLiveAgentMessage reads ONLY top-level keys; the nested-`agent` fallback is
        // LIVE_AGENT_JOINED-only. A message carrying only {agent:{…}} yields null id/name/avatar.
        val json = """{"type":"EVENT_TYPE_LIVE_AGENT_MESSAGE","id":"e1","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m1","text":"hi",
            "agent":{"id":"A","name":"Ana","avatar_url":"https://x/a.png"}}}"""
        val e = decoder.decode(json).single() as MessagingEvent.LiveAgentMessage
        assertEquals(null, e.payload.agentId)
        assertEquals(null, e.payload.agentName)
        assertEquals(null, e.payload.avatarUrl)
    }

    @Test
    fun decode_liveAgentJoined_stillReadsNestedAgentObject() {
        // The nested-`agent` fallback MUST remain for LIVE_AGENT_JOINED (decodeLiveAgentJoined).
        val json = """{"type":"EVENT_TYPE_LIVE_AGENT_JOINED","id":"e1","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","payload":{"agent":{"id":"A","name":"Ana"}}}"""
        val e = decoder.decode(json).single() as MessagingEvent.LiveAgentJoined
        assertEquals("A", e.payload.agentId)
        assertEquals("Ana", e.payload.agentName)
    }

    @Test
    fun decode_batch_withNonObjectElement_dropsEntireBatch() {
        // The events array is all-or-nothing: a single non-object element drops the WHOLE batch
        // (it does not salvage the valid events).
        val json = """{"type":"EVENT_TYPE_EVENT_BATCH","payload":{"events":[
            {"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"a","sequence":1,"timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m1","text":"one"}},
            "not-an-object"
        ]}}"""
        assertTrue(decoder.decode(json).isEmpty())
    }

    @Test
    fun decode_attachments_withNonObjectElement_dropsAll() {
        // The attachments array is all-or-nothing → [] when any element is a non-object.
        val json = """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e1","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m1","text":"hi",
            "attachments":[{"content_type":"image","content_url":"https://x/i.png"},"oops"]}}"""
        val e = decoder.decode(json).single() as MessagingEvent.AgentMessage
        assertTrue(e.payload.attachments.isEmpty())
    }

    @Test
    fun decode_metadataCustom_mixedTypes_yieldsNull_butAllStringsPreserved() {
        // The `custom` map is all-or-nothing: a non-string value nulls the WHOLE map.
        // decodeMetadata still returns a non-null EventMetadata when the `metadata` dict is present —
        // only `custom` becomes null — so envelope.metadata must be non-null with custom == null.
        val mixed = """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e1","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","metadata":{"custom":{"a":"x","b":5}},
            "payload":{"message_id":"m1","text":"hi"}}"""
        val mixedMeta = decoder.decode(mixed).single().envelope?.metadata
        assertTrue(mixedMeta != null) // metadata dict present → EventMetadata object present
        assertEquals(null, mixedMeta.custom)
        val allStrings = """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e2","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","metadata":{"custom":{"a":"x","b":"y"}},
            "payload":{"message_id":"m1","text":"hi"}}"""
        assertEquals(mapOf("a" to "x", "b" to "y"), decoder.decode(allStrings).single().envelope?.metadata?.custom)
    }

    @Test
    fun decode_metadata_presenceTracksDict_notCustom() {
        // decodeMetadata(json.dict("metadata")): null ONLY when the metadata dict is absent. When the
        // dict is present but `custom` is missing, metadata is still a non-null EventMetadata(custom = null).
        val noCustom = """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e1","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","metadata":{"other":"v"},
            "payload":{"message_id":"m1","text":"hi"}}"""
        val meta = decoder.decode(noCustom).single().envelope?.metadata
        assertTrue(meta != null) // metadata dict present (even without custom) → object present
        assertEquals(null, meta.custom)
        // No metadata dict at all → envelope.metadata is null.
        val noMeta = """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e2","sequence":1,
            "timestamp":"2026-06-08T10:00:00Z","payload":{"message_id":"m1","text":"hi"}}"""
        assertEquals(null, decoder.decode(noMeta).single().envelope?.metadata)
    }

    @Test
    fun decode_fractionalSeconds_parsedAsTrueFraction() {
        fun ts(t: String) = decoder.decode(
            """{"type":"EVENT_TYPE_POLY_AGENT_MESSAGE","id":"e1","sequence":1,"timestamp":"$t","payload":{"message_id":"m","text":"x"}}""",
        ).single().envelope!!.timestamp
        val base = ts("2026-06-08T10:00:00Z")
        assertEquals(base + 500, ts("2026-06-08T10:00:00.5Z"))      // ".5" = 500 ms (true fraction), not 5 ms
        assertEquals(base + 500, ts("2026-06-08T10:00:00.500Z"))    // canonical 3-digit unchanged
        assertEquals(base + 123, ts("2026-06-08T10:00:00.123456Z")) // microseconds truncated to ~123 ms, not +123456 ms
    }

    @Test
    fun encode_userMessage_carriesLocalId() {
        val frame = WireEncoder.encode(OutgoingEvent.UserMessage("hello", mapOf("local_id" to "L1")))
        val obj = JSONObject(frame)
        assertEquals("EVENT_TYPE_USER_MESSAGE", obj.getString("type"))
        assertEquals("hello", obj.getJSONObject("payload").getString("text"))
        assertEquals("L1", obj.getJSONObject("metadata").getJSONObject("custom").getString("local_id"))
    }

    @Test
    fun encode_endConversationAndLeft_sameWireType() {
        assertEquals("EVENT_TYPE_USER_END_SESSION", JSONObject(WireEncoder.encode(OutgoingEvent.UserEndConversation)).getString("type"))
        assertEquals("EVENT_TYPE_USER_END_SESSION", JSONObject(WireEncoder.encode(OutgoingEvent.UserLeft)).getString("type"))
        assertEquals("EVENT_TYPE_REQUEST_POLY_AGENT_JOIN", JSONObject(WireEncoder.encode(OutgoingEvent.RequestPolyAgentJoin)).getString("type"))
    }

    @Test
    fun jwt_structuralValidity() {
        assertFalse(JwtValidator.isStructurallyValid("not-a-jwt"))
        assertFalse(JwtValidator.isStructurallyValid("only.two"))
        assertTrue(JwtValidator.isStructurallyValid(FakeRestApi.TEST_JWT)) // no exp -> non-expiring -> valid
        // Expired token: exp in the past.
        val expired = "eyJhbGciOiJub25lIn0." + base64Url("""{"exp":1000}""") + ".sig"
        assertFalse(JwtValidator.isStructurallyValid(expired, nowMillis = 2000_000L))
        // Future exp -> valid.
        val future = "eyJhbGciOiJub25lIn0." + base64Url("""{"exp":9999999999}""") + ".sig"
        assertTrue(JwtValidator.isStructurallyValid(future, nowMillis = 1000_000L))
    }

    private fun base64Url(s: String): String {
        val std = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
        return std.replace('+', '-').replace('/', '_').trimEnd('=')
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
