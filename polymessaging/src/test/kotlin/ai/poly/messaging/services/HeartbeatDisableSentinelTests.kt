// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.HeartbeatService
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
 * Direct unit tests of the heartbeat disable sentinel. Server capability
 * `heartbeatIntervalSeconds=0` means "no heartbeat needed": [HeartbeatService.start] with `0`
 * must not start the loop.
 *
 * Tests use kotlinx-coroutines-test virtual time (the service's scope is driven by
 * `UnconfinedTestDispatcher(testScheduler)`), so the assertions run with no real delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatDisableSentinelTests {

    // start(0) must disable the heartbeat entirely.
    @Test
    fun startWithZeroInterval_neverTicks() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val service = HeartbeatService(scope)

        var tickCount = 0
        scope.launch { service.ticks.collect { tickCount++ } }

        service.start(0)

        // Well past any plausible interval (default is 30s) — would tick if the loop were running.
        advanceTimeBy(60_000); runCurrent()

        assertEquals(0, tickCount, "intervalSeconds=0 should disable heartbeat")
        service.stop()
        scope.cancel() // stop the collector/loop so runTest's end-of-test idle drain terminates
    }

    // A positive interval DOES start ticking.
    @Test
    fun startWithPositiveInterval_ticks() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val service = HeartbeatService(scope)

        var tickCount = 0
        scope.launch { service.ticks.collect { tickCount++ } }

        service.start(1)
        advanceTimeBy(1_100); runCurrent() // first tick fires at 1_000 of virtual time

        assertTrue(tickCount >= 1)
        service.stop()
        scope.cancel()
    }

    // The default interval is 30s (`defaultIntervalSeconds = 30` and `PolyMessagingClient`'s
    // `config.heartbeatIntervalSeconds ?: 30`). With virtual time the constant is observable,
    // so the 30s boundary is asserted directly.
    @Test
    fun defaultInterval_is30Seconds() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val service = HeartbeatService(scope) // no interval arg → defaultIntervalSeconds

        var tickCount = 0
        scope.launch { service.ticks.collect { tickCount++ } }

        service.start() // no-arg start runs at the default interval

        advanceTimeBy(29_999); runCurrent()
        assertEquals(0, tickCount, "no tick before the 30s default elapses")

        advanceTimeBy(1); runCurrent() // cross exactly 30_000 of virtual time
        assertEquals(1, tickCount, "exactly one tick at the 30s default boundary")

        service.stop()
        scope.cancel()
    }
}
