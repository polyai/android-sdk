// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.PolyError
import ai.poly.messaging.voice.CallState
import ai.poly.voice.internal.ports.AudioInterruption
import ai.poly.voice.internal.ports.PeerConnectionState
import ai.poly.voice.internal.services.CallCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The call state machine, driven over virtual time with port fakes. Covers the start pipeline,
 * answer/ICE handling, ICE buffering both directions, connection timeout, disconnect grace,
 * error/close, mute, and teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallCoordinatorTest {

    private class Rig(
        val coordinator: CallCoordinator,
        val rest: FakeRestApi,
        val link: FakeSessionLink,
        val sig: FakeSignalingTransport,
        val peer: FakeWebRtcPeer,
        val audio: FakeAudioControl,
        val scope: CoroutineScope,
    ) {
        val state get() = coordinator.state.value
    }

    private fun TestScope.rig(
        rest: FakeRestApi = FakeRestApi(),
        link: FakeSessionLink = FakeSessionLink(),
        sig: FakeSignalingTransport = FakeSignalingTransport(),
        peer: FakeWebRtcPeer = FakeWebRtcPeer(),
        audio: FakeAudioControl = FakeAudioControl(),
        connectionTimeoutMs: Long = 30_000,
        disconnectGraceMs: Long = 5_000,
    ): Rig {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val coordinator = CallCoordinator(
            gatewayToken = "api-key",
            restApi = rest,
            sessionLink = link,
            signaling = sig,
            webrtc = peer,
            signalingUrl = "wss://test/signal",
            scope = scope,
            logger = NoopLogger,
            audioControl = audio,
            newCallSid = { "cs-1" },
            connectionTimeoutMs = connectionTimeoutMs,
            disconnectGraceMs = disconnectGraceMs,
        )
        return Rig(coordinator, rest, link, sig, peer, audio, scope)
    }

    // ── inbound frame builders ─────────────────────────────────────
    private fun answer(sessionId: String? = "sig-1", sdp: String = "REMOTE"): String =
        JSONObject()
            .put("type", "answer")
            .apply { if (sessionId != null) put("sessionId", sessionId) }
            .put("data", JSONObject().put("type", "answer").put("sdp", sdp))
            .toString()

    private fun iceFrame(candidate: String, sdpMid: String? = "0", sdpMLineIndex: Int? = 0): String =
        JSONObject()
            .put("type", "ice-candidate")
            .put("data", JSONObject().put("candidate", candidate).put("sdpMid", sdpMid).put("sdpMLineIndex", sdpMLineIndex))
            .toString()

    private fun errorFrame(message: String): String =
        JSONObject().put("type", "error").put("data", JSONObject().put("message", message)).toString()

    private val closeFrame = """{"type":"close"}"""

    // ── tests ──────────────────────────────────────────────────────

    @Test
    fun happyPath_offerThenAnswerThenConnected() = runTest {
        val r = rig()
        r.coordinator.start()

        // Pipeline ran: link established, peer created with ICE servers, offer sent with our SDP + key.
        assertEquals(CallState.Connecting, r.state)
        assertTrue(r.link.opened)
        assertEquals("cs-1", r.link.lastCallSid)
        assertTrue(r.peer.created)
        assertEquals(1, r.sig.sentOfType("offer").size)
        val offer = JSONObject(r.sig.sentOfType("offer").first())
        assertEquals("OFFER_SDP", offer.getJSONObject("data").getString("sdp"))
        assertEquals("api-key", offer.getString("authToken"))
        assertEquals("cs-1", offer.getString("callSid"))

        r.sig.deliver(answer()); runCurrent()
        assertEquals("REMOTE", r.peer.remoteAnswer)

        r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()
        assertEquals(CallState.Connected, r.state)

        r.scope.cancel()
    }

    @Test
    fun outboundIce_bufferedUntilSignalSessionId_thenFlushed() = runTest {
        val r = rig()
        r.coordinator.start()

        // ICE gathered before the answer has no signal session id yet → buffered, not sent.
        r.peer.emitLocalIce("cand-1"); runCurrent()
        assertEquals(0, r.sig.sentOfType("ice-candidate").size)

        r.sig.deliver(answer(sessionId = "sig-1")); runCurrent()
        val sentIce = r.sig.sentOfType("ice-candidate")
        assertEquals(1, sentIce.size)
        val ice = JSONObject(sentIce.first())
        assertEquals("sig-1", ice.getString("sessionId"))
        assertEquals("cand-1", ice.getJSONObject("data").getString("candidate"))

        // After the id is known, further candidates send immediately.
        r.peer.emitLocalIce("cand-2"); runCurrent()
        assertEquals(2, r.sig.sentOfType("ice-candidate").size)

        r.scope.cancel()
    }

    @Test
    fun inboundIce_bufferedUntilRemoteAnswer_thenApplied() = runTest {
        val r = rig()
        r.coordinator.start()

        r.sig.deliver(iceFrame("rc-1")); runCurrent()
        assertEquals(0, r.peer.addedRemoteIce.size) // no remote answer yet

        r.sig.deliver(answer()); runCurrent()
        assertEquals(1, r.peer.addedRemoteIce.size)
        assertEquals("rc-1", r.peer.addedRemoteIce.first().first)

        // Post-answer candidates apply immediately.
        r.sig.deliver(iceFrame("rc-2")); runCurrent()
        assertEquals(2, r.peer.addedRemoteIce.size)

        r.scope.cancel()
    }

    @Test
    fun connectionTimeout_failsWithTimedOut() = runTest {
        val r = rig(connectionTimeoutMs = 1_000)
        r.coordinator.start()
        assertEquals(CallState.Connecting, r.state)

        advanceTimeBy(1_001); runCurrent()
        assertEquals(CallState.Failed(PolyError.Voice.TimedOut), r.state)

        r.scope.cancel()
    }

    @Test
    fun disconnect_failsAfterGraceWindow() = runTest {
        val r = rig(disconnectGraceMs = 5_000)
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()
        assertEquals(CallState.Connected, r.state)

        r.peer.emitState(PeerConnectionState.DISCONNECTED); runCurrent()
        assertEquals(CallState.Connected, r.state) // still within grace

        advanceTimeBy(5_001); runCurrent()
        assertEquals(CallState.Failed(PolyError.Voice.Disconnected), r.state)

        r.scope.cancel()
    }

    @Test
    fun disconnect_recoversWithinGrace_staysConnected() = runTest {
        val r = rig(disconnectGraceMs = 5_000)
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.peer.emitState(PeerConnectionState.DISCONNECTED); runCurrent()
        advanceTimeBy(2_000)
        r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()
        advanceTimeBy(10_000); runCurrent()

        assertEquals(CallState.Connected, r.state) // grace cancelled by the reconnect

        r.scope.cancel()
    }

    @Test
    fun signalingError_failsWithMessage() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(errorFrame("boom")); runCurrent()
        assertEquals(CallState.Failed(PolyError.Voice.SignalingFailed("boom")), r.state)
        r.scope.cancel()
    }

    @Test
    fun signalingDrop_reconnects_andKeepsCall() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()
        assertEquals(CallState.Connected, r.state)

        val connectsBefore = r.sig.connectCount
        r.sig.drop(); runCurrent()
        advanceTimeBy(1_001); runCurrent() // first backoff (1s) → reconnect succeeds
        assertEquals(connectsBefore + 1, r.sig.connectCount) // reconnected once
        assertEquals(CallState.Connected, r.state)           // transient drop survived
        r.scope.cancel()
    }

    @Test
    fun signalingDrop_afterConnected_reconnectExhausted_failsDisconnected() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.sig.failReconnect = true
        r.sig.drop(); runCurrent()
        advanceTimeBy(1_000 + 2_000 + 4_000 + 100); runCurrent() // all 3 attempts fail (1s/2s/4s)
        assertEquals(CallState.Failed(PolyError.Voice.Disconnected), r.state)
        r.scope.cancel()
    }

    @Test
    fun signalingDrop_beforeConnected_reconnectExhausted_failsSignaling() = runTest {
        val r = rig()
        r.coordinator.start() // Connecting
        r.sig.failReconnect = true
        r.sig.drop(); runCurrent()
        advanceTimeBy(7_100); runCurrent()
        assertEquals(CallState.Failed(PolyError.Voice.SignalingFailed("signaling connection lost")), r.state)
        r.scope.cancel()
    }

    @Test
    fun failure_sendsGracefulCloseFrame() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); runCurrent() // signalSessionId now known
        r.peer.emitState(PeerConnectionState.FAILED); runCurrent() // failCall → cleanup
        assertTrue(r.state is CallState.Failed)
        assertEquals(1, r.sig.sentOfType("close").size) // close frame sent on failure, not just endCall
        r.scope.cancel()
    }

    @Test
    fun peerFailed_failsWithMediaError() = runTest {
        val r = rig()
        r.coordinator.start()
        r.peer.emitState(PeerConnectionState.FAILED); runCurrent()
        assertTrue(r.state is CallState.Failed && (r.state as CallState.Failed).error is PolyError.Voice.MediaFailed)
        r.scope.cancel()
    }

    @Test
    fun backendClose_endsCleanlyAndTearsDown() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.sig.deliver(closeFrame); runCurrent()
        assertEquals(CallState.Ended, r.state)
        assertEquals(1, r.sig.sentOfType("close").size) // graceful close sent before teardown
        assertEquals(1, r.peer.closeCount)
        assertTrue(r.link.closed)
        r.scope.cancel()
    }

    @Test
    fun authFailure_throwsAndFails() = runTest {
        val r = rig(rest = FakeRestApi(failAt = "token"))
        val error = assertFailsWith<PolyError> { r.coordinator.start() }
        assertEquals(PolyError.Auth.Unauthorized, error)
        assertEquals(CallState.Failed(PolyError.Auth.Unauthorized), r.state)
        assertEquals(0, r.sig.sentOfType("offer").size) // never got to the offer
        r.scope.cancel()
    }

    @Test
    fun sessionLinkFailure_fails() = runTest {
        val r = rig(link = FakeSessionLink(failOpen = true))
        assertFailsWith<PolyError> { r.coordinator.start() }
        assertTrue(r.state is CallState.Failed)
        r.scope.cancel()
    }

    @Test
    fun doubleStart_isIgnored() = runTest {
        val r = rig()
        r.coordinator.start()
        assertEquals(1, r.sig.sentOfType("offer").size)
        r.coordinator.start() // already active
        assertEquals(1, r.sig.sentOfType("offer").size)
        r.scope.cancel()
    }

    @Test
    fun setMuted_togglesMicTrack() = runTest {
        val r = rig()
        r.coordinator.start()

        r.coordinator.setMuted(true)
        assertEquals(false, r.peer.micTrackEnabled)
        assertTrue(r.coordinator.isMuted())

        r.coordinator.setMuted(false)
        assertEquals(true, r.peer.micTrackEnabled)
        assertTrue(!r.coordinator.isMuted())
        r.scope.cancel()
    }

    @Test
    fun selectAudioDevice_forwardsToAudioControl_andSnapshotFlows() = runTest {
        val r = rig()
        r.coordinator.start()

        val speaker = AudioDevice(AudioDevice.Type.SPEAKER_PHONE, "Speaker", nativeId = 1)
        val earpiece = AudioDevice(AudioDevice.Type.EARPIECE, "Earpiece", nativeId = 2)
        r.audio.emitAudio(AudioState(listOf(speaker, earpiece), speaker))

        // The coordinator re-exposes the audio control's snapshot.
        assertEquals(listOf(speaker, earpiece), r.coordinator.audio.value.availableDevices)
        assertEquals(speaker, r.coordinator.audio.value.selectedDevice)

        // Selecting forwards to the control and the confirmed route flows back.
        r.coordinator.selectAudioDevice(earpiece)
        assertEquals(listOf<AudioDevice?>(earpiece), r.audio.selectedRequests)
        assertEquals(earpiece, r.coordinator.audio.value.selectedDevice)

        // null reverts to automatic routing.
        r.coordinator.selectAudioDevice(null)
        assertEquals(listOf<AudioDevice?>(earpiece, null), r.audio.selectedRequests)
        r.scope.cancel()
    }

    @Test
    fun audioFocus_permanentLoss_endsCallAsInterrupted() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()
        assertEquals(CallState.Connected, r.state)

        r.audio.emitInterruption(AudioInterruption.PERMANENT_LOSS); runCurrent()
        assertEquals(CallState.Failed(PolyError.Voice.Interrupted), r.state)
        assertEquals(1, r.peer.closeCount) // call torn down
        assertTrue(r.audio.deactivated)    // audio focus released
        r.scope.cancel()
    }

    @Test
    fun audioFocus_transientLoss_mutesMic_thenRestoresOnGain() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.audio.emitInterruption(AudioInterruption.TRANSIENT_LOSS); runCurrent()
        assertEquals(false, r.peer.micTrackEnabled) // mic muted for the interruption
        assertEquals(CallState.Connected, r.state)  // call survives a transient interruption
        assertTrue(!r.coordinator.isMuted())        // not a user mute

        r.audio.emitInterruption(AudioInterruption.GAINED); runCurrent()
        assertEquals(true, r.peer.micTrackEnabled)  // mic restored on regain
        r.scope.cancel()
    }

    @Test
    fun audioFocus_gainAfterTransient_doesNotOverrideUserMute() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.audio.emitInterruption(AudioInterruption.TRANSIENT_LOSS); runCurrent()
        r.coordinator.setMuted(true) // user mutes during the interruption
        r.audio.emitInterruption(AudioInterruption.GAINED); runCurrent()

        assertEquals(false, r.peer.micTrackEnabled) // stays muted — focus regain must not unmute the user
        assertTrue(r.coordinator.isMuted())
        r.scope.cancel()
    }

    @Test
    fun audioFocus_unmuteDuringTransientLoss_doesNotReopenMic() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.coordinator.setMuted(true)  // user mutes
        r.audio.emitInterruption(AudioInterruption.TRANSIENT_LOSS); runCurrent()
        r.coordinator.setMuted(false) // user un-mutes WHILE focus is lost
        assertEquals(false, r.peer.micTrackEnabled) // mic must stay off — we don't hold focus

        r.audio.emitInterruption(AudioInterruption.GAINED); runCurrent()
        assertEquals(true, r.peer.micTrackEnabled)  // only now (focus back, user unmuted) does it open
        r.scope.cancel()
    }

    @Test
    fun endCall_setsEnded_andReleasesEverything() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.coordinator.endCall()
        assertEquals(CallState.Ended, r.state)
        assertEquals(1, r.peer.closeCount)
        assertTrue(r.sig.closeCount >= 1)
        assertTrue(r.link.closed)
        r.scope.cancel()
    }

    @Test
    fun endCall_afterFailure_preservesFailure() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(errorFrame("boom")); runCurrent()
        assertTrue(r.state is CallState.Failed)

        r.coordinator.endCall()
        assertTrue(r.state is CallState.Failed) // not overwritten with Ended
        r.scope.cancel()
    }

    @Test
    fun disconnect_recoveringViaConnecting_doesNotFailAfterGrace() = runTest {
        val r = rig(disconnectGraceMs = 5_000)
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        // Transient blip that recovers through CONNECTING (not all the way to CONNECTED) before grace ends.
        r.peer.emitState(PeerConnectionState.DISCONNECTED); runCurrent()
        r.peer.emitState(PeerConnectionState.CONNECTING); runCurrent()
        advanceTimeBy(6_000); runCurrent()

        assertEquals(CallState.Connected, r.state) // grace re-check saw CONNECTING, didn't tear down
        r.scope.cancel()
    }

    @Test
    fun start_activatesAudio_andTeardownDeactivates() = runTest {
        val r = rig()
        r.coordinator.start()
        assertTrue(r.audio.activated)

        r.coordinator.endCall()
        assertTrue(r.audio.deactivated)
        r.scope.cancel()
    }

    @Test
    fun dispose_endsCallAndCancelsScope() = runTest {
        val r = rig()
        r.coordinator.start()
        r.sig.deliver(answer()); r.peer.emitState(PeerConnectionState.CONNECTED); runCurrent()

        r.coordinator.dispose(); runCurrent()
        assertEquals(CallState.Ended, r.state)
        assertTrue(r.peer.closeCount >= 1)
        assertTrue(!r.scope.isActive) // per-call scope torn down
    }
}
