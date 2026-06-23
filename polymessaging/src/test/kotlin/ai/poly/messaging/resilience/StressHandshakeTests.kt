// Copyright PolyAI Limited

package ai.poly.messaging

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
import kotlin.test.assertTrue

/**
 * Stress probes for the handshake + invalid-session path. Two invariants:
 *  1. Repeated handshake failures must not stack observation tasks — one inbound frame
 *     still delivered exactly once after several refetch+reconnect cycles.
 *  2. The invalid-session refetch chain is bounded by
 *     ConnectionService.MAX_INVALID_SESSION_ATTEMPTS (3); the 4th 4001 must terminate in
 *     `ConnectionStatus.Failed`, not loop refetching forever.
 *
 * The test runs entirely
 * on virtual time: forTest defaults to Backoff(overrideSeconds = 0.0) and the handshake-failure
 * path routes through invalid-session (not scheduleReconnect) anyway. TWO clocks must advance
 * in step per cycle:
 *  - the hand-advanced MutableClock clears the Coordinator's LEADING 300ms refetch debounce
 *    (onInvalidSession reads clock.nowMillis()); it starts at 1_000 (not 0) because
 *    Coordinator.lastRefetchAtMillis initializes to 0 and a clock at 0 would debounce the
 *    FIRST refetch away (0 - 0 < 300);
 *  - advanceTimeBy(400) runs SessionService.refetch()'s TRAILING delay(REFETCH_DEBOUNCE_MS=300)
 *    which suspends on the test scheduler's virtual
 *    time. runCurrent() alone never advances past it, parking the refetch forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StressHandshakeTests {

    private fun cfg() =
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = 600)

    private fun agentMessage(id: String, sequence: Int?, messageId: String, text: String) =
        MessagingEvent.AgentMessage(
            Envelope(id, sequence, 0L, null),
            AgentMessagePayload(messageId, text, null, null, emptyList(), emptyList(), emptyList()),
        )

    @Test
    fun handshakeFailureRepeated_keepsRefetching_andDeliversFrameExactlyOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val api = FakeRestApi()
        val clock = MutableClock(1_000)
        val client = PolyMessagingClient.forTest(cfg(), fake, api, scope, clock = clock)
        runCurrent()

        assertEquals(1, fake.connectUrls.size, "start() must connect exactly once")
        assertEquals(1, api.createCalls, "start() must create exactly one session")

        val startConnects = fake.connectUrls.size
        val startCreates = api.createCalls

        // Never simulateOpen first, so currentAttemptOpened stays false and each 1006 is a
        // handshake failure → routeInvalidSession → Coordinator refetch + reconnect. Advance the
        // MutableClock by 400ms between cycles so each onInvalidSession clears the 300ms leading
        // refetch debounce instead of coalescing into the first refetch, and advance VIRTUAL time
        // by 400ms so SessionService.refetch()'s trailing delay(300) actually completes and the
        // refetch+reconnect lands before the next close.
        repeat(3) {
            fake.simulateClose(1006, "handshake timeout", wasClean = false); runCurrent()
            clock.now += 400
            advanceTimeBy(400); runCurrent()
        }

        assertTrue(
            fake.connectUrls.size >= startConnects + 2,
            "repeated handshake failures must keep driving reconnects; got ${fake.connectUrls.size}",
        )
        assertTrue(
            api.createCalls >= startCreates + 2,
            "repeated handshake failures must keep refetching fresh sessions; got ${api.createCalls}",
        )

        fake.simulateOpen(); runCurrent()
        assertTrue(client.connectionStatus.value is ConnectionStatus.Open)

        // The transport.messages collector is wired ONCE in Coordinator.start() (Coordinator.kt:84),
        // so stacked observers are impossible by construction — this is a regression guard for that wiring.
        // client.events is a SharedFlow(extraBufferCapacity = 256): with the collector already
        // launched, delivery under the test scheduler is deterministic — no probe frame needed.
        val delivered = mutableListOf<String>()
        val collector = launch {
            client.events.collect { event ->
                if (event is MessagingEvent.AgentMessage) delivered += event.payload.text
            }
        }
        runCurrent()

        fake.simulateMessage(agentMessage("evt_single", 99, "m_single", "single"))
        runCurrent()

        assertEquals(
            1, delivered.count { it == "single" },
            "after repeated handshake-failure reconnects, one frame must surface exactly once — " +
                "stacked observation tasks would duplicate it",
        )

        collector.cancel()
        scope.cancel()
    }

    @Test
    fun invalidSessionRefetchChain_isBoundedAndTerminatesInFailed() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val api = FakeRestApi()
        val clock = MutableClock(1_000)
        val client = PolyMessagingClient.forTest(cfg(), fake, api, scope, clock = clock)
        runCurrent()

        assertEquals(1, fake.connectUrls.size, "start() must connect once")
        fake.simulateOpen(); runCurrent()
        assertEquals(1, api.createCalls, "one session created at start")

        // Three in-budget 4001s. No simulateOpen after this point, so ConnectionService.onOpen
        // never resets invalidSessionReconnects and the counter accumulates across the chain.
        // Advance the MutableClock >= 400ms before each close so the Coordinator's 300ms leading
        // refetch debounce never swallows a cycle, then advance VIRTUAL time so the refetch
        // coroutine clears SessionService.refetch()'s trailing delay(300) before we assert.
        for (cycle in 1..3) {
            clock.now += 400
            fake.simulateClose(4001, "unknown session", wasClean = false); runCurrent()
            advanceTimeBy(400); runCurrent()
            assertEquals(
                1 + cycle, api.createCalls,
                "in-budget 4001 cycle $cycle must refetch a fresh session",
            )
            assertEquals(
                1 + cycle, fake.connectUrls.size,
                "in-budget 4001 cycle $cycle must reconnect once",
            )
        }

        assertEquals(4, api.createCalls, "three in-budget 4001s each refetch a fresh session")
        assertEquals(4, fake.connectUrls.size, "three in-budget 4001s each reconnect once")

        // The 4th 4001 exceeds MAX_INVALID_SESSION_ATTEMPTS → terminal Failed(SessionExpired),
        // not another refetch. StateFlow conflates intermediate values, so we
        // assert the terminal connectionStatus.value rather than the emission sequence.
        clock.now += 400
        fake.simulateClose(4001, "unknown session", wasClean = false); runCurrent()
        // Advance virtual time past the 300ms
        // refetch delay so a wrongly-launched refetch would surface before the negative asserts.
        advanceTimeBy(400); runCurrent()

        assertEquals(
            ConnectionStatus.Failed(PolyError.Session.SessionExpired),
            client.connectionStatus.value,
            "the exhausted invalid-session chain must terminate in Failed(SessionExpired)",
        )
        assertEquals(
            4, api.createCalls,
            "the 4th invalid-session must NOT trigger a further refetch — chain is terminal",
        )
        assertEquals(
            4, fake.connectUrls.size,
            "the 4th invalid-session must NOT trigger a further reconnect",
        )

        scope.cancel()
    }
}
