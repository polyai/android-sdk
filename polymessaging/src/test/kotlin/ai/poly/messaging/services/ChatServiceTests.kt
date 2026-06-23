// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.ChatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ChatService].
 *
 * [ChatService] is a pure state machine — `handleInbound` returns the emitted events as a List,
 * so no event-stream subscribe/finish/drain choreography is necessary for the pure cases. The
 * effects that live in the Coordinator (agent-join request, clean-close latch, send-retry
 * ladder, destroy) are exercised as Coordinator-level tests via `PolyMessagingClient.forTest`
 * + [FakeTransport] on virtual time, in the style of ResilienceTest.
 *
 * Fields asserted nowhere here by design:
 * - `chatStarted` — the SDK does not gate prepareUserMessage on session start.
 * - `agentChatEnded` — only `chatEnded` exists.
 * - `isAgentTyping` — lives in the public ChatSession (already tested there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceTests {

    private fun cfg() =
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = 600)

    // ---- Session start (Coordinator-level: the agent-join effect lives in Coordinator) ----

    @Test
    fun sessionStart_requestsAgentJoin_andAppliesMaxMessageSize() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val received = mutableListOf<MessagingEvent>()
        scope.launch { client.events.collect { received += it } }
        runCurrent(); fake.simulateOpen(); runCurrent()

        // SESSION_START with a tiny maxMessageSize cap (a tiny cap proves enforcement).
        fake.simulateMessage(sessionStart("ss-1", maxMessageSize = 10)); runCurrent()

        // Coordinator.requestAgentJoinIfNeeded fired the join request.
        assertTrue(fake.sent.any { it is OutgoingEvent.RequestPolyAgentJoin })

        // The cap applied: an oversized send fails. The SDK never creates the optimistic bubble for an
        // oversized message — prepareUserMessage returns only MessageFailed and no outgoing frame
        // reaches the transport.
        client.send("definitely more than ten bytes"); runCurrent()
        assertTrue(received.any { it is MessagingEvent.MessageFailed })
        assertTrue(fake.sent.none { it is OutgoingEvent.UserMessage })

        // NOTE: there is no chatStarted flag, so chat-started state is not asserted here.
        scope.cancel()
    }

    @Test
    fun sessionStart_afterReplayedAgentJoined_doesNotResendAgentJoin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        // A replayed AGENT_JOINED (resume) sets Coordinator.agentJoinRequested.
        fake.simulateMessage(MessagingEvent.AgentJoined(env("aj-1"), AgentJoinedPayload("Ada", null)))
        runCurrent()

        fake.simulateMessage(sessionStart("ss-1")); runCurrent()
        assertTrue(fake.sent.none { it is OutgoingEvent.RequestPolyAgentJoin })
        scope.cancel()
    }

    // ---- Session end (pure) ----

    @Test
    fun sessionEnd_isIdempotent() {
        val cs = ChatService()
        // Distinct envelope ids — a repeated id would hit the dedup path instead of the idempotency one.
        val first = MessagingEvent.SessionEnd(env("end-1"), SessionEndPayload(null))
        val second = MessagingEvent.SessionEnd(env("end-2"), SessionEndPayload(null))

        cs.handleInbound(first)
        assertTrue(cs.chatEnded)

        val out = cs.handleInbound(second)
        assertTrue(cs.chatEnded)
        // Already ended: the second SessionEnd just passes through — no second flush, no extra events.
        assertEquals(listOf<MessagingEvent>(second), out)
    }

    // ---- Agent message endConversation (pure) ----

    @Test
    fun agentMessage_withoutEndConversation_doesNotEndChat() {
        val cs = ChatService()
        val out = cs.handleInbound(
            MessagingEvent.AgentMessage(env("a-1"), agentPayload(text = "Hello", endConversation = false)),
        )
        assertFalse(cs.chatEnded)
        assertTrue(out.none { it is MessagingEvent.SessionEnd })
    }

    // ---- Empty agent message discarded (pure: the hasContent gate in handleAgentMessage) ----

    @Test
    fun emptyAgentMessage_notEmitted() {
        val cs = ChatService()
        // handleInbound returns a List, so no stream subscribe/finish/drain machinery is needed.
        val out = cs.handleInbound(
            MessagingEvent.AgentMessage(env("a-1"), agentPayload(text = "", endConversation = false)),
        )
        assertTrue(out.none { it is MessagingEvent.AgentMessage })
    }

    // ---- Streaming (pure) ----

    @Test
    fun completelyEmptyStream_discarded() {
        val cs = ChatService()
        val out = cs.handleInbound(
            MessagingEvent.AgentMessageChunk(
                env("c-1"),
                AgentMessageChunkPayload("m1", "", 0, true, emptyList(), emptyList()),
            ),
        )
        // The chunk pass-through is fine (keeps the typing indicator alive); the assembled bubble must be absent.
        assertTrue(out.none { it is MessagingEvent.AgentMessage })
    }

    // ---- Dedup / echo (pure) ----

    // The text-fallback branch of handleEcho (no local_id in metadata).
    @Test
    fun echo_confirmsPending_byTextFallback_whenNoLocalId() {
        val cs = ChatService()
        val prepared = cs.prepareUserMessage("Hello")

        // Envelope metadata is null → no local_id → the match falls back to text.
        val echo = MessagingEvent.UserMessage(env("e-1"), UserMessageEchoPayload("server_id_1", "Hello"))
        val out = cs.handleInbound(echo)

        val confirmed = out.single() as MessagingEvent.MessageConfirmed
        assertEquals(prepared.draftId, confirmed.draftId)
        assertEquals("server_id_1", confirmed.messageId)
    }

    @Test
    fun echoWithNoMatch_passesThroughAsReplayedUserMessage() {
        val cs = ChatService()
        val echo = MessagingEvent.UserMessage(env("e-1"), UserMessageEchoPayload("sid_1", "From another tab"))
        val out = cs.handleInbound(echo)
        // The unmatched echo is a genuine replayed user message (another tab / resume): exact pass-through.
        assertEquals(listOf<MessagingEvent>(echo), out)
        assertTrue(out.none { it is MessagingEvent.MessageConfirmed })
    }

    // ---- Clean close (Coordinator-level: the clean-close latch lives in Coordinator.bridgeStatus) ----

    @Test
    fun cleanClose1000_latchesChatEnded_andFlushesStream() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val received = mutableListOf<MessagingEvent>()
        scope.launch { client.events.collect { received += it } }
        runCurrent(); fake.simulateOpen(); runCurrent()

        // A partial in-flight stream that the clean close must flush.
        fake.simulateMessage(
            MessagingEvent.AgentMessageChunk(
                env("c-1"),
                AgentMessageChunkPayload("m1", "partial", 0, false, emptyList(), emptyList()),
            ),
        )
        runCurrent()

        fake.simulateClose(1000, wasClean = true); runCurrent()

        // The flushed bubble was emitted on the clean close.
        assertTrue(received.filterIsInstance<MessagingEvent.AgentMessage>().any { it.payload.text == "partial" })

        // chatEnded is latched — a subsequent send is dropped (no outgoing frame).
        client.send("after clean close"); runCurrent()
        assertTrue(fake.sent.none { it is OutgoingEvent.UserMessage })
        scope.cancel()
    }

    @Test
    fun cleanCloseAfterSessionEnd_isIdempotent() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val received = mutableListOf<MessagingEvent>()
        scope.launch { client.events.collect { received += it } }
        runCurrent(); fake.simulateOpen(); runCurrent()

        // SESSION_END latches chatEnded first…
        fake.simulateMessage(MessagingEvent.SessionEnd(env("end-1"), SessionEndPayload("normal"))); runCurrent()
        // …then the clean close must be a no-op (no crash, no duplicate flush/SessionEnd).
        fake.simulateClose(1000, wasClean = true); runCurrent()

        assertEquals(1, received.count { it is MessagingEvent.SessionEnd })
        assertTrue(received.none { it is MessagingEvent.AgentMessage }) // nothing to flush, no duplicate flush

        // chatEnded remains latched — sends are still dropped.
        client.send("still dropped"); runCurrent()
        assertTrue(fake.sent.none { it is OutgoingEvent.UserMessage })
        scope.cancel()
    }

    // ---- Reset (pure) ----

    @Test
    fun resetChat_clearsChatEnded() {
        val cs = ChatService()
        cs.handleInbound(MessagingEvent.SessionEnd(env("end-1"), SessionEndPayload(null)))
        assertTrue(cs.chatEnded)

        cs.resetChat(isResume = false)
        assertFalse(cs.chatEnded)
        // NOTE: there is no separate agentChatEnded flag to clear.
    }

    @Test
    fun resetChat_preservesMaxMessageSize() {
        val cs = ChatService()
        cs.handleInbound(sessionStart("ss-1", maxMessageSize = 12345)) // applyCapabilities
        cs.resetChat(isResume = false)
        assertEquals(12345, cs.maxMessageSize) // resetChat does not touch the server cap
    }

    // ---- Destroy (Coordinator-level smoke test) ----

    @Test
    fun destroy_disconnectsCleanly_withoutCrash() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val sdkScope = CoroutineScope(dispatcher)
        val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), sdkScope)
        val received = mutableListOf<MessagingEvent>()
        // Collect on a scope OUTSIDE the SDK scope so it survives destroy and can observe inertness.
        collectorScope.launch { client.events.collect { received += it } }
        runCurrent(); fake.simulateOpen(); runCurrent()

        client.shutdown(); runCurrent() // PolyMessagingClient.shutdown → coordinator.destroy()

        assertTrue(fake.disconnectCalls.any { it.first == 1000 && it.second == "client shutdown" })
        assertFalse(sdkScope.isActive) // the SDK scope is cancelled

        // Subsequent inbound traffic is inert — the cancelled scope's collectors are gone.
        val before = received.size
        fake.simulateMessage(MessagingEvent.AgentThinking(env("after-destroy"))); runCurrent()
        assertEquals(before, received.size)
        collectorScope.cancel()
    }

    // ---- Manual-retry delivery model (Coordinator-level) ----
    //
    // We do NOT auto-resend a pending message when the socket recovers (that risked delivering it twice).
    // A message that can't be confirmed is marked Failed → "Tap to retry"; the UI resends it explicitly.

    @Test
    fun connectionDropWhilePending_failsAndDoesNotAutoResend() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        session.send("hello"); runCurrent()
        assertEquals(1, fake.sent.count { it is OutgoingEvent.UserMessage }) // the one send on the open socket

        // Drop the socket while the send is still unconfirmed: 1006 routes to reconnecting.
        fake.simulateClose(1006); runCurrent()
        // The in-flight frame may be lost and we never auto-resend → the message is marked Failed so the
        // user can tap to retry.
        advanceTimeBy(SEND_POLL_GRACE); runCurrent()
        assertEquals(Delivery.FAILED, session.userMessages.first().delivery)

        // The socket comes back up — crucially, NOTHING is re-sent automatically (no duplicate).
        fake.simulateOpen(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, fake.sent.count { it is OutgoingEvent.UserMessage })
        scope.cancel()
    }
}

private const val SEND_POLL_GRACE = 1_000L

// ---- file-private fixtures (env/sessionStart helpers) ----

/** An envelope with a non-empty id + sequence (dedup-eligible) and NO metadata (no local_id). */
private fun env(id: String, sequence: Int? = 1): Envelope = Envelope(id, sequence, 0L, null)

private fun sessionStart(id: String, maxMessageSize: Int = 1 shl 20): MessagingEvent.SessionStart =
    MessagingEvent.SessionStart(
        env(id),
        SessionStartPayload(
            SessionCapabilities(
                streaming = true,
                maxMessageSize = maxMessageSize,
                heartbeatIntervalSeconds = null,
                maxReconnectAttempts = null,
            ),
        ),
    )

private fun agentPayload(text: String, endConversation: Boolean = false): AgentMessagePayload =
    AgentMessagePayload(
        messageId = "m1",
        text = text,
        agentName = null,
        avatarUrl = null,
        attachments = emptyList(),
        responseSuggestions = emptyList(),
        chatCallActions = emptyList(),
        endConversation = endConversation,
    )
