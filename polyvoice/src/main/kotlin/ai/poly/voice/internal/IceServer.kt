// Copyright PolyAI Limited

package ai.poly.voice.internal

/**
 * A STUN/TURN server, decoupled from `org.webrtc.PeerConnection.IceServer` so the port layer and
 * tests never touch the native type. The `AndroidWebRtcPeer` adapter maps these to the libwebrtc form.
 */
internal data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    internal companion object {
        /** The public-STUN fallback used when the gateway's ice-servers endpoint is unavailable. */
        val DEFAULT: List<IceServer> = listOf(IceServer(urls = listOf("stun:stun.l.google.com:19302")))
    }
}
