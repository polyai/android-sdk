// Copyright PolyAI Limited

package ai.poly.voice

/**
 * Options for `PolyVoice.call`. [webrtcToken] is **required** — every voice call needs the WebRTC
 * gateway token, which is a distinct value from the API key. The rest are dev / self-hosted overrides
 * with working defaults for the standard regions.
 *
 * @property webrtcToken The connector's **WebRTC token** from Agent Studio — the credential the WebRTC
 *   gateway authenticates (the signaling offer's `authToken` and the ICE-servers fetch). Always a
 *   separate value from `Configuration.apiKey`, and always required for a voice call.
 * @property signalingHost Override the WebRTC gateway host (no scheme, e.g.
 *   `"webrtc-gateway.example.com"`). Required when using `Environment.Custom`,
 *   since the gateway host can't be derived from a custom messaging endpoint. When null, the host is
 *   resolved from the configured environment.
 * @property speakerphone The **fallback** route used when no headset/Bluetooth is connected: the
 *   loudspeaker (hands-free — the `true` default, natural for a voice agent) or the earpiece (`false`).
 *   A connected wired/Bluetooth headset is always preferred automatically; switch manually mid-call via
 *   `VoiceCall.setAudioDevice` (and pass `null` to return to automatic).
 */
public class VoiceOptions @JvmOverloads constructor(
    @JvmField public val webrtcToken: String,
    @JvmField public val signalingHost: String? = null,
    @JvmField public val speakerphone: Boolean = true,
) {
    /** Java-friendly builder. The WebRTC token is required, so it's a constructor argument. */
    public class Builder(private val webrtcToken: String) {
        private var signalingHost: String? = null
        private var speakerphone: Boolean = true

        public fun signalingHost(value: String?): Builder = apply { signalingHost = value }
        public fun speakerphone(value: Boolean): Builder = apply { speakerphone = value }

        public fun build(): VoiceOptions =
            VoiceOptions(webrtcToken = webrtcToken, signalingHost = signalingHost, speakerphone = speakerphone)
    }
}
