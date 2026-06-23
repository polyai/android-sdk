// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.ports.AccessToken
import ai.poly.messaging.internal.ports.CreatedSession
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.SessionCreateContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end *scenario* coverage at the public [ChatSession] layer. Each test drives the real
 * `Coordinator`/`ChatService`/`ConnectionService`
 * pipeline over a [FakeTransport] (no network) and asserts the published state: the handoff
 * ladder, a mid-chat drop + reconnect, offline-at-launch, and End → Start-New.
 *
 * Everything runs deterministically
 * on virtual time (`runCurrent`/`advanceTimeBy`), so state is asserted synchronously. StateFlow
 * conflates intermediate values, so we assert the terminal
 * state plus any intermediate at a point where it is stable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class E2EScenarioTests {

    // ---- Test: websocketConnect_happyPath_greetingRenders ----

    @Test
    fun websocketConnect_happyPath_greetingRenders() = runTest {
        val s = makeStack()
        // SESSION_START applies capabilities, then the agent greeting arrives.
        s.fake.simulateMessage(MessagingEvent.SessionStart(env("ss1", 1), sessionStartPayload()))
        runCurrent()
        assertTrue(s.session.hasStarted.value, "session should start")

        s.fake.simulateMessage(agentMessage("greet", 2, "m1", "👋 Welcome"))
        runCurrent()
        assertEquals(1, s.session.agentMessages.size, "greeting bubble should render")
        assertEquals("👋 Welcome", s.session.agentMessages.first().text)
        assertTrue(s.session.connection.value is ConnectionStatus.Open)
        assertTrue(!s.session.hasEnded.value)
        assertNull(s.session.failureReason.value)
    }

    // ---- Test: userEndsConversation_setsEnded_showsNoEndedPill ----

    // end() sends USER_END_SESSION, flips
    // hasEnded, and shows NO "Conversation ended" pill (the user's own UI drove it — SKILL 4.5;
    // ChatSession.handle's SessionEnd branch skips the pill when reason == "user_ended").
    @Test
    fun userEndsConversation_setsEnded_showsNoEndedPill() = runTest {
        val s = makeStack()
        s.fake.simulateMessage(MessagingEvent.SessionStart(env("ss1", 1), sessionStartPayload()))
        runCurrent()
        assertTrue(s.session.hasStarted.value)

        s.session.end()
        runCurrent()

        assertTrue(s.session.hasEnded.value, "session should end")
        assertTrue(
            s.fake.sent.any { it is OutgoingEvent.UserEndConversation },
            "end() must send USER_END_SESSION",
        )
        assertTrue(
            s.session.systemMessages.none { it.event is SystemEvent.ConversationEnded },
            "a user-initiated end shows no ended pill",
        )
    }

    // ---- Test: handoffAccepted_appendsPill ----

    @Test
    fun handoffAccepted_appendsPill() = runTest {
        val s = makeStack()
        s.fake.simulateMessage(
            MessagingEvent.HandoffAccepted(env("ha1", 1), HandoffAcceptedPayload("support")),
        )
        runCurrent()
        assertTrue(
            s.session.systemMessages.any { it.event is SystemEvent.HandoffAccepted },
            "handoffAccepted pill",
        )
    }

    // ---- Test: handoffFullLadder_allEventsInOrder_endsOnLiveAgentLeft ----

    // Every rung of the live-agent handoff; pills appear
    // in ladder order, the live bubble renders as LIVE, and liveAgentLeft ends the conversation
    // (ChatSession.handle's LiveAgentLeft branch → markConversationEnded; distinct from the
    // service layer, where liveAgentLeft does NOT end the chat).
    @Test
    fun handoffFullLadder_allEventsInOrder_endsOnLiveAgentLeft() = runTest {
        val s = makeStack()

        s.fake.simulateMessage(MessagingEvent.AgentTriggeredHandoff(env("e1", 1)))
        s.fake.simulateMessage(
            MessagingEvent.HandoffAccepted(env("e2", 2), HandoffAcceptedPayload("support")),
        )
        s.fake.simulateMessage(
            MessagingEvent.HandoffQueueStatus(
                env("e3", 3),
                HandoffQueueStatusPayload(2, 90, "support", "You are #2 in line"),
            ),
        )
        s.fake.simulateMessage(
            MessagingEvent.LiveAgentJoined(env("e4", 4), LiveAgentJoinedPayload("a1", "Sam", null)),
        )
        runCurrent()

        // Typing frames: null sequence (dedup-exempt) + distinct envelope ids so STOPPED isn't dropped.
        s.fake.simulateMessage(
            MessagingEvent.LiveAgentTyping(env("ty1", null), LiveAgentTypingPayload(TypingState.STARTED, "a1", "Sam")),
        )
        runCurrent()
        assertTrue(s.session.isAgentTyping.value, "live agent typing shows")
        s.fake.simulateMessage(
            MessagingEvent.LiveAgentTyping(env("ty2", null), LiveAgentTypingPayload(TypingState.STOPPED, "a1", "Sam")),
        )
        s.fake.simulateMessage(
            MessagingEvent.LiveAgentMessage(
                env("e5", 5),
                LiveAgentMessagePayload("lm1", "Hi, I'm Sam", "a1", "Sam", null, emptyList(), emptyList(), emptyList()),
            ),
        )
        s.fake.simulateMessage(
            MessagingEvent.LiveAgentLeft(env("e6", 6), LiveAgentLeftPayload("a1", "Sam", "resolved")),
        )
        runCurrent()

        assertTrue(s.session.hasEnded.value, "conversation ends after liveAgentLeft")

        val pills = s.session.systemMessages
        assertTrue(pills.any { it.event is SystemEvent.HandoffStarted })
        assertTrue(pills.any { it.event is SystemEvent.HandoffAccepted })
        assertTrue(pills.any { (it.event as? SystemEvent.QueueStatus)?.position == 2 })
        assertTrue(pills.any { it.event is SystemEvent.LiveAgentJoined })
        assertTrue(s.session.agentMessages.any { it.agentKind == AgentKind.LIVE && it.text == "Hi, I'm Sam" })
        assertTrue(!s.session.isAgentTyping.value, "typing must clear once the live agent leaves")

        // Pills must appear in ladder order.
        val startedIdx = pills.indexOfFirst { it.event is SystemEvent.HandoffStarted }
        val joinedIdx = pills.indexOfFirst { it.event is SystemEvent.LiveAgentJoined }
        assertTrue(startedIdx >= 0, "handoffStarted pill present")
        assertTrue(joinedIdx >= 0, "liveAgentJoined pill present")
        assertTrue(startedIdx < joinedIdx, "handoffStarted before liveAgentJoined")
    }

    // ---- Test: handoffFailed_afterQueueStatus_isRecoverable ----

    @Test
    fun handoffFailed_afterQueueStatus_isRecoverable() = runTest {
        val s = makeStack()
        s.fake.simulateMessage(MessagingEvent.AgentTriggeredHandoff(env("e1", 1)))
        s.fake.simulateMessage(
            MessagingEvent.HandoffQueueStatus(env("e2", 2), HandoffQueueStatusPayload(1, null, null, null)),
        )
        s.fake.simulateMessage(
            MessagingEvent.HandoffFailed(env("e3", 3), HandoffFailedPayload("no_agents")),
        )
        runCurrent()

        assertTrue(
            s.session.systemMessages.any { (it.event as? SystemEvent.HandoffFailed)?.reasonText == "no_agents" },
            "handoffFailed pill with reason",
        )
        assertTrue(s.session.systemMessages.any { it.event is SystemEvent.HandoffStarted })
        assertTrue(s.session.systemMessages.any { it.event is SystemEvent.QueueStatus })
        assertTrue(!s.session.hasEnded.value, "handoffFailed is recoverable — does not end the conversation")
        assertNull(s.session.failureReason.value, "handoffFailed is not an SDK connection error")
    }

    // ---- Test: handoffTimeout_afterQueueStatus_isRecoverable ----

    @Test
    fun handoffTimeout_afterQueueStatus_isRecoverable() = runTest {
        val s = makeStack()
        s.fake.simulateMessage(MessagingEvent.AgentTriggeredHandoff(env("e1", 1)))
        s.fake.simulateMessage(
            MessagingEvent.HandoffQueueStatus(env("e2", 2), HandoffQueueStatusPayload(1, null, null, null)),
        )
        s.fake.simulateMessage(
            MessagingEvent.HandoffTimeout(env("e3", 3), HandoffTimeoutPayload(null)),
        )
        runCurrent()

        assertTrue(s.session.systemMessages.any { it.event is SystemEvent.HandoffTimeout }, "handoffTimeout pill")
        assertTrue(!s.session.hasEnded.value, "handoffTimeout is recoverable")
        assertNull(s.session.failureReason.value)
    }

    // ---- Test: midChatDrop_reconnects_andPreservesTranscript ----

    // forTest's default
    // Backoff(overrideSeconds = 0.0) makes the reconnect fire immediately on virtual time.
    @Test
    fun midChatDrop_reconnects_andPreservesTranscript() = runTest {
        val s = makeStack()
        s.fake.simulateMessage(MessagingEvent.SessionStart(env("ss1", 1), sessionStartPayload()))
        s.fake.simulateMessage(agentMessage("a1", 2, "m1", "Hello"))
        runCurrent()
        assertEquals(1, s.session.agentMessages.size, "greeting present before drop")
        val connectsBefore = s.fake.connectUrls.size

        // Transport dies (1006 = transient, non-clean). Reconnect ladder must engage.
        s.fake.simulateClose(1006, "keep-alive failed")
        runCurrent()
        // StateFlow-conflation idiom: ChatSession.connection only moves off Reconnecting on the
        // Connected event, so Reconnecting is STABLE here (until simulateOpen) — safe to assert
        // directly rather than tracking the full emission sequence.
        assertTrue(
            s.session.connection.value is ConnectionStatus.Reconnecting,
            "status transitions to reconnecting (no closed flash)",
        )
        // Transcript is NOT cleared on a transient drop.
        assertEquals(1, s.session.agentMessages.size, "messages preserved across transient drop")
        assertTrue(!s.session.hasEnded.value)
        // Backoff(override 0s) fired a fresh connect already.
        assertTrue(s.fake.connectUrls.size > connectsBefore, "reconnect re-attempts the socket")

        // Let the 500ms abnormal-close latch fire while still down (the ResilienceTest latch), then
        // complete the reconnect; I30: the first valid message after reconnect clears the error.
        advanceTimeBy(600)
        runCurrent()
        s.fake.simulateOpen()
        runCurrent()
        s.fake.simulateMessage(MessagingEvent.SessionStart(env("ss2", 3), sessionStartPayload()))
        runCurrent()

        assertTrue(s.session.connection.value is ConnectionStatus.Open, "reconnect reaches open")
        assertTrue(s.session.agentMessages.size >= 1)
        assertNull(s.session.failureReason.value, "I30: clearError fires on the first valid message after reconnect")
    }

    // ---- Test: startNewSession_clearsTranscript_andGreetsFresh ----

    // Virtual time makes the session-id-change delivery fully deterministic.
    @Test
    fun startNewSession_clearsTranscript_andGreetsFresh() = runTest {
        val rest = ScenarioRestApi()
        val s = makeStack(rest)
        s.fake.simulateMessage(MessagingEvent.SessionStart(env("ss1", 1), sessionStartPayload()))
        s.fake.simulateMessage(agentMessage("a1", 2, "m1", "Old conversation"))
        runCurrent()
        assertEquals(1, s.session.agentMessages.size, "first conversation present")

        // "Start New Conversation": the refetch yields a NEW session id, which ChatSession detects
        // (applySessionIdChange) and uses to clear the transcript; then it connects and greets fresh.
        rest.nextSessionId = "sess-2"
        val newSession = launch { s.client.startNewSession() }
        runCurrent()
        advanceTimeBy(400) // past SessionService's 300ms refetch debounce
        runCurrent()
        newSession.join()
        assertTrue(s.fake.connectUrls.size >= 2, "new session re-attempts the socket")

        s.fake.simulateOpen()
        runCurrent()
        s.fake.simulateMessage(MessagingEvent.SessionStart(env("ss2", 1), sessionStartPayload()))
        s.fake.simulateMessage(agentMessage("a2", 2, "m2", "Fresh start"))
        runCurrent()

        // Transcript was cleared and replaced: only the fresh greeting remains.
        assertEquals(listOf("Fresh start"), s.session.agentMessages.map { it.text })
        assertTrue(
            s.session.agentMessages.none { it.text == "Old conversation" },
            "old transcript must be cleared on the new session",
        )
        assertTrue(s.session.hasStarted.value)
    }

    // ---- Test: offlineAtLaunch_neverAttemptsWebSocket ----

    // Token acquisition fails (offline), so session
    // creation aborts (SessionService catches the thrown PolyError) and the WS is never attempted.
    @Test
    fun offlineAtLaunch_neverAttemptsWebSocket() = runTest {
        val rest = ScenarioRestApi(tokenError = PolyError.Auth.TokenAcquisitionFailed)
        val s = makeStack(rest, open = false)
        runCurrent()
        advanceUntilIdle()

        assertTrue(s.fake.connectUrls.isEmpty(), "no WebSocket attempt when the token cannot be obtained")
        assertTrue(s.session.connection.value !is ConnectionStatus.Open, "connection must never reach Open while offline")
        assertTrue(s.session.agentMessages.isEmpty())
    }
}

// ---- File-private stack builder + fakes ----

/** Everything a scenario needs: the public façade, its client, and the drivable doubles. */
private class Stack(
    val session: ChatSession,
    val client: PolyMessagingClient,
    val fake: FakeTransport,
    val rest: ScenarioRestApi,
)

/**
 * Builds `FakeTransport → Coordinator → PolyMessagingClient → ChatSession` on this test's
 * scheduler — the exact wiring proven in ChatSessionTest/ResilienceTest. Pass a pre-configured
 * [rest] to simulate REST failure (offline at launch) or a new-session id (Start-New-Chat).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.makeStack(rest: ScenarioRestApi = ScenarioRestApi(), open: Boolean = true): Stack {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val scope = CoroutineScope(dispatcher)
    val fake = FakeTransport()
    val config = Configuration(apiKey = "key", heartbeatIntervalSeconds = 0)
    val client = PolyMessagingClient.forTest(config, fake, rest, scope)
    val session = ChatSession(client, dispatcher = dispatcher)
    runCurrent()
    if (open) {
        fake.simulateOpen()
        runCurrent()
    }
    return Stack(session, client, fake, rest)
}

/**
 * The REST API double for these scenarios. The shared [FakeRestApi] hardcodes
 * "sess-1" and cannot fail token acquisition, and Doubles.kt must not be edited while sibling
 * files are being written concurrently — so the two injection seams live here, file-private:
 * [nextSessionId] (Start-New-Chat returns a NEW id → ChatSession clears the transcript) and
 * [tokenError] (thrown from token acquisition → SessionService aborts session creation).
 */
private class ScenarioRestApi(
    var nextSessionId: String = "sess-1",
    var tokenError: PolyError? = null,
) : RestApiPort {
    var tokenCalls = 0
    var createCalls = 0

    override suspend fun obtainAccessToken(): AccessToken {
        tokenCalls++
        tokenError?.let { throw it }
        return AccessToken(FakeRestApi.TEST_JWT, Long.MAX_VALUE)
    }

    override suspend fun ensureAccessToken(): String {
        tokenCalls++
        tokenError?.let { throw it }
        return FakeRestApi.TEST_JWT
    }

    override suspend fun createSession(token: String, context: SessionCreateContext): CreatedSession {
        createCalls++
        return CreatedSession(sessionId = nextSessionId, accessToken = token)
    }
}

// ---- File-private event factories ----

private fun env(id: String, sequence: Int?): Envelope = Envelope(id, sequence, 0L, null)

private fun sessionStartPayload(): SessionStartPayload = SessionStartPayload(
    SessionCapabilities(
        streaming = true,
        maxMessageSize = 1 shl 20,
        heartbeatIntervalSeconds = null,
        maxReconnectAttempts = null,
    ),
)

private fun agentMessage(envId: String, sequence: Int, messageId: String, text: String): MessagingEvent.AgentMessage =
    MessagingEvent.AgentMessage(
        env(envId, sequence),
        AgentMessagePayload(messageId, text, null, null, emptyList(), emptyList(), emptyList()),
    )
