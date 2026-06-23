// Copyright PolyAI Limited

package ai.poly.messaging.internal.ports

import ai.poly.messaging.Connection
import ai.poly.messaging.ConnectionCloseEvent
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.PolyError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Two-step auth result. */
internal data class AccessToken(val token: String, val expiresAtMillis: Long)

/** Body context for `POST /sessions`. */
internal data class SessionCreateContext(
    val streamingEnabled: Boolean,
    val platform: String,
    val deviceType: String,
)

/** Result of creating (or resuming) a session. [failure] is set when auth/create failed. */
internal data class CreatedSession(
    val sessionId: String,
    val accessToken: String,
    val hasInvalidApiKey: Boolean = false,
    val failure: PolyError? = null,
    /** True when an existing session was resumed (the agent has already joined — don't re-request). */
    val wasResumed: Boolean = false,
)

/** REST port (token + session); lets tests swap a fake. */
internal interface RestApiPort {
    suspend fun obtainAccessToken(): AccessToken

    /** A valid access token, refreshing if the cached one is missing or expiring soon (300s). */
    suspend fun ensureAccessToken(): String

    suspend fun createSession(token: String, context: SessionCreateContext): CreatedSession
}

/** Connectivity source (offline/online). `NetworkMonitor` implements it; a fake drives resilience tests. */
internal interface NetworkStatePort {
    val isOnline: StateFlow<Boolean>
    fun start()
    fun stop()
}

/** App-foreground source. `AppLifecycleObserver` implements it; a fake drives lifecycle tests. */
internal interface ForegroundPort {
    val foreground: SharedFlow<Unit>
    fun start()
    fun stop()
}

/**
 * The full internal transport surface. Extends the public [Connection] (narrow raw-frame
 * API) with the lifecycle streams the [ConnectionService] needs.
 * A `FakeTransport` implements this for deterministic resilience tests.
 */
internal interface Transport : Connection {
    val openEvents: Flow<Unit>
    val closeEvents: Flow<ConnectionCloseEvent>
    val errors: Flow<PolyError>

    /** EVENT_BATCH frames, delivered as a unit so the Coordinator can pre-scan them. */
    val batchEvents: Flow<List<MessagingEvent>>

    suspend fun connect(url: String)
    fun isOpen(): Boolean
}
