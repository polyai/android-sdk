// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.VoiceSessionLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * The voice-session WebSocket on the messaging host. Opens, waits for `EVENT_TYPE_SESSION_START`
 * (bare or inside an `EVENT_TYPE_EVENT_BATCH`), then sends `EVENT_TYPE_LINK_TO_WEBRTC_CONVERSATION`
 * to bind the call sid to the session.
 * `open` suspends until the link frame has been sent.
 *
 * @param wsUrl builds the messaging WS URL (with session_id + auth_token) for a session.
 */
internal class OkHttpVoiceSessionLink(
    private val wsUrl: (sessionId: String, token: String) -> String,
    private val logger: PolyLogger,
    private val client: OkHttpClient = OkHttpClient(),
    private val handshakeTimeoutMs: Long = 15_000,
) : VoiceSessionLink {

    @Volatile private var webSocket: WebSocket? = null

    override suspend fun open(token: String, sessionId: String, callSid: String) {
        val linked = CompletableDeferred<Unit>()
        logger.d("[voice] opening voice-session socket")
        val listener = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (linked.isCompleted) return
                if (hasSessionStart(text)) {
                    logger.d("[voice] voice session started — linking to webrtc call")
                    val sent = ws.send(
                        JSONObject()
                            .put("type", "EVENT_TYPE_LINK_TO_WEBRTC_CONVERSATION")
                            .put("payload", JSONObject().put("call_sid", callSid))
                            .toString(),
                    )
                    if (sent) linked.complete(Unit) else linked.completeExceptionally(IllegalStateException("link frame send failed"))
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(NORMAL, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!linked.isCompleted) linked.completeExceptionally(IllegalStateException("voice session closed before handshake"))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!linked.isCompleted) linked.completeExceptionally(t)
            }
        }
        webSocket = client.newWebSocket(Request.Builder().url(wsUrl(sessionId, token)).build(), listener)
        try {
            withTimeout(handshakeTimeoutMs) { linked.await() }
        } catch (t: Throwable) {
            logger.w("[voice] voice-session handshake failed", mapOf("error" to (t.message ?: "")))
            close()
            throw PolyError.Voice.SignalingFailed("voice session link failed: ${t.message ?: "timeout"}")
        }
    }

    override fun close() {
        // cancel() rather than a graceful close(NORMAL): teardown must not block waiting on an
        // unresponsive peer — the handshake-timeout path is exactly when the server isn't responding.
        webSocket?.cancel()
        webSocket = null
    }

    private fun hasSessionStart(text: String): Boolean {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (msg.optString("type") == SESSION_START) return true
        if (msg.optString("type") == EVENT_BATCH) {
            val events = msg.optJSONObject("payload")?.optJSONArray("events") ?: return false
            for (i in 0 until events.length()) {
                if (events.optJSONObject(i)?.optString("type") == SESSION_START) return true
            }
        }
        return false
    }

    private companion object {
        const val NORMAL = 1000
        const val SESSION_START = "EVENT_TYPE_SESSION_START"
        const val EVENT_BATCH = "EVENT_TYPE_EVENT_BATCH"
    }
}
