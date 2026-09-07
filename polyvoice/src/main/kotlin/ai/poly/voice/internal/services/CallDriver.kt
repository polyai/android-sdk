// Copyright PolyAI Limited

package ai.poly.voice.internal.services

import ai.poly.messaging.PolyError
import ai.poly.messaging.voice.CallState
import ai.poly.voice.AudioDevice
import ai.poly.voice.AudioState
import kotlinx.coroutines.flow.StateFlow

/**
 * The call state machine behind `VoiceCall`.
 *
 * Two implementations exist because the two backends negotiate differently, not merely over
 * different transports: [CallCoordinator] drives `webrtc-gateway` (one WebSocket, trickle ICE, a
 * client-minted call SID) and [BridgeCallCoordinator] drives `webrtc-bridge` (HTTPS SDP, a control
 * socket, a server-minted call id and a second negotiation for agent audio).
 *
 * `VoiceCall` holds one of these and knows about neither.
 */
internal interface CallDriver {
    val state: StateFlow<CallState>
    val audio: StateFlow<AudioState>

    suspend fun start()
    fun endCall()
    fun dispose()
    fun setMuted(value: Boolean)
    fun isMuted(): Boolean
    fun selectAudioDevice(device: AudioDevice?)

    /** Reflect a pre-flight failure (e.g. mic permission denied) in [state] without starting. */
    fun failPreflight(error: PolyError)
}
