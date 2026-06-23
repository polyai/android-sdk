// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.ChatService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Echo-dedup stress coverage.
 *
 * `prepareUserMessage` doesn't surface the internal `clientEventId`, so the test can't
 * reconstruct `local_id` and instead exercises the text-fallback matching path
 * (no `local_id` metadata on the echo envelopes).
 *
 * `ChatService` is a pure state machine whose `handleInbound` returns the emitted events
 * synchronously, so we collect the returned lists in order — no coroutines or virtual time
 * needed.
 */
class StressDedupTests {

    // With identical text and no local_id, firstOrNull(text==) over an insertion-ordered
    // LinkedHashMap confirms the i-th draft QUEUED with the i-th echo to ARRIVE (FIFO by
    // arrival, not by echo label).
    @Test
    fun burstIdenticalSends_outOfOrderEchoesWithoutLocalId_confirmFifoByArrival() {
        val service = ChatService()

        val prepared1 = service.prepareUserMessage("ok")
        val prepared2 = service.prepareUserMessage("ok")
        val prepared3 = service.prepareUserMessage("ok")
        assertTrue(service.isPending(prepared1.draftId))
        assertTrue(service.isPending(prepared2.draftId))
        assertTrue(service.isPending(prepared3.draftId))

        // Echoes arrive in REVERSE queue order; metadata = null -> text-fallback matcher.
        val emitted = mutableListOf<MessagingEvent>()
        emitted += service.handleInbound(echo(envelopeId = "s3", serverId = "server_3"))
        emitted += service.handleInbound(echo(envelopeId = "s2", serverId = "server_2"))
        emitted += service.handleInbound(echo(envelopeId = "s1", serverId = "server_1"))

        val leakedUserMessages = emitted.filterIsInstance<MessagingEvent.UserMessage>()
        assertTrue(
            leakedUserMessages.isEmpty(),
            "every echo should dedup into a confirmation, none should pass through as UserMessage",
        )

        val confirmed = emitted.filterIsInstance<MessagingEvent.MessageConfirmed>()
        assertEquals(3, confirmed.size, "exactly 3 confirmations expected")

        val confirmedDrafts = confirmed.map { it.draftId }
        assertEquals(
            3,
            confirmedDrafts.toSet().size,
            "each of the 3 drafts confirmed exactly once (no duplicate draft ids)",
        )

        val expectedDrafts = setOf(prepared1.draftId, prepared2.draftId, prepared3.draftId)
        assertEquals(
            expectedDrafts,
            confirmedDrafts.toSet(),
            "the confirmed drafts are exactly the three we queued (no orphans)",
        )

        val confirmedServerIds = confirmed.map { it.messageId }.toSet()
        assertEquals(
            setOf("server_1", "server_2", "server_3"),
            confirmedServerIds,
            "every echoed server_id maps onto exactly one confirmation",
        )

        // FIFO-by-arrival: arrival order [s3,s2,s1] pairs with queue order [d1,d2,d3].
        assertEquals(prepared1.draftId, confirmed[0].draftId)
        assertEquals("server_3", confirmed[0].messageId)
        assertEquals(prepared2.draftId, confirmed[1].draftId)
        assertEquals("server_2", confirmed[1].messageId)
        assertEquals(prepared3.draftId, confirmed[2].draftId)
        assertEquals("server_1", confirmed[2].messageId)
    }
}

/** An echoed user message with NO local_id metadata, forcing the text-fallback matcher. */
private fun echo(envelopeId: String, serverId: String, text: String = "ok") =
    MessagingEvent.UserMessage(
        Envelope(envelopeId, 1, 0L, metadata = null),
        UserMessageEchoPayload(serverId, text),
    )
