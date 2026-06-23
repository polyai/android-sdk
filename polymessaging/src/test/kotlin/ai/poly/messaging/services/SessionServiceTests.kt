// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.helpers.DeviceTypeDetector
import ai.poly.messaging.internal.ports.AccessToken
import ai.poly.messaging.internal.ports.CreatedSession
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.SessionCreateContext
import ai.poly.messaging.internal.services.ConnectionService
import ai.poly.messaging.internal.services.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `SessionService` suite: session create (token-then-create, platform/device-type context,
 * active state, token failure), socket-lifecycle readiness, end-session, and the
 * invalid-session refetch cap. The create entry point is `resumeOrCreate()` (no store →
 * createFresh), readiness is `markReady()`/`onSocketClose()`, and the 3-attempt refetch cap
 * lives in `ConnectionService.routeInvalidSession` rather than `SessionService`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionServiceTests {

    private fun service(
        rest: RestApiPort,
        scope: CoroutineScope,
        platform: String = "android",
        deviceType: String = "mobile",
    ) = SessionService(
        api = rest, store = null, streamingEnabled = true,
        platform = platform, deviceType = deviceType,
        sessionTimeoutSeconds = 600, logger = NoopLogger, scope = scope, clock = MutableClock(0),
    )

    // ---- createSession ----

    @Test
    fun createSession_obtainsTokenThenCreatesSession() = runTest {
        val rest = FakeRestApi()
        val svc = service(rest, backgroundScope)
        svc.resumeOrCreate() // no store -> createFresh
        assertEquals(1, rest.tokenCalls)
        assertEquals(1, rest.createCalls)
    }

    @Test
    fun createSession_sendsPlatformAndInjectedDeviceType() = runTest {
        val rest = FakeRestApi()
        val svc = service(rest, backgroundScope, platform = "android", deviceType = "tablet")
        svc.resumeOrCreate()
        // The SDK reports "android" as its platform.
        assertEquals("android", rest.lastContext?.platform)
        // device_type is orthogonal to platform — both are sent.
        assertEquals("tablet", rest.lastContext?.deviceType)
    }

    @Test
    fun createSession_sendsEachInjectedDeviceType() = runTest {
        // "desktop" is not applicable, so the loop covers only mobile + tablet.
        for (deviceType in listOf(DeviceTypeDetector.MOBILE, DeviceTypeDetector.TABLET)) {
            val rest = FakeRestApi()
            val svc = service(rest, backgroundScope, deviceType = deviceType)
            svc.resumeOrCreate()
            assertEquals(deviceType, rest.lastContext?.deviceType)
        }
    }

    @Test
    fun deviceTypeDetector_classifiesBy600dpThreshold() {
        // The default-detection seam is DeviceTypeDetector (PolyMessagingClient passes
        // DeviceTypeDetector.detect(context)); detect() needs a Context, so this exercises the
        // pure deviceType(smallestWidthDp) mapping using the 600dp smallest-width heuristic.
        assertEquals(DeviceTypeDetector.MOBILE, DeviceTypeDetector.deviceType(smallestWidthDp = 599))
        assertEquals(DeviceTypeDetector.TABLET, DeviceTypeDetector.deviceType(smallestWidthDp = 600))
        // The field is always a known class ("desktop" is intentionally absent).
        for (widthDp in listOf(0, 320, 599, 600, 800, 1280)) {
            assertTrue(
                DeviceTypeDetector.deviceType(smallestWidthDp = widthDp) in
                    setOf(DeviceTypeDetector.MOBILE, DeviceTypeDetector.TABLET),
            )
        }
    }

    @Test
    fun createSession_setsActiveState() = runTest {
        val svc = service(FakeRestApi(), backgroundScope)
        val created = svc.resumeOrCreate()
        val state = svc.current
        assertEquals(SessionStatus.ACTIVE, state.status)
        assertEquals("sess-1", state.sessionId)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(state.sessionId, created.sessionId)
    }

    @Test
    fun tokenFailure_setsErrorAndFlagsInvalidApiKey() = runTest {
        val rest = TokenFailingRestApi()
        val svc = service(rest, backgroundScope)
        // failState RETURNS a failure-carrying CreatedSession, so assert the carried failure.
        val created = svc.resumeOrCreate()
        assertEquals(SessionErrorCode.CONNECTOR_VALIDATION_FAILED, svc.current.error)
        assertTrue(svc.current.hasInvalidApiKey)
        assertTrue(created.failure is PolyError.Auth.Unauthorized)
        assertEquals(0, rest.createCalls) // token failed first — create was never reached
    }

    @Test
    fun secondCreate_reobtainsToken() = runTest {
        // The token cache lives in the OkHttp adapter (not the service), so each createFresh
        // re-obtains via the port.
        val rest = FakeRestApi()
        val svc = service(rest, backgroundScope)
        svc.resumeOrCreate()
        svc.resumeOrCreate()
        assertEquals(2, rest.tokenCalls)
        assertEquals(2, rest.createCalls)
    }

    // ---- Socket lifecycle ----

    @Test
    fun markReady_setsReadyAndClearsError() = runTest {
        val svc = service(FakeRestApi(), backgroundScope)
        svc.resumeOrCreate()
        svc.markReady() // the onSocketOpen seam
        assertTrue(svc.current.isReady)
        assertEquals(SessionStatus.ACTIVE, svc.current.status)
        assertNull(svc.current.error)
    }

    @Test
    fun onSocketClose_setsNotReady() = runTest {
        // backgroundScope owns the 500ms abnormal-close latch job so runTest reaps it; the latch
        // itself is covered by ResilienceTest.abnormalClose_latchesConnectionClosedAbnormally_thenClearsOnInbound.
        val svc = service(FakeRestApi(), backgroundScope)
        svc.resumeOrCreate()
        svc.markReady()
        svc.onSocketClose(ConnectionCloseEvent(1006, "abnormal", wasClean = false))
        assertFalse(svc.current.isReady) // not-ready is immediate, before any latch delay
    }

    // ---- End session ----

    @Test
    fun markEnded_setsEndedAndClearsStoredSession() = runTest {
        val svc = service(FakeRestApi(), backgroundScope)
        svc.resumeOrCreate()
        svc.markEnded(expired = false)
        assertEquals(SessionStatus.ENDED, svc.current.status)
        assertNull(svc.current.error)
        assertNull(svc.lastSessionId) // clearStored: persisted session + lastSessionId dropped
        // markEnded preserves cur.sessionId in the SessionState snapshot.
        assertEquals("sess-1", svc.current.sessionId)
    }

    // ---- Refetch cap ----

    @Test
    fun invalidSessionCap_failsWithSessionExpiredAfterThreeAttempts() = runTest {
        // The 3-attempt cap lives in ConnectionService.routeInvalidSession
        // (MAX_INVALID_SESSION_ATTEMPTS = 3 -> fail(SessionExpired)), not SessionService.
        // Complements notifyRefetchFailed_rollsBackInvalidSessionBudget, which proves rollback
        // AVOIDS the cap; here nothing rolls the budget back, so the breaker trips.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val cs = ConnectionService(fake, "wss://test", scope, Backoff(overrideSeconds = 0.0), NoopLogger)
        val invalid = mutableListOf<Unit>()
        scope.launch { cs.invalidSession.collect { invalid += it } }
        cs.observe(); cs.connectNow(); advanceUntilIdle()
        // 4 pre-open 1006 closes = handshake failures -> invalid-session routes; the 4th exceeds
        // the cap of 3 and trips the terminal breaker instead of emitting.
        repeat(4) { fake.simulateClose(1006); advanceUntilIdle() }
        assertEquals(3, invalid.size)
        val status = cs.currentStatus()
        assertTrue(status is ConnectionStatus.Failed)
        assertTrue((status as ConnectionStatus.Failed).reason is PolyError.Session.SessionExpired)
    }
}

/** REST double whose token step fails with an unauthorized error.
 *  FakeRestApi is not open, so this implements [RestApiPort] directly (file-private). */
private class TokenFailingRestApi : RestApiPort {
    var tokenCalls = 0
    var createCalls = 0

    override suspend fun obtainAccessToken(): AccessToken {
        tokenCalls++
        throw PolyError.Auth.Unauthorized
    }

    override suspend fun ensureAccessToken(): String {
        tokenCalls++
        throw PolyError.Auth.Unauthorized
    }

    override suspend fun createSession(token: String, context: SessionCreateContext): CreatedSession {
        createCalls++
        throw IllegalStateException("createSession must not be reached when the token step fails")
    }
}
