// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.i
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.SignalingTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The WebRTC-gateway signaling WebSocket over OkHttp. `connect` suspends until the socket is open
 * (or fails/times out). Inbound frames flow on `incoming`; an unexpected close/failure emits on
 * `closed`. The transport itself holds a single connection — the coordinator drives reconnect (with
 * backoff) by calling `connect` again, since each gateway frame is routed by `sessionId`.
 */
internal class OkHttpSignalingTransport(
    private val logger: PolyLogger,
    private val client: OkHttpClient = OkHttpClient(),
    private val connectTimeoutMs: Long = 10_000,
) : SignalingTransport {

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val incoming: Flow<String> = _incoming.asSharedFlow()
    override val closed: Flow<Unit> = _closed.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var closedByUs = false

    override suspend fun connect(url: String) {
        closedByUs = false
        val opened = CompletableDeferred<Unit>()
        logger.d("[voice] connecting signaling socket")
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                logger.i("[voice] signaling socket open")
                opened.complete(Unit)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                _incoming.tryEmit(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(NORMAL, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                logger.i("[voice] signaling socket closed", mapOf("code" to code))
                if (!closedByUs) _closed.tryEmit(Unit)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                logger.w("[voice] signaling socket failure", mapOf("error" to (t.message ?: "")))
                if (!opened.isCompleted) opened.completeExceptionally(t)
                if (!closedByUs) _closed.tryEmit(Unit)
            }
        }
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        try {
            withTimeout(connectTimeoutMs) { opened.await() }
        } catch (t: Throwable) {
            close()
            throw PolyError.Voice.SignalingFailed("signaling connect failed: ${t.message ?: "timeout"}")
        }
    }

    override fun send(text: String): Boolean = webSocket?.send(text) ?: false

    override fun close() {
        closedByUs = true
        webSocket?.close(NORMAL, null)
        webSocket = null
    }

    private companion object {
        const val NORMAL = 1000
    }
}
