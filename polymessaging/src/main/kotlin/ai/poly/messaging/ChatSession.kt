// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executor

/**
 * The high-level, observable conversation façade — the primary integration surface.
 * It turns the raw event stream into a
 * UI-ready [messages] list plus lifecycle flags, exposed as StateFlows (collect them on the
 * dispatcher of your choice; Java listeners receive values on the Executor they supply).
 *
 * - **Compose:** `val messages by session.messages.collectAsStateWithLifecycle()`
 * - **Views:** `lifecycleScope.launch { repeatOnLifecycle(STARTED) { session.messages.collect { … } } }`
 * - **Java:** `session.getMessages()` + `session.addListener(executor) { … }`
 */
public class ChatSession internal constructor(
    public val client: PolyMessagingClient,
    private val typingTimeoutMillis: Long = 10_000,
    private val streamingOverride: Boolean? = null,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    /**
     * Create a fresh session view over an existing [client]. Useful to swap in an empty
     * transcript bound to the same connection (e.g. right before `client.startNewSession()`).
     *
     * @param streamingEnabled optional per-session override; `null` (the default) uses
     *   `Configuration.streamingEnabled` from `initialize(...)`.
     */
    public constructor(
        client: PolyMessagingClient,
        typingTimeoutMillis: Long = 10_000,
        streamingEnabled: Boolean? = null,
    ) : this(client, typingTimeoutMillis, streamingEnabled, Dispatchers.Main.immediate)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Each StateFlow property carries a "...Flow" JVM getter name so the natural Java name
    // (getMessages(), isReady(), ...) stays free for the snapshot bridges below — otherwise the
    // two would differ only by return type, which javac cannot disambiguate at any call site.
    // Kotlin callers are unaffected (property access syntax never sees the JVM name).
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    @get:JvmName("getMessagesFlow")
    public val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    @get:JvmName("getConnectionFlow")
    public val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _isAgentTyping = MutableStateFlow(false)
    @get:JvmName("isAgentTypingFlow")
    public val isAgentTyping: StateFlow<Boolean> = _isAgentTyping.asStateFlow()

    private val _agentAvatarUrl = MutableStateFlow<URI?>(null)
    @get:JvmName("getAgentAvatarUrlFlow")
    public val agentAvatarUrl: StateFlow<URI?> = _agentAvatarUrl.asStateFlow()

    private val _hasStarted = MutableStateFlow(false)
    @get:JvmName("getHasStartedFlow")
    public val hasStarted: StateFlow<Boolean> = _hasStarted.asStateFlow()

    private val _hasEnded = MutableStateFlow(false)
    @get:JvmName("getHasEndedFlow")
    public val hasEnded: StateFlow<Boolean> = _hasEnded.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    @get:JvmName("isReadyFlow")
    public val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _failureReason = MutableStateFlow<PolyError?>(null)
    @get:JvmName("getFailureReasonFlow")
    public val failureReason: StateFlow<PolyError?> = _failureReason.asStateFlow()

    private val streamingBubbles = HashMap<String, UUID>() // messageId -> bubble id
    private var currentSessionId: String? = null
    private var typingDismissJob: Job? = null

    private val streamsProgressively: Boolean
        get() = streamingOverride ?: client.config.streamingEnabled

    init {
        scope.launch { client.events.collect { handle(it) } }
        scope.launch {
            client.sessionState.collect { state ->
                _isReady.value = state.isReady
                applySessionIdChange(state.sessionId)
                // Surface ONLY hasInvalidApiKey here; all other terminal failures flow through
                // the connectionStatus.Failed collector below. (state.error is not read.)
                if (state.hasInvalidApiKey) {
                    _failureReason.value = PolyError.Auth.Unauthorized
                    clearTypingIndicator()
                }
            }
        }
        scope.launch {
            client.connectionStatus.collect { status ->
                if (status is ConnectionStatus.Failed) {
                    _connection.value = status
                    _failureReason.value = status.reason
                    clearTypingIndicator()
                }
            }
        }
    }

    // ---- Public API (Kotlin: suspend; Java: callback overloads below) ----

    public suspend fun send(text: String): Unit = client.send(text)
    public suspend fun sendTyping(): Unit = client.sendTyping()
    public suspend fun end(): Unit = client.end()

    public fun clearChat() {
        _messages.value = emptyList()
        streamingBubbles.clear()
        _hasEnded.value = false
        _hasStarted.value = false
        _failureReason.value = null
        _agentAvatarUrl.value = null
        clearTypingIndicator()
    }

    public fun removeMessage(draftId: String) {
        _messages.value = _messages.value.filterNot { it is ChatMessage.User && it.message.draftId == draftId }
    }

    public fun clearSuggestions(messageId: UUID) {
        _messages.value = _messages.value.map { msg ->
            if (msg is ChatMessage.Agent && msg.message.id == messageId) {
                ChatMessage.Agent(msg.message.copyWith(suggestions = emptyList()))
            } else {
                msg
            }
        }
    }

    public val userMessages: List<UserMessage> get() = _messages.value.mapNotNull { (it as? ChatMessage.User)?.message }
    public val agentMessages: List<AgentMessage> get() = _messages.value.mapNotNull { (it as? ChatMessage.Agent)?.message }
    public val systemMessages: List<SystemMessage> get() = _messages.value.mapNotNull { (it as? ChatMessage.System)?.message }
    public val lastAgentMessage: AgentMessage? get() = agentMessages.lastOrNull()

    /** Tear down this session's observers. Call when the chat surface goes away. */
    public fun close() {
        scope.cancel()
    }

    // ---- Java bridges ----

    public fun getMessages(): List<ChatMessage> = _messages.value
    public fun getConnection(): ConnectionStatus = _connection.value
    public fun isAgentTyping(): Boolean = _isAgentTyping.value
    public fun getAgentAvatarUrl(): URI? = _agentAvatarUrl.value
    public fun getHasStarted(): Boolean = _hasStarted.value
    public fun getHasEnded(): Boolean = _hasEnded.value
    public fun isReady(): Boolean = _isReady.value
    public fun getFailureReason(): PolyError? = _failureReason.value

    public fun send(text: String, executor: Executor, callback: Callback<Unit>): Cancellable =
        client.runAsync(executor, callback) { client.send(text) }

    public fun sendTyping(executor: Executor, callback: Callback<Unit>): Cancellable =
        client.runAsync(executor, callback) { client.sendTyping() }

    public fun end(executor: Executor, callback: Callback<Unit>): Cancellable =
        client.runAsync(executor, callback) { client.end() }

    /** Fires immediately with the current state, then on every change. */
    public fun addListener(executor: Executor, listener: ChatSessionListener): Cancellable {
        val job = scope.launch {
            combine(
                messages, connection, isAgentTyping, agentAvatarUrl,
                hasStarted, hasEnded, isReady, failureReason,
            ) { _ -> Unit }.collect { executor.execute { listener.onChanged(this@ChatSession) } }
        }
        return Cancellable { job.cancel() }
    }

    // ---- Event reducer ----

    private fun handle(event: MessagingEvent) {
        when (event) {
            is MessagingEvent.Connected -> { _connection.value = ConnectionStatus.Open; _failureReason.value = null }
            is MessagingEvent.Reconnecting -> _connection.value = ConnectionStatus.Reconnecting(event.attempt)
            is MessagingEvent.Disconnected -> {
                _connection.value = ConnectionStatus.Closed(null)
                if (event.error?.isSessionExpired == true) { _hasEnded.value = true; clearTypingIndicator() }
            }
            is MessagingEvent.SessionStart -> { _hasStarted.value = true; _hasEnded.value = false; _failureReason.value = null }
            is MessagingEvent.SessionEnd -> {
                clearTypingIndicator()
                if (event.payload.reason != "user_ended") markConversationEnded(event.payload.reason) else _hasEnded.value = true
            }
            is MessagingEvent.AgentThinking -> startTypingIndicator()
            is MessagingEvent.LiveAgentTyping -> when (event.payload.state) {
                TypingState.STARTED -> startTypingIndicator()
                TypingState.STOPPED -> clearTypingIndicator()
            }
            is MessagingEvent.AgentMessage -> handleAgentMessage(event)
            is MessagingEvent.AgentMessageChunk -> if (streamsProgressively) handleChunk(event)
            is MessagingEvent.AgentLeft -> clearTypingIndicator()
            is MessagingEvent.LiveAgentJoined -> {
                clearTypingIndicator()
                event.payload.avatarUrl?.let { _agentAvatarUrl.value = it }
                append(ChatMessage.System(SystemMessage(SystemEvent.LiveAgentJoined(event.payload.agentName))))
            }
            is MessagingEvent.LiveAgentMessage -> {
                clearTypingIndicator()
                event.payload.avatarUrl?.let { _agentAvatarUrl.value = it }
                append(ChatMessage.Agent(event.payload.toAgentMessage(AgentKind.LIVE)))
            }
            is MessagingEvent.LiveAgentLeft -> { clearTypingIndicator(); markConversationEnded(event.payload.reason) }
            is MessagingEvent.MessagePending -> append(ChatMessage.User(UserMessage(event.text, Delivery.PENDING, event.draftId)))
            is MessagingEvent.MessageConfirmed -> updateDelivery(event.draftId, Delivery.SENT)
            is MessagingEvent.MessageFailed -> updateDelivery(event.draftId, Delivery.FAILED)
            is MessagingEvent.SystemMessage -> append(ChatMessage.System(SystemMessage(SystemEvent.ServerMessage(event.payload.message, event.payload.level))))
            is MessagingEvent.HandoffQueueStatus -> append(ChatMessage.System(SystemMessage(SystemEvent.QueueStatus(event.payload.position, event.payload.displayMessage))))
            is MessagingEvent.AgentTriggeredHandoff -> { clearTypingIndicator(); append(ChatMessage.System(SystemMessage(SystemEvent.HandoffStarted))) }
            is MessagingEvent.ClientHandoffRequired -> {
                clearTypingIndicator()
                append(ChatMessage.System(SystemMessage(SystemEvent.HandoffRequired(event.payload.route ?: event.payload.reason ?: ""))))
            }
            is MessagingEvent.HandoffAccepted -> append(ChatMessage.System(SystemMessage(SystemEvent.HandoffAccepted)))
            is MessagingEvent.HandoffFailed -> { clearTypingIndicator(); append(ChatMessage.System(SystemMessage(SystemEvent.HandoffFailed(event.payload.reason)))) }
            is MessagingEvent.HandoffTimeout -> { clearTypingIndicator(); append(ChatMessage.System(SystemMessage(SystemEvent.HandoffTimeout))) }
            is MessagingEvent.SessionIdleWarning -> append(ChatMessage.System(SystemMessage(SystemEvent.IdleWarning)))
            is MessagingEvent.UserMessage -> {
                val alreadyShown = _messages.value.any { it is ChatMessage.User && it.message.draftId == event.env.id }
                if (!alreadyShown) append(ChatMessage.User(UserMessage(event.payload.text, Delivery.SENT, event.env.id)))
            }
            is MessagingEvent.AgentJoined -> event.payload.avatarUrl?.let { _agentAvatarUrl.value = it }
            else -> {}
        }
    }

    private fun handleAgentMessage(event: MessagingEvent.AgentMessage) {
        clearTypingIndicator()
        val p = event.payload
        p.avatarUrl?.let { _agentAvatarUrl.value = it }
        val bubbleId = streamingBubbles.remove(p.messageId)
        if (bubbleId != null && _messages.value.any { it.id == bubbleId }) {
            replaceById(bubbleId, ChatMessage.Agent(p.toAgentMessage(AgentKind.POLY, id = bubbleId)))
        } else {
            // The streaming bubble was recorded but is no longer in the list (e.g. cleared) — append a
            // fresh final bubble rather than dropping the message.
            append(ChatMessage.Agent(p.toAgentMessage(AgentKind.POLY)))
        }
    }

    private fun handleChunk(event: MessagingEvent.AgentMessageChunk) {
        clearTypingIndicator()
        val msgId = event.payload.messageId
        val text = event.payload.text ?: ""
        val existingId = streamingBubbles[msgId]
        val current = existingId?.let { id -> _messages.value.firstOrNull { it.id == id } as? ChatMessage.Agent }
        if (existingId != null && current != null) {
            replaceById(existingId, ChatMessage.Agent(current.message.copyWith(appendText = text)))
        } else {
            // No recorded bubble, OR the recorded one was evicted from the list — start a fresh bubble
            // rather than dropping the chunk + leaking a stale map entry.
            val bubbleId = UUID.randomUUID()
            streamingBubbles[msgId] = bubbleId
            append(ChatMessage.Agent(AgentMessage(messageId = msgId, agentKind = AgentKind.POLY, text = text, id = bubbleId)))
        }
    }

    // ---- helpers ----

    private fun append(message: ChatMessage) { _messages.value = _messages.value + message }

    private fun replaceById(id: UUID, replacement: ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) replacement else it }
    }

    private fun updateDelivery(draftId: String, delivery: Delivery) {
        _messages.value = _messages.value.map { msg ->
            if (msg is ChatMessage.User && msg.message.draftId == draftId) {
                ChatMessage.User(msg.message.copyWith(delivery = delivery))
            } else {
                msg
            }
        }
    }

    private fun markConversationEnded(reason: String?) {
        val already = _messages.value.any { it is ChatMessage.System && it.message.event is SystemEvent.ConversationEnded }
        _hasEnded.value = true
        if (!already) append(ChatMessage.System(SystemMessage(SystemEvent.ConversationEnded(reason))))
    }

    private fun applySessionIdChange(newId: String?) {
        if (newId == null) return
        val current = currentSessionId
        if (current != null && newId != current) {
            _messages.value = emptyList()
            _hasEnded.value = false
            _hasStarted.value = false
            _failureReason.value = null
            _agentAvatarUrl.value = null
            streamingBubbles.clear()
            clearTypingIndicator()
        }
        currentSessionId = newId
    }

    private fun startTypingIndicator() {
        typingDismissJob?.cancel()
        _isAgentTyping.value = true
        typingDismissJob = scope.launch {
            delay(typingTimeoutMillis)
            _isAgentTyping.value = false
        }
    }

    private fun clearTypingIndicator() {
        typingDismissJob?.cancel()
        typingDismissJob = null
        _isAgentTyping.value = false
    }
}

// ---- small immutable "copy" helpers (no data-class copy in the public API) ----

internal fun AgentMessage.copyWith(
    suggestions: List<ResponseSuggestion> = this.suggestions,
    appendText: String? = null,
): AgentMessage = AgentMessage(
    messageId = messageId,
    agentKind = agentKind,
    text = if (appendText != null) text + appendText else text,
    agentName = agentName,
    avatarUrl = avatarUrl,
    attachments = attachments,
    suggestions = suggestions,
    callActions = callActions,
    id = id,
    timestamp = timestamp,
)

internal fun UserMessage.copyWith(delivery: Delivery): UserMessage =
    UserMessage(text = text, delivery = delivery, draftId = draftId, id = id, timestamp = timestamp)

internal fun AgentMessagePayload.toAgentMessage(kind: AgentKind, id: UUID = UUID.randomUUID()): AgentMessage =
    AgentMessage(
        messageId = messageId, agentKind = kind, text = text, agentName = agentName, avatarUrl = avatarUrl,
        attachments = attachments, suggestions = responseSuggestions, callActions = chatCallActions, id = id,
    )

internal fun LiveAgentMessagePayload.toAgentMessage(kind: AgentKind): AgentMessage =
    AgentMessage(
        messageId = messageId, agentKind = kind, text = text, agentName = agentName, avatarUrl = avatarUrl,
        attachments = attachments, suggestions = responseSuggestions, callActions = chatCallActions,
    )
