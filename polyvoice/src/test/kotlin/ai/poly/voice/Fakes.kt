// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.LogLevel
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.IceServer
import ai.poly.voice.internal.ports.AudioControl
import ai.poly.voice.internal.ports.AudioInterruption
import ai.poly.voice.internal.ports.PeerConnectionState
import ai.poly.voice.internal.ports.PeerEvent
import ai.poly.voice.internal.ports.SignalingTransport
import ai.poly.voice.internal.ports.VoiceRestApi
import ai.poly.voice.internal.ports.VoiceSessionLink
import ai.poly.voice.internal.ports.WebRtcPeer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Swallows all logs in tests. */
internal object NoopLogger : PolyLogger {
    override fun log(level: LogLevel, message: String, metadata: Map<String, Any?>?) {}
}

/** Which pipeline step the fake REST should fail at (null = succeed). */
internal class FakeRestApi(
    var token: String = "access-tok",
    var sessionId: String = "sess-1",
    var iceServers: List<IceServer> = IceServer.DEFAULT,
    var failAt: String? = null,
) : VoiceRestApi {
    override suspend fun obtainAccessToken(): String {
        if (failAt == "token") throw PolyError.Auth.Unauthorized
        return token
    }

    override suspend fun createSession(token: String): String {
        if (failAt == "session") throw PolyError.Voice.SignalingFailed("session create failed")
        return sessionId
    }

    override suspend fun fetchIceServers(token: String): List<IceServer> = iceServers
}

internal class FakeAudioControl : AudioControl {
    var activated = false
    var deactivated = false
    val selectedRequests = mutableListOf<AudioDevice?>()

    private val _audio = MutableStateFlow(AudioState.EMPTY)
    override val audio: StateFlow<AudioState> = _audio

    private val _interruptions = MutableSharedFlow<AudioInterruption>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val interruptions: Flow<AudioInterruption> = _interruptions

    override fun activate() { activated = true }
    override fun deactivate() { deactivated = true }
    override fun selectAudioDevice(device: AudioDevice?) {
        selectedRequests += device
        // Mimic the real adapter confirming the route asynchronously via the flow.
        if (device != null) _audio.value = AudioState(_audio.value.availableDevices, device)
    }

    /** Push a routing snapshot as if the platform reported it. */
    fun emitAudio(state: AudioState) { _audio.value = state }

    /** Push an audio-focus interruption as if the platform's focus listener fired. */
    fun emitInterruption(interruption: AudioInterruption) { _interruptions.tryEmit(interruption) }
}

internal class FakeSessionLink(var failOpen: Boolean = false) : VoiceSessionLink {
    var opened = false
    var closed = false
    var lastCallSid: String? = null

    override suspend fun open(token: String, sessionId: String, callSid: String) {
        if (failOpen) throw PolyError.Voice.SignalingFailed("voice session link failed")
        opened = true
        lastCallSid = callSid
    }

    override fun close() { closed = true }
}

internal class FakeSignalingTransport : SignalingTransport {
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: Flow<String> = _incoming
    override val closed: Flow<Unit> = _closed

    val sent = mutableListOf<String>()
    var connectedUrl: String? = null
    var isOpen = true
    var closeCount = 0
    var connectCount = 0
    var failReconnect = false // make subsequent connect() calls (reconnects) throw

    override suspend fun connect(url: String) {
        connectCount++
        if (failReconnect) throw PolyError.Voice.SignalingFailed("reconnect failed")
        connectedUrl = url
        isOpen = true
    }

    override fun send(text: String): Boolean {
        if (!isOpen) return false
        sent += text
        return true
    }

    override fun close() { closeCount++; isOpen = false }

    /** Push an inbound frame as if from the gateway. */
    fun deliver(frame: String) { _incoming.tryEmit(frame) }

    /** Simulate the socket dropping (closes it, then signals the unexpected close). */
    fun drop() { isOpen = false; _closed.tryEmit(Unit) }

    /** Frames sent whose JSON "type" equals [type]. */
    fun sentOfType(type: String): List<String> =
        sent.filter { org.json.JSONObject(it).optString("type") == type }
}

internal class FakeWebRtcPeer(var offerSdp: String = "OFFER_SDP") : WebRtcPeer {
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<PeerEvent> = _events

    var created = false
    var lastIceServers: List<IceServer> = emptyList()
    var remoteAnswer: String? = null
    var micTrackEnabled = true
    var closeCount = 0
    val addedRemoteIce = mutableListOf<Triple<String, String?, Int?>>()

    override suspend fun create(iceServers: List<IceServer>) { created = true; lastIceServers = iceServers }
    override suspend fun createOfferSdp(): String = offerSdp
    override suspend fun setRemoteAnswer(sdp: String) { remoteAnswer = sdp }
    override fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        addedRemoteIce += Triple(candidate, sdpMid, sdpMLineIndex)
    }
    override fun setMicEnabled(enabled: Boolean) { micTrackEnabled = enabled }
    override fun close() { closeCount++ }

    fun emitLocalIce(candidate: String, sdpMid: String? = "0", sdpMLineIndex: Int? = 0) {
        _events.tryEmit(PeerEvent.LocalIce(candidate, sdpMid, sdpMLineIndex))
    }

    fun emitState(state: PeerConnectionState) { _events.tryEmit(PeerEvent.ConnectionState(state)) }
}
