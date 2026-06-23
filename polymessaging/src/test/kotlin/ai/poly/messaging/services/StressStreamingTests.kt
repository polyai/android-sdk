// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.ChatService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stress probes for `ChatService` streaming-chunk assembly: replayed chunk index and payloads
 * on the `maxMessageSize` boundary.
 *
 * `ChatService` is a pure state machine whose `handleInbound` returns the emitted events
 * synchronously, so we collect the returned lists in order — no coroutines, Turbine, or
 * virtual time needed.
 */
class StressStreamingTests {

    /** Envelope shorthand — distinct ids per frame; sequence non-null so dedup applies. */
    private fun env(id: String): Envelope = Envelope(id, 1, 0L, null)

    private fun chunk(
        messageId: String,
        chunkIndex: Int,
        text: String,
        isComplete: Boolean = false,
    ): AgentMessageChunkPayload =
        AgentMessageChunkPayload(messageId, text, chunkIndex, isComplete, emptyList(), emptyList())

    private fun assembledMessages(events: List<MessagingEvent>): List<AgentMessagePayload> =
        events.filterIsInstance<MessagingEvent.AgentMessage>().map { it.payload }

    @Test
    fun streaming_duplicateChunkIndex_notReordered_andNotDeduped() {
        val cs = ChatService()

        val events = mutableListOf<MessagingEvent>()
        events += cs.handleInbound(MessagingEvent.AgentMessageChunk(env("e0"), chunk("m1", 0, "alpha")))
        events += cs.handleInbound(MessagingEvent.AgentMessageChunk(env("e1"), chunk("m1", 1, "beta")))
        events += cs.handleInbound(MessagingEvent.AgentMessageChunk(env("e2"), chunk("m1", 2, "gamma")))
        // Replay of index 1 (e.g. transport redelivery after a reconnect). The envelope id MUST be
        // distinct ("e1_replay") or handleInbound's global envelope-id dedup would drop the event
        // before it ever reaches the StreamingBuffer.
        events += cs.handleInbound(MessagingEvent.AgentMessageChunk(env("e1_replay"), chunk("m1", 1, "beta")))
        events += cs.handleInbound(MessagingEvent.AgentMessageChunk(env("e3"), chunk("m1", 3, "delta", isComplete = true)))

        val assembled = assembledMessages(events)
        assertEquals(1, assembled.size, "Exactly one assembled message expected")
        val text = assembled.single().text

        val tokens = text.split(" ")

        // Ordering invariant: Kotlin sortedBy is a stable sort by chunkIndex, so lower indices
        // stay ahead and the replayed index-1 chunk lands adjacent in its slot.
        val alpha = tokens.indexOf("alpha")
        val firstBeta = tokens.indexOf("beta")
        val lastBeta = tokens.lastIndexOf("beta")
        val gamma = tokens.indexOf("gamma")
        val delta = tokens.indexOf("delta")

        assertTrue(alpha >= 0)
        assertTrue(firstBeta >= 0)
        assertTrue(lastBeta >= 0)
        assertTrue(gamma >= 0)
        assertTrue(delta >= 0)

        assertTrue(alpha < firstBeta, "alpha (idx0) must precede beta (idx1)")
        assertTrue(lastBeta < gamma, "all beta (idx1) must precede gamma (idx2)")
        assertTrue(gamma < delta, "gamma (idx2) must precede delta (idx3)")
        // Two beta tokens adjacent proves the replayed index landed in its slot.
        assertEquals(1, lastBeta - firstBeta, "replayed index-1 token must sit adjacent in its slot")

        // Documents CURRENT behaviour: StreamingBuffer does NOT dedup by chunkIndex — "beta"
        // appears twice (suspected bug). Revisit if it ever dedups.
        assertEquals(
            2,
            tokens.count { it == "beta" },
            "ACTUAL current behaviour: replayed chunk index is NOT deduped (suspected bug)",
        )
        assertEquals("alpha beta beta gamma delta", text)
    }

    @Test
    fun streaming_chunkAtMaxMessageSizeBoundary_notTruncated() {
        val maxSize = 1024

        for (delta in listOf(0, 1)) {
            val cs = ChatService()
            cs.handleInbound(
                MessagingEvent.SessionStart(
                    env("boot"),
                    SessionStartPayload(
                        SessionCapabilities(
                            streaming = true,
                            maxMessageSize = maxSize,
                            heartbeatIntervalSeconds = null,
                            maxReconnectAttempts = null,
                        ),
                    ),
                ),
            )
            // SESSION_START capabilities applied via applyCapabilities.
            assertEquals(maxSize, cs.maxMessageSize)

            // Single-chunk stream so no joining separator perturbs the byte count.
            val length = maxSize + delta
            val body = "x".repeat(length)
            assertEquals(length, body.toByteArray(Charsets.UTF_8).size, "fixture must be exactly $length bytes")

            val events = cs.handleInbound(
                MessagingEvent.AgentMessageChunk(env("c0"), chunk("m_big", 0, body, isComplete = true)),
            )

            val assembled = assembledMessages(events)
            assertEquals(1, assembled.size, "boundary delta=$delta: one assembled message expected")
            val assembledText = assembled.single().text
            assertNotNull(assembledText)
            // maxMessageSize gates only OUTGOING prepareUserMessage, not inbound assembly.
            assertEquals(
                length,
                assembledText.toByteArray(Charsets.UTF_8).size,
                "boundary delta=$delta: assembled utf8 length must be preserved, no truncation",
            )
            assertEquals(
                body,
                assembledText,
                "boundary delta=$delta: assembled text must be byte-identical to the chunk",
            )
        }
    }
}
