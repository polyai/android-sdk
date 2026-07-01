// Copyright PolyAI Limited

package ai.poly.voice.internal.protocol

import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.log.w
import org.json.JSONObject

/**
 * Pure, stateless encode/decode of the WebRTC-gateway signaling wire format (the offer / answer /
 * ice-candidate / close frames). Uses `org.json` only (the same dependency-free approach as the messaging
 * wire layer), so
 * it runs unchanged in JVM unit tests.
 *
 * Frames are JSON text. Every message has a string `type`; type-specific data lives under `data`.
 */
internal object SignalingProtocol {

    // ─── encode (client → gateway) ────────────────────────────────

    /**
     * The initial SDP offer. The gateway validates `authToken` (the connector token / API key) and
     * binds the call to `callSid`. `sessionId` is null on the first offer (the gateway assigns the
     * signal session id and returns it in the answer).
     */
    fun encodeOffer(sessionId: String?, sdp: String, authToken: String, callSid: String): String =
        JSONObject()
            .put("type", "offer")
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .put("data", JSONObject().put("type", "offer").put("sdp", sdp))
            .put("mode", "end-to-end")
            .put("authToken", authToken)
            .put("callSid", callSid)
            .put("caller", "Polyphone")
            .put("callee", "Polyphone")
            .toString()

    /** A trickled local ICE candidate. */
    fun encodeIceCandidate(
        sessionId: String?,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?,
    ): String =
        JSONObject()
            .put("type", "ice-candidate")
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .put(
                "data",
                JSONObject()
                    .put("candidate", candidate)
                    .put("sdpMid", sdpMid ?: JSONObject.NULL)
                    .put("sdpMLineIndex", sdpMLineIndex ?: JSONObject.NULL),
            )
            .toString()

    /** Graceful client-initiated close. */
    fun encodeClose(sessionId: String?): String =
        JSONObject()
            .put("type", "close")
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .toString()

    // ─── decode (gateway → client) ────────────────────────────────

    /** Parse a raw signaling frame. Returns null for malformed/unactionable frames. */
    fun decode(text: String, logger: PolyLogger? = null): SignalMessage? {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: run {
            logger?.w("[voice] dropping unparseable signal frame")
            return null
        }
        val type = msg.optString("type").takeIf { it.isNotEmpty() } ?: run {
            logger?.w("[voice] dropping signal frame without a type")
            return null
        }
        return when (type) {
            "answer" -> decodeAnswer(msg, logger)
            "ice-candidate" -> decodeIceCandidate(msg, logger)
            "error" -> SignalMessage.Error(
                msg.optJSONObject("data")?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: "Connection failed",
            )
            "pong" -> SignalMessage.Pong
            "close" -> SignalMessage.Close
            else -> {
                logger?.w("[voice] unknown signal type", mapOf("type" to type))
                null
            }
        }
    }

    private fun decodeAnswer(msg: JSONObject, logger: PolyLogger?): SignalMessage? {
        val data = msg.optJSONObject("data")
        val sdp = data?.optString("sdp")?.takeIf { it.isNotEmpty() } ?: run {
            logger?.w("[voice] dropping malformed answer payload")
            return null
        }
        val sessionId = msg.optString("sessionId").takeIf { it.isNotEmpty() }
        return SignalMessage.Answer(sessionId = sessionId, sdp = sdp)
    }

    private fun decodeIceCandidate(msg: JSONObject, logger: PolyLogger?): SignalMessage? {
        val data = msg.optJSONObject("data")
        val candidate = data?.optString("candidate")?.takeIf { it.isNotEmpty() } ?: run {
            logger?.w("[voice] dropping malformed ICE candidate")
            return null
        }
        // sdpMid / sdpMLineIndex are optional; preserve absence as null (don't coerce to "" / 0).
        val sdpMid = data.optString("sdpMid").takeIf { data.has("sdpMid") && !data.isNull("sdpMid") && it.isNotEmpty() }
        val sdpMLineIndex = if (data.has("sdpMLineIndex") && !data.isNull("sdpMLineIndex")) {
            (data.opt("sdpMLineIndex") as? Number)?.toInt()
        } else {
            null
        }
        return SignalMessage.IceCandidate(candidate, sdpMid, sdpMLineIndex)
    }
}
