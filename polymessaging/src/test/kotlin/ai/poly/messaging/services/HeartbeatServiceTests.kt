// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.HeartbeatService
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Direct unit tests of the internal [HeartbeatService] ticker. The interval surface is:
 * `applyServerInterval(positive)` sets a new interval, `applyServerInterval(null)` resets to
 * the default, and `applyServerInterval(0)` is the documented "0 disable sentinel".
 *
 * All tests run on kotlinx-coroutines-test virtual time (no real sleeps): waits and inverted
 * expectations become `advanceTimeBy` plus Turbine `awaitItem()` / `expectNoEvents()`. Each
 * test cancels the service scope at the end so the infinite ticker loop does not stall
 * runTest's end-of-test idle drain (as ResilienceTest does).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatServiceTests {

    @Test
    fun start_emitsTicks() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val service = HeartbeatService(scope)

        service.ticks.test {
            runCurrent() // ensure the Turbine collector has subscribed before the first tick
            service.start(1)
            advanceTimeBy(1_100); runCurrent() // advance past the 1s interval in virtual time
            awaitItem() // one tick arrived
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    @Test
    fun stop_cancelsTicking() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val service = HeartbeatService(scope)

        service.start(1)
        service.stop()

        service.ticks.test {
            // Advance well past any interval; no tick should fire after stop().
            advanceTimeBy(5_000); runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    @Test
    fun applyServerIntervalPositive_restartsRunningTimer() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val service = HeartbeatService(scope)

        service.ticks.test {
            runCurrent()
            service.start(100) // next tick is 100s away
            // Change the interval on a running loop.
            service.applyServerInterval(1)
            advanceTimeBy(1_100); runCurrent()
            // A tick well before 100s proves the timer restarted at the new 1s interval
            // rather than finishing the old 100s delay.
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    @Test
    fun applyServerIntervalZero_disablesTicking() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val service = HeartbeatService(scope)

        service.start(1)
        // applyServerInterval(0) is the documented "0 disable sentinel" (server capability
        // semantics folded into one method) and stops the loop.
        service.applyServerInterval(0)

        service.ticks.test {
            advanceTimeBy(5_000); runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }

    @Test
    fun applyServerIntervalNull_resetsToDefaultInterval() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val service = HeartbeatService(scope, defaultIntervalSeconds = 1)

        service.ticks.test {
            runCurrent()
            service.start()
            service.applyServerInterval(100) // stretch the interval far away
            // Reset to the default interval.
            service.applyServerInterval(null)
            advanceTimeBy(1_100); runCurrent()
            // Virtual time lets us assert the 1s default actually took effect (a tick arrives
            // long before the 100s override).
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        scope.cancel()
    }
}
