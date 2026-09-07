// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.IceServer
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.PeerConnectionState
import ai.poly.voice.internal.ports.PeerEvent
import ai.poly.voice.internal.ports.WebRtcPeer
import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The libwebrtc-backed `WebRtcPeer`. Deliberately thin: it owns the native peer connection, the
 * microphone audio track, and the audio device module, and translates libwebrtc callbacks into
 * `PeerEvent`s. All protocol sequencing/buffering lives in the coordinator, so this class only does
 * "build", "offer", "answer", "candidate", "mute", "close".
 *
 * Not covered by JVM unit tests (it needs the native engine + a device); exercised through the
 * mockable `WebRtcPeer` port and on-device manual verification.
 */
internal class AndroidWebRtcPeer(
    private val context: Context,
    private val logger: PolyLogger,
) : WebRtcPeer {

    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<PeerEvent> = _events.asSharedFlow()

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null

    // Set on the coordinator thread in close(); read in observer callbacks that fire on libwebrtc's
    // own signaling thread, so it must be @Volatile. Once closed, late native callbacks are ignored
    // (no touching a disposed peer/track).
    @Volatile private var closed = false

    // Non-trickle gather bookkeeping for the bridge path, keyed by ICE generation (the candidate's
    // own ufrag). A renegotiation shares one transport with the offer under BUNDLE, so its wait must
    // not be ended by the previous generation's candidates — and a retired generation's candidate
    // can still be delivered after the new local description is installed.
    private val candidateCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lastCandidateAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val gatheringDone = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Barge-in intent, applied to the agent track whenever a (re-)pull delivers one. */
    @Volatile private var remoteAudioEnabled = true

    // Serialises the closed-flag flip in close() against the check-then-native-call in onAddTrack so a
    // remote track arriving during teardown can't call into a half-disposed engine. Held only around
    // the flag + the native setEnabled, never across dispose() — holding it across libwebrtc's
    // thread-joining dispose() while its signaling thread waits on the same lock would deadlock.
    private val trackLock = Any()

    override suspend fun create(iceServers: List<IceServer>) {
        closed = false
        candidateCounts.clear()
        lastCandidateAt.clear()
        gatheringDone.clear()
        remoteAudioEnabled = true
        ensureFactoryInitialized()
        val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule().also { audioDeviceModule = it }
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
            .also { this.factory = it }

        val source = factory.createAudioSource(MediaConstraints()).also { audioSource = it }
        val track = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source).also { localAudioTrack = it }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers.map(::toNativeIceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: throw PolyError.Voice.MediaFailed("could not create peer connection")
        peerConnection = pc
        pc.addTrack(track, listOf(LOCAL_STREAM_ID))
        logger.d("[voice] peer connection created", mapOf("iceServers" to iceServers.size))
    }

    override suspend fun createOfferSdp(): String {
        val pc = peerConnection ?: throw PolyError.Voice.MediaFailed("peer connection not created")
        val offer = suspendCancellableCoroutine { cont ->
            pc.createOffer(
                object : NoopSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) { cont.resume(desc) }
                    override fun onCreateFailure(error: String?) {
                        cont.resumeWithException(PolyError.Voice.MediaFailed("createOffer failed: ${error.orEmpty()}"))
                    }
                },
                MediaConstraints(),
            )
        }
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(
                object : NoopSdpObserver() {
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(PolyError.Voice.MediaFailed("setLocalDescription failed: ${error.orEmpty()}"))
                    }
                },
                offer,
            )
        }
        return offer.description
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        val pc = peerConnection ?: throw PolyError.Voice.MediaFailed("peer connection not created")
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(
                object : NoopSdpObserver() {
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(PolyError.Voice.MediaFailed("setRemoteDescription failed: ${error.orEmpty()}"))
                    }
                },
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
        }
    }

    override fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    // ── webrtc-bridge capabilities ────────────────────────────────

    /**
     * Wait for ICE gathering to settle before the offer is POSTed.
     *
     * Deliberately not `iceGatheringState == COMPLETE`: a STUN transaction that never terminates
     * pins that state at GATHERING and suppresses the end-of-candidates event with it, so on some
     * networks neither of libwebrtc's "done" signals ever arrives. A quiet candidate stream is the
     * real signal; [capMs] is only a backstop. The quiet window arms only once a candidate exists,
     * so a gather producing nothing falls through to the cap rather than returning an empty SDP.
     */
    override suspend fun awaitIceGathering(quietMs: Long, capMs: Long) {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (closed) return
            val key = currentIceUfrag() ?: ""
            if (key in gatheringDone) return
            if (peerConnection?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
            val count = candidateCounts[key] ?: 0
            val last = lastCandidateAt[key]
            if (count > 0 && last != null && System.currentTimeMillis() - last >= quietMs) return
            delay(GATHER_POLL_MS)
        }
    }

    override fun localDescriptionSdp(): String? = peerConnection?.localDescription?.description

    /** The mid of the transceiver carrying the microphone track. */
    override fun audioMid(): String? {
        val pc = peerConnection ?: return null
        val trackId = localAudioTrack?.id() ?: return null
        return runCatching {
            pc.transceivers.firstOrNull { it.sender?.track()?.id() == trackId }?.mid
        }.getOrNull()
    }

    /**
     * Apply the bridge's renegotiation offer (which adds the agent's recvonly m-line) and return the
     * answer.
     */
    override suspend fun acceptRemoteOffer(sdp: String): String {
        val pc = peerConnection ?: throw PolyError.Voice.MediaFailed("peer connection not created")
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(
                object : NoopSdpObserver() {
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(PolyError.Voice.MediaFailed("setRemoteDescription (offer) failed: ${error.orEmpty()}"))
                    }
                },
                SessionDescription(SessionDescription.Type.OFFER, sdp),
            )
        }
        val answer = suspendCancellableCoroutine { cont ->
            pc.createAnswer(
                object : NoopSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) { cont.resume(desc) }
                    override fun onCreateFailure(error: String?) {
                        cont.resumeWithException(PolyError.Voice.MediaFailed("createAnswer failed: ${error.orEmpty()}"))
                    }
                },
                MediaConstraints(),
            )
        }
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(
                object : NoopSdpObserver() {
                    override fun onSetSuccess() { cont.resume(Unit) }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(PolyError.Voice.MediaFailed("setLocalDescription (answer) failed: ${error.orEmpty()}"))
                    }
                },
                answer,
            )
        }
        return answer.description
    }

    /**
     * Barge-in: the SFU and jitter buffer already hold agent audio this client cannot drop, so the
     * received track is silenced the moment the bridge says so.
     */
    override fun setRemoteAudioEnabled(enabled: Boolean) {
        remoteAudioEnabled = enabled
        synchronized(trackLock) {
            if (closed) return
            remoteAudioTrack?.setEnabled(enabled)
        }
    }

    private fun currentIceUfrag(): String? =
        peerConnection?.localDescription?.description
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("a=ice-ufrag:") }
            ?.removePrefix("a=ice-ufrag:")
            ?.trim()

    override fun close() {
        // Flip the flag under the lock so any in-flight onAddTrack finishes its native setEnabled
        // before we start disposing — then release the lock and dispose without holding it.
        synchronized(trackLock) { closed = true } // stop honouring late native callbacks before we tear anything down
        // Graceful peer shutdown (stop ICE/DTLS, transition to CLOSED) BEFORE freeing the native
        // object — dispose() alone can race in-flight onIceCandidate/onConnectionChange callbacks.
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        remoteAudioTrack = null
        // factory must be disposed AFTER every object it created (pc, track, source).
        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        factory = null
        audioDeviceModule = null
    }

    private val observer = object : PeerConnection.Observer {
        /**
         * Candidates are never trickled — the bridge's SDP proxy has no channel for them. They are
         * only counted here, so [awaitIceGathering] can tell when the stream has gone quiet and the
         * local description is complete enough to send.
         */
        override fun onIceCandidate(candidate: IceCandidate) {
            val key = candidate.sdp.ufragValue() ?: currentIceUfrag() ?: ""
            if (candidate.sdp.isEmpty()) {
                gatheringDone += key
            } else {
                candidateCounts[key] = (candidateCounts[key] ?: 0) + 1
                lastCandidateAt[key] = System.currentTimeMillis()
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            logger.d("[voice] peer connection state", mapOf("state" to newState.name))
            _events.tryEmit(PeerEvent.ConnectionState(newState.toPortState()))
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track() as? AudioTrack ?: return
            synchronized(trackLock) {
                if (closed) return // ignore a track arriving after teardown (don't touch a disposed factory)
                remoteAudioTrack = track
                // Remote audio plays through the AudioDeviceModule. A re-pull can deliver a track
                // while a barge-in mute is in force, so it inherits the current intent rather than
                // unconditionally unmuting.
                track.setEnabled(remoteAudioEnabled)
            }
            _events.tryEmit(PeerEvent.Track)
        }

        // Unused callbacks (required by the interface).
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: RtpTransceiver) {}
    }

    /**
     * Extract `ufrag` from a candidate's SDP attribute line — what keys the gather bookkeeping to an
     * ICE generation. The local description alone isn't a safe key: a candidate from a retired
     * generation can still arrive after a renegotiation installs a new one.
     */
    private fun String.ufragValue(): String? =
        substringAfter("ufrag ", "").substringBefore(' ').takeIf { it.isNotEmpty() }

    private fun ensureFactoryInitialized() {
        if (factoryInitialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
        }
    }

    private fun toNativeIceServer(server: IceServer): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(server.urls)
            .apply {
                server.username?.let { setUsername(it) }
                server.credential?.let { setPassword(it) }
            }
            .createIceServer()

    private fun PeerConnection.PeerConnectionState.toPortState(): PeerConnectionState = when (this) {
        PeerConnection.PeerConnectionState.NEW,
        PeerConnection.PeerConnectionState.CONNECTING,
        -> PeerConnectionState.CONNECTING
        PeerConnection.PeerConnectionState.CONNECTED -> PeerConnectionState.CONNECTED
        PeerConnection.PeerConnectionState.DISCONNECTED -> PeerConnectionState.DISCONNECTED
        PeerConnection.PeerConnectionState.FAILED -> PeerConnectionState.FAILED
        PeerConnection.PeerConnectionState.CLOSED -> PeerConnectionState.CLOSED
    }

    /** SdpObserver with empty defaults so each call site overrides only the two it cares about. */
    private abstract class NoopSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private companion object {
        /** Poll interval for the non-trickle gather wait (bridge path). */
        const val GATHER_POLL_MS = 25L
        const val LOCAL_AUDIO_TRACK_ID = "poly_audio_0"
        const val LOCAL_STREAM_ID = "poly_stream_0"
        val factoryInitialized = AtomicBoolean(false)
    }
}
