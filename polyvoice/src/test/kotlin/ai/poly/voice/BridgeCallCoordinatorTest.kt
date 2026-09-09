// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.PolyError
import ai.poly.messaging.voice.CallState
import ai.poly.voice.internal.IceServer
import ai.poly.voice.internal.ports.PeerConnectionState
import ai.poly.voice.internal.protocol.BridgeProtocol
import ai.poly.voice.internal.services.BridgeCallCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `webrtc-bridge` call state machine, driven over virtual time with port fakes. Covers the
 * reordered start pipeline (provision before link), the non-trickle offer, the agent-track
 * renegotiation, barge-in, re-pull serialisation, and teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeCallCoordinatorTest {

    private class Rig(
        val coordinator: BridgeCallCoordinator,
        val rest: FakeRestApi,
        val bridge: FakeBridgeApi,
        val link: FakeSessionLink,
        val events: FakeEventsTransport,
        val peer: FakeWebRtcPeer,
        val audio: FakeAudioControl,
        val scope: CoroutineScope,
    ) {
        val state get() = coordinator.state.value
    }

    private fun TestScope.rig(
        rest: FakeRestApi = FakeRestApi(),
        bridge: FakeBridgeApi = FakeBridgeApi(),
        link: FakeSessionLink = FakeSessionLink(),
        events: FakeEventsTransport = FakeEventsTransport(),
        peer: FakeWebRtcPeer = FakeWebRtcPeer(),
        audio: FakeAudioControl = FakeAudioControl(),
        connectionTimeoutMs: Long = 30_000,
    ): Rig {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val coordinator = BridgeCallCoordinator(
            bridgeToken = "webrtc-tok",
            restApi = rest,
            bridge = bridge,
            sessionLink = link,
            events = events,
            webrtc = peer,
            scope = scope,
            logger = NoopLogger,
            audioControl = audio,
            connectionTimeoutMs = connectionTimeoutMs,
            iceQuietMs = 10,
            iceCapMs = 50,
        )
        return Rig(coordinator, rest, bridge, link, events, peer, audio, scope)
    }

    /**
     * Runs the pipeline to a connected call. `start()` suspends until the agent track is pulled, and
     * the pull waits on media, so the peer must report CONNECTED from a separate coroutine.
     */
    private fun TestScope.connect(rig: Rig) {
        val start = rig.scope.launch { rig.coordinator.start() }
        runCurrent()
        rig.peer.emitState(PeerConnectionState.CONNECTED)
        runCurrent()
        assertTrue(start.isCompleted, "start() completes once the agent track is pulled")
    }

    // ── happy path ────────────────────────────────────────────────

    @Test
    fun `pipeline provisions links offers pulls and connects`() = runTest {
        val rig = rig()
        connect(rig)

        assertEquals(1, rig.bridge.provisionCount)
        assertEquals(1, rig.bridge.sentOffers.size)
        assertEquals(1, rig.bridge.pullCount, "the agent track is pulled on connect")
        assertEquals(1, rig.bridge.renegotiatedAnswers.size)
        assertEquals(CallState.Connected, rig.state)
        rig.scope.cancel()
    }

    /**
     * The migration's central ordering change: the bridge mints the call id, so the messaging
     * session is linked only after provision returns — and to *that* id, not a client UUID.
     */
    @Test
    fun `messaging session is linked to the bridge-minted call id`() = runTest {
        val rig = rig()
        connect(rig)

        assertTrue(rig.link.opened)
        assertEquals("call-5f9ec645", rig.link.lastCallSid)
        rig.scope.cancel()
    }

    @Test
    fun `provision failure fails the call without linking`() = runTest {
        val bridge = FakeBridgeApi()
        bridge.provisionError = PolyError.Voice.SignalingFailed("bridge provision rejected the call credentials (401)")
        val rig = rig(bridge = bridge)

        val start = rig.scope.launch { runCatching { rig.coordinator.start() } }
        runCurrent()

        assertTrue(start.isCompleted)
        assertTrue(rig.state is CallState.Failed)
        assertTrue(!rig.link.opened, "no messaging link without a call id")
        rig.scope.cancel()
    }

    /** Non-trickle: the SDP sent must be the description read back after the gather wait. */
    @Test
    fun `offer posted is the gathered description`() = runTest {
        val peer = FakeWebRtcPeer()
        peer.gatheredSdp = "GATHERED_WITH_CANDIDATES"
        peer.mid = "7"
        val rig = rig(peer = peer)
        connect(rig)

        assertEquals("GATHERED_WITH_CANDIDATES" to "7", rig.bridge.sentOffers.first())
        assertTrue(peer.gatherWaits >= 2, "offer and renegotiation each wait for gathering")
        rig.scope.cancel()
    }

    @Test
    fun `offer fails when the peer exposes no gathered description`() = runTest {
        val peer = FakeWebRtcPeer()
        peer.gatheredSdp = null
        val rig = rig(peer = peer)

        val start = rig.scope.launch { runCatching { rig.coordinator.start() } }
        runCurrent()

        assertTrue(rig.state is CallState.Failed)
        assertTrue(rig.bridge.sentOffers.isEmpty(), "nothing is POSTed without candidates")
        start.cancel()
        rig.scope.cancel()
    }

    /** Media terminates at Cloudflare here, so its STUN is the fallback — not the gateway's. */
    @Test
    fun `ice servers fall back to cloudflare stun`() = runTest {
        val rig = rig()
        connect(rig)

        assertEquals(IceServer.DEFAULT, rig.peer.lastIceServers)
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), rig.peer.lastIceServers.first().urls)
        rig.scope.cancel()
    }

    @Test
    fun `ice servers prefer the bridge-supplied list`() = runTest {
        val base = FakeBridgeApi.defaultProvision()
        val bridge = FakeBridgeApi(
            provisionResult = base.copy(
                credentials = base.credentials.copy(
                    iceServers = listOf(IceServer(urls = listOf("turn:turn.dev.polyai.app:3478"), username = "u", credential = "c")),
                ),
            ),
        )
        val rig = rig(bridge = bridge)
        connect(rig)

        assertEquals(listOf("turn:turn.dev.polyai.app:3478"), rig.peer.lastIceServers.first().urls)
        rig.scope.cancel()
    }

    // ── events socket ─────────────────────────────────────────────

    @Test
    fun `events socket sends the auth frame first`() = runTest {
        val rig = rig()
        connect(rig)

        val first = rig.events.sent.firstOrNull()
        assertTrue(first != null, "an auth frame is sent on connect")
        assertEquals(BridgeProtocol.Event.REPULL, BridgeProtocol.parseEvent("""{"event":"repull"}"""))
        assertEquals("auth", org.json.JSONObject(first!!).optString("type"))
        assertEquals(
            FakeBridgeApi.defaultProvision().credentials.token,
            org.json.JSONObject(first).optString("token"),
        )
        rig.scope.cancel()
    }

    @Test
    fun `repull event pulls the agent track again`() = runTest {
        val rig = rig()
        connect(rig)
        assertEquals(1, rig.bridge.pullCount)

        rig.events.deliver("""{"event":"repull"}""")
        runCurrent()

        assertEquals(2, rig.bridge.pullCount)
        assertEquals(2, rig.bridge.renegotiatedAnswers.size)
        rig.scope.cancel()
    }

    /** Two renegotiations must never interleave on one peer connection. */
    @Test
    fun `overlapping repulls are serialised`() = runTest {
        val rig = rig()
        connect(rig)

        repeat(5) { rig.events.deliver("""{"event":"repull"}""") }
        runCurrent()

        assertEquals(6, rig.bridge.pullCount)
        assertEquals(
            rig.bridge.pullCount,
            rig.bridge.renegotiatedAnswers.size,
            "one renegotiation per pull — an interleaved pair would renegotiate twice off one pull",
        )
        rig.scope.cancel()
    }

    @Test
    fun `barge-in and unmute control the agent track`() = runTest {
        val rig = rig()
        connect(rig)

        rig.events.deliver("""{"event":"barge-in"}""")
        runCurrent()
        assertEquals(listOf(false), rig.peer.remoteAudioEnabledCalls)

        rig.events.deliver("""{"event":"unmute"}""")
        runCurrent()
        assertEquals(listOf(false, true), rig.peer.remoteAudioEnabledCalls)
        rig.scope.cancel()
    }

    @Test
    fun `unknown control frames are ignored`() = runTest {
        val rig = rig()
        connect(rig)

        rig.events.deliver("""{"event":"something-new"}""")
        rig.events.deliver("not json")
        runCurrent()

        assertEquals(1, rig.bridge.pullCount, "no spurious re-pull")
        assertTrue(rig.peer.remoteAudioEnabledCalls.isEmpty())
        assertEquals(CallState.Connected, rig.state)
        rig.scope.cancel()
    }

    /** Losing the control socket means barge-in and re-pull stop working — end, don't run blind. */
    @Test
    fun `losing the events socket after connect ends the call`() = runTest {
        val rig = rig()
        connect(rig)

        rig.events.drop()
        runCurrent()

        assertEquals(CallState.Ended, rig.state)
        rig.scope.cancel()
    }

    // ── teardown ──────────────────────────────────────────────────

    @Test
    fun `end deletes the call and releases everything`() = runTest {
        val rig = rig()
        connect(rig)

        rig.coordinator.endCall()
        runCurrent()

        assertEquals(1, rig.bridge.deleteCount, "DELETE replaces the gateway's close frame")
        assertEquals(1, rig.peer.closeCount)
        assertTrue(rig.events.closeCount >= 1)
        assertTrue(rig.link.closed)
        assertTrue(rig.audio.deactivated)
        assertEquals(CallState.Ended, rig.state)
        rig.scope.cancel()
    }

    @Test
    fun `connection timeout fails the call`() = runTest {
        val rig = rig(connectionTimeoutMs = 1_000)
        val start = rig.scope.launch { runCatching { rig.coordinator.start() } }
        runCurrent()
        // Media never connects.
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(CallState.Failed(PolyError.Voice.TimedOut), rig.state)
        start.cancel()
        rig.scope.cancel()
    }

    @Test
    fun `mute applies to the microphone`() = runTest {
        val rig = rig()
        connect(rig)

        rig.coordinator.setMuted(true)
        assertTrue(!rig.peer.micTrackEnabled)
        assertTrue(rig.coordinator.isMuted())

        rig.coordinator.setMuted(false)
        assertTrue(rig.peer.micTrackEnabled)
        rig.scope.cancel()
    }

    @Test
    fun `peer failure fails the call`() = runTest {
        val rig = rig()
        connect(rig)

        rig.peer.emitState(PeerConnectionState.FAILED)
        runCurrent()

        assertTrue(rig.state is CallState.Failed)
        assertNull(rig.bridge.provisionResult.credentials.iceServers.firstOrNull())
        rig.scope.cancel()
    }
}
