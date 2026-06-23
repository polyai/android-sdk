// Copyright PolyAI Limited

package ai.poly.messaging.internal.services

import ai.poly.messaging.ConnectionCloseEvent
import ai.poly.messaging.PolyError
import ai.poly.messaging.SessionErrorCode
import ai.poly.messaging.SessionState
import ai.poly.messaging.SessionStatus
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.internal.helpers.Clock
import ai.poly.messaging.internal.helpers.JwtValidator
import ai.poly.messaging.internal.helpers.SessionStore
import ai.poly.messaging.internal.helpers.d
import ai.poly.messaging.internal.helpers.i
import ai.poly.messaging.internal.ports.CreatedSession
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.SessionCreateContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Resume-or-create + token auth + persistence. Resumes
 * only if the stored session is within [sessionTimeoutSeconds] AND the stored token is
 * structurally valid; otherwise clears and creates fresh. Emits [SessionState].
 */
internal class SessionService(
    private val api: RestApiPort,
    private val store: SessionStore?,
    private val streamingEnabled: Boolean,
    private val platform: String,
    private val deviceType: String,
    private val sessionTimeoutSeconds: Int,
    private val logger: PolyLogger,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.SYSTEM,
) {
    // A buffered SharedFlow delivers EVERY update — a conflated StateFlow would drop transient
    // values (the back-to-back RESTORED -> ACTIVE on resume would never be observable). replay=1
    // keeps the new-subscriber-sees-current behavior; the buffer keeps intermediate states
    // deliverable. [current] holds the latest value for synchronous reads.
    private val _state = MutableSharedFlow<SessionState>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    var current: SessionState = SessionState(SessionStatus.UNKNOWN, isReady = false, isLoading = false)
        private set
    val state: SharedFlow<SessionState> = _state.asSharedFlow()

    init {
        _state.tryEmit(current)
    }

    private fun setState(next: SessionState) {
        current = next
        _state.tryEmit(next)
    }

    var lastSessionId: String? = null
        private set

    private var lastActivityMillis: Long = clock.nowMillis()

    // A 500ms-delayed connectionClosedAbnormally error is scheduled on a 1006 close,
    // cancelled by the next close/clearError; the isReady check no-ops it if the socket reopens first.
    private var errorDelayJob: Job? = null

    suspend fun resumeOrCreate(): CreatedSession {
        // Resume emits NO loading state — only createFresh sets isLoading=true.
        // A resume hit therefore emits exactly RESTORED then ACTIVE (no leading isLoading=true flicker).
        store?.load()?.let { stored ->
            val ageMillis = clock.nowMillis() - stored.lastActivityMillis
            val fresh = ageMillis < sessionTimeoutSeconds * 1000L
            val token = stored.accessToken
            if (fresh && token != null && JwtValidator.isStructurallyValid(token, clock.nowMillis())) {
                lastSessionId = stored.sessionId
                lastActivityMillis = clock.nowMillis()
                logger.i("[Session] resumed prior session")
                setState(SessionState(SessionStatus.RESTORED, isReady = false, isLoading = false, sessionId = stored.sessionId))
                setState(SessionState(SessionStatus.ACTIVE, isReady = false, isLoading = false, sessionId = stored.sessionId))
                return CreatedSession(sessionId = stored.sessionId, accessToken = token, wasResumed = true)
            }
            // Log WHY a stored session was rejected before creating fresh.
            if (!fresh) logger.i("Stored session past idle timeout — starting fresh")
            else logger.i("Stored access token missing or expired — starting fresh")
        }
        return createFresh()
    }

    /** Force a brand-new session (Start-New-Chat, or an invalid-session refetch — clears any stored one). */
    suspend fun refetch(): CreatedSession {
        // Sleep 300ms BEFORE re-creating the session (a trailing debounce that coalesces a burst of
        // invalid-session routes). Both the auto invalid-session path and the manual Start-New-Chat
        // path go through this delay.
        delay(REFETCH_DEBOUNCE_MS)
        return createFresh()
    }

    private suspend fun createFresh(): CreatedSession {
        // Emit isLoading=true here (preserving the current status/sessionId), on BOTH the
        // create-from-resume-miss path and the refetch path — but NOT on a resume hit.
        val cur = current
        setState(SessionState(status = cur.status, isReady = false, isLoading = true, sessionId = cur.sessionId))
        store?.clear()
        // Token/session acquisition can fail (bad key, network). The adapter throws the SPECIFIC PolyError;
        // failState maps it to the exact SessionErrorCode + the hasInvalidApiKey flag. Never throw out of
        // the background start() coroutine (which would crash the host app).
        val token = try {
            api.obtainAccessToken()
        } catch (e: PolyError) {
            return failState(e)
        }
        val created = try {
            api.createSession(token.token, SessionCreateContext(streamingEnabled, platform, deviceType))
        } catch (e: PolyError) {
            return failState(e)
        }
        lastSessionId = created.sessionId
        lastActivityMillis = clock.nowMillis()
        store?.save(created.sessionId, token.token, clock.nowMillis(), token.expiresAtMillis)
        logger.i("[Session] session created")
        setState(SessionState(SessionStatus.ACTIVE, isReady = false, isLoading = false, sessionId = created.sessionId))
        return created
    }

    /** A valid access token, refreshed if it's expiring soon — used before each (re)connect. */
    suspend fun currentAccessToken(): String? = runCatching { api.ensureAccessToken() }.getOrNull()

    /** True once the session has been idle past the timeout. Cold-launch aware via the persisted
     *  timestamp (max of in-memory and stored activity). */
    fun checkTimeout(): Boolean {
        if (lastSessionId == null) return false
        val storedActivity = store?.load()?.lastActivityMillis ?: 0L
        val newest = maxOf(lastActivityMillis, storedActivity)
        // STRICT greater-than: a session exactly at the timeout is NOT yet expired.
        return clock.nowMillis() - newest > sessionTimeoutSeconds * 1000L
    }

    private fun failState(error: PolyError): CreatedSession {
        // Map the error to a SessionErrorCode (a raw 401/403 → connectorValidationFailed; a parsed
        // connector body → its SPECIFIC code; anything else → unknown), then derive hasInvalidApiKey
        // from membership in the connector-validation set (NOT just "is Unauthorized"). The verbatim
        // error is carried so the facade rethrows exactly what was thrown.
        val errorCode: SessionErrorCode = when (error) {
            is PolyError.Auth.Unauthorized -> SessionErrorCode.CONNECTOR_VALIDATION_FAILED
            is PolyError.Session.SessionCreationFailed -> error.code
            else -> SessionErrorCode.UNKNOWN
        }
        // The PolyError catch is SILENT — it emits no log on the failure path; only the state is
        // updated. Do not log here.
        val invalidKey = errorCode in CONNECTOR_VALIDATION_ERRORS
        setState(SessionState(
            status = SessionStatus.UNKNOWN, isReady = false, isLoading = false,
            error = errorCode, hasInvalidApiKey = invalidKey,
        ))
        return CreatedSession(sessionId = "", accessToken = "", hasInvalidApiKey = invalidKey, failure = error)
    }

    private companion object {
        const val ABNORMAL_CLOSE_CODE = 1006
        const val ABNORMAL_CLOSE_ERROR_DELAY_MS = 500L
        const val REFETCH_DEBOUNCE_MS = 300L // trailing debounce before re-create

        // The connector-validation error set — these codes mark a bad API key / host.
        val CONNECTOR_VALIDATION_ERRORS = setOf(
            SessionErrorCode.ERROR_PARSING_REQUEST,
            SessionErrorCode.MISSING_AUTH_HEADERS,
            SessionErrorCode.CONNECTOR_LOOKUP_FAILED,
            SessionErrorCode.CONNECTOR_VALIDATION_FAILED,
        )
    }

    /** isReady=true — set on socket OPEN, not on SESSION_START. Also cancels the pending
     *  abnormal-close job and clears error (error=null here via the reconstruction). */
    fun markReady() {
        errorDelayJob?.cancel(); errorDelayJob = null
        val cur = current
        setState(SessionState(
            status = SessionStatus.ACTIVE, isReady = true, isLoading = false,
            sessionId = cur.sessionId,
        ))
    }

    /** On EVERY close, set isReady=false (preserving any latched error); SPECIFICALLY on a 1006 close
     *  schedule a 500ms job that latches connectionClosedAbnormally iff still no error and still not
     *  ready — i.e. only if reconnect did not recover within 500ms. */
    fun onSocketClose(event: ConnectionCloseEvent) {
        val cur = current
        // Reconstruct SessionState WITHOUT hasInvalidApiKey, so it resets to the false default (not preserved).
        setState(SessionState(
            status = cur.status, isReady = false, isLoading = cur.isLoading,
            sessionId = cur.sessionId, error = cur.error,
        ))
        if (event.code == ABNORMAL_CLOSE_CODE) {
            errorDelayJob?.cancel()
            errorDelayJob = scope.launch {
                delay(ABNORMAL_CLOSE_ERROR_DELAY_MS)
                val s = current
                if (s.error == null && !s.isReady) {
                    // The deferred reconstruction also omits hasInvalidApiKey (defaults false).
                    setState(SessionState(
                        status = s.status, isReady = false, isLoading = false,
                        sessionId = s.sessionId, error = SessionErrorCode.CONNECTION_CLOSED_ABNORMALLY,
                    ))
                }
            }
        }
    }

    /** Drop any latched session error (e.g. connectionClosedAbnormally). The Coordinator
     *  calls this on every valid non-heartbeat inbound message — a fresh message proves the link is healthy. */
    fun clearError() {
        val cur = current
        if (cur.error == null) return
        errorDelayJob?.cancel(); errorDelayJob = null
        setState(SessionState(
            status = cur.status, isReady = cur.isReady, isLoading = cur.isLoading,
            sessionId = cur.sessionId, error = null, hasInvalidApiKey = cur.hasInvalidApiKey,
        ))
    }

    fun markEnded(expired: Boolean) {
        logger.i("[Session] ended", mapOf("expired" to expired))
        val cur = current
        // Set error=null on end/expiry; the EXPIRED status + the Disconnected(sessionExpired)
        // event convey expiry, and ChatSession surfaces hasEnded from that event.
        setState(SessionState(
            status = if (expired) SessionStatus.EXPIRED else SessionStatus.ENDED,
            isReady = false, isLoading = false, sessionId = cur.sessionId,
            error = null,
        ))
        clearStored() // clear the persisted session + lastSessionId
    }

    /** Record real conversation activity (NOT heartbeats) — drives the idle-timeout check. */
    fun touch() {
        val now = clock.nowMillis()
        lastActivityMillis = now
        lastSessionId?.let { store?.touch(now) }
    }

    fun clearStored() {
        store?.clear()
        lastSessionId = null
    }
}
