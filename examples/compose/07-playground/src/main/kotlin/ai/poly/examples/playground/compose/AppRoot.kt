// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

import ai.poly.examples.playground.compose.components.DebugStrip
import ai.poly.examples.playground.compose.components.NewMessageNotifier
import ai.poly.examples.playground.compose.components.NotificationPolicy
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.Delivery
import ai.poly.messaging.DevSettings
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.OutgoingEvent
import ai.poly.messaging.PolyMessaging
import ai.poly.messaging.SessionStatus
import ai.poly.messaging.TypingState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The 06 connect/loading/chat/error router plus the playground's developer surfaces: the event
 * log, the Dev Settings sheet (DevSettings + DevDiagnostics), raw-frame sends and close-code
 * simulations over `getConnection()`, the in-chat debug strip, and message timestamps.
 */
sealed interface AppScreen {
    data object Connect : AppScreen
    data object Loading : AppScreen
    data object Chat : AppScreen
    data class Error(val message: String) : AppScreen
}

private val AppScreen.isError: Boolean get() = this is AppScreen.Error

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Connect) }
    var messageText by remember { mutableStateOf("") }
    val logs = remember { mutableStateListOf<LogEntry>() }
    var session by remember { mutableStateOf<ChatSession?>(null) }
    var wasResumed by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showEndConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val reachability = remember { NetworkMonitor(context) }
    DisposableEffect(reachability) {
        reachability.start()
        onDispose { reachability.stop() }
    }
    val isOnline by reachability.isOnline.collectAsStateWithLifecycle()
    val devSettings = remember { DevSettings(context) }
    val diagnostics = remember { DevDiagnostics() }
    val showDebugStrip by devSettings.showDebugStrip.collectAsStateWithLifecycle()
    val showTimestamps by devSettings.showMessageTimestamps.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // MARK: - Logging

    fun log(msg: String) {
        logs.add(EventLogger.makeEntry(msg))
    }

    fun shouldLog(event: MessagingEvent): Boolean = when (event) {
        is MessagingEvent.MessagePending, is MessagingEvent.MessageConfirmed, is MessagingEvent.MessageFailed,
        is MessagingEvent.Heartbeat, is MessagingEvent.UserTyping, is MessagingEvent.UserEndSession,
        is MessagingEvent.RequestPolyAgentJoin,
        -> false
        else -> true
    }

    // MARK: - Actions

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        messageText = ""
        val s = session
        scope.launch {
            runCatching { s?.send(trimmed) }
                .onFailure { log("Send failed: $it") }
        }
    }

    fun pauseAndGoBack() {
        screen = AppScreen.Connect
    }

    /** Awaits `end()` before navigating so `hasResumableSession` doesn't show a phantom "Resume" button. */
    fun endConversation() {
        val pending = session
        scope.launch {
            runCatching { pending?.end() }
            session = null
            wasResumed = false
            logs.clear()
            diagnostics.reset()
            screen = AppScreen.Connect
        }
    }

    /** Bypasses SDK dedup/throttle layers via `getConnection()` for protocol-level testing. */
    fun rawSend(event: OutgoingEvent) {
        val c = session?.client ?: return
        // Keep the typing state visible — the label includes the payload.
        val label = when (event) {
            is OutgoingEvent.UserTyping -> "UserTyping(${event.state})"
            else -> event::class.simpleName
        }
        log("Debug send: $label")
        scope.launch {
            c.getConnection().send(event)
            diagnostics.recordOutgoing()
        }
    }

    // OkHttp refuses to SEND reserved codes, so force-reconnect / network-drop use 4002 — the
    // SDK's app close code that routes through the same exponential-backoff reconnect path.
    fun forceReconnect() {
        val c = session?.client ?: return
        log("Debug: force reconnect (closing with 4002)")
        scope.launch { c.getConnection().disconnect(4002, "Debug force reconnect") }
    }

    fun simulateNetworkDrop() {
        val c = session?.client ?: return
        log("Debug: simulating network drop")
        scope.launch { c.getConnection().disconnect(4002, "Debug simulated drop") }
    }

    fun closeWith(code: Int, reason: String) {
        val c = session?.client ?: return
        log("Debug: close with code $code — $reason")
        scope.launch { c.getConnection().disconnect(code, reason) }
    }

    fun startNewConversationInPlace() {
        val s = session ?: return
        log("Starting new conversation in place...")
        s.clearChat()
        scope.launch {
            runCatching { s.client.startNewSession() }
                .onFailure { log("Start new failed: $it") }
        }
    }

    fun configureAndStart(forceFresh: Boolean) {
        val existing = session
        if (existing != null) {
            if (forceFresh) {
                screen = AppScreen.Loading
                wasResumed = false
                // A fresh, empty transcript over the same client.
                session = ChatSession(existing.client)
                logs.clear()
                log("Ending current session and starting fresh...")
                scope.launch {
                    runCatching { existing.client.startNewSession() }
                        .onFailure { error ->
                            log("Start new failed: $error")
                            screen = AppScreen.Error("Couldn't start a new session.\n$error")
                        }
                }
            } else {
                screen = AppScreen.Chat
            }
            return
        }

        // Streaming behaviour is driven by `Configuration.streamingEnabled`
        // (set in the Settings sheet). No per-session override needed here.
        val config = devSettings.buildConfiguration()
        val s = if (forceFresh) PolyMessaging.start(config) else PolyMessaging.chat(config)
        diagnostics.attach(s.client, scope)
        session = s
        wasResumed = false
        screen = AppScreen.Loading
        log(if (forceFresh) "Starting new session..." else "Resuming session...")

        val client = s.client
        scope.launch {
            client.events.collect { event ->
                if (shouldLog(event)) {
                    logs.add(EventLogger.makeEntry(event))
                }
                if (event is MessagingEvent.SessionStart && screen == AppScreen.Loading) {
                    screen = AppScreen.Chat
                }
                if (event is MessagingEvent.Disconnected && screen == AppScreen.Loading) {
                    event.error?.let { err ->
                        screen = AppScreen.Error("Couldn't connect.\n${err.debugDescription}")
                    }
                }
            }
        }
        scope.launch {
            client.connectionStatus.collect { status ->
                log("Connection: ${connectionLabel(status)}")
                if (status is ConnectionStatus.Failed && screen == AppScreen.Loading) {
                    screen = AppScreen.Error("Connection failed.\n${status.reason?.debugDescription}")
                }
            }
        }
        scope.launch {
            var observedFirstSessionId: String? = null
            client.sessionState.collect { state ->
                if (state.status == SessionStatus.RESTORED) {
                    wasResumed = true
                    log("Resumed previous conversation")
                }
                if (state.isReady && (screen == AppScreen.Loading || screen.isError)) {
                    screen = AppScreen.Chat
                }
                val sid = state.sessionId
                if (state.isReady && sid != null && observedFirstSessionId == null && !wasResumed) {
                    observedFirstSessionId = sid
                    devSettings.recordSessionApplied()
                }
                if (state.isError && screen == AppScreen.Loading) {
                    screen = AppScreen.Error(state.errorMessage ?: "Couldn't start the session.")
                }
            }
        }
    }

    fun applySettingsAndRestart() {
        val pending = session
        scope.launch {
            runCatching { pending?.end() }
            runCatching { pending?.client?.shutdown() }
            session = null
            wasResumed = false
            logs.clear()
            diagnostics.reset()
            screen = AppScreen.Loading
            devSettings.recordSessionApplied()
            configureAndStart(forceFresh = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PolyMessaging", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (screen != AppScreen.Connect) {
                        IconButton(onClick = { pauseAndGoBack() }) {
                            Icon(
                                painterResource(R.drawable.ic_chevron_left),
                                contentDescription = "Back",
                                tint = SystemBlue,
                            )
                        }
                    }
                },
                actions = {
                    if (screen != AppScreen.Connect) {
                        // Overflow menu: View Logs / Dev Settings / End Conversation.
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.semantics { contentDescription = "More actions" },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_horiz),
                                contentDescription = null,
                                tint = SystemBlue,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("View Logs") },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_find_in_page),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    showLogs = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Dev Settings") },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_settings),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    showSettings = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("End Conversation", color = SystemRed) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_close_circle),
                                        contentDescription = null,
                                        tint = SystemRed,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    showEndConfirm = true
                                },
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.semantics { contentDescription = "Dev Settings" },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_settings),
                                contentDescription = null,
                                tint = SystemBlue,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
            )
        },
        // contentWindowInsets(0) so the content owns the bottom inset itself — otherwise the
        // Scaffold subtracts the keyboard height here AND the composer's imePadding adds it
        // again, floating the input bar a full keyboard-height above the keyboard.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                AppScreen.Connect -> ConnectView(
                    hasActiveSession = session != null,
                    canResume = PolyMessaging.hasResumableSession(),
                    hasCustomSettings = devSettings.hasCustomization,
                    environmentLabel = devSettings.environmentDisplayName(),
                    onResume = { configureAndStart(forceFresh = false) },
                    onStartNew = { configureAndStart(forceFresh = true) },
                )
                AppScreen.Loading -> LoadingView()
                AppScreen.Chat -> session?.let { s ->
                    ChatScreenHost(
                        session = s,
                        messageText = messageText,
                        onMessageTextChange = { messageText = it },
                        isOnline = isOnline,
                        wasResumed = wasResumed,
                        showDebugStrip = showDebugStrip,
                        showTimestamps = showTimestamps,
                        diagnostics = diagnostics,
                        onSend = ::send,
                        onLog = ::log,
                        onEndConversation = { showEndConfirm = true },
                        onStartNewConversation = ::startNewConversationInPlace,
                    )
                }
                is AppScreen.Error -> ErrorScreen(
                    message = current.message,
                    onBack = { screen = AppScreen.Connect },
                )
            }
        }
    }

    if (showLogs) {
        LogsSheet(logs = logs, onDismiss = { showLogs = false })
    }

    if (showSettings) {
        SettingsSheet(
            settings = devSettings,
            diagnostics = diagnostics,
            hasLiveSession = session != null,
            hasResumableSession = PolyMessaging.hasResumableSession(),
            onApplyAndRestart = { applySettingsAndRestart() },
            onForceReconnect = { forceReconnect() },
            onSimulateDrop = { simulateNetworkDrop() },
            onDisconnectClean = { closeWith(1000, "Debug clean disconnect") },
            onSimulateServerReject = { closeWith(4001, "Debug server-reject simulation") },
            onSimulateIdleTimeout = { closeWith(4002, "Debug idle-timeout simulation") },
            onSendHeartbeat = { rawSend(OutgoingEvent.Heartbeat) },
            onSendTypingStart = { rawSend(OutgoingEvent.UserTyping(TypingState.STARTED)) },
            onSendTypingStop = { rawSend(OutgoingEvent.UserTyping(TypingState.STOPPED)) },
            onSendUserEndSession = { rawSend(OutgoingEvent.UserEndConversation) },
            onSendUserLeft = { rawSend(OutgoingEvent.UserLeft) },
            onDismiss = { showSettings = false },
        )
    }

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("End Conversation") },
            text = { Text("This will permanently end the current conversation. You won't be able to resume it.") },
            confirmButton = {
                TextButton(onClick = {
                    showEndConfirm = false
                    endConversation()
                }) {
                    Text("End Conversation", color = SystemRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** Formats a connection status for the connection log line. */
private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    is ConnectionStatus.Idle -> "idle"
    is ConnectionStatus.Connecting -> "connecting"
    is ConnectionStatus.Open -> "open"
    is ConnectionStatus.Reconnecting -> "reconnecting(attempt: ${status.attempt})"
    is ConnectionStatus.Closing -> "closing"
    is ConnectionStatus.Closed -> "closed"
    is ConnectionStatus.Failed -> "failed(${status.reason?.debugDescription})"
}

/**
 * The chat-screen wrapper — the debug strip, the resumed banner, the delayed "Sending..." labels,
 * and the new-message notifier around [ChatView].
 */
@Composable
private fun ChatScreenHost(
    session: ChatSession,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    isOnline: Boolean,
    wasResumed: Boolean,
    showDebugStrip: Boolean,
    showTimestamps: Boolean,
    diagnostics: DevDiagnostics,
    onSend: (String) -> Unit,
    onLog: (String) -> Unit,
    onEndConversation: () -> Unit,
    onStartNewConversation: () -> Unit,
) {
    val messages by session.messages.collectAsStateWithLifecycle()
    val isAgentTyping by session.isAgentTyping.collectAsStateWithLifecycle()
    val agentAvatarUrl by session.agentAvatarUrl.collectAsStateWithLifecycle()
    val hasEnded by session.hasEnded.collectAsStateWithLifecycle()
    val connection by session.connection.collectAsStateWithLifecycle()
    val isReady by session.isReady.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var sendingLabels by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    val trackedPending = remember { mutableSetOf<UUID>() }
    var showResumeBanner by remember { mutableStateOf(false) }

    // Delays the "Sending..." label by 500ms so fast confirmations don't flash it.
    LaunchedEffect(messages) {
        for (m in messages) {
            val u = (m as? ChatMessage.User)?.message ?: continue
            if (u.delivery == Delivery.PENDING && trackedPending.add(u.id)) {
                val id = u.id
                scope.launch {
                    delay(500)
                    val current = (session.messages.value.firstOrNull { it.id == id } as? ChatMessage.User)?.message
                    if (current?.delivery == Delivery.PENDING) sendingLabels = sendingLabels + id
                }
            }
        }
        val stillPending = messages
            .mapNotNull { (it as? ChatMessage.User)?.message }
            .filter { it.delivery == Delivery.PENDING }
            .map { it.id }
            .toSet()
        sendingLabels = sendingLabels intersect stillPending
        trackedPending.retainAll(stillPending)
    }

    LaunchedEffect(wasResumed) {
        if (wasResumed) {
            showResumeBanner = true
            delay(3_000)
            showResumeBanner = false
        }
    }

    // In-app new-message banners; the default policy stays quiet while on the chat.
    // See NewMessageNotifier.kt.
    NewMessageNotifier(session, policy = NotificationPolicy.WHEN_BACKGROUNDED)

    Column(Modifier.fillMaxSize()) {
        if (showDebugStrip) {
            DebugStrip(diagnostics)
        }
        if (showResumeBanner) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SystemBlue.copy(alpha = 0.85f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_resume),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Resumed previous conversation",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        }

        ChatView(
            messages = messages,
            sendingLabels = sendingLabels,
            messageText = messageText,
            onMessageTextChange = onMessageTextChange,
            isAgentTyping = isAgentTyping,
            agentAvatarUrl = agentAvatarUrl,
            // A clean terminal close (debug 1000 sim, or the server closing 1000 without a
            // SESSION_END) latches the SDK's send gate shut and never reconnects — reflect that
            // as the ended footer instead of leaving a composer whose sends silently vanish.
            chatEnded = hasEnded || connection is ConnectionStatus.Closed,
            isReconnecting = connection.isReconnecting,
            isConnected = connection.isConnected,
            isReady = isReady,
            // Open WS counts as online even if connectivity briefly disagrees (VPN flicker).
            isOnline = isOnline || connection.isConnected,
            hasFailed = connection.isFailed,
            showTimestamps = showTimestamps,
            onSend = onSend,
            onSuggestionTap = { text, id ->
                session.clearSuggestions(id)
                onSend(text)
            },
            onRetry = { text, draftId ->
                if (draftId != null) session.removeMessage(draftId)
                onSend(text)
            },
            onStartNewConversation = onStartNewConversation,
            onTyping = { scope.launch { runCatching { session.sendTyping() } } },
        )
    }
}
