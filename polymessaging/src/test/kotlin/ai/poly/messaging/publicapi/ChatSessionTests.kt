// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic coverage of the public [ChatSession]
 * state machine driven over the full `Coordinator` → `ChatService` → `ChatSession`
 * pipeline with a [FakeTransport].
 * Everything runs on virtual time: `runTest` + `UnconfinedTestDispatcher(testScheduler)`
 * + `runCurrent()` — no real delays, no polling `assertEventually`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTests {

    // -- Connection / session lifecycle --

    @Test
    fun sessionStart_setsHasStarted_notEnded() = runTest {
        val h = startSession()
        h.fake.simulateMessage(MessagingEvent.SessionStart(env("e1"), sessionStartPayload()))
        runCurrent()
        assertTrue(h.session.hasStarted.value)
        assertFalse(h.session.hasEnded.value)
        assertNull(h.session.failureReason.value)
    }

    @Test
    fun sessionEnd_serverReason_endsAndAppendsConversationEndedPill() = runTest {
        val h = startSession()
        h.fake.simulateMessage(MessagingEvent.SessionEnd(env("e1"), SessionEndPayload("agent_ended")))
        runCurrent()
        assertTrue(h.session.hasEnded.value)
        assertTrue(h.session.systemMessages.any { it.event is SystemEvent.ConversationEnded })
    }

    @Test
    fun sessionEnd_userEnded_endsWithoutConversationEndedPill() = runTest {
        val h = startSession()
        h.fake.simulateMessage(MessagingEvent.SessionEnd(env("e1"), SessionEndPayload("user_ended")))
        runCurrent()
        // StateFlow conflation idiom: assert the terminal state, not the emission sequence.
        assertTrue(h.session.hasEnded.value)
        assertFalse(h.session.systemMessages.any { it.event is SystemEvent.ConversationEnded })
    }

    // -- Agent messages --

    @Test
    fun agentMessage_withAttachments_surfacedOnLastAgentMessage() = runTest {
        val h = startSession()
        // Platform idiom: attachment/avatar URLs are java.net.URI, not Foundation URL.
        val img = Attachment(AttachmentContentType.IMAGE, URI("https://x/i.png"), null, null, null)
        h.fake.simulateMessage(
            MessagingEvent.AgentMessage(
                env("e1"),
                AgentMessagePayload("m1", "see this", null, null, listOf(img), emptyList(), emptyList()),
            ),
        )
        runCurrent()
        val attachment = h.session.lastAgentMessage!!.attachments.single()
        assertEquals(AttachmentContentType.IMAGE, attachment.contentType)
    }

    @Test
    fun agentMessage_withSuggestions_onLastAgentMessage() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.AgentMessage(
                env("e1"),
                AgentMessagePayload(
                    "m1", "pick one", null, null, emptyList(),
                    listOf(ResponseSuggestion("Yes"), ResponseSuggestion("No")), emptyList(),
                ),
            ),
        )
        runCurrent()
        assertEquals(listOf("Yes", "No"), h.session.lastAgentMessage!!.suggestions.map { it.messageText })
    }

    @Test
    fun agentMessage_withCallActions_surfaced() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.AgentMessage(
                env("e1"),
                AgentMessagePayload(
                    "m1", "call us", null, null, emptyList(), emptyList(),
                    listOf(ChatCallAction("Call", "+1 555 0100")),
                ),
            ),
        )
        runCurrent()
        assertEquals("+1 555 0100", h.session.lastAgentMessage!!.callActions.single().contactNumber)
    }

    @Test
    fun agentMessage_setsAgentAvatarUrl() = runTest {
        val h = startSession()
        // Platform idiom: avatar URLs are java.net.URI, not Foundation URL.
        val avatar = URI("https://x/avatar.png")
        h.fake.simulateMessage(
            MessagingEvent.AgentMessage(
                env("e1"),
                AgentMessagePayload("m1", "hi", "Webby", avatar, emptyList(), emptyList(), emptyList()),
            ),
        )
        runCurrent()
        assertEquals(avatar, h.session.agentAvatarUrl.value)
    }

    // -- Typing indicator --

    @Test
    fun liveAgentTyping_startedSetsTyping_thenStoppedClears() = runTest {
        val h = startSession()
        // Real typing frames carry a null sequence (transient, dedup-exempt); distinct
        // envelope ids keep ChatService's id-dedup from dropping the STOPPED frame.
        // Virtual time: the 10s typing timeout never fires because we never advance the clock.
        h.fake.simulateMessage(
            MessagingEvent.LiveAgentTyping(
                Envelope("ty1", null, 0L, null),
                LiveAgentTypingPayload(TypingState.STARTED, null, "Sam"),
            ),
        )
        runCurrent()
        assertTrue(h.session.isAgentTyping.value)

        h.fake.simulateMessage(
            MessagingEvent.LiveAgentTyping(
                Envelope("ty2", null, 0L, null),
                LiveAgentTypingPayload(TypingState.STOPPED, null, "Sam"),
            ),
        )
        runCurrent()
        assertFalse(h.session.isAgentTyping.value)
    }

    // -- Live agent + handoff --

    @Test
    fun liveAgentJoined_appendsSystemPill_andSetsAvatarUrl() = runTest {
        val h = startSession()
        // Platform idiom: avatar URLs are java.net.URI, not Foundation URL.
        val avatar = URI("https://x/sam.png")
        h.fake.simulateMessage(
            MessagingEvent.LiveAgentJoined(env("e1"), LiveAgentJoinedPayload("a1", "Sam", avatar)),
        )
        runCurrent()
        assertTrue(h.session.systemMessages.any { it.event is SystemEvent.LiveAgentJoined })
        assertEquals(avatar, h.session.agentAvatarUrl.value)
    }

    @Test
    fun liveAgentLeft_marksConversationEnded() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.LiveAgentLeft(env("e1"), LiveAgentLeftPayload("a1", "Sam", "resolved")),
        )
        runCurrent()
        // The end happens in the ChatSession reducer (markConversationEnded), not ChatService —
        // ChatServiceTest.liveAgentLeft_doesNotEndChat covers the service layer only.
        assertTrue(h.session.hasEnded.value)
        assertTrue(h.session.systemMessages.any { it.event is SystemEvent.ConversationEnded })
    }

    @Test
    fun handoffQueueStatus_appendsQueuePillWithPosition() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.HandoffQueueStatus(
                env("e1"),
                HandoffQueueStatusPayload(3, 120, "support", "You are #3"),
            ),
        )
        runCurrent()
        assertTrue(h.session.systemMessages.any { (it.event as? SystemEvent.QueueStatus)?.position == 3 })
    }

    @Test
    fun handoffFailed_appendsPillWithReason() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.HandoffFailed(env("e1"), HandoffFailedPayload("no_agents")),
        )
        runCurrent()
        assertTrue(h.session.systemMessages.any { (it.event as? SystemEvent.HandoffFailed)?.reasonText == "no_agents" })
    }

    @Test
    fun handoffTimeout_appendsPill() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.HandoffTimeout(env("e1"), HandoffTimeoutPayload(null)),
        )
        runCurrent()
        assertTrue(h.session.systemMessages.any { it.event is SystemEvent.HandoffTimeout })
    }

    // -- Replay / dedup --

    @Test
    fun userMessageReplay_appendsSentBubble_andDedupsByEnvelopeId() = runTest {
        val h = startSession()
        // No matching local pending draft -> the echo is a genuine replay (session resume)
        // and renders straight as a SENT bubble keyed by the envelope id.
        val envelope = env("u1")
        h.fake.simulateMessage(MessagingEvent.UserMessage(envelope, UserMessageEchoPayload("s1", "earlier")))
        runCurrent()
        assertEquals(1, h.session.userMessages.size)
        assertEquals(Delivery.SENT, h.session.userMessages.first().delivery)

        // Identical envelope replayed -> dropped by BOTH ChatService's seen-id dedup and
        // ChatSession's alreadyShown (draftId == env.id) guard; no duplicate bubble.
        h.fake.simulateMessage(MessagingEvent.UserMessage(envelope, UserMessageEchoPayload("s1", "earlier")))
        runCurrent()
        assertEquals(1, h.session.userMessages.size)
    }

    // -- Imperative API --

    @Test
    fun removeMessage_removesPendingDraftById() = runTest {
        val h = startSession()
        h.session.send("oops")
        runCurrent()
        assertEquals(1, h.session.userMessages.size)
        val draftId = h.session.userMessages.first().draftId
        h.session.removeMessage(draftId)
        assertEquals(0, h.session.userMessages.size)
    }

    @Test
    fun clearSuggestions_emptiesSuggestionsOnAgentMessage() = runTest {
        val h = startSession()
        h.fake.simulateMessage(
            MessagingEvent.AgentMessage(
                env("e1"),
                AgentMessagePayload(
                    "m1", "pick", null, null, emptyList(),
                    listOf(ResponseSuggestion("A")), emptyList(),
                ),
            ),
        )
        runCurrent()
        assertEquals(1, h.session.lastAgentMessage!!.suggestions.size)
        h.session.clearSuggestions(h.session.messages.value.last().id)
        assertEquals(0, h.session.lastAgentMessage!!.suggestions.size)
    }

    @Test
    fun clearChat_resetsMessagesAndLifecycleFlags() = runTest {
        val h = startSession()
        h.fake.simulateMessage(MessagingEvent.SessionStart(env("s1"), sessionStartPayload()))
        h.fake.simulateMessage(
            MessagingEvent.AgentMessage(
                env("e1"),
                AgentMessagePayload("m1", "hi", null, null, emptyList(), emptyList(), emptyList()),
            ),
        )
        runCurrent()
        assertTrue(h.session.hasStarted.value)
        assertEquals(1, h.session.agentMessages.size)

        h.session.clearChat()
        // StateFlow conflation idiom: assert the terminal values, not the emission sequence.
        assertEquals(0, h.session.messages.value.size)
        assertFalse(h.session.hasStarted.value)
        assertFalse(h.session.hasEnded.value)
    }
}

// ---- private, file-scoped harness (mirrors ChatSessionTest.kt's wiring) ----

/** The client + session + transport triple a test drives. */
private class SessionHarness(val fake: FakeTransport, val session: ChatSession)

/**
 * Builds the full forTest stack on the runTest scheduler and opens the connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.startSession(): SessionHarness {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val scope = CoroutineScope(dispatcher)
    val fake = FakeTransport()
    val client = PolyMessagingClient.forTest(
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0),
        fake,
        FakeRestApi(),
        scope,
    )
    val session = ChatSession(client, dispatcher = dispatcher)
    runCurrent()
    fake.simulateOpen()
    runCurrent()
    return SessionHarness(fake, session)
}

/** Envelope shorthand — distinct ids per frame; sequence non-null so dedup applies. */
private fun env(id: String, sequence: Int? = 1): Envelope = Envelope(id, sequence, 0L, null)

/** A SESSION_START payload with default capabilities. */
private fun sessionStartPayload(): SessionStartPayload = SessionStartPayload(
    SessionCapabilities(
        streaming = true,
        maxMessageSize = 1 shl 20,
        heartbeatIntervalSeconds = null,
        maxReconnectAttempts = null,
    ),
)
