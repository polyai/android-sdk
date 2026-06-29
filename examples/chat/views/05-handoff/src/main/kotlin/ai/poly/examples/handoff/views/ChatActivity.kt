// Copyright PolyAI Limited

package ai.poly.examples.handoff.views

import ai.poly.examples.handoff.views.databinding.ActivityChatBinding
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Rung 05 (Views) — live-agent handoff. The classic-Views `ChatActivity`:
 * everything from 04-Resilience plus handoff transitions rendered as centered
 * system pills, teal live-agent bubble styling, and a raw `client.events` subscription for app side
 * effects (the connected agent's name in the action bar, deep-linking a handoff route). Failures
 * split in two: terminal errors (auth/config/dead session) take a full-bleed screen with Start New
 * Chat; reconnect exhaustion shows a centered "Connection lost" card with Reconnect.
 */
class ChatActivity : ComponentActivity() {

    companion object {
        private const val MAX_MESSAGE_LENGTH = 500 // matches web MAX_MESSAGE_LENGTH (F2/F3)
        private const val MENU_END = 1
    }

    private lateinit var binding: ActivityChatBinding
    private val session: ChatSession by lazy { PolyMessaging.chat() }
    private val adapter = ChatAdapter(
        onRetry = { draftId, text ->
            // Drop the failed draft first so the retry doesn't leave a duplicate bubble.
            session.removeMessage(draftId)
            lifecycleScope.launch { runCatching { session.send(text) } }
        },
        onSuggestionTap = { messageId, suggestion ->
            session.clearSuggestions(messageId)
            lifecycleScope.launch { runCatching { session.send(suggestion.messageText) } }
        },
    )

    // Tracks device connectivity (ConnectivityManager) independently of the SDK socket — drives the
    // red offline banner. Started in onCreate, stopped in onDestroy.
    private val networkMonitor by lazy { NetworkMonitor(this) }

    private var latestMessages: List<ChatMessage> = emptyList()
    private var latestTyping = false
    private var endedState = false
    // A clean terminal close (the server closing 1000 without a SESSION_END) latches the SDK's
    // send gate shut and never reconnects — treat it as ended in the UI too.
    private var closedState = false

    // F1: "New messages" pill — shown when content arrives while the user is scrolled up.
    private var hasNewBelow = false
        set(value) {
            field = value
            binding.newMessagesPill.visibility = if (value) View.VISIBLE else View.GONE
        }
    // Forces a follow-scroll right after the local user sends.
    private var pendingUserSendScroll = false
    // F1: STICKY follow — only the user scrolling away from the bottom stops it; parking
    // back at the bottom (or sending) resumes it. An instantaneous "near bottom" reading is not
    // enough: a single tall refresh (image card, a burst of handoff pills) lands >80dp at once
    // and would silently stop the follow right after a send.
    private var autoFollow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        actionBar?.title = "Chat"
        binding.banner.setBackgroundColor(Palette.systemYellow15)

        // Keep the composer above the gesture nav bar (closed) / keyboard (open), and the content
        // below the action bar. Under edge-to-edge enforcement (targetSdk 35+) the window content
        // extends behind the top decor — without this top padding the banners and the loading
        // skeleton render underneath the action bar. The decor folds the action bar into the
        // status-bar inset it dispatches, so that inset alone is the full top occlusion; on a
        // fitted (non-edge-to-edge) window it is 0 and no top padding is added.
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, insets ->
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

        // stackFromEnd pins newest content to the bottom (clear of the action bar) — the standard
        // Android chat layout; overflow scrolls normally.
        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            // True from finger-down until the scroll comes to rest — covers the fling that
            // follows a drag. Programmatic scrollToPosition never enters DRAGGING, so a
            // follow-scroll can never be mistaken for the user.
            private var userScrolling = false

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> userScrolling = true
                    RecyclerView.SCROLL_STATE_IDLE -> userScrolling = false
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (userScrolling && !isNearBottom()) {
                    autoFollow = false // the user pulled up away from the bottom
                }
                if (isNearBottom()) {
                    autoFollow = true // parked back at the bottom: resume following
                    if (hasNewBelow) hasNewBelow = false
                }
            }
        })

        // Composer: 500-char hard cap, Return-to-send, typing throttle on every keystroke.
        binding.composer.filters = arrayOf(InputFilter.LengthFilter(MAX_MESSAGE_LENGTH))
        binding.composer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                sendCurrentText()
                true
            } else {
                false
            }
        }
        binding.composer.doAfterTextChanged { text ->
            updateSendEnabled()
            if (!text.isNullOrEmpty()) lifecycleScope.launch { runCatching { session.sendTyping() } }
        }

        binding.send.setOnClickListener { sendCurrentText() }
        setSendEnabled(false)

        binding.newMessagesPill.setOnClickListener {
            autoFollow = true
            hasNewBelow = false
            binding.list.post { scrollToBottom() }
        }

        styleProminent(binding.startNew)
        styleProminent(binding.reconnect)
        styleProminent(binding.terminalStartNew)
        binding.startNew.setOnClickListener {
            actionBar?.title = "Chat"
            lifecycleScope.launch { runCatching { session.client.startNewSession() } }
        }
        binding.reconnect.setOnClickListener {
            lifecycleScope.launch { runCatching { session.client.resume() } }
        }
        binding.terminalStartNew.setOnClickListener {
            actionBar?.title = "Chat"
            lifecycleScope.launch { runCatching { session.client.startNewSession() } }
        }

        networkMonitor.start()
        bind()
        startEventCollector()
    }

    /**
     * README "Listen for events" — the raw decoded stream, for app side effects only (title,
     * deep-link). Lives for the Activity's lifetime.
     */
    private fun startEventCollector() {
        lifecycleScope.launch {
            session.client.events.collect { event -> handle(event) }
        }
    }

    private fun handle(event: MessagingEvent) {
        when (event) {
            is MessagingEvent.LiveAgentJoined -> {
                val name = event.payload.agentName
                actionBar?.title = if (!name.isNullOrEmpty()) name else "Chat"
            }
            is MessagingEvent.ClientHandoffRequired -> {
                // Optionally deep-link if the route parses as http(s).
                val route = event.payload.route
                if (route != null) {
                    val uri = runCatching { Uri.parse(route) }.getOrNull()
                    if (uri?.scheme?.startsWith("http") == true) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                }
            }
            is MessagingEvent.LiveAgentLeft -> actionBar?.title = "Chat"
            is MessagingEvent.SessionStart -> actionBar?.title = "Chat"
            else -> {
                // Handoff progress events flow through session.messages as SystemMessage pills.
                // liveAgentTyping is rendered via session.isAgentTyping, and liveAgentMessage flows
                // into session.messages as an AgentMessage with agentKind == LIVE.
            }
        }
    }

    override fun onDestroy() {
        networkMonitor.stop()
        super.onDestroy()
    }

    private fun bind() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.messages.collect { messages ->
                        latestMessages = messages
                        refresh()
                        updateSkeleton()
                    }
                }
                launch {
                    session.connection.collect { status ->
                        binding.banner.visibility =
                            if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
                        closedState = status is ConnectionStatus.Closed
                        applyEndedUi()
                        updateSendEnabled()
                        refresh()
                    }
                }
                launch {
                    session.isAgentTyping.collect { typing ->
                        latestTyping = typing
                        refresh()
                    }
                }
                launch {
                    // OS-level offline banner — distinct from the SDK's reconnect banner; both can show.
                    networkMonitor.isOnline.collect { online ->
                        binding.offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    session.hasEnded.collect { ended ->
                        endedState = ended
                        applyEndedUi()
                        invalidateOptionsMenu()
                        updateSendEnabled()
                        updateSkeleton()
                        refresh()
                    }
                }
                launch {
                    // Failures split in two: terminal errors (auth/config/dead session)
                    // take the full-bleed screen with Start New Chat; anything else — reconnect
                    // exhaustion — shows the centered "Connection lost" card with Reconnect.
                    session.failureReason.collect { reason ->
                        if (reason == null) {
                            binding.failureOverlay.visibility = View.GONE
                            binding.terminalScreen.visibility = View.GONE
                            return@collect
                        }
                        val message = reason.debugDescription
                        if (isTerminal(reason)) {
                            binding.terminalMessage.text = message
                            binding.terminalScreen.visibility = View.VISIBLE
                            binding.failureOverlay.visibility = View.GONE
                        } else {
                            binding.failureLabel.text = message
                            binding.failureOverlay.visibility = View.VISIBLE
                            binding.terminalScreen.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    // List composition + scroll follow (F1).

    private fun isEnded(): Boolean = endedState || closedState

    private fun applyEndedUi() {
        val ended = isEnded()
        binding.composer.isEnabled = !ended
        binding.inputContainer.visibility = if (ended) View.GONE else View.VISIBLE
        binding.chatEndedView.visibility = if (ended) View.VISIBLE else View.GONE
    }

    private fun buildItems(): List<ListItem> {
        val msgs = latestMessages
        val items = mutableListOf<ListItem>()
        msgs.forEach { m -> items.add(ListItem.Message(m)) }
        // Suggestions render as their own row under the last message while the chat is live.
        if (!isEnded()) {
            val last = msgs.lastOrNull()
            if (last != null && last.suggestions.isNotEmpty()) {
                items.add(ListItem.Suggestions(last.id, last.suggestions))
            }
        }
        // Typing indicator footer, after everything.
        if (latestTyping) {
            val avatar = (msgs.lastOrNull { it is ChatMessage.Agent } as? ChatMessage.Agent)?.message?.avatarUrl
            items.add(ListItem.Typing(avatar))
        }
        return items
    }

    private fun refresh() {
        // Decide whether to follow BEFORE the list changes: follow only if
        // the user is already near the bottom, or just sent. Otherwise surface the pill.
        val follow = autoFollow || pendingUserSendScroll
        if (pendingUserSendScroll) autoFollow = true // a send re-arms following
        pendingUserSendScroll = false
        adapter.submit(buildItems())
        binding.list.post {
            if (follow) {
                scrollToBottom()
                // Never surface the pill while following: the scroll above only lands on the
                // next layout pass, so reading canScrollVertically(1) here would flash it.
                hasNewBelow = false
            } else {
                // The pill shows iff there is content below the viewport.
                hasNewBelow = binding.list.canScrollVertically(1)
            }
        }
    }

    private fun updateSkeleton() {
        // Show the skeleton (and hide the list) while the WebSocket is still opening AND nothing has
        // arrived AND the chat hasn't already ended. On warm resume, messages are already in memory
        // so we skip the skeleton entirely. (isReady is read directly — re-evaluated on every
        // messages/hasEnded change.)
        val show = !session.isReady.value && latestMessages.isEmpty() && !isEnded()
        binding.skeleton.visibility = if (show) View.VISIBLE else View.GONE
        binding.list.visibility = if (show) View.GONE else View.VISIBLE
    }

    /** Auth/config/expired-session errors are terminal — show the big screen, not the reconnect card. */
    private fun isTerminal(error: PolyError): Boolean {
        if (error.isRetryable) return false
        return when (error) {
            is PolyError.InvalidConfiguration, is PolyError.Auth -> true
            is PolyError.Session.SessionExpired,
            is PolyError.Session.SessionEnded,
            is PolyError.Session.SessionCreationFailed,
            -> true
            else -> false
        }
    }

    private fun isNearBottom(): Boolean {
        val rv = binding.list
        // Exactly at the bottom (reliable, not an estimate) — or within ~80px of it.
        if (!rv.canScrollVertically(1)) return true
        val remaining = rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent() - rv.computeVerticalScrollOffset()
        return remaining <= 80 * resources.displayMetrics.density
    }

    private fun scrollToBottom() {
        // With stackFromEnd=true the layout manager bottom-aligns this position, reliably revealing the
        // newest item — the estimate-based scrollBy can under-scroll past tall rich-content rows.
        val count = adapter.itemCount
        if (count > 0) binding.list.scrollToPosition(count - 1)
    }

    // Composer / send.

    private fun sendCurrentText() {
        val text = binding.composer.text.toString().trim()
        if (text.isEmpty()) return
        binding.composer.setText("")
        updateSendEnabled()
        pendingUserSendScroll = true
        lifecycleScope.launch { runCatching { session.send(text) } }
    }

    private fun updateSendEnabled() {
        val hasText = binding.composer.text.toString().trim().isNotEmpty()
        setSendEnabled(hasText && !isEnded())
    }

    private fun setSendEnabled(enabled: Boolean) {
        binding.send.isEnabled = enabled
        binding.send.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (enabled) Palette.systemBlue else Palette.systemGray3)
        }
    }

    private fun styleProminent(button: android.widget.Button) {
        button.backgroundTintList = ColorStateList.valueOf(Palette.systemBlue)
        button.setTextColor(Color.WHITE)
        button.isAllCaps = false // button titles are not upper-cased
    }

    // Action bar "End" button.

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_END, 0, "End").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Hidden once the chat ends (not hidden for failures).
        menu.findItem(MENU_END)?.isVisible = !endedState
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_END) {
            lifecycleScope.launch { runCatching { session.end() } }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
