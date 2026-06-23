// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.helpers.DeviceTypeDetector
import ai.poly.messaging.internal.services.ChatService
import ai.poly.messaging.internal.services.ConnectionService
import ai.poly.messaging.internal.services.Coordinator
import ai.poly.messaging.internal.services.HeartbeatService
import ai.poly.messaging.internal.services.SessionService
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `Coordinator` cases not already covered by `ChatSessionTest` / `ResilienceTest`
 * (send-forwarding, end semantics, and agent-message forwarding live there). Most cases drive
 * the [Coordinator] through the `PolyMessagingClient.forTest` seam over [FakeTransport] +
 * [FakeRestApi] on virtual time; the start-idempotency case constructs the internal
 * [Coordinator] directly, since `client.resume()` only joins the startJob and would never
 * re-enter `start()`. No store teardown is needed — `forTest` assembles with `store = null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTests {

    private fun cfg() = Configuration(apiKey = "key", heartbeatIntervalSeconds = 0)

    @Test
    fun start_createsSessionAndConnects() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val rest = FakeRestApi()
        PolyMessagingClient.forTest(cfg(), fake, rest, scope) // autoStart = true
        runCurrent() // let the auto-start (token → session → connect) complete

        assertEquals(1, rest.tokenCalls)
        assertEquals(1, rest.createCalls)
        assertEquals(1, fake.connectUrls.size)
        scope.cancel() // stop the SDK scope so runTest's end-of-test idle drain terminates
    }

    @Test
    fun start_isIdempotent_secondStartCreatesNoNewSession() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val rest = FakeRestApi()
        // Direct Coordinator construction: client.resume() only joins the existing startJob, so
        // it would never exercise the `started` flag guard.
        val coordinator = makeCoordinator(scope, fake, rest)

        coordinator.start()
        coordinator.start()
        runCurrent()

        assertEquals(1, rest.createCalls) // the `started` guard short-circuits the second start
        scope.cancel()
    }

    @Test
    fun shutdown_destroysCoordinator_withoutCrash() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent() // let start() complete before tearing down

        client.shutdown() // → Coordinator.destroy()
        runCurrent()

        assertTrue(fake.disconnectCalls.contains(1000 to "client shutdown"))
        assertTrue(scope.coroutineContext[Job]!!.isCancelled) // destroy() cancelled the SDK scope
        client.shutdown() // idempotent — should not crash
        runCurrent()
    }

    @Test
    fun connectionOpen_emitsConnectedOnEventsStream() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent()

        // Subscribe BEFORE simulateOpen: events is a non-replaying SharedFlow, so a late
        // subscriber would miss the Open emission.
        client.events.test {
            fake.simulateOpen()
            // Coordinator.bridgeStatus bridges the Open status onto the events stream.
            while (awaitItem() !is MessagingEvent.Connected) { /* skip any earlier events */ }
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    @Test
    fun sessionStart_triggersRequestPolyAgentJoin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        fake.simulateMessage(MessagingEvent.SessionStart(Envelope("evt_1", 1, 0L, null), sessionStartPayload()))
        runCurrent()

        // Coordinator.onSessionStart → requestAgentJoinIfNeeded sends exactly one join request.
        assertEquals(1, fake.sent.filterIsInstance<OutgoingEvent.RequestPolyAgentJoin>().size)
        scope.cancel()
    }

    @Test
    fun startNewSession_reconnects_andFreshSessionStartSendsSecondAgentJoin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        // First session: SESSION_START → 1 RequestPolyAgentJoin.
        fake.simulateMessage(MessagingEvent.SessionStart(Envelope("evt_1", 1, 0L, null), sessionStartPayload()))
        runCurrent()
        assertEquals(1, fake.sent.filterIsInstance<OutgoingEvent.RequestPolyAgentJoin>().size)

        // Start a fresh chat — ends the prior session server-side, refetches, and reconnects
        // (connectNow), resetting agentJoinRequested.
        client.startNewSession()
        runCurrent()
        assertTrue(fake.sent.any { it is OutgoingEvent.UserEndConversation })
        assertEquals(2, fake.connectUrls.size) // startNewSession should reconnect

        // New socket opens and emits a fresh SESSION_START. A DIFFERENT envelope id is required —
        // ChatService dedups repeated envelope ids, so reusing evt_1 would be dropped.
        fake.simulateOpen(); runCurrent()
        fake.simulateMessage(MessagingEvent.SessionStart(Envelope("evt_2", 2, 0L, null), sessionStartPayload()))
        runCurrent()

        assertEquals(2, fake.sent.filterIsInstance<OutgoingEvent.RequestPolyAgentJoin>().size)
        scope.cancel()
    }
}

/** A minimal SESSION_START payload; internal constructors are visible inside the module. */
private fun sessionStartPayload() = SessionStartPayload(
    SessionCapabilities(
        streaming = true,
        maxMessageSize = 4096,
        heartbeatIntervalSeconds = null, // null → keep the (disabled, 0s) default; never restarts a stopped loop
        maxReconnectAttempts = null,
    ),
)

/** Wire the internal services over the shared fakes. */
private fun makeCoordinator(scope: CoroutineScope, fake: FakeTransport, rest: FakeRestApi): Coordinator {
    val sessionService = SessionService(
        api = rest,
        store = null,
        streamingEnabled = true,
        platform = Platform.ANDROID.raw,
        deviceType = DeviceTypeDetector.MOBILE,
        sessionTimeoutSeconds = 600,
        logger = NoopLogger,
        scope = scope,
    )
    val connectionService = ConnectionService(fake, "wss://test.local", scope, Backoff(overrideSeconds = 0.0), NoopLogger)
    return Coordinator(
        transport = fake,
        sessionService = sessionService,
        connectionService = connectionService,
        chatService = ChatService(),
        heartbeatService = HeartbeatService(scope, 0), // 0 = heartbeat disabled (the test convention)
        logger = NoopLogger,
        scope = scope,
    )
}
