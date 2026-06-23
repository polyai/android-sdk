// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.services.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconnect-ladder tests driven by a fake transport over virtual time. Backoff is overridden
 * to 0 so the schedule fires immediately and deterministically (the injectable-backoff pattern).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionServiceTest {

    private fun service(scope: kotlinx.coroutines.CoroutineScope, fake: FakeTransport, maxAttempts: Int = 10) =
        ConnectionService(fake, "wss://test", scope, Backoff(overrideSeconds = 0.0), NoopLogger).apply {
            maxReconnectAttempts = maxAttempts
        }

    @Test
    fun connect_thenOpen_reportsOpen() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        assertEquals(1, fake.connectUrls.size)
        fake.simulateOpen(); advanceUntilIdle()
        assertTrue(cs.currentStatus() is ConnectionStatus.Open)
    }

    @Test
    fun cleanClose_doesNotReconnect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        fake.simulateOpen(); advanceUntilIdle()
        fake.simulateClose(1000); advanceUntilIdle()
        assertEquals(1, fake.connectUrls.size) // no reconnect on a clean close
    }

    @Test
    fun abnormalCloseAfterOpen_reconnects() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        fake.simulateOpen(); advanceUntilIdle()
        fake.simulateClose(1006); advanceUntilIdle()
        assertEquals(2, fake.connectUrls.size) // reconnected
        // The sequence is Reconnecting(N) → (backoff) → Connecting → Open. With backoff=0 the delay
        // elapses immediately, so the connect step's Connecting is the current status (it stays
        // Connecting until the socket opens, since the fake doesn't auto-open on connect()).
        assertTrue(cs.currentStatus() is ConnectionStatus.Connecting)
    }

    @Test
    fun handshakeFailureBeforeOpen_routesToInvalidSession() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        val invalid = mutableListOf<Unit>()
        scope.launch { cs.invalidSession.collect { invalid += it } }
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        // 1006 before the socket ever opened = handshake failure -> invalid-session path.
        fake.simulateClose(1006); advanceUntilIdle()
        assertEquals(1, invalid.size)
    }

    @Test
    fun transientCloseAfterOpen_reconnects_doesNotRouteInvalidSession() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        val invalid = mutableListOf<Unit>()
        scope.launch { cs.invalidSession.collect { invalid += it } }
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        fake.simulateOpen(); advanceUntilIdle()
        // 4003 (transient network) after open -> plain reconnect, NOT invalid-session.
        fake.simulateClose(4003); advanceUntilIdle()
        assertEquals(2, fake.connectUrls.size)
        assertEquals(0, invalid.size)
    }

    @Test
    fun dropConnectionForReconnect_closesOpenSocketWithSendableCode() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        cs.dropConnectionForReconnect("test") // no-op while not open
        assertTrue(fake.disconnectCalls.isEmpty())
        fake.simulateOpen(); advanceUntilIdle()
        cs.dropConnectionForReconnect("network lost"); advanceUntilIdle()
        // 4002 (a sendable app code, not the reserved 1006) so the close actually happens.
        assertTrue(fake.disconnectCalls.any { it.first == 4002 })
    }

    @Test
    fun notifyRefetchFailed_rollsBackInvalidSessionBudget() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake)
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        // 4 handshake failures would exceed the cap of 3 and reach Failed — but each failed refetch
        // rolls the budget back, so the session keeps recovering instead of terminating.
        repeat(4) { fake.simulateClose(1006); advanceUntilIdle(); cs.notifyRefetchFailed() }
        assertTrue(cs.currentStatus() !is ConnectionStatus.Failed)
    }

    @Test
    fun reconnectStorm_failsWhenBudgetExhausted() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = service(scope, fake, maxAttempts = 2)
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        // Transient (4003) closes that never re-open exhaust the budget. The budget resets on
        // socket OPEN, so an open-then-drop loop would reset it — only no-open failures accumulate.
        repeat(3) { fake.simulateClose(4003); advanceUntilIdle() } // attempt 1, 2, then 3 (>2) -> Failed
        assertTrue(cs.currentStatus() is ConnectionStatus.Failed)
        assertTrue((cs.currentStatus() as ConnectionStatus.Failed).reason is PolyError.Transport)
    }
}
