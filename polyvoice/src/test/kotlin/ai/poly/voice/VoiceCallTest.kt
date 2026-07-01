// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Callback
import ai.poly.messaging.PolyError
import ai.poly.messaging.voice.CallState
import ai.poly.voice.internal.services.CallCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The public [VoiceCall] surface: the mic-permission gate, state exposure, and the Java overloads. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallTest {

    private class Harness(val call: VoiceCall, val sig: FakeSignalingTransport, val scope: CoroutineScope)

    private fun TestScope.harness(micPermission: Boolean): Harness {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val sig = FakeSignalingTransport()
        val coordinator = CallCoordinator(
            gatewayToken = "k",
            restApi = FakeRestApi(),
            sessionLink = FakeSessionLink(),
            signaling = sig,
            webrtc = FakeWebRtcPeer(),
            signalingUrl = "wss://t/signal",
            scope = scope,
            logger = NoopLogger,
            newCallSid = { "cs" },
        )
        return Harness(VoiceCall(coordinator, scope, permissionGranted = { micPermission }), sig, scope)
    }

    @Test
    fun start_withoutMicPermission_failsFastAndReflectsState() = runTest {
        val h = harness(micPermission = false)
        val error = assertFailsWith<PolyError> { h.call.start() }
        assertTrue(error is PolyError.Voice.MediaFailed)
        assertTrue(h.call.getState() is CallState.Failed)
        assertEquals(0, h.sig.sent.size) // pipeline never ran
        h.scope.cancel()
    }

    @Test
    fun start_withPermission_connectsThenEnds() = runTest {
        val h = harness(micPermission = true)
        h.call.start()
        assertEquals(CallState.Connecting, h.call.getState())
        assertEquals(1, h.sig.sentOfType("offer").size)

        h.call.end()
        assertEquals(CallState.Ended, h.call.getState())
        h.scope.cancel()
    }

    @Test
    fun javaStartCallback_onErrorWhenPermissionDenied() = runTest {
        val h = harness(micPermission = false)
        val delivered = CompletableDeferred<Throwable>()
        val directExecutor = Executor { it.run() }
        h.call.start(directExecutor, object : Callback<Unit> {
            override fun onSuccess(value: Unit) {}
            override fun onError(error: Throwable) { delivered.complete(error) }
        })
        val error = withTimeout(2_000) { delivered.await() }
        assertTrue(error is PolyError.Voice.MediaFailed)
        h.scope.cancel()
    }
}
