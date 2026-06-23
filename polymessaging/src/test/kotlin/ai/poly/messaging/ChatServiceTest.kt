// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.ChatService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests of the optimistic-send / dedup / streaming state machine.
 */
class ChatServiceTest {

    @Test
    fun optimisticSend_emitsPending_andOutgoingWithLocalId() {
        val cs = ChatService()
        val prepared = cs.prepareUserMessage("hello")
        assertTrue(prepared.events.single() is MessagingEvent.MessagePending)
        val out = prepared.outgoing as OutgoingEvent.UserMessage
        assertEquals("hello", out.text)
        assertTrue(!out.metadata?.get("local_id").isNullOrEmpty())
    }

    @Test
    fun echo_confirmsPending_byLocalId() {
        val cs = ChatService()
        val prepared = cs.prepareUserMessage("hi")
        val localId = (prepared.outgoing as OutgoingEvent.UserMessage).metadata!!.getValue("local_id")
        val echo = MessagingEvent.UserMessage(
            Envelope("ev1", 1, 0L, EventMetadata(mapOf("local_id" to localId))),
            UserMessageEchoPayload("srv-1", "hi"),
        )
        val confirmed = cs.handleInbound(echo).single() as MessagingEvent.MessageConfirmed
        assertEquals(prepared.draftId, confirmed.draftId)
        assertEquals("srv-1", confirmed.messageId)
    }

    @Test
    fun dedup_dropsRepeatedEnvelopeId() {
        val cs = ChatService()
        val event = MessagingEvent.AgentThinking(Envelope("dup", 5, 0L, null))
        assertEquals(1, cs.handleInbound(event).size)
        assertEquals(0, cs.handleInbound(event).size)
    }

    @Test
    fun streaming_reassemblesChunksInOrder() {
        val cs = ChatService()
        fun chunk(idx: Int, text: String, complete: Boolean) = MessagingEvent.AgentMessageChunk(
            Envelope("c$idx", idx, 0L, null),
            AgentMessageChunkPayload("m1", text, idx, complete, emptyList(), emptyList()),
        )
        cs.handleInbound(chunk(0, "Hello", false))
        val out = cs.handleInbound(chunk(1, "world", true))
        val assembled = out.filterIsInstance<MessagingEvent.AgentMessage>().single()
        assertEquals("Hello world", assembled.payload.text)
    }

    @Test
    fun oversizedMessage_failsWithoutSending() {
        val cs = ChatService().apply { maxMessageSize = 4 }
        val prepared = cs.prepareUserMessage("waytoolong")
        assertTrue(prepared.events.single() is MessagingEvent.MessageFailed)
        assertNull(prepared.outgoing)
    }

    @Test
    fun resume_preservesSeenIds_freshClears() {
        val cs = ChatService()
        val e = MessagingEvent.AgentThinking(Envelope("k", 1, 0L, null))
        cs.handleInbound(e)
        cs.resetChat(isResume = true)
        assertEquals(0, cs.handleInbound(e).size) // still deduped after resume
        cs.resetChat(isResume = false)
        assertEquals(1, cs.handleInbound(e).size) // fresh start clears seen ids
    }

    @Test
    fun emptyMessage_silentlyDropped_noEventNoOutgoing() {
        val cs = ChatService()
        val prepared = cs.prepareUserMessage("   ")
        assertTrue(prepared.events.isEmpty()) // silent
        assertNull(prepared.outgoing)
    }

    @Test
    fun afterEnd_silentlyDropped() {
        val cs = ChatService().apply { chatEnded = true }
        val prepared = cs.prepareUserMessage("hello")
        assertTrue(prepared.events.isEmpty())
        assertNull(prepared.outgoing)
    }

    @Test
    fun failPending_emitsMessageFailed_andClearsPending() {
        val cs = ChatService()
        val prepared = cs.prepareUserMessage("stuck")
        assertTrue(cs.isPending(prepared.draftId))
        val failed = cs.failPending(prepared.draftId) as MessagingEvent.MessageFailed
        assertEquals(prepared.draftId, failed.draftId)
        assertTrue(!cs.isPending(prepared.draftId))
        assertNull(cs.failPending(prepared.draftId)) // second call is a no-op
    }

    @Test
    fun flushStreams_finalizesOpenBuffer() {
        val cs = ChatService()
        cs.handleInbound(
            MessagingEvent.AgentMessageChunk(Envelope("c0", 1, 0L, null), AgentMessageChunkPayload("m1", "partial", 0, false, emptyList(), emptyList())),
        )
        val flushed = cs.flushStreams().filterIsInstance<MessagingEvent.AgentMessage>().single()
        assertEquals("partial", flushed.payload.text)
        assertTrue(cs.flushStreams().isEmpty()) // buffer cleared
    }

    @Test
    fun streaming_suggestionsFromEarlierChunk_survive_emptyFinalChunk() {
        val cs = ChatService()
        cs.handleInbound(
            MessagingEvent.AgentMessageChunk(Envelope("c0", 1, 0L, null), AgentMessageChunkPayload("m1", "Pick one", 0, false, emptyList(), listOf(ResponseSuggestion("Yes"), ResponseSuggestion("No")))),
        )
        val out = cs.handleInbound(
            MessagingEvent.AgentMessageChunk(Envelope("c1", 2, 0L, null), AgentMessageChunkPayload("m1", "", 1, true, emptyList(), emptyList())),
        )
        val assembled = out.filterIsInstance<MessagingEvent.AgentMessage>().single()
        assertEquals(2, assembled.payload.responseSuggestions.size) // not wiped by the empty final chunk
    }

    @Test
    fun streaming_suggestionsOnlyFinalChunk_stillEmitsBubble() {
        val cs = ChatService()
        val out = cs.handleInbound(
            MessagingEvent.AgentMessageChunk(Envelope("c0", 1, 0L, null), AgentMessageChunkPayload("m1", "", 0, true, emptyList(), listOf(ResponseSuggestion("Tap me")))),
        )
        val assembled = out.filterIsInstance<MessagingEvent.AgentMessage>().single()
        assertEquals(1, assembled.payload.responseSuggestions.size)
    }

    @Test
    fun sessionEnd_flushesInFlightStream_thenEnds() {
        val cs = ChatService()
        cs.handleInbound(
            MessagingEvent.AgentMessageChunk(Envelope("c0", 1, 0L, null), AgentMessageChunkPayload("m1", "half", 0, false, emptyList(), emptyList())),
        )
        val out = cs.handleInbound(MessagingEvent.SessionEnd(Envelope("e", 2, 0L, null), SessionEndPayload("user_ended")))
        // The partial stream is flushed as a bubble BEFORE the SessionEnd passes through.
        assertEquals("half", out.filterIsInstance<MessagingEvent.AgentMessage>().single().payload.text)
        assertTrue(out.any { it is MessagingEvent.SessionEnd })
        assertTrue(cs.chatEnded)
    }

    @Test
    fun agentMessage_endConversation_synthesizesSessionEnd() {
        val cs = ChatService()
        val out = cs.handleInbound(
            MessagingEvent.AgentMessage(
                Envelope("a", 1, 0L, null),
                AgentMessagePayload("m1", "bye now", null, null, emptyList(), emptyList(), emptyList(), endConversation = true),
            ),
        )
        assertTrue(out.any { it is MessagingEvent.AgentMessage }) // the bubble
        assertTrue(out.any { it is MessagingEvent.SessionEnd })   // synthesized end
        assertTrue(cs.chatEnded)
    }

    @Test
    fun liveAgentLeft_doesNotEndChat() {
        val cs = ChatService()
        cs.handleInbound(MessagingEvent.LiveAgentLeft(Envelope("l", 1, 0L, null), LiveAgentLeftPayload(null, "Sam", "done")))
        assertTrue(!cs.chatEnded) // a live agent leaving is NOT a chat-ended signal
    }
}
