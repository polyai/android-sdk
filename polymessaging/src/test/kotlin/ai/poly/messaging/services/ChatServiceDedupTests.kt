// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.ChatService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the user-message echo dedup path. The text-fallback match was once
 * flagged as broken for burst-identical sends (P0-MSG-1), but `firstOrNull + remove` over a
 * LinkedHashMap already produces FIFO ordering because each match removes the matched entry.
 * These tests pin that behaviour so a future refactor can't regress it.
 *
 * [ChatService.handleInbound] returns the emitted events synchronously, so no event-stream
 * subscribe/finish/drain dance is necessary here.
 */
class ChatServiceDedupTests {

    /** An echo envelope with no metadata — forces the text-fallback match. */
    private fun echoEnvelope(id: String) = Envelope(id, 1, 0L, null)

    @Test
    fun burstIdenticalEchoes_withoutLocalId_confirmFifo() {
        val cs = ChatService()

        // Two optimistic sends with identical text.
        val prepared1 = cs.prepareUserMessage("ok")
        val prepared2 = cs.prepareUserMessage("ok")

        // Server echoes both, in order, WITHOUT local_id metadata (text-fallback path).
        val echo1 = MessagingEvent.UserMessage(echoEnvelope("s1"), UserMessageEchoPayload("server_1", "ok"))
        val echo2 = MessagingEvent.UserMessage(echoEnvelope("s2"), UserMessageEchoPayload("server_2", "ok"))

        // Both echoes resolve to MessageConfirmed (not pass through as fresh UserMessage events),
        // and FIFO ordering pairs first echo -> first draft, second echo -> second draft.
        val confirmed1 = cs.handleInbound(echo1).single() as MessagingEvent.MessageConfirmed
        assertEquals(prepared1.draftId, confirmed1.draftId)
        assertEquals("server_1", confirmed1.messageId)

        val confirmed2 = cs.handleInbound(echo2).single() as MessagingEvent.MessageConfirmed
        assertEquals(prepared2.draftId, confirmed2.draftId)
        assertEquals("server_2", confirmed2.messageId)
    }

    @Test
    fun interleavedEchoes_withoutLocalId_matchByText_eachDraftConfirmedOnce() {
        val cs = ChatService()

        val prepHi = cs.prepareUserMessage("hi")
        val prepOk = cs.prepareUserMessage("ok")
        val prepHi2 = cs.prepareUserMessage("hi")

        // Server echoes in a different order: ok, hi, hi (distinct envelope ids, no metadata).
        val out = listOf(
            cs.handleInbound(MessagingEvent.UserMessage(echoEnvelope("s1"), UserMessageEchoPayload("m_ok", "ok"))),
            cs.handleInbound(MessagingEvent.UserMessage(echoEnvelope("s2"), UserMessageEchoPayload("m_hi_1", "hi"))),
            cs.handleInbound(MessagingEvent.UserMessage(echoEnvelope("s3"), UserMessageEchoPayload("m_hi_2", "hi"))),
        ).flatten()

        val confirmedIds = out.filterIsInstance<MessagingEvent.MessageConfirmed>().map { it.draftId }

        // Each optimistic draft was confirmed exactly once.
        assertEquals(3, confirmedIds.size)
        assertEquals(3, confirmedIds.toSet().size)
        assertTrue(prepHi.draftId in confirmedIds)
        assertTrue(prepOk.draftId in confirmedIds)
        assertTrue(prepHi2.draftId in confirmedIds)
    }
}
