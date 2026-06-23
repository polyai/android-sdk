// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.ports.AccessToken
import ai.poly.messaging.internal.ports.CreatedSession
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.SessionCreateContext
import ai.poly.messaging.internal.services.ChatService
import ai.poly.messaging.internal.services.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ship-readiness resilience matrix, covering the failure modes
 * not already asserted end-to-end by [ResilienceTest] / [ConnectionServiceTest]:
 *
 *   1. offline at launch -> start fails + auto-retry on network restore
 *   2. 1006 reconnect + cursor replay (plain-int `cursor=N`, NOT `seq:N`, same session_id)
 *   3. network lost THEN restored -> reconnect round-trip back to Open ("reconnected to wifi")
 *   4. max-reconnect terminal emits BOTH a closeEvent AND the Failed status (invariant I15)
 *   5. 4001 -> invalid session -> refetch -> reconnect
 *   6. streaming reassembly: a mid-stream message_id switch finalises the old buffer (I17)
 *
 * (The dedup / network-lost-drop / idle-expiry / foreground-reconnect / batch-order
 * rows are covered by ChatServiceTest, ResilienceTest, SessionServiceTest, and WireTest.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilienceMatrixTests {

    private fun cfg() =
        Configuration(apiKey = "key", heartbeatIntervalSeconds = 0, sessionTimeoutSeconds = 600)

    @Test
    fun offlineStart_failsThenRetriesOnNetworkRestore() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val net = FakeNetworkMonitor(initial = false)
        val rest = FailableRestApi().apply { tokenFailure = PolyError.Auth.TokenAcquisitionFailed }
        val client = PolyMessagingClient.forTest(cfg(), fake, rest, scope, networkMonitor = net)
        runCurrent()

        // Offline at launch: the Coordinator latches startFailure
        // and rethrows it through resume()/send() — assert via resume().
        assertFailsWith<PolyError> { client.resume() }
        assertTrue(fake.connectUrls.isEmpty()) // no WS while offline
        assertEquals(0, rest.createCalls) // token failure short-circuits createSession

        // Network comes back: the SDK must auto-retry the resume-or-create flow
        // (Coordinator.observeNetwork -> onNetworkRestored -> reconnectOrRecreate).
        rest.tokenFailure = null
        net.setOnline(true); runCurrent()

        assertEquals(1, rest.createCalls) // session created after restore
        assertEquals(1, fake.connectUrls.size) // WS connected after restore
        assertTrue(rest.tokenCalls >= 2) // token retried after restore
    }

    @Test
    fun abnormalClose_reconnectsAndReplaysCursorAsPlainInt() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = ConnectionService(fake, "wss://test", scope, Backoff(overrideSeconds = 0.0), NoopLogger)
        cs.setSessionId("sess_1")
        cs.setAccessToken("tok_1")
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        fake.simulateOpen(); advanceUntilIdle() // currentAttemptOpened = true
        // Simulate having received history up to sequence 12.
        cs.updateLastSequence(12)

        // Abnormal close (keep-alive timeout) -> reconnect ladder engages.
        fake.simulateClose(1006, "keep-alive timeout"); advanceUntilIdle()

        assertEquals(2, fake.connectUrls.size) // reconnected after 1006
        val replayUrl = fake.connectUrls[1]
        // Cursor replay must send the high-water sequence as a PLAIN int…
        assertTrue(replayUrl.contains("cursor=12"), "cursor replay must send the high-water sequence; got $replayUrl")
        // …because the server parses cursor with strconv.ParseUint — a `seq:` prefix would be rejected.
        assertTrue(!replayUrl.contains("seq:"), "`seq:` prefix would be rejected by the server; got $replayUrl")
        assertTrue(replayUrl.contains("session_id=sess_1"), "same session on reconnect (I21); got $replayUrl")
    }

    @Test
    fun networkLostThenRestored_reconnectsToOpen() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val net = FakeNetworkMonitor(initial = true)
        val client = PolyMessagingClient.forTest(cfg(), fake, FakeRestApi(), scope, networkMonitor = net)
        runCurrent(); fake.simulateOpen(); runCurrent()
        assertEquals(1, fake.connectUrls.size)

        // Wi-Fi off: the open socket is dropped. OkHttp refuses
        // to SEND reserved close codes, so the forced drop uses 4002 (I26).
        net.setOnline(false); runCurrent()
        assertTrue(fake.disconnectCalls.any { it.first == 4002 }, "network-lost must drop the open socket; got ${fake.disconnectCalls}")

        // Wi-Fi back: the SDK re-attempts the socket.
        net.setOnline(true); runCurrent(); advanceUntilIdle()
        assertTrue(fake.connectUrls.size >= 2, "network restore must re-attempt the socket; got ${fake.connectUrls.size}")

        // Reconnect completes. StateFlow conflates intermediate values (accepted idiom), so assert
        // the TERMINAL state: Open — which also proves no terminal Failed was latched along the way
        // (Failed is terminal: ConnectionService.fail stops the ladder, so Open could not follow it).
        fake.simulateOpen(); runCurrent()
        assertTrue(client.connectionStatus.value is ConnectionStatus.Open,
            "reconnect after Wi-Fi restore must reach Open, not Failed; got ${client.connectionStatus.value}")
    }

    @Test
    fun maxReconnect_emitsTerminalCloseEventAndFailedStatus() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        // Backoff = 1s (NOT 0) on virtual time, for the pre-reconnect timing: the second
        // 1006 must land BEFORE the scheduled connectNow resets currentAttemptOpened, otherwise it
        // would route as a handshake failure -> invalid-session instead of tripping the budget.
        val cs = ConnectionService(fake, "wss://test", scope, Backoff(overrideSeconds = 1.0), NoopLogger)
        cs.maxReconnectAttempts = 1

        val closes = mutableListOf<ConnectionCloseEvent>()
        scope.launch { cs.closeEvents.collect { closes += it } }

        cs.observe(); cs.connectNow(); runCurrent()
        fake.simulateOpen(); runCurrent() // currentAttemptOpened = true
        fake.simulateClose(1006); runCurrent() // attempt -> 1 (== max); reconnect scheduled at +1s
        fake.simulateClose(1006); runCurrent() // before the reconnect fires: guard trips -> terminal

        // Invariant I15: the terminal breaker emits BOTH the Failed status AND a synthetic close
        // event (ConnectionService.fail), so the Coordinator's close observer sees the terminal close.
        assertTrue(cs.currentStatus() is ConnectionStatus.Failed, "budget exhaustion must latch Failed; got ${cs.currentStatus()}")
        assertTrue(closes.any { it.reason.contains("Max reconnect") },
            "terminal failure must emit a synthetic close event; got $closes")
    }

    @Test
    fun close4001_refetchesSessionAndReconnects() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val fake = FakeTransport()
        val rest = FakeRestApi()
        PolyMessagingClient.forTest(cfg(), fake, rest, scope)
        runCurrent(); fake.simulateOpen(); runCurrent()

        assertEquals(1, rest.createCalls)
        fake.simulateClose(4001, "unknown session"); runCurrent()
        // SessionService.refetch sleeps a 300ms trailing debounce before re-creating — ride it out.
        advanceTimeBy(400); runCurrent()

        assertEquals(2, rest.createCalls) // 4001 must trigger a session refetch
        assertEquals(2, fake.connectUrls.size) // reconnect with the refetched session
    }

    @Test
    fun streaming_midStreamIdSwitchFinalizesOldBuffer() {
        val chat = ChatService()
        // Open a stream for m1 (no isComplete), then a chunk for m2 arrives.
        chat.handleInbound(
            MessagingEvent.AgentMessageChunk(
                Envelope("c1", 1, 0L, null),
                AgentMessageChunkPayload("m1", "Hello from one", 0, false, emptyList(), emptyList()),
            ),
        )
        val out = chat.handleInbound(
            MessagingEvent.AgentMessageChunk(
                Envelope("c2", 2, 0L, null),
                AgentMessageChunkPayload("m2", "Hello from two", 0, false, emptyList(), emptyList()),
            ),
        )
        // The abandoned m1 buffer must finalise as its own bubble (I17), not mix into m2.
        val assembled = out.filterIsInstance<MessagingEvent.AgentMessage>()
        assertTrue(assembled.any { it.payload.text == "Hello from one" },
            "old buffer (m1) must finalise as its own bubble; got ${out.map { it::class.simpleName }}")
    }
}

/**
 * [FakeRestApi] (Doubles.kt) has no token-failure mode and is final, so the offline test uses this
 * file-private variant: set [tokenFailure] to make [obtainAccessToken] throw; clear it to
 * restore success.
 */
private class FailableRestApi : RestApiPort {
    var tokenFailure: PolyError? = null
    var tokenCalls = 0
    var createCalls = 0

    override suspend fun obtainAccessToken(): AccessToken {
        tokenCalls++
        tokenFailure?.let { throw it }
        return AccessToken(FakeRestApi.TEST_JWT, Long.MAX_VALUE)
    }

    override suspend fun ensureAccessToken(): String {
        tokenCalls++
        tokenFailure?.let { throw it }
        return FakeRestApi.TEST_JWT
    }

    override suspend fun createSession(token: String, context: SessionCreateContext): CreatedSession {
        createCalls++
        return CreatedSession(sessionId = "sess-1", accessToken = token)
    }
}
