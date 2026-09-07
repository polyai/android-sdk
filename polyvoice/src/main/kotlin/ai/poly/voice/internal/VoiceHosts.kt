// Copyright PolyAI Limited

package ai.poly.voice.internal

import ai.poly.messaging.Environment
import ai.poly.messaging.PolyError

/**
 * Resolves the three endpoints a call needs from a messaging `Environment`:
 *  - the messaging REST base (`messaging.{region}.poly.ai/api/v1`) — token + session,
 *  - the messaging voice-session WS (`…/ws`) — the LINK_TO_WEBRTC handshake,
 *  - the `webrtc-bridge` base (`webrtc-bridge.…`) — provision, SDP and the events socket.
 *
 * The bridge lives on a *different* domain from messaging and isn't derivable from the messaging
 * host, so it's resolved from a known per-environment mapping with an optional explicit
 * `signalingHost` override (`VoiceOptions.signalingHost`) for dev / self-hosted deployments.
 */
internal class VoiceHosts(
    private val environment: Environment,
    private val signalingHost: String? = null,
) {
    /** Messaging REST base, e.g. `https://messaging.us-1.poly.ai/api/v1`. */
    fun restBaseUrl(): String = when (environment) {
        is Environment.Custom -> environment.restBaseUrl.toString().trimEnd('/')
        else -> "https://messaging.${region()}.poly.ai$REST_PATH"
    }

    /** Voice-session WS URL (messaging host) with query params already attached. */
    fun voiceSessionWsUrl(sessionId: String, token: String): String {
        val base = when (environment) {
            is Environment.Custom -> environment.wsBaseUrl.toString().trimEnd('/')
            else -> "wss://messaging.${region()}.poly.ai$WS_PATH"
        }
        return "$base?session_id=$sessionId&auth_token=$token"
    }

    /** Gateway signaling WS, e.g. `wss://webrtc-gateway.us-1.platform.polyai.app/api/v1/webrtc/signal`. */
    /**
     * `webrtc-bridge` base, e.g. `https://webrtc-bridge.dev.polyai.app/`. Every credentials path the
     * bridge returns resolves against this, so it keeps its trailing slash.
     *
     * Host rules come from the bridge's own gitops overlays and don't quite match the gateway's:
     * `dev` is standalone, `plg-us-1-prod` sits directly under `polyai.app`, and every other cluster
     * is under `.platform`.
     */
    fun bridgeBaseUrl(): String = "https://${bridgeHost()}/"

    private fun bridgeHost(): String {
        signalingHost?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return when (environment) {
            is Environment.US -> "webrtc-bridge.us-1.platform.polyai.app"
            is Environment.UK -> "webrtc-bridge.uk-1.platform.polyai.app"
            is Environment.EUW -> "webrtc-bridge.euw-1.platform.polyai.app"
            is Environment.Cluster -> when (environment.name) {
                "dev" -> "webrtc-bridge.dev.polyai.app"
                "plg-us-1-prod" -> "webrtc-bridge.plg-us-1-prod.polyai.app"
                else -> "webrtc-bridge.${environment.name}.platform.polyai.app"
            }
            is Environment.Custom -> throw PolyError.InvalidConfiguration(
                "Environment.Custom has no known webrtc-bridge — set VoiceOptions.signalingHost.",
            )
        }
    }

    private fun region(): String = when (environment) {
        is Environment.US -> "us-1"
        is Environment.UK -> "uk-1"
        is Environment.EUW -> "euw-1"
        is Environment.Cluster -> environment.name
        // Custom never reaches here (callers branch on it first).
        is Environment.Custom -> error("unreachable")
    }

    private companion object {
        const val REST_PATH = "/api/v1"
        const val WS_PATH = "/ws"
    }
}
