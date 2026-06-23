// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.voice.CallState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests the public, gated voice-call surface.
 * Voice calling ships without an on-device media engine, so the public entry points must
 * report [PolyError.Voice.NotImplemented] — never silently no-op or appear to connect.
 */
class PolyCallTests {

    private val config = Configuration(apiKey = "test_api_key", environment = Environment.US)

    @Test
    fun start_throwsNotImplemented() = runTest {
        val call = PolyMessaging.voice(config)
        assertEquals(CallState.Idle, call.state.value)

        val error = assertFailsWith<PolyError> { call.start() }
        assertEquals(PolyError.Voice.NotImplemented, error)
        assertEquals(CallState.Failed(PolyError.Voice.NotImplemented), call.state.value)
    }

    @Test
    fun states_replaysGatedFailureToLateSubscriber() = runTest {
        val call = PolyMessaging.voice(config)
        runCatching { call.start() }

        // The state StateFlow replays the current value to late subscribers.
        // StateFlow conflation idiom: only the terminal value is asserted.
        val received = call.state.first()
        assertEquals(CallState.Failed(PolyError.Voice.NotImplemented), received)
    }

    @Test
    fun end_isSafeOnGatedCall() = runTest {
        val call = PolyMessaging.voice(config)
        call.end()
        assertEquals(CallState.Ended, call.state.value)
    }

    @Test
    fun setMuted_isSafeOnGatedCall() = runTest {
        // No media engine, but mute must not crash; the Android no-op leaves state at Idle.
        val call = PolyMessaging.voice(config)
        call.setMuted(true)
        assertEquals(CallState.Idle, call.state.value)
    }
}
