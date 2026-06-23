// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.services.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The remaining `ConnectionService` cases not already covered by `ConnectionServiceTest.kt`
 * (clean-close, handshake-failure, and refetch-rollback live there). Driven by [FakeTransport]
 * over virtual time with Backoff overridden to 0 (the injectable-backoff pattern).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionServiceTests {

    private fun service(scope: CoroutineScope, fake: FakeTransport, maxAttempts: Int = 10) =
        ConnectionService(fake, "wss://test", scope, Backoff(overrideSeconds = 0.0), NoopLogger).apply {
            maxReconnectAttempts = maxAttempts
        }

    @Test
    fun connect_buildsUrlWithAccessTokenAndSessionId() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        // Connecting to a session is a split seam: setSessionId + setAccessToken + connectNow.
        cs.setSessionId("sess_abc")
        cs.setAccessToken("tok_123")
        cs.connectNow()
        assertEquals(1, fake.connectUrls.size)
        val url = fake.connectUrls.first()
        assertTrue(url.contains("access_token=tok_123"))
        assertTrue(url.contains("session_id=sess_abc"))
    }

    @Test
    fun newSessionConnect_resetsCursorAndReconnectsFresh() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        cs.observe()
        cs.setSessionId("s1")
        cs.setAccessToken("t1")
        cs.connectNow(); advanceUntilIdle()
        cs.updateLastSequence(5)
        // Simulate a close + reconnect cycle (1006 pre-open routes to invalid-session, no auto-reconnect).
        fake.simulateClose(1006); advanceUntilIdle()

        // Fresh connect resets. The reset semantic is split: setSessionId with a NEW id resets the
        // replay cursor; resetReconnectBudget resets the attempt counter.
        cs.setSessionId("s2")
        cs.setAccessToken("t2")
        cs.resetReconnectBudget()
        cs.connectNow(); advanceUntilIdle()

        assertEquals(2, fake.connectUrls.size)
        // The new session must connect WITHOUT the old session's replay cursor.
        assertFalse(fake.connectUrls.last().contains("cursor="))
    }

    @Test
    fun clientReplacedClose4000_isIgnored_noReconnectNoInvalidSession() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        val invalid = mutableListOf<Unit>()
        scope.launch { cs.invalidSession.collect { invalid += it } }
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        fake.simulateOpen(); advanceUntilIdle()

        // 4000 = intentional replace-on-connect: the CLIENT_REPLACED branch ignores it entirely.
        fake.simulateClose(4000, reason = "replaced", wasClean = true); advanceUntilIdle()

        assertEquals(1, fake.connectUrls.size) // no second connect
        assertEquals(0, invalid.size)          // and no invalid-session route
    }

    @Test
    fun maxReconnectAttemptsZero_disablesCap_neverFails() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        // maxReconnectAttempts is a plain var where 0 means "no cap" — scheduleReconnect's
        // `maxReconnectAttempts in 1..(reconnectAttempt - 1)` range check never trips for 0.
        val cs = service(scope, fake, maxAttempts = 0)
        cs.observe(); cs.connectNow(); advanceUntilIdle()

        // 4003 (transient network, a NON_HANDSHAKE code) without a re-open accumulates reconnect
        // attempts — with a capped budget this storm would reach Failed (see reconnectStorm test).
        repeat(5) {
            fake.simulateClose(4003); advanceUntilIdle()
            assertFalse(cs.currentStatus() is ConnectionStatus.Failed)
        }
        // Every close kept reconnecting: initial connect + 5 reconnects.
        assertEquals(6, fake.connectUrls.size)
    }

    @Test
    fun close4001AfterOpen_emitsInvalidSession() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        // Subscribe BEFORE connect so we don't miss the emit (subscribe-first pattern).
        val invalid = mutableListOf<Unit>()
        scope.launch { cs.invalidSession.collect { invalid += it } }

        cs.observe(); cs.connectNow(); advanceUntilIdle()
        fake.simulateOpen(); advanceUntilIdle()

        // 4001 AFTER open exercises the SESSION_UNKNOWN branch (distinct from the pre-open
        // 1006 handshake-failure route already covered in ConnectionServiceTest).
        fake.simulateClose(4001, reason = "unknown session", wasClean = false); advanceUntilIdle()

        assertEquals(1, invalid.size)
    }

    @Test
    fun shutdown_disconnectsTransport() = runTest {
        // ConnectionService has no destroy(); the destroy seam is Coordinator.destroy(),
        // reachable via PolyMessagingClient.shutdown(). destroy() cancels the SDK scope, so the
        // client gets its own child scope (the existing forTest pattern) rather than the test scope.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val cfg = Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = 600)
        val client = PolyMessagingClient.forTest(cfg, fake, FakeRestApi(), scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        client.shutdown()

        assertTrue(fake.disconnectCalls.any { it.first == 1000 && it.second == "client shutdown" })
    }
}
