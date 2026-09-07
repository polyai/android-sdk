// Copyright PolyAI Limited

package ai.poly.voice.internal.services

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.voice.CallState
import ai.poly.voice.AudioDevice
import ai.poly.voice.AudioState
import ai.poly.voice.internal.IceServer
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.e
import ai.poly.voice.internal.log.i
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.AudioControl
import ai.poly.voice.internal.ports.AudioInterruption
import ai.poly.voice.internal.ports.BridgeApi
import ai.poly.voice.internal.ports.PeerConnectionState
import ai.poly.voice.internal.ports.PeerEvent
import ai.poly.voice.internal.ports.EventsTransport
import ai.poly.voice.internal.ports.VoiceRestApi
import ai.poly.voice.internal.ports.VoiceSessionLink
import ai.poly.voice.internal.ports.WebRtcPeer
import ai.poly.voice.internal.protocol.BridgeProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The call state machine for `webrtc-bridge` (RUN-1279 / MES-1658) — the one and only call pipeline
 * since the `webrtc-gateway` path was removed.
 *
 * For anyone reading this alongside the old gateway code, the difference isn't the transport alone;
 * the shape of the handshake changed:
 *
 * | | gateway | bridge |
 * |---|---|---|
 * | credential | `authToken` inside the offer | `Authorization: Bearer` on provision |
 * | signalling | one WebSocket, trickle ICE | HTTPS for SDP + a control socket |
 * | call id | client mints it, links, then calls | bridge mints it, so provision comes first |
 * | agent audio | arrives on the single answer | a second negotiation (pull → answer → renegotiate) |
 *
 * Pipeline: auth → session → **provision** → link → offer (gathered, over HTTPS) → media connects →
 * pull the agent track → events socket.
 *
 * Every collaborator is a port, so the whole machine runs on the JVM with fakes,
 * and all state is confined to [scope]'s single-threaded dispatcher — the plain `var`s need no locks.
 */
internal class BridgeCallCoordinator(
    /** The connector's WebRTC token — the Bearer credential the bridge provisions against. */
    private val bridgeToken: String,
    private val restApi: VoiceRestApi,
    private val bridge: BridgeApi,
    private val sessionLink: VoiceSessionLink,
    private val events: EventsTransport,
    private val webrtc: WebRtcPeer,
    private val scope: CoroutineScope,
    private val logger: PolyLogger,
    private val audioControl: AudioControl = AudioControl.NONE,
    private val connectionTimeoutMs: Long = 30_000,
    private val disconnectGraceMs: Long = 5_000,
    private val iceQuietMs: Long = 200,
    private val iceCapMs: Long = 2_000,
) {

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private var active = false
    private var callAttempt = 0

    private var connectionTimeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private val collectors = mutableListOf<Job>()
    /** Serialises agent-track pulls: two renegotiations must never interleave on one peer. */
    private var pullJob: Job? = null

    private var provision: BridgeProtocol.Provision? = null
    private var muted = false
    private var interruptionMuted = false
    private var lastPeerState: PeerConnectionState? = null
    private var mediaConnected: CompletableDeferred<Unit>? = null

    suspend fun start(): Unit = withContext(scope.coroutineContext) {
        if (active) {
            logger.d("[voice] start() ignored — a call is already active")
            return@withContext
        }
        val attempt = ++callAttempt
        active = true
        muted = false
        lastPeerState = null
        mediaConnected = CompletableDeferred()
        _state.value = CallState.Connecting
        audioControl.activate()

        val ready = CompletableDeferred<Unit>()
        val pipeline = scope.launch { runPipeline(attempt, ready) }
        try {
            ready.await()
        } catch (c: CancellationException) {
            pipeline.cancel()
            if (attempt == callAttempt) cleanup()
            throw c
        }
    }

    fun failPreflight(error: PolyError) {
        _state.value = CallState.Failed(error)
    }

    private suspend fun runPipeline(attempt: Int, ready: CompletableDeferred<Unit>) {
        fun stale() = !active || attempt != callAttempt

        collectors += scope.launch { events.incoming.collect { onEventFrame(it) } }
        collectors += scope.launch { events.closed.collect { onEventsClosed() } }
        collectors += scope.launch { webrtc.events.collect { onPeerEvent(it) } }
        collectors += scope.launch { audioControl.interruptions.collect { onInterruption(it) } }

        connectionTimeoutJob = scope.launch {
            delay(connectionTimeoutMs)
            if (active && attempt == callAttempt && _state.value !is CallState.Connected) {
                logger.e("[voice] connection timeout")
                failCall(PolyError.Voice.TimedOut)
            }
        }

        try {
            logger.d("[voice] obtaining access token")
            val token = restApi.obtainAccessToken()
            if (stale()) { ready.complete(Unit); return }

            logger.d("[voice] creating session")
            val sessionId = restApi.createSession(token)
            if (stale()) { ready.complete(Unit); return }

            // Provision BEFORE the link: the bridge mints `call-<8 hex>` and accepts no client
            // identifier, so the id the messaging session must carry doesn't exist until this returns.
            logger.d("[voice] provisioning the bridge call")
            val call = bridge.provision()
            provision = call
            if (stale()) { ready.complete(Unit); return }

            logger.d("[voice] linking voice session to the bridge call")
            sessionLink.open(token, sessionId, call.callId)
            if (stale()) { sessionLink.close(); ready.complete(Unit); return }

            val iceServers = call.credentials.iceServers.ifEmpty { IceServer.DEFAULT }
            webrtc.create(iceServers)
            if (stale()) { ready.complete(Unit); return }

            webrtc.createOfferSdp()
            // Non-trickle: the candidates must be in the SDP before it is POSTed.
            webrtc.awaitIceGathering(iceQuietMs, iceCapMs)
            if (stale()) { ready.complete(Unit); return }

            val offer = webrtc.localDescriptionSdp()
                ?: throw PolyError.Voice.MediaFailed("no gathered offer to send to the bridge")
            // "0" matches the browser client's fallback; the bridge prefers the mid it reads out of
            // the offer anyway.
            val mid = webrtc.audioMid() ?: "0"

            val answer = bridge.sendOffer(call, offer, mid)
            if (stale()) { ready.complete(Unit); return }
            webrtc.setRemoteAnswer(answer)
            logger.i("[voice] bridge answer applied — waiting for media")

            // `start()` returns here, with the call `Connecting`, exactly as it did on the gateway:
            // the caller watches `state` for `Connected`. What follows needs a connected peer (the
            // SFU rejects the agent-track pull before that), so it runs on for the caller.
            ready.complete(Unit)

            awaitMediaConnected()
            if (stale()) return

            pullAgentTrack(call)
            if (stale()) return

            openEventsSocket(call)
            logger.i("[voice] bridge call negotiated")
        } catch (c: CancellationException) {
            ready.completeExceptionally(c)
            throw c
        } catch (t: Throwable) {
            if (!stale()) failCall(t.toPolyError())
            ready.completeExceptionally(t)
        }
    }

    /**
     * Subscribe to the agent track: the pull returns an offer we answer and hand back. Runs on
     * connect and on every server-requested re-pull.
     */
    private suspend fun pullAgentTrack(call: BridgeProtocol.Provision) {
        val offer = bridge.pullAgentTrack(call)
        val answer = webrtc.acceptRemoteOffer(offer)
        webrtc.awaitIceGathering(iceQuietMs, iceCapMs)
        // Prefer the post-gathering description; the engine's answer is still valid if it exposes none.
        val gathered = webrtc.localDescriptionSdp() ?: answer
        bridge.renegotiate(call, gathered)
        logger.d("[voice] agent track pulled", mapOf("callId" to call.callId))
    }

    /**
     * Queue a re-pull behind any in-flight one. Overlapping renegotiations on a single peer
     * connection are a broken call, so they're chained rather than run concurrently.
     */
    private fun queuePull() {
        val call = provision ?: return
        val previous = pullJob
        pullJob = scope.launch {
            previous?.join()
            if (!active) return@launch
            runCatching { pullAgentTrack(call) }
                .onFailure { logger.w("[voice] agent-track re-pull failed", mapOf("error" to (it.message ?: ""))) }
        }
    }

    private suspend fun openEventsSocket(call: BridgeProtocol.Provision) {
        val url = bridge.eventsUrl(call)
        if (url == null) {
            logger.w("[voice] bridge offered no events socket — barge-in and re-pull are unavailable")
            return
        }
        events.connect(url)
        // A WebSocket upgrade can't carry X-Call-Token, so the same token goes down the socket as
        // its first frame. The bridge closes the socket on anything else.
        call.credentials.token?.let { events.send(BridgeProtocol.eventsAuthFrame(it)) }
    }

    private fun onEventFrame(text: String) {
        if (!active) return
        when (BridgeProtocol.parseEvent(text)) {
            BridgeProtocol.Event.REPULL -> queuePull()
            BridgeProtocol.Event.BARGE_IN -> webrtc.setRemoteAudioEnabled(false)
            BridgeProtocol.Event.UNMUTE -> webrtc.setRemoteAudioEnabled(true)
            null -> Unit
        }
    }

    /**
     * The control socket is the only channel that can drive the call once media is up — losing it
     * means barge-in and re-pull stop working — so end rather than run blind. There is no reconnect
     * ladder here (unlike the gateway): the bridge reaps a call whose events socket drops.
     */
    private fun onEventsClosed() {
        if (!active) return
        logger.w("[voice] bridge events socket closed")
        if (_state.value is CallState.Connected) endCall() else failCall(
            PolyError.Voice.SignalingFailed("bridge events socket closed before the call connected"),
        )
    }

    // ── peer / audio ──────────────────────────────────────────────

    private fun onPeerEvent(event: PeerEvent) {
        if (!active) return
        when (event) {
            is PeerEvent.ConnectionState -> onPeerConnectionState(event.state)
            PeerEvent.Track -> Unit
        }
    }

    private fun onPeerConnectionState(state: PeerConnectionState) {
        lastPeerState = state
        when (state) {
            PeerConnectionState.CONNECTED -> {
                connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                disconnectGraceJob?.cancel(); disconnectGraceJob = null
                _state.value = CallState.Connected
                mediaConnected?.complete(Unit)
            }
            PeerConnectionState.FAILED -> failCall(PolyError.Voice.MediaFailed("peer connection failed"))
            PeerConnectionState.DISCONNECTED -> {
                disconnectGraceJob?.cancel()
                disconnectGraceJob = scope.launch {
                    delay(disconnectGraceMs)
                    if (active && (lastPeerState == PeerConnectionState.DISCONNECTED || lastPeerState == PeerConnectionState.FAILED)) {
                        failCall(PolyError.Voice.Disconnected)
                    }
                }
            }
            PeerConnectionState.CONNECTING, PeerConnectionState.CLOSED -> Unit
        }
    }

    /** Suspend until media reports connected; a failed/ended call releases the wait too. */
    private suspend fun awaitMediaConnected() {
        val gate = mediaConnected ?: return
        gate.await()
    }

    private fun onInterruption(interruption: AudioInterruption) {
        if (!active) return
        when (interruption) {
            AudioInterruption.PERMANENT_LOSS -> {
                logger.i("[voice] audio focus lost permanently — ending the call (interrupted)")
                failCall(PolyError.Voice.Interrupted)
            }
            AudioInterruption.TRANSIENT_LOSS -> {
                interruptionMuted = true
                applyMicState()
            }
            AudioInterruption.GAINED -> {
                interruptionMuted = false
                applyMicState()
            }
        }
    }

    // ── public control ────────────────────────────────────────────

    fun endCall() {
        val wasFailed = _state.value is CallState.Failed
        cleanup()
        if (!wasFailed) _state.value = CallState.Ended
    }

    fun dispose() {
        scope.launch { endCall() }.invokeOnCompletion { scope.cancel() }
    }

    fun setMuted(value: Boolean) {
        muted = value
        applyMicState()
    }

    fun isMuted(): Boolean = muted

    val audio: StateFlow<AudioState> get() = audioControl.audio

    fun selectAudioDevice(device: AudioDevice?) = audioControl.selectAudioDevice(device)

    private fun applyMicState() {
        if (active) webrtc.setMicEnabled(!muted && !interruptionMuted)
    }

    private fun failCall(error: PolyError) {
        if (!active && _state.value is CallState.Failed) return
        logger.e("[voice] call failed", mapOf("error" to error.debugDescription))
        cleanup()
        _state.value = CallState.Failed(error)
    }

    private fun cleanup() {
        callAttempt += 1
        active = false
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        disconnectGraceJob?.cancel(); disconnectGraceJob = null
        pullJob?.cancel(); pullJob = null
        collectors.forEach { it.cancel() }
        collectors.clear()
        // Release anything waiting on media so a teardown mid-handshake can't strand the pipeline.
        // Completed, not cancelled: the waiter is a plain `await()` in the pipeline, and a
        // cancellation there would propagate out as a JobCancellationException rather than the
        // staleness check the pipeline already handles.
        mediaConnected?.complete(Unit)
        mediaConnected = null
        runCatching { webrtc.close() }
        runCatching { events.close() }
        runCatching { sessionLink.close() }
        // DELETE last: the socket close is what the bridge reaps on, and this replaces the gateway's
        // graceful close frame. Detached because cleanup() is synchronous.
        provision?.let { call ->
            scope.launch { runCatching { bridge.deleteCall(call) } }
        }
        provision = null
        runCatching { audioControl.deactivate() }
        lastPeerState = null
        muted = false
        interruptionMuted = false
    }

    private fun Throwable.toPolyError(): PolyError = when (this) {
        is PolyError -> this
        else -> PolyError.Voice.SignalingFailed(message ?: "voice call setup failed")
    }
}
