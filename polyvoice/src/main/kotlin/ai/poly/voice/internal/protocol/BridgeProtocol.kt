// Copyright PolyAI Limited

package ai.poly.voice.internal.protocol

import ai.poly.voice.internal.IceServer
import org.json.JSONObject

/**
 * Wire types and framing for `webrtc-bridge` (RUN-1117 / RUN-1279), the replacement for
 * `webrtc-gateway`.
 *
 * Where the gateway is one WebSocket carrying auth, SDP and trickle ICE, the bridge splits a call
 * across HTTPS and a control socket:
 *
 *  1. `POST /api/v1/call` (`Authorization: Bearer <webrtcToken>`) mints the call and returns
 *     `callId` plus short-lived per-call credentials.
 *  2. Every route after that carries the per-call token in `X-Call-Token` — never in a URL
 *     (RUN-1692).
 *  3. SDP travels as JSON over HTTPS (`/sdp`, `/sdp/pull`, `/sdp/renegotiate`), non-trickle: the
 *     offer must already carry its candidates.
 *  4. The events WebSocket can't set a header on its upgrade, so it sends the same token as its
 *     first frame instead.
 *
 * Pure and stateless (`org.json` only), so it runs unchanged in JVM unit tests — the same approach
 * as [SignalingProtocol].
 */
internal object BridgeProtocol {

    /** Header carrying the per-call token on every route after provision. */
    const val CALL_TOKEN_HEADER: String = "X-Call-Token"

    /**
     * The only mode a customer-facing call uses. The bridge defaults to `"echo"`, which
     * authenticated deployments reject with a 403.
     */
    const val VOICE_AGENT_MODE: String = "voice-agent"

    // ─── provision ────────────────────────────────────────────────

    /**
     * Body for `POST /api/v1/call`. Account, project and client environment are derived server-side
     * from the verified token, so the body carries only what the token cannot.
     */
    fun provisionBody(mode: String = VOICE_AGENT_MODE): String =
        JSONObject().put("mode", mode).toString()

    /** The per-call credentials the bridge returns. Every URL is a **path** to resolve against the base. */
    data class Credentials(
        val provider: String,
        val connectPath: String,
        val token: String?,
        val trackName: String?,
        /**
         * ICE servers the bridge wants this call to use. The dev deployment doesn't send these yet
         * (RUN-1780), so callers fall back to [IceServer.DEFAULT].
         */
        val iceServers: List<IceServer>,
        val eventsPath: String?,
        val pullPath: String?,
        val renegotiatePath: String?,
    )

    /** A provisioned call: the bridge-minted id plus its credentials. */
    data class Provision(val callId: String, val credentials: Credentials)

    /**
     * Parse the provision response. Returns null when the payload lacks the two fields a call can't
     * proceed without (`callId` and `creds.connectUrl`).
     */
    fun parseProvision(body: String): Provision? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val callId = json.optString("callId").takeIf { it.isNotEmpty() } ?: return null
        val creds = json.optJSONObject("creds") ?: return null
        val connect = creds.optString("connectUrl").takeIf { it.isNotEmpty() } ?: return null
        val extra = creds.optJSONObject("extra")
        return Provision(
            callId = callId,
            credentials = Credentials(
                provider = creds.optString("provider"),
                connectPath = connect,
                token = creds.optString("token").takeIf { it.isNotEmpty() },
                trackName = creds.optString("trackName").takeIf { it.isNotEmpty() },
                iceServers = IceServer.parseList(creds.optJSONArray("iceServers")),
                eventsPath = extra?.optString("eventsUrl")?.takeIf { it.isNotEmpty() },
                pullPath = extra?.optString("pullUrl")?.takeIf { it.isNotEmpty() },
                renegotiatePath = extra?.optString("renegotiateUrl")?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    // ─── SDP exchange ─────────────────────────────────────────────

    /**
     * Body for `POST {connectUrl}`: the gathered offer plus the microphone transceiver's mid, which
     * tells the SFU which m-line we publish.
     */
    fun offerBody(sdp: String, mid: String): String =
        JSONObject().put("sdp", sdp).put("mid", mid).toString()

    /** Body for `POST {renegotiateUrl}`: the answer to the pull's offer. */
    fun sdpBody(sdp: String): String = JSONObject().put("sdp", sdp).toString()

    /** Both `/sdp` (answer) and `/sdp/pull` (renegotiation offer) reply with the same one-field shape. */
    fun parseSdp(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("sdp").takeIf { it.isNotEmpty() }
    }

    // ─── events socket ────────────────────────────────────────────

    /**
     * The first frame the events socket must send. A WebSocket upgrade can't carry
     * [CALL_TOKEN_HEADER], so the token rides here; the bridge closes the socket (policy violation)
     * on anything else.
     */
    fun eventsAuthFrame(token: String): String =
        JSONObject().put("type", "auth").put("token", token).toString()

    /** A control message from the bridge's events socket. */
    enum class Event {
        /**
         * The agent's track was republished into a new SFU session — the existing subscription has
         * gone silent and must be pulled again.
         */
        REPULL,

        /**
         * Barge-in fired server-side: silence the agent immediately rather than play out what the
         * SFU and jitter buffer already hold.
         */
        BARGE_IN,

        /** The agent resumed after a barge-in. */
        UNMUTE,
    }

    /** Parse an inbound events frame. Unknown frames are ignored (null). */
    fun parseEvent(text: String): Event? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (val event = json.optString("event")) {
            "repull" -> Event.REPULL
            "unmute" -> Event.UNMUTE
            // Barge-in arrives as a family of `barge-in*` events; they all mean "silence the agent".
            else -> if (event.startsWith("barge-in")) Event.BARGE_IN else null
        }
    }
}
