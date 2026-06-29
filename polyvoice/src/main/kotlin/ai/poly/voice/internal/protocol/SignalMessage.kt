// Copyright PolyAI Limited

package ai.poly.voice.internal.protocol

/**
 * A decoded inbound signaling message from the WebRTC gateway (`…/api/v1/webrtc/signal`).
 * The wire format matches the WebRTC gateway's signaling protocol. Frames we don't act on decode to `null`.
 */
internal sealed interface SignalMessage {
    /** SDP answer to our offer. `sessionId` is the gateway's signal session id (used on later frames). */
    data class Answer(val sessionId: String?, val sdp: String) : SignalMessage

    /** A trickled remote ICE candidate. */
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?,
    ) : SignalMessage

    /** Server-side error; the call should fail. */
    data class Error(val message: String) : SignalMessage

    /** Heartbeat response — no action. */
    data object Pong : SignalMessage

    /** Backend-initiated graceful close (e.g. the agent finished); end the call cleanly. */
    data object Close : SignalMessage
}
