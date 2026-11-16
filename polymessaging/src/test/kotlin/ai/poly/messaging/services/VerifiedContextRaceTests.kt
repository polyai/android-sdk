// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interleavings the virtual-time tests can't reach: queued tasks run one at a time, so a refresh
 * can land between SESSION_START handling and the gate coroutine starting.
 */
class VerifiedContextRaceTests {

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun runNext(): Boolean {
            val next = synchronized(queue) { queue.pollFirst() } ?: return false
            next.run()
            return true
        }

        fun runAll() {
            while (runNext()) Unit
        }
    }

    @Test
    fun refreshRightAfterSessionStart_doesNotOverlapTheGate() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val rest = FakeRestApi()
        var handlerCalls = 0
        val config = Configuration(
            apiKey = "key",
            heartbeatIntervalSeconds = 0,
            onContextRequired = { handlerCalls++; "signed.jwt.token" },
        )
        val client = PolyMessagingClient.forTest(config, fake, rest, scope)
        dispatcher.runAll()
        fake.simulateOpen()
        dispatcher.runAll()

        fake.simulateMessage(MessagingEvent.SessionStart(Envelope("evt_1", 1, 0L, null), sessionStart("ctx_abc")))
        dispatcher.runNext() // handles SESSION_START; the gate coroutine is queued but not started
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.refreshVerifiedContext() }
        dispatcher.runAll()

        assertEquals(1, handlerCalls, "A refresh must not run alongside the fresh-session gate")
        assertEquals(1, rest.submittedContexts.size)
        assertEquals(1, fake.sent.filterIsInstance<OutgoingEvent.RequestPolyAgentJoin>().size)
        scope.cancel()
    }

    private fun sessionStart(contextId: String) = SessionStartPayload(
        SessionCapabilities(streaming = true, maxMessageSize = 4096, heartbeatIntervalSeconds = null, maxReconnectAttempts = null),
        contextId,
    )
}
