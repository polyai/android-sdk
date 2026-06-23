// Copyright PolyAI Limited

package ai.poly.messaging.internal.services

import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.Envelope
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.OutgoingEvent
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.SessionEndPayload
import ai.poly.messaging.SessionErrorCode
import ai.poly.messaging.SessionState
import ai.poly.messaging.SessionStatus
import ai.poly.messaging.SystemMessageLevel
import ai.poly.messaging.TypingState
import ai.poly.messaging.internal.helpers.Clock
import ai.poly.messaging.internal.helpers.d
import ai.poly.messaging.internal.helpers.e
import ai.poly.messaging.internal.helpers.i
import ai.poly.messaging.internal.helpers.w
import ai.poly.messaging.internal.ports.ForegroundPort
import ai.poly.messaging.internal.ports.NetworkStatePort
import ai.poly.messaging.internal.ports.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The central orchestrator. Wires session → connection → chat →
 * heartbeat plus the resilience drivers: network-lost/restored, app-foreground, idle-timeout, a
 * 2-hour forced-reconnect cap, per-message send retry, typing throttle + auto-STOPPED, and
 * graceful-close stream flush.
 */
internal class Coordinator(
    private val transport: Transport,
    private val sessionService: SessionService,
    private val connectionService: ConnectionService,
    private val chatService: ChatService,
    private val heartbeatService: HeartbeatService,
    private val logger: PolyLogger,
    private val scope: CoroutineScope,
    private val networkMonitor: NetworkStatePort? = null,
    private val appLifecycle: ForegroundPort? = null,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val _events = MutableSharedFlow<MessagingEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<MessagingEvent> = _events.asSharedFlow()
    val connectionStatus: StateFlow<ConnectionStatus> get() = connectionService.status
    val sessionState: SharedFlow<SessionState> get() = sessionService.state

    private var started = false
    private var agentJoinRequested = false
    private var idleTimeoutHandled = false
    private var lastOnline: Boolean? = null

    /** Set when the initial start() fails; rethrown to the caller of send/sendTyping/resume/startNewSession. */
    @Volatile var startFailure: PolyError? = null
        private set

    private val retryJobs = HashMap<String, Job>()
    private var lastTypingStartedAtMillis = 0L
    private var typingStopJob: Job? = null
    private var lastRefetchAtMillis = 0L
    private var refetchInFlight = false

    suspend fun start() {
        if (started) return
        started = true
        logger.i("[Coordinator] starting")

        // NOTE: do NOT re-mint the access token on reconnect. The V2 backend binds a session to
        // the exact token it was created with, so reconnecting with a fresh token → 401. A token
        // that has genuinely expired is handled by the invalid-session → refetch path (which creates
        // a new session WITH a new token). Verified via curl/websocket against the dev backend.
        connectionService.observe()
        scope.launch { transport.messages.collect { handleInbound(it) } }
        scope.launch { transport.batchEvents.collect { handleBatch(it) } }
        scope.launch { heartbeatService.ticks.collect { onHeartbeatTick() } }
        scope.launch { connectionService.invalidSession.collect { onInvalidSession() } }
        scope.launch { connectionService.status.collect { bridgeStatus(it) } }
        // SessionService.onSocketClose is driven off a dedicated close-event stream (not the status),
        // so a 1006 transient close (which routes to reconnect, never a Closed status) still gets the
        // isReady=false + 500ms deferred connectionClosedAbnormally treatment.
        scope.launch { connectionService.closeEvents.collect { sessionService.onSocketClose(it) } }
        observeNetwork()
        observeLifecycle()

        val created = sessionService.resumeOrCreate()
        // Rethrow the VERBATIM start error to send/sendTyping/resume/startNewSession callers; carry
        // the actual PolyError (e.g. sessionCreationFailed(connectorLookupFailed)) rather than normalizing.
        if (created.hasInvalidApiKey || created.failure != null) {
            startFailure = created.failure ?: PolyError.Auth.Unauthorized
            return
        }

        idleTimeoutHandled = false
        // The EVENT_BATCH pre-scan (handleBatch) sets agentJoinRequested=true if the server replays
        // AGENT_JOINED on resume — so we request the join on a fresh session but not on a resume that
        // already includes it (handleBatch; avoids a DUPLICATE greeting).
        agentJoinRequested = false
        connectionService.setSessionId(created.sessionId)
        connectionService.setAccessToken(created.accessToken)
        connectionService.connectNow()
    }

    suspend fun send(text: String) {
        val prepared = chatService.prepareUserMessage(text)
        prepared.events.forEach { _events.tryEmit(it) }
        prepared.outgoing?.let { out ->
            sessionService.touch() // refresh the idle timer at send time
            // Manual-retry model: only put the frame on the wire when the socket is actually open. We do
            // NOT auto-resend a message on the next reconnect (that risked duplicate delivery — a message
            // could be delivered+answered, then re-sent and answered again). If it can't be confirmed it
            // is marked Failed → "Tap to retry" and the user resends explicitly.
            val open = connectionService.currentStatus() is ConnectionStatus.Open
            if (open) runCatching { transport.send(out) }
            trackDelivery(prepared.draftId, sentEpoch = if (open) connectionService.currentOpenEpoch() else 0L)
        }
    }

    suspend fun sendTyping() {
        // Guard on .open BEFORE touching the throttle, sending STARTED, or scheduling the tail.
        if (connectionService.currentStatus() !is ConnectionStatus.Open) return
        val now = clock.nowMillis()
        // The throttle is boundary-INCLUSIVE (>=): a tick at exactly typingThrottleSeconds re-sends STARTED.
        if (now - lastTypingStartedAtMillis >= TYPING_THROTTLE_MS) {
            lastTypingStartedAtMillis = now
            runCatching { transport.send(OutgoingEvent.UserTyping(TypingState.STARTED)) }
        }
        typingStopJob?.cancel()
        typingStopJob = scope.launch {
            delay(TYPING_STOP_MS)
            // emitTypingStopped resets the STARTED latch UNCONDITIONALLY (before the .open guard) so the
            // next sendTyping always re-emits STARTED to bracket the STOPPED.
            lastTypingStartedAtMillis = 0L
            if (connectionService.currentStatus() is ConnectionStatus.Open) {
                runCatching { transport.send(OutgoingEvent.UserTyping(TypingState.STOPPED)) }
            }
        }
    }

    suspend fun end(reason: String?) {
        // Do NOT cancel the send-retry ladder in end() — the disconnect makes resends no-op.
        if (connectionService.currentStatus() is ConnectionStatus.Open) {
            runCatching { transport.send(OutgoingEvent.UserEndConversation) }
            // Route user-end through ConnectionService.disconnect (emits Closing → Closed + cancels
            // reconnect), not transport.disconnect directly — so consumers see Open → Closing → Closed.
            runCatching { connectionService.disconnect(1000, "User ended session") }
        } else {
            // WS already closed — surface a disconnected so consumers can show "couldn't end" UX.
            _events.tryEmit(MessagingEvent.Disconnected(PolyError.Session.SessionEnded("Connection closed abnormally")))
        }
        // ALWAYS end the session + emit SessionEnd afterwards, on BOTH paths.
        sessionService.markEnded(expired = false)
        chatService.chatEnded = true
        _events.tryEmit(MessagingEvent.SessionEnd(Envelope("", null, clock.nowMillis(), null), SessionEndPayload(reason)))
    }

    suspend fun resume() {
        if (!started) {
            start()
            return
        }
        // Already started. If the connection terminally failed (reconnect ladder exhausted →
        // ConnectionStatus.Failed), re-establish it against the SAME session without resetting the
        // transcript. Previously this was just `if (!started) start()` — a no-op once started — so the
        // terminal-error "Try Again" (client.resume()) could never recover a dead connection; only
        // startNewSession() / a fresh client could. A stale session/token falls through to the existing
        // reconnect → invalid-session → refetch path; a successful reopen emits Connected, which clears
        // the consumer's failureReason and dismisses the terminal error screen.
        if (connectionService.currentStatus() is ConnectionStatus.Failed) {
            logger.i("[Coordinator] resume: re-establishing terminally-failed connection")
            connectionService.resetReconnectBudget()
            connectionService.connectNow()
        }
    }

    suspend fun startNewSession() {
        // Only send UserEndConversation when the socket is .open; on a closed/connecting socket it
        // sends nothing (rather than queueing an end frame against the old-session socket).
        if (connectionService.currentStatus() is ConnectionStatus.Open) {
            runCatching { transport.send(OutgoingEvent.UserEndConversation) } // end the prior session server-side
        }
        chatService.resetChat(isResume = false)
        connectionService.resetLastSequence()
        connectionService.cancelReconnect()
        cancelAllRetries()
        agentJoinRequested = false
        val created = sessionService.refetch()
        if (created.hasInvalidApiKey || created.failure != null) {
            // startNewSession surfaces a FIXED sessionCreationFailed(unknown) for a failed Start-New-Chat
            // — NOT the verbatim error — and rolls back the invalid-session budget via
            // the manual-refetch failure path (notifyRefetchFailed).
            logger.e("[Coordinator] start-new-session failed")
            connectionService.notifyRefetchFailed()
            _events.tryEmit(MessagingEvent.Disconnected(PolyError.Session.SessionCreationFailed(SessionErrorCode.UNKNOWN)))
            return
        }
        connectionService.setSessionId(created.sessionId)
        connectionService.setAccessToken(created.accessToken)
        connectionService.resetReconnectBudget()
        connectionService.connectNow()
    }

    suspend fun destroy() {
        heartbeatService.stop()
        cancelAllRetries()
        typingStopJob?.cancel()
        networkMonitor?.stop()
        appLifecycle?.stop()
        runCatching { transport.disconnect(1000, "client shutdown") }
        scope.cancel()
    }

    fun getConnection(): Transport = transport

    // ---- inbound routing ----

    /** A replayed/batched group of events. Pre-scan for AGENT_JOINED (handleBatch) so a resume
     *  that already includes the join doesn't trigger a duplicate RequestPolyAgentJoin, then route each. */
    private fun handleBatch(events: List<MessagingEvent>) {
        if (events.any { it is MessagingEvent.AgentJoined }) agentJoinRequested = true
        events.forEach { handleInbound(it) }
    }

    private fun handleInbound(event: MessagingEvent) {
        if (event !is MessagingEvent.Heartbeat) logger.d("[Event] " + (event::class.simpleName ?: "?"))
        event.envelope?.sequence?.let { connectionService.updateLastSequence(it) }
        // Set agentJoinRequested=true on ANY agentJoined — batched OR standalone — so a later
        // SESSION_START (e.g. a single-frame resume replay) doesn't re-send a DUPLICATE RequestPolyAgentJoin.
        if (event is MessagingEvent.AgentJoined) agentJoinRequested = true
        for (out in chatService.handleInbound(event)) {
            _events.tryEmit(out)
            when (out) {
                is MessagingEvent.SessionStart -> onSessionStart(out)
                is MessagingEvent.SessionEnd -> sessionService.markEnded(expired = false)
                is MessagingEvent.MessageConfirmed -> retryJobs.remove(out.draftId)?.cancel()
                else -> {}
            }
        }
        // ChatService auto-recovery: an error-level SYSTEM_MESSAGE whose
        // text reports the conversation/session is gone re-sends RequestPolyAgentJoin to restart the
        // conversation server-side. The message is lowercased and matched against the three substrings.
        if (event is MessagingEvent.SystemMessage && event.payload.level == SystemMessageLevel.ERROR) {
            val msg = event.payload.message.lowercase()
            if (msg.contains("conversation not found") ||
                msg.contains("conversation id not found") ||
                msg.contains("unable to start a conversation")
            ) {
                logger.i("Auto-recovering from system error", mapOf("message" to event.payload.message))
                scope.launch { runCatching { transport.send(OutgoingEvent.RequestPolyAgentJoin) } }
            }
        }
        // Clear any latched session error (e.g. connectionClosedAbnormally) on every valid
        // non-heartbeat inbound message — a fresh message proves the link is healthy. Heartbeats are
        // transport keep-alives, not real activity, so they must NOT mask a real error.
        if (event !is MessagingEvent.Heartbeat) sessionService.clearError()
        // Real conversation activity (not heartbeats) refreshes the idle timer. Touches on
        // agent / agent-chunk / live-agent / user messages only.
        if (isActivity(event)) {
            sessionService.touch()
        }
    }

    private fun isActivity(event: MessagingEvent): Boolean = when (event) {
        is MessagingEvent.AgentMessage, is MessagingEvent.AgentMessageChunk,
        is MessagingEvent.LiveAgentMessage, is MessagingEvent.UserMessage,
        -> true
        else -> false
    }

    private fun onSessionStart(event: MessagingEvent.SessionStart) {
        val caps = event.payload.capabilities
        caps.maxReconnectAttempts?.let { connectionService.maxReconnectAttempts = it }
        heartbeatService.applyServerInterval(caps.heartbeatIntervalSeconds)
        // (the reconnect budget reset + isReady are handled on socket OPEN now.)
        logger.i("[Coordinator] session ready")
        requestAgentJoinIfNeeded()
    }

    private fun requestAgentJoinIfNeeded() {
        if (agentJoinRequested) return
        agentJoinRequested = true
        scope.launch { runCatching { transport.send(OutgoingEvent.RequestPolyAgentJoin) } }
    }

    // ---- heartbeat tick: keep-alive + 2h cap + idle expiry ----

    private fun onHeartbeatTick() {
        // Order: send the heartbeat (if open) FIRST, then the idle-timeout check, then the 2h cap.
        if (connectionService.currentStatus() is ConnectionStatus.Open) {
            scope.launch { runCatching { transport.send(OutgoingEvent.Heartbeat) } }
        }
        if (sessionService.checkTimeout()) {
            handleIdleTimeout()
            return
        }
        if (connectionService.connectionAgeMillis() > MAX_CONNECTION_DURATION_MS) { // strict > at the 2h boundary
            logger.i("[Coordinator] 2h cap — recycling socket")
            connectionService.dropConnectionForReconnect("max connection duration")
        }
    }

    private fun handleIdleTimeout() {
        if (idleTimeoutHandled) return
        idleTimeoutHandled = true
        logger.i("[Coordinator] idle timeout — session expired")
        // Cancel reconnect, resetChat, endSession(.expired), emit disconnected(.sessionExpired).
        // It does NOT disconnect the socket or stop the heartbeat (the server closes the idle socket).
        connectionService.cancelReconnect()
        cancelAllRetries()
        chatService.resetChat(isResume = false)
        sessionService.markEnded(expired = true)
        _events.tryEmit(MessagingEvent.Disconnected(PolyError.Session.SessionExpired))
    }

    // ---- invalid-session refetch ----

    private fun onInvalidSession() {
        // Coalesce a burst of invalid-session routes within 300ms into a single refetch.
        val now = clock.nowMillis()
        if (refetchInFlight || now - lastRefetchAtMillis < REFETCH_DEBOUNCE_MS) return
        lastRefetchAtMillis = now
        refetchInFlight = true
        scope.launch {
            try {
                // The refetch creates a fresh session — reset chat state + agent-join (resetChat).
                chatService.resetChat(isResume = false)
                agentJoinRequested = false
                val created = runCatching { sessionService.refetch() }.getOrNull()
                if (created == null || created.hasInvalidApiKey || created.failure != null) {
                    logger.e("[Coordinator] invalid-session refetch failed")
                    connectionService.notifyRefetchFailed()
                    return@launch
                }
                connectionService.setSessionId(created.sessionId)
                connectionService.setAccessToken(created.accessToken)
                connectionService.resetLastSequence() // new session → reset the cursor (isNewSession)
                connectionService.resetReconnectBudget() // fresh connect → Connecting, not Reconnecting
                connectionService.connectNow()
            } finally {
                refetchInFlight = false
            }
        }
    }

    // ---- status bridge: connection events + heartbeat gating + clean-close flush ----

    private fun bridgeStatus(status: ConnectionStatus) {
        when (status) {
            is ConnectionStatus.Open -> {
                _events.tryEmit(MessagingEvent.Connected)
                sessionService.markReady() // set isReady on socket OPEN
                sessionService.touch() // refresh the idle timer on every (re)connect
                heartbeatService.start() // (re)start the keep-alive timer for this connection
                // (no bulk resend-on-open — relies solely on the per-message retry ladder.)
            }
            is ConnectionStatus.Reconnecting -> {
                heartbeatService.stop() // stop the heartbeat on close and restart it on open
                _events.tryEmit(MessagingEvent.Reconnecting(status.attempt))
            }
            is ConnectionStatus.Closed -> {
                heartbeatService.stop()
                // isReady=false is now driven by connectionService.closeEvents → sessionService.onSocketClose
                // (which fires on EVERY close, incl. the 1006 reconnect-bound ones the status bridge never sees).
                // A graceful 1000 close without SESSION_END: flush any in-flight stream, then latch
                // chatEnded (idempotent vs a prior SESSION_END), as in onCleanClose.
                // Guard: skip the latch when a new connection is already
                // underway — startNewSession disconnects the old socket with 1000 and the late close can race
                // the new session's resetChat, re-latching chatEnded on the fresh session. For a normal clean
                // close the live status is still Closed (no new connect), so this latches as before.
                val newConnectionUnderway = connectionService.currentStatus().let {
                    it is ConnectionStatus.Connecting || it is ConnectionStatus.Open
                }
                if (status.event?.code == 1000 && !newConnectionUnderway) {
                    chatService.flushStreams().forEach { _events.tryEmit(it) }
                    chatService.chatEnded = true
                }
                _events.tryEmit(MessagingEvent.Disconnected(null))
            }
            is ConnectionStatus.Failed -> {
                heartbeatService.stop()
                // isReady=false via onSocketClose (the synthetic terminal 1006 close from ConnectionService.fail).
                // Do NOT mirror .failed onto the events stream — consumers read connectionStatus.
            }
            else -> {}
        }
    }

    // ---- send delivery tracking (manual retry — no auto-resend) ----

    private fun trackDelivery(draftId: String, sentEpoch: Long) {
        retryJobs[draftId]?.cancel()
        retryJobs[draftId] = scope.launch {
            // Wait for the server echo — it confirms the message and cancels this job (see the
            // MessageConfirmed handling in the inbound observer). If the echo doesn't arrive we mark the
            // message Failed so the UI offers "Tap to retry"; we deliberately do NOT auto-resend (that
            // risked duplicate delivery). Give up — and fail — as soon as the frame can't be acked:
            //   • it never reached an open socket (sentEpoch == 0) → fail immediately,
            //   • the connection drops or reconnects while still pending (the in-flight frame may be lost),
            //   • or the confirm window elapses on an otherwise-healthy socket (the server never echoed).
            if (sentEpoch != 0L) {
                withTimeoutOrNull(SEND_CONFIRM_TIMEOUT_MS) {
                    while (chatService.isPending(draftId)) {
                        val status = connectionService.currentStatus()
                        if (status !is ConnectionStatus.Open ||
                            connectionService.currentOpenEpoch() != sentEpoch
                        ) {
                            break // the connection we sent on went away — the frame may never be acked
                        }
                        delay(SEND_POLL_MS)
                    }
                }
            }
            if (chatService.isPending(draftId)) {
                logger.w("[Chat] message not confirmed — marking failed (tap to retry)")
                chatService.failPending(draftId)?.let { _events.tryEmit(it) }
            }
            retryJobs.remove(draftId)
        }
    }

    private fun cancelAllRetries() {
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
    }

    // ---- network + lifecycle drivers ----

    private fun observeNetwork() {
        val monitor = networkMonitor ?: return
        monitor.start()
        scope.launch {
            monitor.isOnline.collect { online ->
                val prev = lastOnline
                lastOnline = online
                // React to the first path update too: if offline at launch, signal network-lost.
                if (prev == null) { if (!online) onNetworkLost(); return@collect }
                if (!online) onNetworkLost() else onNetworkRestored()
            }
        }
    }

    private fun onNetworkLost() {
        logger.i("[Coordinator] network lost")
        connectionService.dropConnectionForReconnect("network lost")
    }

    private fun onNetworkRestored() {
        logger.i("[Coordinator] network restored")
        connectionService.resetReconnectBudget()
        // Rebuild the WS on network-restored even if the socket reports open (it may be a stale
        // half-open socket) — replace-on-connect tears down any live one.
        scope.launch { reconnectOrRecreate() }
    }

    private fun observeLifecycle() {
        val lifecycle = appLifecycle ?: return
        lifecycle.start()
        scope.launch { lifecycle.foreground.collect { onForeground() } }
    }

    private fun onForeground() {
        logger.d("[Coordinator] foreground")
        sessionService.touch() // touch FIRST — returning to the foreground counts as activity.
        if (sessionService.checkTimeout() && !idleTimeoutHandled) { handleIdleTimeout(); return }
        // Gate the foreground reconnect on session readiness: reconnect when the session is
        // active but the socket isn't ready (covers Connecting/Reconnecting/Closed). A duplicate
        // connect is harmless — replace-on-connect tears down the in-flight socket.
        val s = sessionService.current
        if (s.status == SessionStatus.ACTIVE && !s.isReady && sessionService.lastSessionId != null) {
            connectionService.resetReconnectBudget() // fresh connect → status Connecting, not Reconnecting
            scope.launch { connectionService.connectNow() }
        }
    }

    /** Reconnect the existing session if we have one, else (offline-at-launch) resume-or-create. */
    private suspend fun reconnectOrRecreate() {
        connectionService.cancelReconnect()
        if (sessionService.lastSessionId != null) {
            connectionService.connectNow()
        } else {
            val created = sessionService.resumeOrCreate()
            if (created.hasInvalidApiKey || created.failure != null) return
            // A brand-new (not resumed) session → reset chat state.
            if (!created.wasResumed) { chatService.resetChat(isResume = false); agentJoinRequested = false }
            connectionService.setSessionId(created.sessionId)
            connectionService.setAccessToken(created.accessToken)
            connectionService.connectNow()
        }
    }

    private companion object {
        const val MAX_CONNECTION_DURATION_MS = 7_200_000L // 2 hours
        // Manual-retry delivery tracking: how long to wait for the server echo on a healthy socket
        // before marking the message Failed (→ "Tap to retry"). A drop/reconnect fails it sooner; an
        // offline send fails immediately. No auto-resend — the user retries explicitly.
        const val SEND_CONFIRM_TIMEOUT_MS = 10_000L
        const val SEND_POLL_MS = 150L
        const val TYPING_THROTTLE_MS = 3_000L
        const val TYPING_STOP_MS = 5_000L
        const val REFETCH_DEBOUNCE_MS = 300L
    }
}
