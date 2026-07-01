// Copyright PolyAI Limited

package ai.poly.voice.internal

import ai.poly.messaging.Environment
import ai.poly.messaging.PolyError

/**
 * Resolves the three endpoints a call needs from a messaging `Environment`:
 *  - the messaging REST base (`messaging.{region}.poly.ai/api/v1`) — token + session,
 *  - the messaging voice-session WS (`…/ws`) — the LINK_TO_WEBRTC handshake,
 *  - the WebRTC gateway (`webrtc-gateway.…`) — signaling + ICE servers.
 *
 * The gateway lives on a *different* domain from messaging and isn't derivable from the messaging
 * host, so it's resolved from a known per-environment mapping with an optional explicit
 * `signalingHost` override (`VoiceOptions.signalingHost`) for dev / self-hosted gateways.
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
    fun signalingUrl(): String = "wss://${gatewayHost()}$SIGNAL_PATH"

    /** Gateway ICE-servers endpoint with the access token attached. */
    fun iceServersUrl(token: String): String = "https://${gatewayHost()}$ICE_SERVERS_PATH?token=$token"

    private fun gatewayHost(): String {
        signalingHost?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return when (environment) {
            is Environment.US -> "webrtc-gateway.us-1.platform.polyai.app"
            is Environment.UK -> "webrtc-gateway.uk-1.platform.polyai.app"
            is Environment.EUW -> "webrtc-gateway.euw-1.platform.polyai.app"
            is Environment.Cluster ->
                // `dev` is a standalone gateway; other named clusters follow the production
                // `…platform.polyai.app` pattern.
                if (environment.name == "dev") "webrtc-gateway.dev.polyai.app"
                else "webrtc-gateway.${environment.name}.platform.polyai.app"
            is Environment.Custom -> throw PolyError.InvalidConfiguration(
                "Environment.Custom has no known WebRTC gateway — set VoiceOptions.signalingHost.",
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
        const val SIGNAL_PATH = "/api/v1/webrtc/signal"
        const val ICE_SERVERS_PATH = "/api/v1/ice-servers"
    }
}
