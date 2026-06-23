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
import kotlin.test.assertTrue

/**
 * The connection-status stress cases: the full ordered open -> drop -> reconnect -> open
 * status ladder, plus the connection-age reset invariant. Driven by [FakeTransport] over
 * virtual time with Backoff overridden to 0 (the injectable-backoff pattern) and a
 * [MutableClock] so the connection age is observable without real time passing. No
 * re-emit-until-registered polling is needed: FakeTransport delivers via flows collected on an
 * UnconfinedTestDispatcher, so a single `simulateOpen()` is always observed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StressStatusEmissionTests {

    @Test
    fun openDropReconnectOpen_walksFullStatusLadder_andResetsConnectionAge() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val fake = FakeTransport()
        // now must start > 0: connectionOpenedAt == 0 is the service's "not open" sentinel, so an
        // open stamped at t=0 would be indistinguishable from closed.
        val clock = MutableClock(now = 1_000L)
        val cs = ConnectionService(fake, "wss://test", scope, Backoff(overrideSeconds = 0.0), NoopLogger, clock)

        // Subscribe before connect so we capture the very first Connecting (subscribe-first
        // pattern). Unconfined dispatcher => every _status.value set is delivered synchronously.
        val collected = mutableListOf<ConnectionStatus>()
        scope.launch { cs.status.collect { collected += it } }

        // Connecting to a session is a split seam: setSessionId + setAccessToken + connectNow.
        cs.setSessionId("s1")
        cs.setAccessToken("t1")
        cs.observe()
        cs.connectNow(); advanceUntilIdle()

        // driveOpen #1.
        fake.simulateOpen(); advanceUntilIdle()
        clock.now += 50
        // connectionAgeMillis() > 0 confirms the open timestamp is set once open.
        assertTrue(cs.connectionAgeMillis() > 0, "connection age should tick once open")

        // Network drop: schedules reconnect, emits Reconnecting(1), clears the open timestamp.
        fake.simulateClose(1006, reason = "drop", wasClean = false)
        // The contract is age == 0 while disconnected.
        assertEquals(0L, cs.connectionAgeMillis(), "connection age must be 0 while disconnected")
        advanceUntilIdle()

        // Exactly one extra connect: each reschedule cancels the prior timer, so no flood.
        assertEquals(2, fake.connectUrls.size, "reconnect should issue exactly one extra connect")

        // driveOpen #2.
        fake.simulateOpen(); advanceUntilIdle()
        clock.now += 50
        assertTrue(cs.connectionAgeMillis() > 0, "connection age should tick again on reopen")

        // Ordered-transition invariant, collapsing runs of identical consecutive statuses:
        // StateFlow conflates rapid same-value / transient emissions, so we assert the collapsed
        // ladder rather than every raw emission. The leading Idle is StateFlow's replayed initial
        // value, so it is dropped before comparing.
        val collapsed = collected
            .fold(mutableListOf<ConnectionStatus>()) { acc, s -> if (acc.lastOrNull() != s) acc.add(s); acc }
            .dropWhile { it is ConnectionStatus.Idle }
        val expected = listOf<ConnectionStatus>(
            ConnectionStatus.Connecting,
            ConnectionStatus.Open,
            ConnectionStatus.Reconnecting(1),
            ConnectionStatus.Connecting,
            ConnectionStatus.Open,
        )
        assertEquals(expected, collapsed, "collapsed status ladder must match the promised transition order; got $collapsed")

        // No terminal breaker on a single clean reconnect cycle.
        assertTrue(
            collected.none { it is ConnectionStatus.Failed },
            "a single open->drop->reconnect->open cycle must not emit Failed",
        )
    }
}
