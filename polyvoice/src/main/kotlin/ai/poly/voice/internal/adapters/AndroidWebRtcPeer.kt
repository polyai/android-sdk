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

    // Serialises the closed-flag flip in close() against the check-then-native-call in onAddTrack so a
    // remote track arriving during teardown can't call into a half-disposed engine. Held only around
    // the flag + the native setEnabled, never across dispose() — holding it across libwebrtc's
    // thread-joining dispose() while its signaling thread waits on the same lock would deadlock.
    private val trackLock = Any()

    override suspend fun create(iceServers: List<IceServer>) {
        closed = false
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

    override fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        if (closed) return
        peerConnection?.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate))
    }

    override fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

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
        override fun onIceCandidate(candidate: IceCandidate) {
            _events.tryEmit(PeerEvent.LocalIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
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
                track.setEnabled(true) // remote audio plays through the AudioDeviceModule
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
        const val LOCAL_AUDIO_TRACK_ID = "poly_audio_0"
        const val LOCAL_STREAM_ID = "poly_stream_0"
        val factoryInitialized = AtomicBoolean(false)
    }
}
