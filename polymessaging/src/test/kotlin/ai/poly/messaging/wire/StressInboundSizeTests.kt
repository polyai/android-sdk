// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.ChatService
import ai.poly.messaging.internal.wire.WireDecoder
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Robustness / stress probes for oversized and content-only inbound frames. Pure JVM: the
 * decoder runs over the real `org.json`, and `ChatService.handleInbound` is synchronous (no
 * streams/virtual time needed).
 */
class StressInboundSizeTests {

    private val decoder = WireDecoder(now = { FIXED_NOW })

    // max_message_size_bytes is an outbound guard (prepareUserMessage); inbound is never truncated.
    @Test
    fun decode_agentMessage_oversizedText_passesThroughUntruncated() {
        val bigText = "A".repeat(512 * 1024)

        // Build via JSONObject so org.json does the quoting/escaping — never string-template
        // a 512 KiB payload into raw JSON.
        val frame = JSONObject()
            .put("id", "evt_big")
            .put("sequence", 1)
            .put("timestamp", "2026-05-07T12:00:00Z")
            .put("type", "EVENT_TYPE_POLY_AGENT_MESSAGE")
            .put(
                "payload",
                JSONObject()
                    .put("message_id", "msg_big")
                    .put("text", bigText),
            )

        val events = decoder.decode(frame.toString())
        assertEquals(1, events.size)

        val e = events.single() as MessagingEvent.AgentMessage
        assertEquals("evt_big", e.env.id)
        assertEquals("msg_big", e.payload.messageId)
        assertEquals(524_288, e.payload.text.length)
        assertEquals(bigText, e.payload.text)
    }

    // handleAgentMessage counts a non-empty chatCallActions as content (web hasContent parity), so a call-actions-only message surfaces rather than being dropped.
    @Test
    fun agentMessage_onlyCallActions_stillSurfaced() {
        val cs = ChatService()

        val payload = AgentMessagePayload(
            messageId = "msg_calls",
            text = "",
            agentName = null,
            avatarUrl = null,
            attachments = emptyList(),
            responseSuggestions = emptyList(),
            chatCallActions = listOf(ChatCallAction(title = "Call support", contactNumber = "+15551234567")),
            endConversation = false,
        )
        val event = MessagingEvent.AgentMessage(Envelope("evt_calls", 1, 0L, null), payload)

        // handleInbound is synchronous and RETURNS the events, so we assert directly on the
        // returned list. Complements
        // ChatServiceTest.emptyMessage_silentlyDropped_noEventNoOutgoing (the negative case).
        val emitted = cs.handleInbound(event)

        val agentMessages = emitted.filterIsInstance<MessagingEvent.AgentMessage>()
        assertEquals(1, agentMessages.size, "A call-actions-only agent message must surface (hasContent == true)")
        val surfaced = agentMessages.single().payload
        assertEquals("", surfaced.text)
        assertEquals(1, surfaced.chatCallActions.size)
        assertEquals("Call support", surfaced.chatCallActions.single().title)
        assertEquals("+15551234567", surfaced.chatCallActions.single().contactNumber)
    }

    private companion object {
        // Fixed wall clock for the decoder's timestamp fallback (matches WireTest.FIXED_NOW).
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
