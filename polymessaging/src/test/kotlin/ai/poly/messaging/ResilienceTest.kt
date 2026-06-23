// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests of the resilience drivers wired into the [ai.poly.messaging.internal.services.Coordinator]:
 * network-lost/restored, app-foreground reconnect, idle-timeout expiry, the per-message send retry
 * ladder, and a complete `end()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilienceTest {

    private fun cfg(timeout: Int = 600) =
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = timeout)

    @Test
    fun networkLost_dropsOpenSocketFast() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val net = FakeNetworkMonitor(initial = true)
        PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope, networkMonitor = net)
        runCurrent(); fake.simulateOpen(); runCurrent()

        net.setOnline(false); runCurrent()
        assertTrue(net.started)
        assertTrue(fake.disconnectCalls.any { it.first == 4002 }) // dropped (4002, sendable) → exponential reconnect
    }

    @Test
    fun foreground_whenDisconnected_reconnects() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val fg = FakeForeground()
        PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope, appLifecycle = fg)
        runCurrent(); fake.simulateOpen(); runCurrent()
        fake.simulateClose(1000, wasClean = true); runCurrent() // clean close → no auto-reconnect

        val before = fake.connectUrls.size
        fg.emitForeground(); runCurrent()
        assertTrue(fake.connectUrls.size > before) // foreground drove a reconnect
    }

    @Test
    fun idleTimeout_onHeartbeatTick_endsSession() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val clock = MutableClock(0)
        // Heartbeat enabled (1s) so the tick drives the idle check; the clock drives the timeout window.
        val config = Configuration(apiKey = "key", heartbeatIntervalSeconds = 1, sessionTimeoutSeconds = 600)
        val client = PolyMessagingClient.forTest(config, fake, FakeRestApi(), scope, clock = clock)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        clock.now = 601_000 // idle past the 600s timeout
        advanceTimeBy(1_100); runCurrent() // a heartbeat tick fires → checkTimeout → idle expiry
        assertTrue(session.hasEnded.value)
        scope.cancel() // stop the SDK scope so runTest's end-of-test idle drain terminates
    }

    @Test
    fun sendRetryExhaustion_marksMessageFailed() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        session.send("never confirmed"); runCurrent()
        assertEquals(Delivery.PENDING, session.userMessages.first().delivery)

        // Manual-retry model: on a stable open connection we wait for the echo but never auto-resend (no
        // duplicate risk). If the echo never arrives the message fails after the confirm window
        // (SEND_CONFIRM_TIMEOUT_MS = 10s) so the UI can offer "Tap to retry".
        advanceTimeBy(11_000); runCurrent()
        assertEquals(Delivery.FAILED, session.userMessages.first().delivery)
    }

    @Test
    fun abnormalClose_latchesConnectionClosedAbnormally_thenClearsOnInbound() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        // A 1006 transient close routes to reconnect (never a Closed status). onSocketClose
        // latches connectionClosedAbnormally 500ms later iff the socket hasn't recovered.
        fake.simulateClose(1006); runCurrent()
        assertEquals(null, client.sessionState.replayCache.last().error)                 // within the 500ms window: not yet
        advanceTimeBy(600); runCurrent()
        assertEquals(SessionErrorCode.CONNECTION_CLOSED_ABNORMALLY, client.sessionState.replayCache.last().error)

        // A valid inbound message proves the link is healthy → the latched error clears.
        fake.simulateMessage(MessagingEvent.AgentThinking(Envelope("e1", 1, 0L, null))); runCurrent()
        assertEquals(null, client.sessionState.replayCache.last().error)
        scope.cancel()
    }

    @Test
    fun errorSystemMessage_autoRecovers_byResendingAgentJoin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        // A non-matching / non-error system message must NOT trigger recovery.
        fake.simulateMessage(MessagingEvent.SystemMessage(Envelope("s0", 1, 0L, null),
            SystemMessagePayload("all good", SystemMessageLevel.INFO))); runCurrent()
        assertTrue(fake.sent.none { it is OutgoingEvent.RequestPolyAgentJoin })

        // An error-level "conversation not found" system message re-sends RequestPolyAgentJoin (auto-recovery).
        fake.simulateMessage(MessagingEvent.SystemMessage(Envelope("s1", 2, 0L, null),
            SystemMessagePayload("Conversation not found", SystemMessageLevel.ERROR))); runCurrent()
        assertTrue(fake.sent.any { it is OutgoingEvent.RequestPolyAgentJoin })
        scope.cancel()
    }

    @Test
    fun end_sendsEndFrame_disconnects_andMarksEnded() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        runCurrent(); fake.simulateOpen(); runCurrent()

        session.end(); runCurrent()
        assertTrue(fake.sent.any { it is OutgoingEvent.UserEndConversation })
        assertTrue(fake.disconnectCalls.any { it.first == 1000 })
        assertTrue(session.hasEnded.value)
    }
}
