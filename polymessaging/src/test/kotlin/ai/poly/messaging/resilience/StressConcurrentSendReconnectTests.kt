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
 * Send / reconnect delivery behaviour for the **manual-retry** model. The SDK sends a user message
 * once on a live socket and then waits for the server echo; it never auto-resends on reconnect (which
 * had been delivering the same message multiple times). A message that can't be confirmed — offline at
 * send time, or the connection dropping while it's in flight — is marked `Delivery.FAILED` so the UI
 * can offer "Tap to retry", and the user resends explicitly. Invariants exercised here: a message is
 * delivered at most once, a delivered+echoed message ends SENT (never falsely FAILED), and recovery
 * never silently re-sends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StressConcurrentSendReconnectTests {

    private fun cfg() =
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = 600)

    // Sending while the socket is down fails fast (for manual retry) and is never auto-sent on recovery.
    @Test
    fun sendWhileOffline_failsFast_andNeverAutoResends() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)

        val seen = mutableListOf<MessagingEvent>()
        scope.launch { client.events.collect { seen += it } }

        runCurrent(); fake.simulateOpen(); runCurrent()
        fake.simulateClose(1006); runCurrent() // offline

        session.send("offline message"); runCurrent()
        // Never put on the wire (socket down) and never queued for auto-resend → fails for manual retry.
        advanceTimeBy(1_000); runCurrent()
        assertEquals(
            Delivery.FAILED, session.userMessages.first().delivery,
            "a message composed while offline fails fast so the user can tap to retry",
        )
        assertTrue(
            fake.sent.none { it is OutgoingEvent.UserMessage },
            "nothing is sent while the socket is down",
        )

        // The socket recovers — crucially the failed message is NOT auto-resent (no duplicate).
        fake.simulateOpen(); runCurrent()
        advanceTimeBy(15_000); runCurrent()
        assertTrue(
            fake.sent.none { it is OutgoingEvent.UserMessage },
            "a failed message must never be auto-resent when the connection recovers",
        )

        scope.cancel()
    }

    // After an offline failure, an explicit retry (a fresh send) on the recovered socket delivers once
    // and ends SENT — the manual-retry happy path.
    @Test
    fun manualRetryAfterRecovery_deliversExactlyOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)

        runCurrent(); fake.simulateOpen(); runCurrent()
        fake.simulateClose(1006); runCurrent()

        session.send("will fail then retry"); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Delivery.FAILED, session.userMessages.first().delivery)

        // Socket back; the user taps retry → a fresh send (what the example's RetryButton does).
        fake.simulateOpen(); runCurrent()
        session.send("will fail then retry"); runCurrent()
        assertEquals(
            1, fake.sent.count { it is OutgoingEvent.UserMessage },
            "the manual retry is delivered exactly once on the live socket",
        )

        // Server echoes it → the retried message ends SENT, delivered once.
        val retriedDraftId = session.userMessages.last().draftId
        fake.simulateMessage(MessagingEvent.MessageConfirmed(retriedDraftId, "srv-1")); runCurrent()
        advanceTimeBy(12_000); runCurrent()
        assertEquals(Delivery.SENT, session.userMessages.last().delivery)
        assertEquals(1, fake.sent.count { it is OutgoingEvent.UserMessage })

        scope.cancel()
    }

    // A message sent on a healthy connection whose echo is SLOW must NOT be re-fired while that same
    // connection stays up (re-firing duplicates it server-side). It is delivered once; we wait for ack.
    @Test
    fun slowEchoOnStableConnection_isNotResent() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope)
        val session = ChatSession(client, dispatcher = dispatcher)

        runCurrent(); fake.simulateOpen(); runCurrent()
        session.send("slow echo"); runCurrent()
        val draft = session.userMessages.first()
        assertEquals(
            1, fake.sent.count { it is OutgoingEvent.UserMessage },
            "delivered once on the open socket",
        )

        // Time passes with the connection healthy but no echo yet — must NOT re-fire (same open-epoch).
        advanceTimeBy(6_000); runCurrent()
        assertEquals(
            1, fake.sent.count { it is OutgoingEvent.UserMessage },
            "an unconfirmed send must not be re-fired while the same connection stays open",
        )
        assertEquals(
            Delivery.PENDING, session.userMessages.first().delivery,
            "within the confirm window it's still PENDING, not failed",
        )

        // The echo lands within the confirm window → SENT, delivered exactly once.
        fake.simulateMessage(MessagingEvent.MessageConfirmed(draft.draftId, "srv-late")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(Delivery.SENT, session.userMessages.first().delivery)
        assertEquals(1, fake.sent.count { it is OutgoingEvent.UserMessage })

        scope.cancel()
    }
}
