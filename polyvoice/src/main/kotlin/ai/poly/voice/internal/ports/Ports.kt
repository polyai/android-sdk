// Copyright PolyAI Limited

package ai.poly.voice.internal.ports

import ai.poly.voice.AudioDevice
import ai.poly.voice.AudioState
import ai.poly.voice.internal.IceServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The seams the `ai.poly.voice.internal.services.CallCoordinator` drives. Each has a real OkHttp /
 * libwebrtc adapter for production and a fake in tests, so the whole call state machine is exercised
 * on the JVM without a device or network — the same ports/adapters discipline as `:polymessaging`.
 */

/** Self-contained REST auth for a call (its own session, independent of any active chat). */
internal interface VoiceRestApi {
    /** `POST /access-token` (X-Token = apiKey) → access token. */
    suspend fun obtainAccessToken(): String

    /** `POST /sessions` (Bearer) → session id. */
    suspend fun createSession(token: String): String

    /** `GET /api/v1/ice-servers` on the gateway → STUN/TURN. Implementations fall back to STUN on failure. */
    suspend fun fetchIceServers(token: String): List<IceServer>
}

/**
 * Platform audio routing for a call. A voice call needs `AudioManager.MODE_IN_COMMUNICATION` and
 * audio focus while it's live, otherwise remote audio is silent or misrouted. The real adapter wraps
 * `AudioManager`; tests use `NONE`.
 */
internal interface AudioControl {
    /** Enter in-communication mode, take audio focus, and apply the initial output route. */
    fun activate()

    /** Abandon focus, restore the prior audio mode/route, and stop observing devices. */
    fun deactivate()

    /**
     * Live routing snapshot — the outputs available now and the active one. Backed by a
     * `MutableStateFlow` so platform callbacks (which fire on a Handler thread) can update it
     * lock-free; [AudioState.EMPTY] until [activate]. Read by `CallCoordinator` / `VoiceCall`.
     */
    val audio: StateFlow<AudioState>

    /**
     * Route call audio to [device] on the live call. Pass `null` to revert to automatic
     * preferred-device routing. A no-op if [device] isn't currently available. The applied route is
     * confirmed asynchronously via [audio] (Bluetooth can take seconds), not synchronously here.
     */
    fun selectAudioDevice(device: AudioDevice?)

    /**
     * Audio-focus interruptions while the call is live — driven by the platform `AudioManager`'s focus
     * callbacks. The coordinator reacts: mute on a transient loss (a notification / nav prompt),
     * restore on the matching gain, and end the call on a permanent loss (an incoming phone call the
     * user answered, or another app taking an exclusive audio session).
     */
    val interruptions: Flow<AudioInterruption>

    companion object {
        val NONE: AudioControl = object : AudioControl {
            override val audio: StateFlow<AudioState> = MutableStateFlow(AudioState.EMPTY)
            override val interruptions: Flow<AudioInterruption> = kotlinx.coroutines.flow.emptyFlow()
            override fun activate() {}
            override fun deactivate() {}
            override fun selectAudioDevice(device: AudioDevice?) {}
        }
    }
}

/** A change in audio focus that the call must react to. */
internal enum class AudioInterruption {
    /** Focus lost temporarily (notification, nav prompt) — mute the mic until [GAINED]. */
    TRANSIENT_LOSS,

    /** Focus lost for good (incoming call answered, exclusive audio session) — end the call. */
    PERMANENT_LOSS,

    /** Focus regained after a [TRANSIENT_LOSS] — restore the mic. */
    GAINED,
}

/**
 * The voice-session WebSocket on the messaging host: opens, waits for SESSION_START, then sends
 * `EVENT_TYPE_LINK_TO_WEBRTC_CONVERSATION` to bind `callSid` to the session. `open` suspends until
 * the link has been sent (or throws on timeout / early close).
 */
internal interface VoiceSessionLink {
    suspend fun open(token: String, sessionId: String, callSid: String)
    fun close()
}

/** The signaling WebSocket on the gateway (SDP + ICE exchange). */
internal interface SignalingTransport {
    /** Raw inbound text frames (decoded by `ai.poly.voice.internal.protocol.SignalingProtocol`). */
    val incoming: Flow<String>

    /** Emits when the socket closes unexpectedly (no auto-reconnect — the coordinator decides what to do). */
    val closed: Flow<Unit>

    suspend fun connect(url: String)

    /** Send a frame. Returns false if the socket isn't open. */
    fun send(text: String): Boolean

    fun close()
}

/**
 * Wraps a libwebrtc peer connection + microphone audio track. Deliberately "dumb": all protocol
 * sequencing and ICE buffering lives in the coordinator, so this adapter only translates to/from
 * the native API and emits `PeerEvent`s.
 */
internal interface WebRtcPeer {
    val events: Flow<PeerEvent>

    /** Build the factory (once), the mic audio track, and the peer connection with `iceServers`. */
    suspend fun create(iceServers: List<IceServer>)

    /** `createOffer` + `setLocalDescription`; returns the local SDP to send. */
    suspend fun createOfferSdp(): String

    /** Apply the remote SDP answer (`setRemoteDescription`). */
    suspend fun setRemoteAnswer(sdp: String)

    /** Add a remote ICE candidate. The coordinator only calls this after the answer is applied. */
    fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    /** Enable/disable the local mic track (mute). */
    fun setMicEnabled(enabled: Boolean)

    fun close()
}

/** Events surfaced by `WebRtcPeer`. */
internal sealed interface PeerEvent {
    /** A locally gathered ICE candidate to trickle to the gateway. */
    data class LocalIce(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : PeerEvent

    /** Peer connection state changed. */
    data class ConnectionState(val state: PeerConnectionState) : PeerEvent

    /** Remote audio track arrived (playout is handled inside the adapter). */
    data object Track : PeerEvent
}

/** Library-agnostic mirror of `org.webrtc.PeerConnection.PeerConnectionState`. */
internal enum class PeerConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }
