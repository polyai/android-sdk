// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the session lifecycle: idle-timeout check, force-create refetch, and token
 * refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionServiceTest {

    private fun service(rest: FakeRestApi, clock: MutableClock, scope: CoroutineScope, timeout: Int = 600) = SessionService(
        api = rest, store = null, streamingEnabled = true,
        platform = "android", deviceType = "mobile",
        sessionTimeoutSeconds = timeout, logger = NoopLogger, scope = scope, clock = clock,
    )

    @Test
    fun checkTimeout_trueOnlyAfterIdlePastTimeout() = runTest {
        val clock = MutableClock(0)
        val svc = service(FakeRestApi(), clock, backgroundScope, timeout = 600)
        svc.resumeOrCreate()
        assertFalse(svc.checkTimeout())          // just created
        clock.now = 599_000
        assertFalse(svc.checkTimeout())          // still inside the window
        clock.now = 601_000
        assertTrue(svc.checkTimeout())           // idle past 600s
    }

    @Test
    fun touch_resetsIdleTimer() = runTest {
        val clock = MutableClock(0)
        val svc = service(FakeRestApi(), clock, backgroundScope, timeout = 600)
        svc.resumeOrCreate()
        clock.now = 601_000
        assertTrue(svc.checkTimeout())
        svc.touch()                              // real activity
        assertFalse(svc.checkTimeout())          // timer reset to now
    }

    @Test
    fun refetch_forcesFreshCreate() = runTest {
        val clock = MutableClock(0)
        val rest = FakeRestApi()
        val svc = service(rest, clock, backgroundScope)
        svc.resumeOrCreate()
        assertEquals(1, rest.createCalls)
        svc.refetch()
        assertEquals(2, rest.createCalls)        // always a fresh create, never a resume
    }

    @Test
    fun refetch_debounces300msBeforeCreate() = runTest {
        // executeRefetch sleeps refetchDebounceNanos (300ms) before re-creating the session.
        val rest = FakeRestApi()
        val svc = service(rest, MutableClock(0), backgroundScope)
        svc.resumeOrCreate()                     // create #1 (no debounce on resume/create path)
        val before = testScheduler.currentTime
        svc.refetch()
        assertEquals(2, rest.createCalls)
        assertEquals(300L, testScheduler.currentTime - before) // the refetch waited the 300ms trailing debounce
    }

    @Test
    fun currentAccessToken_refreshesViaEnsure() = runTest {
        val svc = service(FakeRestApi(), MutableClock(0), backgroundScope)
        svc.resumeOrCreate()
        assertNotNull(svc.currentAccessToken())
    }

    @Test
    fun invalidKey_flagsHasInvalidApiKey_withConnectorCode() = runTest {
        val svc = service(FakeRestApi(invalidKey = true), MutableClock(0), backgroundScope)
        val created = svc.resumeOrCreate()
        assertTrue(created.hasInvalidApiKey)
        assertTrue(svc.current.hasInvalidApiKey)
        assertEquals(SessionErrorCode.CONNECTOR_VALIDATION_FAILED, svc.current.error)
    }

    @Test
    fun invalidKey_preservesSpecificConnectorCode() = runTest {
        // Surface the SPECIFIC parsed connector code (NOT collapsed to connectorValidationFailed)
        // while still flagging hasInvalidApiKey via set membership.
        val rest = FakeRestApi(invalidKey = true, invalidKeyCode = SessionErrorCode.CONNECTOR_LOOKUP_FAILED)
        val svc = service(rest, MutableClock(0), backgroundScope)
        svc.resumeOrCreate()
        assertEquals(SessionErrorCode.CONNECTOR_LOOKUP_FAILED, svc.current.error)
        assertTrue(svc.current.hasInvalidApiKey)
    }
}
