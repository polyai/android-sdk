// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.services.ChatService
import ai.poly.messaging.internal.services.ConnectionService
import ai.poly.messaging.internal.services.Coordinator
import ai.poly.messaging.internal.services.HeartbeatService
import ai.poly.messaging.internal.services.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * Stress probes for lifecycle races around the typing-indicator timer and the heartbeat tick
 * gate. Assert CURRENT intended behaviour; must pass unchanged.
 *
 * The typing timer and dedup are split: dedup lives in the pure `ChatService.handleInbound`
 * and the 10s typing timer lives in the public `ChatSession`. These tests therefore drive the
 * full pipeline (`FakeTransport.simulateMessage` → `Coordinator` → `ChatService` →
 * `ChatSession`) on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StressLifecycleRaceTests {

    // Arm the typing timer, storm the pipeline with interleaved AgentThinking + uniquely-keyed
    // AgentMessage pairs, then assert typing settles false, every distinct message survives
    // dedup, and typing re-arms.
    @Test
    fun typingIndicatorRace_agentThinkingMessageStorm_settlesFalseAndAllDistinctMessagesSurviveDedup() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val config = Configuration(apiKey = "key", heartbeatIntervalSeconds = 0)
        val client = PolyMessagingClient.forTest(config, fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        // Arm the typing timer (the 10s dismiss window stays pending on virtual time) so a
        // deferred timer write is genuinely in flight during the storm.
        fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("warmup_think", null, 0L, null)))
        runCurrent()
        assertTrue(session.isAgentTyping.value, "agentThinking should set isAgentTyping")

        // Storm: each iteration re-arms typing then terminates it with a uniquely-keyed
        // agentMessage. Dedup contract: ChatService.handleInbound de-duplicates by envelope id
        // (only when the envelope has a non-empty id AND a non-null sequence), so each
        // agentMessage uses a distinct id AND a distinct non-null sequence — neither dedup
        // path can swallow it. The ChatSession runs on a single dispatcher, so the stress is a
        // rapid interleaved sequence on virtual time.
        val stormCount = 200
        repeat(stormCount) { i ->
            fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("think_$i", null, 0L, null)))
            fake.simulateMessage(
                MessagingEvent.AgentMessage(
                    Envelope("msg_$i", i + 1, 0L, null),
                    agentMessagePayload(messageId = "m_$i", text = "reply $i"),
                ),
            )
            runCurrent()
        }

        // Final terminating message so the last processed op is unambiguously a typing-stop;
        // sequence past the storm range stays unique.
        fake.simulateMessage(
            MessagingEvent.AgentMessage(
                Envelope("msg_final", stormCount + 1, 0L, null),
                agentMessagePayload(messageId = "m_final", text = "final"),
            ),
        )
        runCurrent()

        // StateFlow conflates intermediate values, so we assert only the TERMINAL typing state.
        assertFalse(
            session.isAgentTyping.value,
            "After an agentMessage stops typing, isAgentTyping must settle to false",
        )

        // Every distinct agentMessage must survive ChatService dedup and render exactly one
        // bubble (no swallows, no dupes) — the rendered-bubble count must equal the distinct
        // messages sent (200 storm + 1 final).
        assertEquals(
            stormCount + 1,
            session.agentMessages.size,
            "Exactly the distinct agent messages should render (no swallows, no dupes)",
        )

        // Sanity: re-arming after the storm still works (the typing timer is not wedged).
        fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("think_again", null, 0L, null)))
        runCurrent()
        assertTrue(session.isAgentTyping.value, "Typing indicator must be re-armable after the race storm")

        session.close() // cancel the pending 10s typing-dismiss job
        scope.cancel() // stop the SDK scope so runTest's end-of-test idle drain terminates
    }

    // The open-gate in Coordinator.onHeartbeatTick suppresses heartbeat sends while the socket
    // is not open; a tick positive control proves the timer fired, and opening the socket must
    // then let heartbeats flow.
    @Test
    fun heartbeat_suppressedWhileConnectionNotOpen_thenFlowsAfterOpen() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val rest = FakeRestApi()
        // PolyMessagingClient keeps the Coordinator private, so construct the internal services
        // directly (same module) to drive heartbeat.start(1) manually while not-open.
        val sessionService = SessionService(
            api = rest, store = null, streamingEnabled = true,
            platform = Platform.ANDROID.raw, deviceType = "mobile",
            sessionTimeoutSeconds = 600, logger = NoopLogger, scope = scope,
        )
        val connectionService = ConnectionService(fake, "wss://test.local", scope, Backoff(overrideSeconds = 0.0), NoopLogger)
        val heartbeatService = HeartbeatService(scope, 1)
        val coordinator = Coordinator(
            fake, sessionService, connectionService, ChatService(), heartbeatService, NoopLogger, scope,
        )

        // Positive control: subscribe BEFORE start() — ticks do not replay, so a late
        // subscriber would miss them. Proves the timer actually fired, so "0 sent" is
        // meaningful (timer ticked but the gate suppressed).
        var ticks = 0
        scope.launch { heartbeatService.ticks.collect { ticks++ } }

        coordinator.start(); runCurrent()

        // Precondition: start() → session create → connect, but NO simulateOpen — the
        // transport has left Idle and is Connecting, not Open.
        assertTrue(fake.connectUrls.isNotEmpty(), "start() should drive a connect")
        assertTrue(
            connectionService.currentStatus() is ConnectionStatus.Connecting,
            "Precondition: transport must NOT be open yet",
        )

        // Drive the heartbeat to tick repeatedly while NOT open; the open-gate in
        // onHeartbeatTick must suppress every send.
        heartbeatService.start(1)
        advanceTimeBy(2_100); runCurrent() // ticks at 1s and 2s of virtual time

        val ticksWhileNotOpen = ticks
        assertTrue(
            ticksWhileNotOpen >= 2,
            "Positive control: the heartbeat timer must tick while not-open so the " +
                "suppression assertion is meaningful (saw $ticksWhileNotOpen ticks)",
        )
        // Accepted idiom (doubly guaranteed on Android): onHeartbeatTick gates the send on
        // ConnectionStatus.Open, AND FakeTransport.send throws NotConnected before recording
        // when not open — so fake.sent staying empty is over-determined. The meaningful proof
        // is the tick positive control above plus heartbeats flowing after simulateOpen below.
        assertEquals(
            0,
            fake.sent.count { it is OutgoingEvent.Heartbeat },
            "No heartbeat frames may be sent while the connection is not open " +
                "(timer fired ${ticksWhileNotOpen}x but the open-gate suppressed all sends)",
        )

        // Open the socket: bridgeStatus(Open) restarts the heartbeat and ticks now produce
        // real sends — proving the gate is open-only, not always-off.
        fake.simulateOpen(); runCurrent()
        assertTrue(connectionService.currentStatus() is ConnectionStatus.Open, "Transport should be Open after simulateOpen")

        advanceTimeBy(3_100); runCurrent() // several post-open ticks
        heartbeatService.stop() // quiesce the writer before reading

        val totalTicks = ticks
        val heartbeatsSent = fake.sent.count { it is OutgoingEvent.Heartbeat }
        assertTrue(
            heartbeatsSent >= 1,
            "Heartbeats must flow once the connection is open (gate is open-only, not always-off)",
        )
        assertTrue(
            heartbeatsSent <= totalTicks,
            "Each heartbeat send is driven by one tick — sends ($heartbeatsSent) cannot exceed observed ticks ($totalTicks)",
        )

        scope.cancel() // stop the SDK scope so runTest's end-of-test idle drain terminates
    }
}

// ---- file-private helpers (do not collide with sibling test files) ----

/** Builds a dedup-resistant agent message payload. */
private fun agentMessagePayload(messageId: String, text: String): AgentMessagePayload =
    AgentMessagePayload(
        messageId = messageId,
        text = text,
        agentName = null,
        avatarUrl = null,
        attachments = emptyList(),
        responseSuggestions = emptyList(),
        chatCallActions = emptyList(),
    )
