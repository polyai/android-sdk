// Copyright PolyAI Limited

package ai.poly.messaging.internal.adapters

import ai.poly.messaging.ConnectionCloseEvent
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.OutgoingEvent
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.internal.ports.Transport
import ai.poly.messaging.internal.helpers.d
import ai.poly.messaging.internal.helpers.i
import ai.poly.messaging.internal.helpers.w
import ai.poly.messaging.internal.helpers.e
import ai.poly.messaging.internal.wire.WireDecoder
import ai.poly.messaging.internal.wire.WireEncoder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket transport over OkHttp. No protocol-level ping (keep-alive is the application
 * heartbeat); replace-on-connect tears down a stale socket before opening a new one, with a
 * stale-callback identity filter. Decodes inbound frames via [WireDecoder].
 */
internal class OkHttpWebSocketTransport(
    private val logger: PolyLogger,
    // No protocol-level ping — keep-alive is the application heartbeat only.
    private val client: OkHttpClient = OkHttpClient(),
) : Transport {

    private val decoder = WireDecoder(logger = logger)

    private val _messages = MutableSharedFlow<MessagingEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _batchEvents = MutableSharedFlow<List<MessagingEvent>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _rawFrames = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _open = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _close = MutableSharedFlow<ConnectionCloseEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _errors = MutableSharedFlow<PolyError>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val messages: Flow<MessagingEvent> = _messages.asSharedFlow()
    override val batchEvents: Flow<List<MessagingEvent>> = _batchEvents.asSharedFlow()
    override val rawFrames: Flow<String> = _rawFrames.asSharedFlow()
    override val openEvents: Flow<Unit> = _open.asSharedFlow()
    override val closeEvents: Flow<ConnectionCloseEvent> = _close.asSharedFlow()
    override val errors: Flow<PolyError> = _errors.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var opened = false

    override suspend fun connect(url: String) {
        // Replace-on-connect: cleanly tear down any in-flight socket first. Synthesize a 4000
        // "Replacing connection" close event BEFORE teardown (the old socket's later onClosed is dropped as
        // stale, so this synthetic emit is the only one) — drives onSocketClose → isReady=false during the
        // replace window. The wire-level close stays 4000 (OkHttp refuses the reserved 1001 going-away).
        webSocket?.let { stale ->
            logger.d("[WS] replacing existing socket", mapOf("code" to CLIENT_REPLACED))
            stale.close(CLIENT_REPLACED, "Replacing connection")
            _close.tryEmit(ConnectionCloseEvent(CLIENT_REPLACED, "Replacing connection", wasClean = true))
        }
        webSocket = null
        opened = false
        // Log the full WS URL (incl. access_token / session_id query params) at DEBUG.
        logger.d("Connecting to $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    override suspend fun disconnect(code: Int, reason: String) {
        webSocket?.close(code, reason)
        opened = false
    }

    override fun isOpen(): Boolean = opened

    override suspend fun send(event: OutgoingEvent) {
        logger.i("WS frame sending", mapOf("type" to (event::class.simpleName ?: "?"))) // log every frame at INFO
        // send() is fire-and-forget: a failure is EMITTED on the error stream (which ConnectionService
        // bridges to a synthetic 1006 close → reconnect), NOT thrown. All call sites use runCatching and the
        // retry ladder is timer/isPending-driven, so non-throwing + error-emitting drives recovery.
        try {
            val ws = webSocket ?: throw PolyError.Transport.NotConnected
            if (!ws.send(WireEncoder.encode(event))) throw PolyError.Transport.NotConnected
        } catch (e: PolyError) {
            _errors.tryEmit(e)
        } catch (t: Throwable) {
            _errors.tryEmit(PolyError.Transport.NetworkError(t.message ?: ""))
        }
    }

    override suspend fun sendRaw(data: String) {
        logger.i("WS frame sending (raw)")
        val ws = webSocket ?: throw PolyError.Transport.NotConnected
        if (!ws.send(data)) throw PolyError.Transport.NotConnected
    }

    private val listener = object : WebSocketListener() {
        // Ignore callbacks from a socket we've already replaced:
        // a late onClosed/onFailure from the OLD socket must not misroute the NEW attempt.
        private fun isStale(ws: WebSocket): Boolean = ws !== this@OkHttpWebSocketTransport.webSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isStale(webSocket)) return
            opened = true
            logger.i("[WS] socket open") // log WebSocket open at INFO
            _open.tryEmit(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isStale(webSocket)) return
            logger.i("WS frame received", mapOf("type" to frameType(text))) // log every frame at INFO
            _rawFrames.tryEmit(text)
            val decoded = decoder.decode(text)
            // EVENT_BATCH frames go through batchEvents (pre-scanned by the Coordinator); singles via messages.
            if (decoder.isBatchFrame(text)) _batchEvents.tryEmit(decoded) else decoded.forEach { _messages.tryEmit(it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (isStale(webSocket)) return
            // V2 sends only text frames; a binary frame is always a protocol error.
            // Surfaced via errors -> ConnectionService bridges it to a 1006 close to force reconnect.
            logger.w("[WS] binary frame rejected")
            _errors.tryEmit(PolyError.Transport.ProtocolError("Binary frames not supported"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isStale(webSocket)) return
            opened = false
            logger.i("[WS] socket closed", mapOf("code" to code, "reason" to reason)) // log WebSocket close at INFO
            _close.tryEmit(ConnectionCloseEvent(code, reason, wasClean = code == NORMAL || code == CLIENT_REPLACED))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isStale(webSocket)) return
            opened = false
            // Classify the failure (transient network error vs handshake-reject):
            //  - got an HTTP response that wasn't a 101 upgrade  -> genuine handshake reject (1006)
            //  - a transient network exception (offline/DNS/timeout) -> 4003 (keeps the session,
            //    spares the invalid-session budget so an offline retry doesn't get misrouted)
            //  - anything else -> 1006
            val code = when {
                response != null && response.code != 101 -> ABNORMAL
                isTransientNetwork(t) -> NETWORK_TRANSIENT
                else -> ABNORMAL
            }
            // The classified close carries the failure; ConnectionService routes it (no separate
            // error emit, which would double-trigger via the errors->close bridge).
            logger.w("[WS] socket failure", mapOf("error" to (t.message ?: ""), "code" to code))
            _close.tryEmit(ConnectionCloseEvent(code, t.message ?: "", wasClean = false))
        }
    }

    private fun frameType(text: String): String =
        runCatching { org.json.JSONObject(text).optString("type") }.getOrDefault("")

    private fun isTransientNetwork(t: Throwable): Boolean {
        if (t is java.net.UnknownHostException ||
            t is java.net.SocketTimeoutException ||
            t is java.net.ConnectException ||
            t is java.net.NoRouteToHostException ||
            t is java.net.PortUnreachableException
        ) {
            return true
        }
        val m = t.message?.lowercase() ?: return false
        return "unreachable" in m || "network is down" in m || "connection abort" in m ||
            "timeout" in m || "timed out" in m || "connection reset" in m || "no address" in m
    }

    private companion object {
        const val NORMAL = 1000
        const val ABNORMAL = 1006
        const val NETWORK_TRANSIENT = 4003
        const val CLIENT_REPLACED = 4000
    }
}
