// Copyright PolyAI Limited

package ai.poly.messaging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Regression for the Java listener-bridge registration race (found live by the consumer-project
 * SDK exerciser): `addEventListener` used a plain `scope.launch`, so the SharedFlow subscription
 * went live asynchronously — and because [PolyMessagingClient.events] has replay = 0, an event
 * emitted in that gap was silently lost. Java callers have no `onSubscription` to handshake with,
 * so the bridge must guarantee the subscription is live when the call returns
 * (CoroutineStart.UNDISPATCHED).
 *
 * The race is only observable across real threads, so this test uses a real multithreaded
 * dispatcher (not virtual time) and hammers the register→emit-immediately window: with the
 * UNDISPATCHED guarantee it is deterministically green; with a plain launch it flakes red.
 */
class JavaListenerBridgeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun addEventListener_subscriptionIsLiveOnReturn_noEventLostInRegistrationGap() = runTest {
        val transport = FakeTransport()
        val client = PolyMessagingClient.forTest(Configuration(apiKey = "key"), transport, FakeRestApi(), scope)
        client.resume() // initial start complete — the pipeline is live before we begin

        repeat(50) { round ->
            val latch = CountDownLatch(1)
            val cancellable = client.addEventListener({ it.run() }) { latch.countDown() }
            // Emit IMMEDIATELY after registration returns — the exact window the race lost.
            transport.simulateMessage(MessagingEvent.Heartbeat(Envelope("hb-$round", round, 0L, null)))
            assertTrue(
                latch.await(2, TimeUnit.SECONDS),
                "round $round: event emitted right after addEventListener returned was lost " +
                    "(listener subscription was not live on return)",
            )
            cancellable.cancel()
        }
    }

    @Test
    fun sessionStateListener_subscriptionIsLiveOnReturn() = runTest {
        val transport = FakeTransport()
        val client = PolyMessagingClient.forTest(Configuration(apiKey = "key"), transport, FakeRestApi(), scope)
        client.resume()

        repeat(50) { round ->
            val latch = CountDownLatch(1)
            // sessionState replays its current value to a live subscriber — so a registered
            // listener must always fire at least once without any further emission.
            val cancellable = client.addSessionStateListener({ it.run() }) { latch.countDown() }
            assertTrue(
                latch.await(2, TimeUnit.SECONDS),
                "round $round: sessionState listener never fired after registration",
            )
            cancellable.cancel()
        }
    }
}
