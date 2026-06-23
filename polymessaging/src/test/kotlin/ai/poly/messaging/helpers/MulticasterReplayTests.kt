// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Late-subscriber replay semantics.
 *
 * The replay/no-replay behavior lives in the kotlinx flow configuration of the real SDK seams:
 * `SessionService.state` (MutableSharedFlow(replay = 1) — the deliberate new-subscriber-sees-current
 * choice that closes the cold-subscribe race on `PolyMessagingClient.sessionState`), `Coordinator.events`
 * (MutableSharedFlow(replay = 0) — one-shot events are never replayed), and `ConnectionService.status`
 * (StateFlow — current value on subscribe). These tests pin those configurations against the real services.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MulticasterReplayTests {

    private fun cfg() =
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = 600)

    private fun sessionService(rest: FakeRestApi, clock: MutableClock, scope: CoroutineScope) = SessionService(
        api = rest, store = null, streamingEnabled = true,
        platform = "android", deviceType = "mobile",
        sessionTimeoutSeconds = 600, logger = NoopLogger, scope = scope, clock = clock,
    )

    @Test
    fun sessionState_lateSubscriberReceivesLastValueNotHistory() = runTest {
        val svc = sessionService(FakeRestApi(), MutableClock(0), backgroundScope)
        // Drive two state transitions BEFORE anyone subscribes: createFresh emits
        // isLoading=true, then ACTIVE — on top of the initial UNKNOWN. That is the history.
        svc.resumeOrCreate()
        runCurrent()

        // Late subscriber: replay=1 delivers ONLY the latest value (ACTIVE), never the
        // UNKNOWN/loading history.
        val received = mutableListOf<SessionState>()
        backgroundScope.launch { svc.state.collect { received += it } }
        runCurrent()

        val latest = SessionState(SessionStatus.ACTIVE, isReady = false, isLoading = false, sessionId = "sess-1")
        assertEquals(listOf(latest), received)
    }

    @Test
    fun events_lateSubscriberSkipsHistoryReceivesNextEmit() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        // History BEFORE anyone subscribes (Coordinator events use replay = 0).
        fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("e1", 1, 0L, null))); runCurrent()

        val received = mutableListOf<MessagingEvent>()
        backgroundScope.launch { client.events.collect { received += it } }
        runCurrent()

        // No replay → no emissions yet.
        assertTrue(received.isEmpty())

        // New emit after subscribe → delivered, and ONLY the new one.
        fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("e2", 2, 0L, null))); runCurrent()
        assertEquals(1, received.size)
        assertEquals("e2", received.single().envelope?.id)
        scope.cancel()
    }

    @Test
    fun connectionStatus_multipleLateSubscribersEachGetCurrentValue() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()
        assertTrue(client.connectionStatus.value.isConnected)

        // Two INDEPENDENT late subscribers; each immediately receives the current value.
        // StateFlow-conflation idiom: assert the terminal/current state (Open), not a sequence.
        val received1 = mutableListOf<ConnectionStatus>()
        val received2 = mutableListOf<ConnectionStatus>()
        backgroundScope.launch { client.connectionStatus.collect { received1 += it } }
        backgroundScope.launch { client.connectionStatus.collect { received2 += it } }
        runCurrent()

        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Open), received1)
        assertEquals(listOf<ConnectionStatus>(ConnectionStatus.Open), received2)
        scope.cancel()
    }
}
