// Copyright PolyAI Limited

package ai.poly.examples.playground.views

import ai.poly.examples.playground.views.databinding.ActivityRootBinding
import ai.poly.examples.playground.views.databinding.ScreenChatBinding
import ai.poly.examples.playground.views.databinding.ScreenConnectBinding
import ai.poly.examples.playground.views.databinding.ScreenErrorBinding
import ai.poly.messaging.ChatSession
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.OutgoingEvent
import ai.poly.messaging.PolyMessaging
import ai.poly.messaging.PolyMessagingClient
import ai.poly.messaging.SessionStatus
import ai.poly.messaging.TypingState
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The playground container. Owns the [ChatSession] plus the dev surfaces: DevSettings (runtime
 * config), DevDiagnostics
 * (live counters), a filtered event log, the Dev Settings / Logs sheets, and raw-transport
 * protocol actions via `getConnection()`.
 */
class RootActivity : ComponentActivity() {

    private enum class Screen { CONNECT, LOADING, CHAT, ERROR }

    private val devSettings by lazy { ai.poly.messaging.DevSettings(this) }
    private val diagnostics = DevDiagnostics()
    private val logs = mutableListOf<LogEntry>()

    private lateinit var binding: ActivityRootBinding
    private var session: ChatSession? = null
    private var wasResumed = false
    private var screen = Screen.CONNECT
    private var chatController: ChatScreenController? = null
    private val lifecycleJobs = mutableListOf<Job>()
    private var observedFirstSessionId: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootBinding.inflate(layoutInflater)
        setContentView(binding.root)
        actionBar?.title = "PolyMessaging"
        actionBar?.setHomeAsUpIndicator(R.drawable.ic_chevron_left)

        // Edge-to-edge (targetSdk 35+): the decor action bar overlays the content — pad the top
        // with the dispatched status-bar inset (the decor folds the action bar into it), and lift
        // the bottom above the gesture nav bar (closed) / keyboard (open).
        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { v, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            v.updatePadding(
                top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                bottom = bottom,
            )
            insets
        }

        showConnect()
    }

    // ---- Screen transitions ----

    private fun transition(view: View) {
        chatController?.stop()
        chatController = null
        binding.container.removeAllViews()
        binding.container.addView(view)
        invalidateOptionsMenu()
        actionBar?.setDisplayHomeAsUpEnabled(screen != Screen.CONNECT)
    }

    private fun showConnect() {
        screen = Screen.CONNECT
        val b = ScreenConnectBinding.inflate(LayoutInflater.from(this), binding.container, false)
        val hasActiveSession = session != null
        val canResume = PolyMessaging.hasResumableSession()
        val primaryShowsResume = hasActiveSession || canResume
        // Environment label resolved live from DevSettings; custom badge when knobs differ.
        b.envValue.text = devSettings.environmentDisplayName()
        b.customBadge.visibility = if (devSettings.hasCustomization) View.VISIBLE else View.GONE
        b.primaryAction.text = if (primaryShowsResume) "Resume Chat" else "Start Chat"
        b.primaryAction.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (primaryShowsResume) R.drawable.ic_resume else R.drawable.ic_bolt, 0, 0, 0,
        )
        b.primaryAction.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        styleProminent(b.primaryAction)
        b.primaryAction.setOnClickListener { configureAndStart(forceFresh = false) }
        b.startNewAction.visibility = if (primaryShowsResume) View.VISIBLE else View.GONE
        b.startNewAction.backgroundTintList = ColorStateList.valueOf(Palette.systemGray5)
        b.startNewAction.setTextColor(Palette.systemBlue)
        b.startNewAction.isAllCaps = false
        b.startNewAction.setOnClickListener { configureAndStart(forceFresh = true) }
        if (hasActiveSession) {
            b.statusCaption.text = "Your conversation is still active"
            b.statusCaption.visibility = View.VISIBLE
        } else if (canResume) {
            b.statusCaption.text = "A previous conversation is available to resume"
            b.statusCaption.visibility = View.VISIBLE
        }
        transition(b.root)
    }

    private fun showLoading() {
        screen = Screen.LOADING
        transition(LayoutInflater.from(this).inflate(R.layout.screen_loading, binding.container, false))
    }

    private fun showChat() {
        val s = session ?: return
        if (screen == Screen.CHAT) return
        screen = Screen.CHAT
        val b = ScreenChatBinding.inflate(LayoutInflater.from(this), binding.container, false)
        requestNotificationPermissionIfNeeded()
        val controller = ChatScreenController(
            this, b, s, wasResumed,
            showDebugStrip = devSettings.showDebugStrip.value,
            showTimestamps = devSettings.showMessageTimestamps.value,
            diagnostics = diagnostics,
        )
        transition(b.root)
        chatController = controller // transition() clears it — set after
        controller.start()
    }

    private fun showError(message: String) {
        screen = Screen.ERROR
        val b = ScreenErrorBinding.inflate(LayoutInflater.from(this), binding.container, false)
        b.errorMessage.text = message
        styleProminent(b.goBack)
        b.goBack.setOnClickListener { showConnect() }
        transition(b.root)
    }

    private fun styleProminent(button: android.widget.Button) {
        button.backgroundTintList = ColorStateList.valueOf(Palette.systemBlue)
        button.setTextColor(Color.WHITE)
        button.isAllCaps = false
    }

    // ---- Sheets ----

    private fun presentLogs() {
        LogsDialog(this, logs.toList()).show()
    }

    private fun presentSettings() {
        SettingsDialog(
            context = this,
            scope = lifecycleScope,
            settings = devSettings,
            diagnostics = diagnostics,
            hasLiveSession = session != null,
            hasResumableSession = PolyMessaging.hasResumableSession(),
            onApplyAndRestart = { applySettingsAndRestart() },
            // The reserved 1006 maps to force-reconnect / network-drop, but OkHttp refuses to
            // SEND reserved codes, so we use 4002 — same reconnect path.
            onForceReconnect = { closeWith(4002, "Debug force reconnect") },
            onSimulateDrop = { closeWith(4002, "Debug simulated drop") },
            onDisconnectClean = { closeWith(1000, "Debug clean disconnect") },
            onSimulateServerReject = { closeWith(4001, "Debug server-reject simulation") },
            onSimulateIdleTimeout = { closeWith(4002, "Debug idle-timeout simulation") },
            onSendHeartbeat = { rawSend(OutgoingEvent.Heartbeat) },
            onSendTypingStart = { rawSend(OutgoingEvent.UserTyping(TypingState.STARTED)) },
            onSendTypingStop = { rawSend(OutgoingEvent.UserTyping(TypingState.STOPPED)) },
            onSendUserEndSession = { rawSend(OutgoingEvent.UserEndConversation) },
            onSendUserLeft = { rawSend(OutgoingEvent.UserLeft) },
        ).show()
    }

    // ---- Nav actions ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Connect screen: a lone Dev Settings gear. Chat/loading/error: the overflow menu.
        menu.add(0, MENU_GEAR, 0, "Dev Settings")
            .setIcon(R.drawable.ic_settings)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_LOGS, 1, "View Logs")
        menu.add(0, MENU_SETTINGS, 2, "Dev Settings")
        menu.add(0, MENU_END, 3, "End Conversation")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val onConnect = screen == Screen.CONNECT
        menu.findItem(MENU_GEAR)?.isVisible = onConnect
        menu.findItem(MENU_LOGS)?.isVisible = !onConnect
        menu.findItem(MENU_SETTINGS)?.isVisible = !onConnect
        menu.findItem(MENU_END)?.isVisible = !onConnect
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { showConnect(); true } // pause back WITHOUT ending
        MENU_GEAR, MENU_SETTINGS -> { presentSettings(); true }
        MENU_LOGS -> { presentLogs(); true }
        MENU_END -> { confirmEnd(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmEnd() {
        AlertDialog.Builder(this)
            .setTitle("End Conversation")
            .setMessage("This will permanently end the current conversation. You won't be able to resume it.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("End Conversation") { _, _ -> endConversation() }
            .show()
    }

    private fun endConversation() {
        val pending = session
        lifecycleScope.launch {
            runCatching { pending?.end() }
            session = null
            wasResumed = false
            logs.clear()
            diagnostics.reset()
            showConnect()
        }
    }

    // ---- SDK lifecycle ----

    private fun configureAndStart(forceFresh: Boolean) {
        val existing = session
        if (existing != null) {
            if (forceFresh) {
                wasResumed = false
                session = ChatSession(existing.client)
                logs.clear()
                log("Ending current session and starting fresh...")
                showLoading()
                lifecycleScope.launch {
                    runCatching { existing.client.startNewSession() }
                        .onFailure {
                            log("Start new failed: $it")
                            showError("Couldn't start a new session.\n$it")
                        }
                }
            } else {
                showChat()
            }
            return
        }

        // Streaming behaviour is driven by `Configuration.streamingEnabled`
        // (set in the Settings sheet). No per-session override needed here.
        val config = devSettings.buildConfiguration()
        val s = if (forceFresh) PolyMessaging.start(config) else PolyMessaging.chat(config)
        diagnostics.attach(s.client, lifecycleScope)
        session = s
        wasResumed = false
        observedFirstSessionId = null
        showLoading()
        log(if (forceFresh) "Starting new session..." else "Resuming session...")
        subscribeLifecycle(s.client)
    }

    private fun subscribeLifecycle(client: PolyMessagingClient) {
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs.clear()

        lifecycleJobs += lifecycleScope.launch {
            client.events.collect { event ->
                if (shouldLog(event)) logs.add(EventLogger.makeEntry(event))
                if (event is MessagingEvent.SessionStart && screen == Screen.LOADING) showChat()
                if (event is MessagingEvent.Disconnected && screen == Screen.LOADING) {
                    event.error?.let { showError("Couldn't connect.\n${it.debugDescription}") }
                }
            }
        }
        lifecycleJobs += lifecycleScope.launch {
            client.connectionStatus.collect { status ->
                log("Connection: ${connectionLabel(status)}")
                if (status is ConnectionStatus.Failed && screen == Screen.LOADING) {
                    val message = status.reason?.debugDescription ?: "Unknown failure"
                    showError("Connection failed.\n$message")
                }
            }
        }
        lifecycleJobs += lifecycleScope.launch {
            client.sessionState.collect { state ->
                if (state.status == SessionStatus.RESTORED) {
                    wasResumed = true
                    log("Resumed previous conversation")
                }
                if (state.isReady && (screen == Screen.LOADING || screen == Screen.ERROR)) showChat()
                val sid = state.sessionId
                if (state.isReady && sid != null && observedFirstSessionId == null && !wasResumed) {
                    observedFirstSessionId = sid
                    devSettings.recordSessionApplied()
                }
                if (state.isError && screen == Screen.LOADING) {
                    showError(state.errorMessage ?: "Couldn't start the session.")
                }
            }
        }
    }

    private fun applySettingsAndRestart() {
        val pending = session
        lifecycleScope.launch {
            runCatching { pending?.end() }
            runCatching { pending?.client?.shutdown() }
            session = null
            wasResumed = false
            logs.clear()
            diagnostics.reset()
            devSettings.recordSessionApplied()
            configureAndStart(forceFresh = true)
        }
    }

    // ---- Raw transport (getConnection escape hatch) ----

    private fun rawSend(event: OutgoingEvent) {
        val client = session?.client ?: return
        // Keep the typing state visible — the label includes the payload.
        val label = when (event) {
            is OutgoingEvent.UserTyping -> "UserTyping(${event.state})"
            else -> event::class.simpleName
        }
        log("Debug send: $label")
        lifecycleScope.launch {
            client.getConnection().send(event)
            diagnostics.recordOutgoing()
        }
    }

    private fun closeWith(code: Int, reason: String) {
        val client = session?.client ?: return
        log("Debug: close with code $code — $reason")
        lifecycleScope.launch { client.getConnection().disconnect(code, reason) }
    }

    // ---- Logging ----

    private fun log(msg: String) {
        logs.add(EventLogger.makeEntry(msg))
    }

    private fun shouldLog(event: MessagingEvent): Boolean = when (event) {
        is MessagingEvent.MessagePending, is MessagingEvent.MessageConfirmed, is MessagingEvent.MessageFailed,
        is MessagingEvent.Heartbeat, is MessagingEvent.UserTyping, is MessagingEvent.UserEndSession,
        is MessagingEvent.RequestPolyAgentJoin,
        -> false
        else -> true
    }

    /** Formats the connection status for the connection log line. */
    private fun connectionLabel(status: ConnectionStatus): String = when (status) {
        is ConnectionStatus.Idle -> "idle"
        is ConnectionStatus.Connecting -> "connecting"
        is ConnectionStatus.Open -> "open"
        is ConnectionStatus.Reconnecting -> "reconnecting(attempt: ${status.attempt})"
        is ConnectionStatus.Closing -> "closing"
        is ConnectionStatus.Closed -> "closed"
        is ConnectionStatus.Failed -> "failed(${status.reason?.debugDescription})"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val MENU_GEAR = 1
        private const val MENU_LOGS = 2
        private const val MENU_SETTINGS = 3
        private const val MENU_END = 4
    }
}
