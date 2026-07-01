// Copyright PolyAI Limited

//  DevDiagnostics.kt — Examples/views/07-playground
//  A read-only observability tap over the client's raw streams (events /
//  connectionStatus / sessionState), feeding the Settings sheet's Diagnostics
//  section and the in-chat DebugStrip. Each value is a StateFlow updated by
//  collector jobs launched in [attach].

package ai.poly.examples.playground.views

import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.PolyMessagingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DevDiagnostics {

    // ---- Live state ----

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _sessionStatus = MutableStateFlow("idle")
    val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /** Cursor the SDK uses on reconnect — useful for checking EVENT_BATCH replay. */
    private val _lastSequence = MutableStateFlow(0)
    val lastSequence: StateFlow<Int> = _lastSequence.asStateFlow()

    private val _connectionLabel = MutableStateFlow("idle")
    val connectionLabel: StateFlow<String> = _connectionLabel.asStateFlow()

    private val _streamingCapability = MutableStateFlow<Boolean?>(null)
    val streamingCapability: StateFlow<Boolean?> = _streamingCapability.asStateFlow()

    private val _maxMessageSize = MutableStateFlow<Int?>(null)
    val maxMessageSize: StateFlow<Int?> = _maxMessageSize.asStateFlow()

    private val _serverHeartbeatSeconds = MutableStateFlow<Int?>(null)
    val serverHeartbeatSeconds: StateFlow<Int?> = _serverHeartbeatSeconds.asStateFlow()

    private val _serverMaxReconnectAttempts = MutableStateFlow<Int?>(null)
    val serverMaxReconnectAttempts: StateFlow<Int?> = _serverMaxReconnectAttempts.asStateFlow()

    // ---- Counters ----

    private val _framesIn = MutableStateFlow(0)
    val framesIn: StateFlow<Int> = _framesIn.asStateFlow()

    private val _framesOut = MutableStateFlow(0)
    val framesOut: StateFlow<Int> = _framesOut.asStateFlow()

    private val _chunksIn = MutableStateFlow(0)
    val chunksIn: StateFlow<Int> = _chunksIn.asStateFlow()

    private val _heartbeatsIn = MutableStateFlow(0)
    val heartbeatsIn: StateFlow<Int> = _heartbeatsIn.asStateFlow()

    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount: StateFlow<Int> = _reconnectCount.asStateFlow()

    private val _lastInboundAt = MutableStateFlow<Long?>(null)
    val lastInboundAt: StateFlow<Long?> = _lastInboundAt.asStateFlow()

    // ---- Internals ----

    private var eventJob: Job? = null
    private var statusJob: Job? = null
    private var stateJob: Job? = null

    fun attach(client: PolyMessagingClient, scope: CoroutineScope) {
        reset()

        eventJob = scope.launch {
            client.events.collect { consume(it) }
        }
        statusJob = scope.launch {
            client.connectionStatus.collect { consumeStatus(it) }
        }
        stateJob = scope.launch {
            client.sessionState.collect { s ->
                _sessionId.value = s.sessionId
                _sessionStatus.value = s.status.name.lowercase()
                _isReady.value = s.isReady
            }
        }
    }

    fun reset() {
        eventJob?.cancel(); eventJob = null
        statusJob?.cancel(); statusJob = null
        stateJob?.cancel(); stateJob = null
        _sessionId.value = null
        _sessionStatus.value = "idle"
        _isReady.value = false
        _lastSequence.value = 0
        _connectionLabel.value = "idle"
        _streamingCapability.value = null
        _maxMessageSize.value = null
        _serverHeartbeatSeconds.value = null
        _serverMaxReconnectAttempts.value = null
        _framesIn.value = 0
        _framesOut.value = 0
        _chunksIn.value = 0
        _heartbeatsIn.value = 0
        _reconnectCount.value = 0
        _lastInboundAt.value = null
    }

    /** SDK doesn't expose an outbound-frame stream, so the app tracks this manually. */
    fun recordOutgoing() {
        _framesOut.value += 1
    }

    // ---- Stream handlers ----

    private fun consume(event: MessagingEvent) {
        _framesIn.value += 1
        _lastInboundAt.value = System.currentTimeMillis()

        event.envelope?.sequence?.let { seq ->
            if (seq > _lastSequence.value) _lastSequence.value = seq
        }

        when (event) {
            is MessagingEvent.SessionStart -> {
                _streamingCapability.value = event.payload.capabilities.streaming
                _maxMessageSize.value = event.payload.capabilities.maxMessageSize
                _serverHeartbeatSeconds.value = event.payload.capabilities.heartbeatIntervalSeconds
                _serverMaxReconnectAttempts.value = event.payload.capabilities.maxReconnectAttempts
            }
            is MessagingEvent.AgentMessageChunk -> _chunksIn.value += 1
            is MessagingEvent.Heartbeat -> _heartbeatsIn.value += 1
            else -> Unit
        }
    }

    private fun consumeStatus(status: ConnectionStatus) {
        _connectionLabel.value = labelFor(status)
        if (status is ConnectionStatus.Reconnecting) {
            _reconnectCount.value += 1
        }
    }

    private fun labelFor(status: ConnectionStatus): String = when (status) {
        is ConnectionStatus.Idle -> "idle"
        is ConnectionStatus.Connecting -> "connecting"
        is ConnectionStatus.Open -> "open"
        is ConnectionStatus.Reconnecting -> "reconnecting (${status.attempt})"
        is ConnectionStatus.Closing -> "closing"
        is ConnectionStatus.Closed -> "closed"
        is ConnectionStatus.Failed -> "failed"
    }
}
