// Copyright PolyAI Limited

package ai.poly.voice.internal.services

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.voice.CallState
import ai.poly.voice.AudioDevice
import ai.poly.voice.AudioState
import ai.poly.voice.internal.ports.AudioControl
import ai.poly.voice.internal.ports.AudioInterruption
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.e
import ai.poly.voice.internal.log.i
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.PeerConnectionState
import ai.poly.voice.internal.ports.PeerEvent
import ai.poly.voice.internal.ports.SignalingTransport
import ai.poly.voice.internal.ports.VoiceRestApi
import ai.poly.voice.internal.ports.VoiceSessionLink
import ai.poly.voice.internal.ports.WebRtcPeer
import ai.poly.voice.internal.protocol.SignalMessage
import ai.poly.voice.internal.protocol.SignalingProtocol
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
 * The call state machine. Owns the start
 * pipeline (auth → session → link → signaling → peer → offer), the answer/ICE/error/close handling,
 * the connection-timeout and disconnect-grace timers, ICE buffering, mute, and idempotent teardown.
 *
 * Every collaborator is a port, so this whole machine runs on the JVM with fakes. All state is
 * confined to `scope`'s (single-threaded) dispatcher — collectors and the pipeline never run in
 * parallel, so the plain `var`s below need no locks.
 *
 * @param connectionTimeoutMs how long to wait for the peer to reach `connected` before failing.
 * @param disconnectGraceMs how long a `disconnected` peer has to recover before the call fails.
 */
internal class CallCoordinator(
    /** The token the gateway authenticates — the offer `authToken` and the ICE-servers fetch. */
    private val gatewayToken: String,
    private val restApi: VoiceRestApi,
    private val sessionLink: VoiceSessionLink,
    private val signaling: SignalingTransport,
    private val webrtc: WebRtcPeer,
    private val signalingUrl: String,
    private val scope: CoroutineScope,
    private val logger: PolyLogger,
    private val audioControl: AudioControl = AudioControl.NONE,
    private val newCallSid: () -> String = { java.util.UUID.randomUUID().toString() },
    private val connectionTimeoutMs: Long = 30_000,
    private val disconnectGraceMs: Long = 5_000,
    private val maxSignalingReconnects: Int = 3,
    private val signalingReconnectBaseMs: Long = 1_000,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    // ── lifecycle / staleness ──────────────────────────────────────
    private var active = false
    private var callAttempt = 0

    // ── timers & collectors (cancelled on teardown) ────────────────
    private var connectionTimeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private val collectors = mutableListOf<Job>()

    // ── signaling/ICE state ────────────────────────────────────────
    private var signalSessionId: String? = null
    private val pendingOutboundIce = mutableListOf<PeerEvent.LocalIce>()
    private var remoteAnswerApplied = false
    private val pendingInboundIce = mutableListOf<SignalMessage.IceCandidate>()
    private var muted = false
    private var interruptionMuted = false // mic muted by a transient audio-focus loss (not the user)
    private var signalingReconnecting = false
    private var lastPeerState: PeerConnectionState? = null

    /**
     * Run the start pipeline. Suspends until the offer has been sent (the call is now "connecting");
     * the transition to `CallState.Connected` arrives later via `state`. Throws if the synchronous
     * setup fails (auth, session, signaling connect) — the failure is also reflected in `state`.
     * A no-op if a call is already in progress.
     */
    suspend fun start(): Unit = withContext(scope.coroutineContext) {
        if (active) {
            logger.d("[voice] start() ignored — a call is already active")
            return@withContext
        }
        val attempt = ++callAttempt
        active = true
        muted = false
        lastPeerState = null
        _state.value = CallState.Connecting
        audioControl.activate() // in-communication mode + audio focus for the call's duration

        val ready = CompletableDeferred<Unit>()
        val pipeline = scope.launch { runPipeline(attempt, ready) }
        try {
            ready.await()
        } catch (c: CancellationException) {
            // The caller cancelled start() (e.g. Cancellable.cancel()) while setup was in flight.
            // runPipeline is a sibling of this coroutine, so it wouldn't stop on its own — cancel it
            // and tear down the half-started call so nothing is stranded.
            pipeline.cancel()
            if (attempt == callAttempt) cleanup()
            throw c
        }
    }

    /** Reflect a pre-flight failure (e.g. mic permission denied) in `state` without starting. */
    fun failPreflight(error: PolyError) {
        _state.value = CallState.Failed(error)
    }

    private suspend fun runPipeline(attempt: Int, ready: CompletableDeferred<Unit>) {
        fun stale() = !active || attempt != callAttempt

        // Wire up inbound handling first so an answer/ICE can't race ahead of our collectors.
        collectors += scope.launch {
            signaling.incoming.collect { frame -> onSignal(SignalingProtocol.decode(frame, logger)) }
        }
        collectors += scope.launch {
            signaling.closed.collect { onSignalingClosed() }
        }
        collectors += scope.launch {
            webrtc.events.collect { onPeerEvent(it) }
        }
        collectors += scope.launch {
            audioControl.interruptions.collect { onInterruption(it) }
        }

        // Fail the call if it never reaches `connected` in time.
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

            val callSid = newCallSid()

            logger.d("[voice] linking voice session to webrtc call")
            sessionLink.open(token, sessionId, callSid)
            if (stale()) { sessionLink.close(); ready.complete(Unit); return }

            // ICE servers are best-effort — the adapter falls back to STUN on failure. The gateway
            // authenticates this with the gateway token (NOT the messaging access token).
            val iceServers = restApi.fetchIceServers(gatewayToken)
            if (stale()) { ready.complete(Unit); return }

            logger.d("[voice] opening signaling socket")
            signaling.connect(signalingUrl)
            if (stale()) { ready.complete(Unit); return }

            logger.d("[voice] creating peer connection")
            webrtc.create(iceServers)
            if (stale()) { ready.complete(Unit); return }

            val sdp = webrtc.createOfferSdp()
            if (stale()) { ready.complete(Unit); return }

            val offer = SignalingProtocol.encodeOffer(
                sessionId = signalSessionId, // null on the first offer
                sdp = sdp,
                authToken = gatewayToken,
                callSid = callSid,
            )
            if (!signaling.send(offer)) {
                throw PolyError.Voice.SignalingFailed("signaling socket closed before the offer was sent")
            }
            logger.i("[voice] offer sent — waiting for answer")
            ready.complete(Unit)
        } catch (c: CancellationException) {
            ready.completeExceptionally(c)
            throw c
        } catch (t: Throwable) {
            if (!stale()) failCall(t.toPolyError())
            ready.completeExceptionally(t)
        }
    }

    /**
     * The signaling socket dropped unexpectedly. Reconnect with exponential backoff (1s/2s/4s, up to
     * [maxSignalingReconnects]) and re-flush any ICE buffered during the gap — the gateway routes each
     * frame by `sessionId`, so a fresh socket continues the same session. Only when all attempts are
     * exhausted do we fail.
     */
    private fun onSignalingClosed() {
        if (!active || signalingReconnecting) return // a drop mid-reconnect is handled by the loop
        signalingReconnecting = true
        scope.launch { reconnectSignaling() }
    }

    private suspend fun reconnectSignaling() {
        try {
            for (attempt in 1..maxSignalingReconnects) {
                delay(signalingReconnectBaseMs shl (attempt - 1)) // 1s, 2s, 4s
                if (!active) return
                logger.i("[voice] reconnecting signaling", mapOf("attempt" to attempt))
                val reconnected = runCatching { signaling.connect(signalingUrl) }.isSuccess
                if (reconnected && active) {
                    logger.i("[voice] signaling reconnected")
                    flushOutboundIce() // re-send candidates that failed to send during the gap
                    return
                }
            }
            if (active) {
                logger.e("[voice] signaling reconnect exhausted")
                // Once connected, the media path is what was lost; before connect, the handshake never
                // completed — surface the more precise error in each case.
                failCall(
                    if (_state.value is CallState.Connected) PolyError.Voice.Disconnected
                    else PolyError.Voice.SignalingFailed("signaling connection lost"),
                )
            }
        } finally {
            signalingReconnecting = false
        }
    }

    /**
     * React to an audio-focus interruption. A permanent loss (incoming phone call the user answered,
     * or another app taking exclusive audio) ends the call — we can't keep a meaningful call without
     * audio focus. A transient loss (notification / nav prompt) mutes the mic until focus is regained,
     * so a brief interruption doesn't bleed the user's audio out or kill the call.
     */
    private fun onInterruption(interruption: AudioInterruption) {
        if (!active) return
        when (interruption) {
            AudioInterruption.PERMANENT_LOSS -> {
                logger.i("[voice] audio focus lost permanently — ending the call (interrupted)")
                failCall(PolyError.Voice.Interrupted)
            }
            AudioInterruption.TRANSIENT_LOSS -> {
                interruptionMuted = true
                applyMicState() // mic off for the interruption (does not touch the user's mute intent)
            }
            AudioInterruption.GAINED -> {
                interruptionMuted = false
                applyMicState() // restore — but stays off if the user muted meanwhile
            }
        }
    }

    // ── inbound signaling ──────────────────────────────────────────

    private suspend fun onSignal(msg: SignalMessage?) {
        if (!active || msg == null) return
        when (msg) {
            is SignalMessage.Answer -> onAnswer(msg)
            is SignalMessage.IceCandidate ->
                if (remoteAnswerApplied) {
                    webrtc.addRemoteIceCandidate(msg.candidate, msg.sdpMid, msg.sdpMLineIndex)
                } else {
                    pendingInboundIce += msg // buffer until the remote answer is applied
                }
            is SignalMessage.Error -> {
                logger.e("[voice] signaling error", mapOf("message" to msg.message))
                failCall(PolyError.Voice.SignalingFailed(msg.message))
            }
            SignalMessage.Pong -> Unit
            SignalMessage.Close -> {
                logger.i("[voice] backend signaled close — ending call")
                endCall()
            }
        }
    }

    private suspend fun onAnswer(answer: SignalMessage.Answer) {
        // The signal session id (from the answer) unblocks outbound ICE.
        answer.sessionId?.let {
            signalSessionId = it
            flushOutboundIce()
        }
        webrtc.setRemoteAnswer(answer.sdp)
        remoteAnswerApplied = true
        flushInboundIce()
    }

    private fun flushOutboundIce() {
        if (pendingOutboundIce.isEmpty()) return
        // Copy + clear first: sendIce re-buffers into pendingOutboundIce if the socket is down.
        val batch = pendingOutboundIce.toList()
        pendingOutboundIce.clear()
        batch.forEach { sendIce(it) }
    }

    private fun flushInboundIce() {
        if (pendingInboundIce.isEmpty()) return
        pendingInboundIce.forEach { webrtc.addRemoteIceCandidate(it.candidate, it.sdpMid, it.sdpMLineIndex) }
        pendingInboundIce.clear()
    }

    // ── peer events ────────────────────────────────────────────────

    private fun onPeerEvent(event: PeerEvent) {
        if (!active) return
        when (event) {
            is PeerEvent.LocalIce ->
                // Outbound ICE can't be sent until the gateway tells us the signal session id.
                if (signalSessionId != null) sendIce(event) else pendingOutboundIce += event
            is PeerEvent.ConnectionState -> onPeerConnectionState(event.state)
            PeerEvent.Track -> Unit // remote audio playout handled inside the adapter
        }
    }

    private fun onPeerConnectionState(state: PeerConnectionState) {
        lastPeerState = state
        when (state) {
            PeerConnectionState.CONNECTED -> {
                connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
                disconnectGraceJob?.cancel(); disconnectGraceJob = null
                _state.value = CallState.Connected
            }
            PeerConnectionState.FAILED -> failCall(PolyError.Voice.MediaFailed("peer connection failed"))
            PeerConnectionState.DISCONNECTED -> {
                // Give ICE a grace window to recover before failing (transient network blips).
                disconnectGraceJob?.cancel()
                disconnectGraceJob = scope.launch {
                    delay(disconnectGraceMs)
                    // Re-check at expiry: only fail if the peer is STILL disconnected/failed. A peer
                    // recovering via DISCONNECTED → CONNECTING → CONNECTED must not be torn down here.
                    if (active && (lastPeerState == PeerConnectionState.DISCONNECTED || lastPeerState == PeerConnectionState.FAILED)) {
                        failCall(PolyError.Voice.Disconnected)
                    }
                }
            }
            PeerConnectionState.CONNECTING, PeerConnectionState.CLOSED -> Unit
        }
    }

    private fun sendIce(ice: PeerEvent.LocalIce) {
        val sent = signaling.send(SignalingProtocol.encodeIceCandidate(signalSessionId, ice.candidate, ice.sdpMid, ice.sdpMLineIndex))
        if (!sent) pendingOutboundIce += ice // socket down (likely mid-reconnect) — re-flush after reconnect
    }

    // ── public control (called on [scope] from VoiceCall) ──────────

    /** End the call cleanly. Preserves a prior failure (doesn't overwrite `CallState.Failed` with Ended). */
    fun endCall() {
        val wasFailed = _state.value is CallState.Failed
        cleanup() // sends the graceful close frame
        if (!wasFailed) _state.value = CallState.Ended
    }

    /**
     * Terminal disposal: end the call gracefully (on the confined scope), then cancel the per-call
     * `scope` so its `SupervisorJob` doesn't outlive the call. After this the call can't be restarted.
     */
    fun dispose() {
        scope.launch { endCall() }.invokeOnCompletion { scope.cancel() }
    }

    fun setMuted(value: Boolean) {
        muted = value
        applyMicState()
    }

    fun isMuted(): Boolean = muted

    /**
     * The mic is live only when the user hasn't muted AND we aren't in a transient audio-focus loss —
     * so un-muting during an interruption can't re-open the mic while another app holds focus. Single
     * source of truth for the native mic-enabled flag.
     */
    private fun applyMicState() {
        if (active) webrtc.setMicEnabled(!muted && !interruptionMuted)
    }

    /** Live audio-routing snapshot (available outputs + the active one). */
    val audio: StateFlow<AudioState> get() = audioControl.audio

    /** Switch the live call's audio output; null reverts to automatic routing. */
    fun selectAudioDevice(device: AudioDevice?) = audioControl.selectAudioDevice(device)

    private fun failCall(error: PolyError) {
        if (!active && _state.value is CallState.Failed) return // already failed; don't churn
        logger.e("[voice] call failed", mapOf("error" to error.debugDescription))
        cleanup()
        _state.value = CallState.Failed(error)
    }

    /** Tear down all resources. Bumps `callAttempt` so any in-flight pipeline sees itself as stale. */
    private fun cleanup() {
        callAttempt += 1
        active = false
        signalingReconnecting = false
        connectionTimeoutJob?.cancel(); connectionTimeoutJob = null
        disconnectGraceJob?.cancel(); disconnectGraceJob = null
        collectors.forEach { it.cancel() }
        collectors.clear()
        // Best-effort graceful close frame before dropping the socket — on a clean end AND on failure.
        // No-op if the socket is already gone.
        if (signalSessionId != null) runCatching { signaling.send(SignalingProtocol.encodeClose(signalSessionId)) }
        runCatching { webrtc.close() }
        runCatching { signaling.close() }
        runCatching { sessionLink.close() }
        runCatching { audioControl.deactivate() } // restore the prior audio mode + abandon focus
        signalSessionId = null
        remoteAnswerApplied = false
        lastPeerState = null
        pendingOutboundIce.clear()
        pendingInboundIce.clear()
        muted = false
        interruptionMuted = false
    }

    private fun Throwable.toPolyError(): PolyError = when (this) {
        is PolyError -> this
        else -> PolyError.Voice.SignalingFailed(message ?: "voice call setup failed")
    }
}
