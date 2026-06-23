// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.flow.Flow

/**
 * The raw WebSocket transport, returned by [PolyMessagingClient.getConnection]. An
 * advanced escape hatch for custom analytics / proprietary frames — most apps use
 * [ChatSession] instead (narrowed public view).
 */
public interface Connection {
    /** Decoded inbound events (the same stream [PolyMessagingClient.events] is built on). */
    public val messages: Flow<MessagingEvent>

    /** Raw inbound text frames, before decoding. */
    public val rawFrames: Flow<String>

    /** Send a typed outgoing event. */
    public suspend fun send(event: OutgoingEvent)

    /**
     * Close the live socket with an explicit close code + reason, exercising the SDK's
     * close-code routing (1000 clean-terminal, 4001 invalid-session refetch, 4002 reconnect).
     * Note: OkHttp refuses to SEND the reserved codes 1001/1005/1006 — use 4002 to simulate a
     * transient drop.
     */
    public suspend fun disconnect(code: Int, reason: String)

    /**
     * Send a raw frame, bypassing delivery tracking / retry / `local_id` correlation
     * (no `MessagePending`/`MessageConfirmed` will follow). Throws [PolyError.Transport.NotConnected]
     * if the socket is not open.
     */
    @Throws(PolyError::class)
    public suspend fun sendRaw(data: String)
}

/**
 * Pluggable logger. Provide your own to route SDK logs; the default writes via
 * `android.util.Log`. **Never log API keys or session identifiers** — the SDK doesn't,
 * and custom loggers shouldn't either.
 */
public interface PolyLogger {
    public fun log(level: LogLevel, message: String, metadata: Map<String, Any?>?)
}
