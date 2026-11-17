// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent-join gate: `Coordinator` holds `RequestPolyAgentJoin` until the verified-context
 * step resolves. Driven through `PolyMessagingClient.forTest` on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedContextGateTests {

    private class Harness(
        val fake: FakeTransport,
        val rest: FakeRestApi,
        val client: PolyMessagingClient,
        val session: ChatSession,
        val scope: CoroutineScope,
        val statuses: MutableList<VerifiedContextStatus>,
    ) {
        val joins: Int get() = fake.sent.filterIsInstance<OutgoingEvent.RequestPolyAgentJoin>().size

        fun sessionStart(contextId: String?, id: String = "evt_1", sequence: Int = 1) {
            fake.simulateMessage(MessagingEvent.SessionStart(Envelope(id, sequence, 0L, null), payload(contextId)))
        }
    }

    private fun TestScope.harness(
        rest: FakeRestApi = FakeRestApi(),
        handler: VerifiedContextHandler?,
    ): Harness {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val config = Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, onContextRequired = handler)
        val client = PolyMessagingClient.forTest(config, fake, rest, scope)
        val session = ChatSession(client, dispatcher = dispatcher)
        val statuses = mutableListOf<VerifiedContextStatus>()
        scope.launch { client.events.collect { if (it is MessagingEvent.VerifiedContext) statuses += it.status } }
        runCurrent()
        fake.simulateOpen()
        runCurrent()
        return Harness(fake, rest, client, session, scope, statuses)
    }

    @Test
    fun join_waitsForTokenSubmit() = runTest {
        val latch = CompletableDeferred<Unit>()
        val seen = mutableListOf<VerifiedContextRequest>()
        val h = harness { seen += it; latch.await(); "signed.jwt.token" }

        h.sessionStart("ctx_abc")
        runCurrent()
        assertEquals(listOf(VerifiedContextRequest("sess-1", "ctx_abc")), seen)
        assertEquals(0, h.joins, "Join must be held while the handler runs")
        assertEquals(VerifiedContextStatus.Pending, h.session.verifiedContext.value)

        latch.complete(Unit)
        runCurrent()
        assertEquals(1, h.joins)
        val submitted = h.rest.submittedContexts.single()
        assertEquals("signed.jwt.token", submitted.token)
        assertEquals(FakeRestApi.TEST_JWT, submitted.accessToken)
        assertEquals(listOf(VerifiedContextStatus.Pending, VerifiedContextStatus.Set), h.statuses)
        assertEquals(VerifiedContextStatus.Set, h.session.verifiedContext.value)
        h.scope.cancel()
    }

    @Test
    fun failedSubmit_stillSendsJoin() = runTest {
        val rest = FakeRestApi().apply {
            submitContextError = PolyError.VerifiedContext.VerificationFailed("invalid token signature")
        }
        val h = harness(rest) { "bad.sig.token" }

        h.sessionStart("ctx_abc")
        runCurrent()

        assertEquals(1, h.joins)
        assertEquals(
            VerifiedContextStatus.Failed(PolyError.VerifiedContext.VerificationFailed("invalid token signature")),
            h.session.verifiedContext.value,
        )
        h.scope.cancel()
    }

    @Test
    fun noContextId_skipsHandler() = runTest {
        var calls = 0
        val h = harness { calls++; "signed.jwt.token" }

        h.sessionStart(null)
        runCurrent()

        assertEquals(1, h.joins)
        assertEquals(0, calls)
        assertTrue(h.statuses.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun noHandler_joinsWithoutSubmitting() = runTest {
        val h = harness(handler = null)

        h.sessionStart("ctx_abc")
        runCurrent()

        assertEquals(1, h.joins)
        assertTrue(h.rest.submittedContexts.isEmpty())
        assertEquals(VerifiedContextStatus.Idle, h.session.verifiedContext.value)
        h.scope.cancel()
    }

    @Test
    fun repeatSessionStart_doesNotAskAgain() = runTest {
        var calls = 0
        val h = harness { calls++; "signed.jwt.token" }

        h.sessionStart("ctx_abc")
        runCurrent()
        h.sessionStart("ctx_abc", id = "evt_2", sequence = 2) // a reconnect replays SESSION_START
        runCurrent()

        assertEquals(1, h.joins)
        assertEquals(1, calls)
        h.scope.cancel()
    }

    @Test
    fun resumedSession_skipsHandler_butCanStillRefresh() = runTest {
        var calls = 0
        val rest = FakeRestApi().apply { nextWasResumed = true }
        val h = harness(rest) { calls++; "signed.jwt.token" }

        h.sessionStart("ctx_abc")
        runCurrent()
        assertEquals(0, calls, "The context was set when the session started")
        assertTrue(rest.submittedContexts.isEmpty())

        h.client.refreshVerifiedContext()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, rest.submittedContexts.size)
        h.scope.cancel()
    }

    @Test
    fun staleJoin_isDropped_whenSessionChangesMidRequest() = runTest {
        val latch = CompletableDeferred<Unit>()
        val h = harness { latch.await(); "signed.jwt.token" }
        h.sessionStart("ctx_old")
        runCurrent()

        h.rest.nextSessionId = "sess-2"
        h.client.startNewSession()
        latch.complete(Unit)
        runCurrent()

        assertEquals(1, h.rest.submittedContexts.size)
        assertEquals(0, h.joins, "Old session's join must not reach the new socket")
        assertEquals(VerifiedContextStatus.Idle, h.session.verifiedContext.value)
        h.scope.cancel()
    }

    @Test
    fun socketDropDuringGate_reconnectSendsJoin_withoutAskingAgain() = runTest {
        val latch = CompletableDeferred<Unit>()
        var calls = 0
        val h = harness { calls++; latch.await(); "signed.jwt.token" }
        h.sessionStart("ctx_abc")
        runCurrent()

        h.fake.simulateClose(1006)
        runCurrent()
        latch.complete(Unit)
        runCurrent()
        assertEquals(0, h.joins)

        h.fake.simulateOpen()
        runCurrent()
        h.sessionStart("ctx_abc", id = "evt_2", sequence = 2)
        runCurrent()

        assertEquals(1, h.joins, "The agent must still join after a drop during the gate")
        assertEquals(1, calls)
        h.scope.cancel()
    }

    // ---- refreshVerifiedContext ----

    @Test
    fun refresh_resignsAndSubmitsWithNotifyAgent() = runTest {
        val seen = mutableListOf<VerifiedContextRequest>()
        val h = harness { seen += it; "signed.jwt.token" }
        h.sessionStart("ctx_abc")
        runCurrent()

        h.session.refreshVerifiedContext(notifyAgent = true)
        runCurrent()

        assertEquals(List(2) { VerifiedContextRequest("sess-1", "ctx_abc") }, seen)
        assertEquals(listOf(false, true), h.rest.submittedContexts.map { it.notifyAgent })
        assertEquals(1, h.joins, "A refresh must not send another agent join")
        assertEquals(
            listOf(VerifiedContextStatus.Pending, VerifiedContextStatus.Set, VerifiedContextStatus.Pending, VerifiedContextStatus.Set),
            h.statuses,
        )
        h.scope.cancel()
    }

    @Test
    fun refresh_beforeSessionStart_isIgnored() = runTest {
        var calls = 0
        val h = harness { calls++; "signed.jwt.token" }

        h.client.refreshVerifiedContext()
        runCurrent()

        assertEquals(0, calls)
        assertTrue(h.rest.submittedContexts.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun refresh_withoutHandler_isIgnored() = runTest {
        val h = harness(handler = null)
        h.sessionStart("ctx_abc")
        runCurrent()

        h.client.refreshVerifiedContext(notifyAgent = true)
        runCurrent()

        assertTrue(h.rest.submittedContexts.isEmpty())
        h.scope.cancel()
    }

    @Test
    fun refresh_duringFreshSessionRequest_isDropped() = runTest {
        val latch = CompletableDeferred<Unit>()
        var calls = 0
        val h = harness { calls++; latch.await(); "signed.jwt.token" }
        h.sessionStart("ctx_abc")
        runCurrent()

        h.client.refreshVerifiedContext()
        latch.complete(Unit)
        runCurrent()

        assertEquals(1, calls, "An overlapping refresh is dropped, not queued")
        assertEquals(1, h.joins)
        h.scope.cancel()
    }

    @Test
    fun oldGateFinishingLate_doesNotUnblockRefreshForNewSession() = runTest {
        val oldLatch = CompletableDeferred<Unit>()
        val newLatch = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val h = harness {
            seen += it.contextId
            if (it.contextId == "ctx_old") oldLatch.await() else newLatch.await()
            "signed.jwt.token"
        }
        h.sessionStart("ctx_old")
        runCurrent()

        h.rest.nextSessionId = "sess-2"
        h.client.startNewSession()
        h.fake.simulateOpen()
        runCurrent()
        h.sessionStart("ctx_new", id = "evt_2", sequence = 2)
        runCurrent()
        assertEquals(listOf("ctx_old", "ctx_new"), seen)

        oldLatch.complete(Unit)
        runCurrent()
        h.client.refreshVerifiedContext()
        runCurrent()
        assertEquals(listOf("ctx_old", "ctx_new"), seen, "Refresh overlapped the new session's gate")

        newLatch.complete(Unit)
        runCurrent()
        assertEquals(1, h.joins)
        h.scope.cancel()
    }

    @Test
    fun refresh_afterSessionChange_ignoresOldContextId() = runTest {
        val seen = mutableListOf<String>()
        val h = harness { seen += it.contextId; "signed.jwt.token" }
        h.sessionStart("ctx_old")
        runCurrent()

        // New session, but its SESSION_START (and context_id) hasn't arrived yet.
        h.rest.nextSessionId = "sess-2"
        h.client.startNewSession()
        h.client.refreshVerifiedContext()
        runCurrent()

        assertEquals(listOf("ctx_old"), seen, "Must not re-sign the previous session's context_id")
        h.scope.cancel()
    }

    @Test
    fun clearChat_resetsStatus() = runTest {
        val h = harness { "signed.jwt.token" }
        h.sessionStart("ctx_abc")
        runCurrent()
        assertEquals(VerifiedContextStatus.Set, h.session.verifiedContext.value)

        h.session.clearChat()

        assertEquals(VerifiedContextStatus.Idle, h.session.verifiedContext.value)
        h.scope.cancel()
    }
}

private fun payload(contextId: String?) = SessionStartPayload(
    SessionCapabilities(
        streaming = true,
        maxMessageSize = 4096,
        heartbeatIntervalSeconds = null,
        maxReconnectAttempts = null,
    ),
    contextId,
)
