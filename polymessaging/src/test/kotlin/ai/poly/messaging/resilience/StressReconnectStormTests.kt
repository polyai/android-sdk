// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.services.ConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stress probes for the reconnect state machine in [ConnectionService].
 * A "reconnect storm" is a burst of 1006 closes arriving faster
 * than backoff can fire a reconnect. Invariants pinned: the attempt counter walks 1,2,3,... with
 * no skipped/repeated step, never exceeds `maxReconnectAttempts`, and a sub-budget storm does NOT
 * trip `Failed` while an over-budget storm does (emitting both a terminal closeEvent and `Failed`).
 *
 * We `cancelReconnect()` the just-scheduled job after each close so no reconnect fires mid-storm
 * (which would reset `currentAttemptOpened` and re-route a later close to invalid-session). With
 * the DEFAULT (nonzero) Backoff the scheduled job sits on virtual time and the cancel happens
 * before any time is advanced, so the ladder is fully deterministic — no jitter floor dependence.
 * Status and close events are collected unconfined into plain lists (flow delivery is synchronous).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StressReconnectStormTests {

    /** Contiguous, strictly-increasing, 1-based ladder. */
    private fun assertContiguousAscendingLadder(attempts: List<Int>) {
        assertTrue(attempts.isNotEmpty(), "expected a non-empty reconnect ladder")
        assertEquals(1, attempts.first(), "ladder must start at attempt 1; got $attempts")
        attempts.forEachIndexed { i, value ->
            assertEquals(
                i + 1,
                value,
                "reconnect attempts must walk every step without skipping or repeating; got $attempts",
            )
        }
    }

    @Test
    fun rapidReconnectStorm_walksContiguousLadderUnderBudget() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        // Default (nonzero) Backoff: the reconnect job suspends on virtual time and is cancelled
        // before any advance, following the cancel-per-close pattern (no connect flood).
        val cs = ConnectionService(fake, "wss://test", scope, Backoff(), NoopLogger).apply {
            maxReconnectAttempts = 10
        }

        // Unconfined collector: every status emission is delivered synchronously into the list
        // (collected from a replay-last multicaster).
        val statuses = mutableListOf<ConnectionStatus>()
        scope.launch { cs.status.collect { statuses += it } }

        cs.observe()
        cs.connectNow()
        advanceUntilIdle()
        assertTrue(
            statuses.any { it is ConnectionStatus.Connecting },
            "status collector must observe the initial Connecting",
        )

        // Open the socket so subsequent 1006 closes are treated as abnormal drops (not handshake
        // failures) and route through scheduleReconnect (currentAttemptOpened = true).
        fake.simulateOpen()
        advanceUntilIdle()
        assertTrue(
            statuses.any { it is ConnectionStatus.Open },
            "onOpen must run so currentAttemptOpened=true",
        )

        for (expected in 1..5) {
            fake.simulateClose(1006, "keep-alive timeout", wasClean = false)
            val ladder = statuses.filterIsInstance<ConnectionStatus.Reconnecting>().map { it.attempt }
            assertTrue(
                ladder.size >= expected,
                "close #$expected must schedule a reconnect; got $ladder",
            )
            // Cancel the just-scheduled backoff job BEFORE advancing time so no reconnect fires
            // mid-storm (deterministic ladder, no connect flood).
            cs.cancelReconnect()
        }
        advanceUntilIdle()

        val attempts = statuses.filterIsInstance<ConnectionStatus.Reconnecting>().map { it.attempt }

        assertEquals(
            5,
            attempts.size,
            "each 1006 close should schedule exactly one reconnect; got $statuses",
        )

        // INVARIANT: contiguous, strictly-increasing, 1-based ladder ([1,2,3,4,5]).
        assertContiguousAscendingLadder(attempts)

        // INVARIANT: never exceed the configured ceiling (budget == 10).
        assertTrue(
            (attempts.maxOrNull() ?: 0) <= 10,
            "attempt counter must stay within maxReconnectAttempts (10); got $attempts",
        )

        // INVARIANT: a sub-budget storm must NOT trip the terminal breaker.
        assertFalse(
            statuses.any { it is ConnectionStatus.Failed },
            "5 closes under a budget of 10 must not emit terminal Failed",
        )

        // INVARIANT: no connect flood — only the initial connect was issued.
        assertEquals(
            1,
            fake.connectUrls.size,
            "storm must not fan out into a connect flood; got ${fake.connectUrls.size}",
        )
    }

    @Test
    fun reconnectStorm_overBudget_tripsBreakerAtCeilingAndEmitsTerminalCloseEvent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        val budget = 3
        val cs = ConnectionService(fake, "wss://test", scope, Backoff(), NoopLogger).apply {
            maxReconnectAttempts = budget
        }

        val statuses = mutableListOf<ConnectionStatus>()
        scope.launch { cs.status.collect { statuses += it } }

        // closeEvents is a non-replay SharedFlow, so subscribe BEFORE any close is driven.
        val closeReasons = mutableListOf<String>()
        scope.launch { cs.closeEvents.collect { closeReasons += it.reason } }

        cs.observe()
        cs.connectNow()
        advanceUntilIdle()
        assertTrue(
            statuses.any { it is ConnectionStatus.Connecting },
            "status collector must observe the initial Connecting",
        )

        fake.simulateOpen()
        advanceUntilIdle()
        assertTrue(
            statuses.any { it is ConnectionStatus.Open },
            "onOpen must run so currentAttemptOpened=true",
        )

        for (expected in 1..budget) {
            fake.simulateClose(1006, "keep-alive timeout", wasClean = false)
            val ladder = statuses.filterIsInstance<ConnectionStatus.Reconnecting>().map { it.attempt }
            assertTrue(
                ladder.size >= expected,
                "close #$expected must bump the ladder; got $ladder",
            )
            cs.cancelReconnect()
        }

        // reconnectAttempt now == budget, so this close trips the guard.
        fake.simulateClose(1006, "keep-alive timeout", wasClean = false)
        advanceUntilIdle()
        assertTrue(
            statuses.any { it is ConnectionStatus.Failed },
            "over-budget close must emit terminal Failed; got $statuses",
        )
        assertTrue(
            cs.currentStatus() is ConnectionStatus.Failed,
            "terminal status must be Failed; got ${cs.currentStatus()}",
        )

        val attempts = statuses.filterIsInstance<ConnectionStatus.Reconnecting>().map { it.attempt }

        // INVARIANT: contiguous & strictly increasing up to the breaker.
        assertContiguousAscendingLadder(attempts)

        // INVARIANT: ladder reaches exactly the budget — the breaker trips on the close AFTER
        // attempt == budget, so no Reconnecting(budget + 1) is emitted.
        assertEquals(
            budget,
            attempts.maxOrNull() ?: 0,
            "ladder must reach exactly the budget ($budget) before the breaker; got $attempts",
        )
        assertEquals(
            budget,
            attempts.size,
            "exactly $budget reconnect attempts must be emitted before the breaker; got $attempts",
        )

        // INVARIANT (I15): the breaker emits a terminal closeEvent (not just the status change) so
        // the Coordinator can surface the failure — ConnectionService.fail emits a synthetic 1006
        // close whose reason carries the error's debugDescription ("Max reconnect attempts exceeded").
        assertTrue(
            closeReasons.any { it.contains("Max reconnect") },
            "breaker must emit a 'Max reconnect attempts exceeded' closeEvent (I15); got $closeReasons",
        )
    }
}
