// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Environment
import ai.poly.voice.internal.VoiceHosts
import ai.poly.voice.internal.adapters.OkHttpSignalingTransport
import ai.poly.voice.internal.adapters.OkHttpVoiceRestApi
import ai.poly.voice.internal.adapters.OkHttpVoiceSessionLink
import ai.poly.voice.internal.protocol.SignalMessage
import ai.poly.voice.internal.protocol.SignalingProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live end-to-end signaling check against the real PolyAI **dev** backend, driving the actual
 * production adapters (REST → voice-session link → gateway signaling) + [SignalingProtocol]. Proves
 * the whole wire layer — hosts, tokens, headers, and the offer envelope — works against live infra.
 *
 * No libwebrtc here, so it uses a hand-crafted SDP offer (the gateway validates `authToken` and
 * answers at the SDP level before DTLS); the [PeerConnection] media path is verified on-device.
 *
 * OPT-IN: skipped unless the dev credentials are supplied via env so no secrets live in source:
 * ```
 * POLYVOICE_CONNECTOR_TOKEN=… POLYVOICE_WEBRTC_TOKEN=… \
 *   ./gradlew :polyvoice:testDebugUnitTest --tests '*LiveDevBackendTest*'
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class LiveDevBackendTest {

    private val connectorToken: String? = System.getenv("POLYVOICE_CONNECTOR_TOKEN")
    private val webrtcToken: String? = System.getenv("POLYVOICE_WEBRTC_TOKEN")
    private val xHost: String = System.getenv("POLYVOICE_X_HOST") ?: "https://poly.ai"

    private val hosts = VoiceHosts(Environment.cluster("dev"))

    @Test
    fun fullSignalingHandshake_reachesAnswer() = runBlocking {
        assumeTrue("set POLYVOICE_CONNECTOR_TOKEN + POLYVOICE_WEBRTC_TOKEN to run", connectorToken != null && webrtcToken != null)
        val connector = connectorToken!!
        val gatewayToken = webrtcToken!!

        // 1) REST: access token + session (X-Token = connector token, X-Host = the registered host).
        val rest = OkHttpVoiceRestApi(
            restBaseUrl = hosts.restBaseUrl(),
            iceServersUrl = { token -> hosts.iceServersUrl(token) },
            apiKey = connector,
            hostIdentifier = xHost,
            deviceType = "mobile",
            version = "live-test",
            logger = NoopLogger,
        )
        val accessToken = rest.obtainAccessToken()
        assertTrue(accessToken.isNotEmpty(), "expected an access token")
        val sessionId = rest.createSession(accessToken)
        assertTrue(sessionId.isNotEmpty(), "expected a session id")

        // 2) ICE servers from the gateway (authenticated with the gateway token, NOT the access token).
        val iceServers = rest.fetchIceServers(gatewayToken)
        assertTrue(iceServers.isNotEmpty(), "expected at least STUN")
        println("[live] ice servers: ${iceServers.flatMap { it.urls }}")

        // 3) Voice-session WS: SESSION_START → LINK_TO_WEBRTC handshake (completes or throws).
        val callSid = "android-live-" + UUID.randomUUID()
        val link = OkHttpVoiceSessionLink(
            wsUrl = { sid, tok -> hosts.voiceSessionWsUrl(sid, tok) },
            logger = NoopLogger,
        )
        link.open(accessToken, sessionId, callSid)

        // 4) Gateway signaling: send our exact offer envelope, expect an SDP answer back.
        val signaling = OkHttpSignalingTransport(NoopLogger)
        val answer = CompletableDeferred<SignalMessage.Answer>()
        val collector = launch(Dispatchers.IO) {
            signaling.incoming.collect { frame ->
                when (val m = SignalingProtocol.decode(frame)) {
                    is SignalMessage.Answer -> answer.complete(m)
                    is SignalMessage.Error -> answer.completeExceptionally(AssertionError("gateway error: ${m.message}"))
                    else -> Unit
                }
            }
        }
        signaling.connect(hosts.signalingUrl())
        signaling.send(SignalingProtocol.encodeOffer(sessionId = null, sdp = craftOfferSdp(), authToken = gatewayToken, callSid = callSid))

        val received = withTimeout(20_000) { answer.await() }
        assertTrue(received.sdp.isNotEmpty(), "expected a non-empty SDP answer")
        println("[live] ANSWER ok: signalSessionId=${received.sessionId}, sdp=${received.sdp.length} bytes")

        collector.cancel()
        signaling.close()
        link.close()
    }

    /** Minimal, well-formed Unified-Plan audio offer (dummy DTLS fingerprint; signaling-only). */
    private fun craftOfferSdp(): String {
        fun hex(n: Int) = (1..n).joinToString("") { "%02x".format((0..255).random()) }
        val fp = (1..32).joinToString(":") { "%02X".format((0..255).random()) }
        return buildString {
            append("v=0\r\n")
            append("o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n")
            append("s=-\r\nt=0 0\r\n")
            append("a=group:BUNDLE 0\r\n")
            append("a=msid-semantic: WMS poly\r\n")
            append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=rtcp:9 IN IP4 0.0.0.0\r\n")
            append("a=ice-ufrag:${hex(4)}\r\n")
            append("a=ice-pwd:${hex(12)}\r\n")
            append("a=ice-options:trickle\r\n")
            append("a=fingerprint:sha-256 $fp\r\n")
            append("a=setup:actpass\r\n")
            append("a=mid:0\r\n")
            append("a=sendrecv\r\n")
            append("a=rtcp-mux\r\n")
            append("a=rtpmap:111 opus/48000/2\r\n")
            append("a=fmtp:111 minptime=10;useinbandfec=1\r\n")
        }
    }
}
