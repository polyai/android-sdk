// Copyright PolyAI Limited

package ai.poly.messaging.internal.services

import ai.poly.messaging.ConnectionCloseEvent
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.helpers.d
import ai.poly.messaging.internal.helpers.i
import ai.poly.messaging.internal.helpers.w
import ai.poly.messaging.internal.ports.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the WebSocket connect + reconnection ladder:
 * close-code routing (1000 clean / 4000 client-replaced / 1006-1005-4002 reconnect /
 * 4003 network / 4001 + handshake-failure → invalid-session), exponential backoff with
 * jitter, cursor replay, and the terminal `.failed` state. Drivable with a fake [Transport]
 * over virtual time.
 */
internal class ConnectionService(
    private val transport: Transport,
    private val wsBaseUrl: String,
    private val scope: CoroutineScope,
    private val backoff: Backoff,
    private val logger: PolyLogger,
    private val clock: ai.poly.messaging.internal.helpers.Clock = ai.poly.messaging.internal.helpers.Clock.SYSTEM,
) {
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _invalidSession = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val invalidSession: SharedFlow<Unit> = _invalidSession.asSharedFlow()

    // A dedicated close-event stream (separate from statusChanges) that the Coordinator
    // observes to drive SessionService.onSocketClose on EVERY close — including 1006 transient closes
    // that route to reconnect (which never surface as a Closed status) and the terminal breaker.
    private val _closeEvents = MutableSharedFlow<ConnectionCloseEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val closeEvents: SharedFlow<ConnectionCloseEvent> = _closeEvents.asSharedFlow()

    var maxReconnectAttempts: Int = 10

    private var sessionId: String? = null
    private var accessToken: String? = null
    private var lastSequence: Int = 0
    private var reconnectAttempt: Int = 0
    private var invalidSessionReconnects: Int = 0
    private var shouldReconnect: Boolean = true
    private var currentAttemptOpened: Boolean = false
    private var lastCloseWasNetwork: Boolean = false
    private var reconnectJob: Job? = null
    private var connectionOpenedAt: Long = 0L
    // Monotonic counter bumped on every socket OPEN. The send-retry ladder uses it to resend a pending
    // message only when the connection has actually been re-established since the last attempt (a frame
    // already written to a still-live socket must not be re-sent — that duplicates it server-side).
    private var openEpoch: Long = 0L

    fun observe() {
        scope.launch { transport.openEvents.collect { onOpen() } }
        scope.launch { transport.closeEvents.collect { onClose(it) } }
        // Bridge a transport-level protocol/IO error (e.g. a binary frame on an open socket)
        // into a synthetic 1006 close so the reconnect ladder engages — and log it at WARN first
        // (`Transport error` is logged before synthesizing the close).
        scope.launch {
            transport.errors.collect {
                // A transport error only tells us the connection is broken if we currently believe it's
                // OPEN (e.g. a binary frame, or a send that failed on a live socket). While we're already
                // reconnecting / closed / terminally failed, a failed send — typically a queued
                // user-message retry hitting a down socket — tells us nothing new. Bridging it to a 1006
                // would schedule yet another reconnect and consume a reconnect-budget slot, so a handful
                // of offline sends would trip the terminal breaker and the connection could never recover
                // when the network returned. Ignore it unless we thought we were open.
                if (currentStatus() is ConnectionStatus.Open) {
                    logger.w("Transport error", mapOf("error" to (it.message ?: "")))
                    onClose(ConnectionCloseEvent(1006, "Transport error: ${it.message}", wasClean = false))
                } else {
                    logger.d("Transport error ignored — socket not open", mapOf("error" to (it.message ?: "")))
                }
            }
        }
    }

    suspend fun connectNow() {
        // reconnect() ALWAYS emits .connecting at the actual connect step; the Reconnecting(N) status
        // is emitted earlier, at schedule time (scheduleReconnect). So the observable sequence on a
        // reconnect is Reconnecting(N) → (backoff) → Connecting → Open, not a Reconnecting(N) that
        // persists straight through to Open.
        _status.value = ConnectionStatus.Connecting
        currentAttemptOpened = false
        // Reconnect with the SAME token the session was created with — the V2 backend binds the
        // session to that token, so a fresh token would be rejected (401). Token expiry is handled
        // upstream by the invalid-session → refetch path (new session + new token).
        logger.d("[Conn] connecting", mapOf("attempt" to reconnectAttempt))
        transport.connect(buildUrl())
    }

    fun setSessionId(id: String) {
        // connectToSession resets the replay cursor whenever the session id CHANGES
        // (isNewSession → lastSequence = 0). Resetting here — at the moment
        // the new id is installed — closes the race where a final old-socket event (e.g. the
        // CHAT_ENDED triggered by UserEndConversation during startNewSession) re-bumps the cursor
        // after an early reset, leaking the old session's cursor into the new session's connect URL.
        if (id != sessionId) lastSequence = 0
        sessionId = id
    }
    fun setAccessToken(token: String) { accessToken = token }
    fun updateLastSequence(seq: Int) { if (seq > lastSequence) lastSequence = seq }
    fun resetLastSequence() { lastSequence = 0 }
    // resetReconnectBudget resets only the reconnect attempt; the invalid-session counter is
    // cleared separately (on socket open / via notifyRefetchFailed).
    fun resetReconnectBudget() { reconnectAttempt = 0; shouldReconnect = true }
    fun cancelReconnect() { reconnectJob?.cancel(); reconnectJob = null }
    fun currentStatus(): ConnectionStatus = _status.value

    /** User-initiated close: cancel any reconnect, stop the ladder, emit
     *  Closing, then close the socket. The terminal Closed status follows from the transport's onClose
     *  (CLEAN branch), giving consumers the Open → Closing → Closed sequence on a user end. */
    suspend fun disconnect(code: Int = CLEAN, reason: String = "normal") {
        cancelReconnect()
        shouldReconnect = false
        _status.value = ConnectionStatus.Closing
        transport.disconnect(code, reason)
    }

    /** Suspend until the socket is Open, up to [timeoutMs]; returns true if it opened, false on timeout.
     *  Lets the send-retry ladder ride out a reconnect window
     *  instead of failing a frame that just needs the new socket to come up. */
    suspend fun waitForOpen(timeoutMs: Long): Boolean {
        if (currentStatus() is ConnectionStatus.Open) return true
        return withTimeoutOrNull(timeoutMs) { status.first { it is ConnectionStatus.Open }; true } ?: false
    }

    /** Millis since the current socket opened (0 if not open). Drives the 2h forced-reconnect cap. */
    fun connectionAgeMillis(): Long = if (connectionOpenedAt == 0L) 0L else clock.nowMillis() - connectionOpenedAt

    /** Force a reconnect by closing the live socket (network-lost / 2h-cap drivers). Uses 4002, a
     *  SENDABLE app code (reserved 1006 which OkHttp refuses to send) that routes through
     *  onClose → the EXPONENTIAL-backoff branch, not the network-poll branch. */
    fun dropConnectionForReconnect(reason: String) {
        if (currentStatus() !is ConnectionStatus.Open) return
        logger.d("[Conn] forced drop", mapOf("reason" to reason))
        scope.launch { runCatching { transport.disconnect(FORCED_DROP, reason) } }
    }

    /** A refetch that followed an invalid-session route failed — roll the budget back so a genuine
     *  transient refetch failure doesn't permanently consume an invalid-session attempt. */
    fun notifyRefetchFailed() {
        invalidSessionReconnects = 0
    }

    private fun onOpen() {
        currentAttemptOpened = true
        connectionOpenedAt = clock.nowMillis()
        openEpoch++
        // Reset the reconnect + invalid-session budgets on every socket OPEN.
        reconnectAttempt = 0
        invalidSessionReconnects = 0
        _status.value = ConnectionStatus.Open
        logger.i("[Conn] open")
    }

    /** Monotonic open-epoch (incremented on each socket OPEN); 0 before the first open. */
    fun currentOpenEpoch(): Long = openEpoch

    private fun onClose(event: ConnectionCloseEvent) {
        // handleClose clears connectionStartedAt first, so the 2h-cap reads age only for a LIVE
        // socket; connectionAgeMillis() then honours its "0 if not open" contract while closed.
        connectionOpenedAt = 0L
        val code = event.code
        logger.d("[Conn] socket closed", mapOf("code" to code, "reason" to event.reason))
        // Emit on closeEvents for EVERY close (before routing) so the Coordinator can drive
        // SessionService.onSocketClose — incl. 1006 closes that route to reconnect (never a Closed status).
        _closeEvents.tryEmit(event)
        val handshakeFailure = !currentAttemptOpened && code !in NON_HANDSHAKE_CODES
        when {
            // A 1000 clean close also tears down any already-scheduled reconnect job so a pending delayed
            // connectNow() can't fire after the clean close (cleared via disconnect → cancelReconnect).
            code == CLEAN -> { logger.i("Normal close — no reconnect"); shouldReconnect = false; cancelReconnect(); _status.value = ConnectionStatus.Closed(event) }
            code == CLIENT_REPLACED -> logger.d("Client replaced — ignoring") // intentional replace; ignore
            code == SESSION_UNKNOWN -> { logger.i("Server rejected session"); routeInvalidSession() }
            handshakeFailure -> { logger.i("Handshake failure detected", mapOf("code" to code)); routeInvalidSession() }
            // Suppress the Disconnected/Closed status on a reconnect-bound close and go
            // straight to Reconnecting (no spurious disconnect flicker).
            code == NETWORK_TRANSIENT -> { lastCloseWasNetwork = true; scheduleReconnect() }
            else -> { lastCloseWasNetwork = false; scheduleReconnect() }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectAttempt++
        if (maxReconnectAttempts in 1..(reconnectAttempt - 1)) {
            fail(PolyError.Transport.NetworkError("Max reconnect attempts exceeded"))
            return
        }
        // Emit Reconnecting(N) immediately at schedule time (not after the backoff delay).
        _status.value = ConnectionStatus.Reconnecting(reconnectAttempt)
        // Compute the delay from the attempt BEFORE incrementing, so the first reconnect waits
        // ~2^0=1s (then 2,4,…). reconnectAttempt was just incremented, so pass attempt-1.
        val delayMs = backoff.delayMillis(reconnectAttempt - 1, lastCloseWasNetwork)
        logger.i("[Conn] scheduling reconnect", mapOf("attempt" to reconnectAttempt, "delayMs" to delayMs))
        reconnectJob = scope.launch {
            delay(delayMs)
            connectNow()
        }
    }

    private fun routeInvalidSession() {
        invalidSessionReconnects++
        // Log the per-route line at INFO (only the terminal breaker trip WARNs, in fail()).
        logger.i("[Conn] invalid session — refetching", mapOf("attempt" to invalidSessionReconnects))
        if (invalidSessionReconnects > MAX_INVALID_SESSION_ATTEMPTS) {
            fail(PolyError.Session.SessionExpired)
            return
        }
        _invalidSession.tryEmit(Unit)
    }

    private fun fail(error: PolyError) {
        // Log the terminal breaker trip at WARN (not ERROR).
        logger.w("[Conn] terminal failure", mapOf("error" to error.debugDescription))
        shouldReconnect = false
        cancelReconnect()
        _status.value = ConnectionStatus.Failed(error)
        // Emit a synthetic 1006 close on BOTH terminal paths so the Coordinator's close observer
        // sees the terminal close (drives onSocketClose → the deferred connectionClosedAbnormally error).
        _closeEvents.tryEmit(ConnectionCloseEvent(ABNORMAL, error.debugDescription, wasClean = false))
    }

    private fun buildUrl(): String {
        // V2 auth is via query params (AWS API Gateway): auth_token + session_id (+ cursor on resume).
        // wsBaseUrl already includes the /ws path.
        val params = buildList {
            accessToken?.let { add("access_token=" + enc(it)) }
            sessionId?.let { add("session_id=" + enc(it)) }
            if (lastSequence > 0) add("cursor=$lastSequence")
        }
        return if (params.isEmpty()) wsBaseUrl else "$wsBaseUrl?${params.joinToString("&")}"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val CLEAN = 1000
        const val ABNORMAL = 1006 // synthesized for transport errors + the terminal breaker
        const val CLIENT_REPLACED = 4000
        const val SESSION_UNKNOWN = 4001
        const val NETWORK_TRANSIENT = 4003
        const val FORCED_DROP = 4002 // sendable code that routes to exponential backoff
        const val MAX_INVALID_SESSION_ATTEMPTS = 3
        val NON_HANDSHAKE_CODES = setOf(1000, 4000, 4001, 4003)
    }
}
