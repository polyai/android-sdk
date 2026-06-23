// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.Clock
import ai.poly.messaging.internal.ports.AccessToken
import ai.poly.messaging.internal.ports.CreatedSession
import ai.poly.messaging.internal.ports.ForegroundPort
import ai.poly.messaging.internal.ports.NetworkStatePort
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.SessionCreateContext
import ai.poly.messaging.internal.ports.Transport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory transport double. */
internal class FakeTransport : Transport {
    val sent = mutableListOf<OutgoingEvent>()
    val sentRaw = mutableListOf<String>()
    val connectUrls = mutableListOf<String>()
    val disconnectCalls = mutableListOf<Pair<Int, String>>()

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

    private var open = false

    override fun isOpen(): Boolean = open
    override suspend fun connect(url: String) { connectUrls += url }
    override suspend fun disconnect(code: Int, reason: String) { open = false; disconnectCalls += code to reason }
    override suspend fun send(event: OutgoingEvent) { if (!open) throw PolyError.Transport.NotConnected; sent += event }
    override suspend fun sendRaw(data: String) { if (!open) throw PolyError.Transport.NotConnected; sentRaw += data }

    // ---- test drivers ----
    fun simulateOpen() { open = true; _open.tryEmit(Unit) }
    fun simulateClose(code: Int, reason: String = "", wasClean: Boolean = false) { open = false; _close.tryEmit(ConnectionCloseEvent(code, reason, wasClean)) }
    fun simulateMessage(event: MessagingEvent) { _messages.tryEmit(event) }
    fun simulateBatch(events: List<MessagingEvent>) { _batchEvents.tryEmit(events) }
}

/** REST double. Mints a structurally-valid JWT. */
internal class FakeRestApi(
    var invalidKey: Boolean = false,
    var invalidKeyCode: SessionErrorCode = SessionErrorCode.CONNECTOR_VALIDATION_FAILED,
) : RestApiPort {
    var tokenCalls = 0
    var createCalls = 0
    var lastContext: SessionCreateContext? = null

    override suspend fun obtainAccessToken(): AccessToken {
        tokenCalls++
        return AccessToken(TEST_JWT, Long.MAX_VALUE)
    }

    override suspend fun ensureAccessToken(): String {
        tokenCalls++
        return TEST_JWT
    }

    override suspend fun createSession(token: String, context: SessionCreateContext): CreatedSession {
        createCalls++
        lastContext = context
        // Mirror the real adapter: an invalid key/host THROWS a connector-validation error (not a flag).
        // SessionService.failState maps the thrown code to hasInvalidApiKey.
        if (invalidKey) throw PolyError.Session.SessionCreationFailed(invalidKeyCode)
        return CreatedSession(sessionId = "sess-1", accessToken = token)
    }

    companion object {
        // header.payload.signature with payload {"alg":"none"} and no exp (non-expiring).
        const val TEST_JWT = "eyJhbGciOiJub25lIn0.e30.sig"
    }
}

/** Connectivity double — drive offline/online transitions in resilience tests. */
internal class FakeNetworkMonitor(initial: Boolean = true) : NetworkStatePort {
    private val _isOnline = MutableStateFlow(initial)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    var started = false
    var stopped = false
    override fun start() { started = true }
    override fun stop() { stopped = true }
    fun setOnline(value: Boolean) { _isOnline.value = value }
}

/** Foreground double — emit a foreground transition in lifecycle tests. */
internal class FakeForeground : ForegroundPort {
    private val _foreground = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val foreground: SharedFlow<Unit> = _foreground.asSharedFlow()
    var started = false
    override fun start() { started = true }
    override fun stop() {}
    fun emitForeground() { _foreground.tryEmit(Unit) }
}

/** A clock whose time can be advanced by hand (idle-timeout + 2h-cap tests). */
internal class MutableClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}
