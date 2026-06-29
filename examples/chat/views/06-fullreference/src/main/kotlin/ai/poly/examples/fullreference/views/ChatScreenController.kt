// Copyright PolyAI Limited

package ai.poly.examples.fullreference.views

import ai.poly.examples.fullreference.views.databinding.ScreenChatBinding
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.Delivery
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full chat surface: message list, resume + offline + reconnecting banners (top stack),
 * typing footer, suggestion pills, delivery tracking with a 500ms-delayed "Sending..." label, and
 * a chat-ended banner with in-place start-new. This controller is handed a [ChatSession] by
 * [RootActivity] instead of creating its own — the connect / loading / error shell and the nav-bar
 * End / back buttons live there.
 */
class ChatScreenController(
    private val activity: ComponentActivity,
    private val binding: ScreenChatBinding,
    private val session: ChatSession,
    private val wasResumed: Boolean,
) {
    companion object {
        private const val MAX_MESSAGE_LENGTH = 500 // matches web MAX_MESSAGE_LENGTH
    }

    private val adapter = ChatAdapter(
        onRetry = { text, draftId ->
            if (draftId != null) session.removeMessage(draftId)
            activity.lifecycleScope.launch { runCatching { session.send(text) } }
        },
        onSuggestionTap = { messageId, suggestion ->
            session.clearSuggestions(messageId)
            activity.lifecycleScope.launch { runCatching { session.send(suggestion.messageText) } }
        },
    )

    private val networkMonitor = NetworkMonitor(activity)
    private val notifier = NewMessageNotifier(activity)

    private var latestMessages: List<ChatMessage> = emptyList()
    private var latestTyping = false
    private var endedState = false
    // A clean terminal close (the server closing 1000 without a SESSION_END) latches the SDK's
    // send gate shut and never reconnects — treat it as ended in the UI too.
    private var closedState = false
    private var hasFailed = false
    private var resumeBannerShown = false

    // Delayed "Sending..." label state.
    private val sendingLabels = mutableSetOf<UUID>()
    private val trackedPending = mutableSetOf<UUID>()

    private var bindJob: Job? = null
    private var notifierJob: Job? = null

    private var hasNewBelow = false
        set(value) {
            field = value
            binding.newMessagesPill.visibility = if (value) View.VISIBLE else View.GONE
        }
    private var pendingUserSendScroll = false
    // F1: STICKY follow — only the user scrolling away from the bottom stops it; parking
    // back at the bottom (or sending) resumes it. An instantaneous "near bottom" reading is not
    // enough: a single tall refresh (image card, a burst of handoff pills) lands >80dp at once
    // and would silently stop the follow right after a send.
    private var autoFollow = true

    fun start() {
        binding.banner.setBackgroundColor(Palette.systemYellow15)

        binding.list.layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }
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
        // F4: the soft keyboard return key SENDS (newlines arrive via paste) — the key listener
        // alone only covers hardware keyboards.
        binding.composer.setHorizontallyScrolling(false)
        binding.composer.maxLines = 5
        binding.composer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentText()
                true
            } else {
                false
            }
        }
        binding.composer.doAfterTextChanged { text ->
            updateSendEnabled()
            if (!text.isNullOrEmpty()) activity.lifecycleScope.launch { runCatching { session.sendTyping() } }
        }

        binding.send.setOnClickListener { sendCurrentText() }
        setSendEnabled(false)

        binding.newMessagesPill.setOnClickListener {
            autoFollow = true
            hasNewBelow = false
            binding.list.post { scrollToBottom() }
        }

        binding.startNew.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Palette.systemBlue)
        binding.startNew.setTextColor(Palette.white)
        binding.startNew.isAllCaps = false
        binding.startNew.setOnClickListener { startNewConversationInPlace() }

        networkMonitor.start()
        // Default policy stays quiet while you're on the chat; switch to ALWAYS or NEVER to
        // taste — see NewMessageNotifier.kt.
        notifierJob =
            notifier.start(activity.lifecycleScope, activity.lifecycle, session, NotificationPolicy.WHEN_BACKGROUNDED)
        bind()
        showResumeBannerIfNeeded()
    }

    fun stop() {
        bindJob?.cancel()
        bindJob = null
        // Kill the notifier on screen swap, or a paused-but-live session would keep banner
        // collectors stacking across chat re-entries.
        notifierJob?.cancel()
        notifierJob = null
        networkMonitor.stop()
    }

    private fun bind() {
        bindJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.messages.collect { messages ->
                        latestMessages = messages
                        syncSendingLabels(messages)
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
                        hasFailed = status is ConnectionStatus.Failed
                        updateSkeleton()
                    }
                }
                launch {
                    session.isAgentTyping.collect { typing ->
                        latestTyping = typing
                        refresh()
                    }
                }
                launch {
                    networkMonitor.isOnline.collect { online ->
                        binding.offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    // Composing stays available while offline/reconnecting — sending is
                    // optimistic. Only the deliberate ended state swaps the input bar.
                    session.hasEnded.collect { ended ->
                        endedState = ended
                        applyEndedUi()
                        updateSendEnabled()
                        updateSkeleton()
                        refresh()
                    }
                }
            }
        }
    }

    // ---- Sending-label delay ----

    private fun syncSendingLabels(messages: List<ChatMessage>) {
        for (m in messages) {
            val u = (m as? ChatMessage.User)?.message ?: continue
            if (u.delivery == Delivery.PENDING && trackedPending.add(u.id)) {
                val id = u.id
                activity.lifecycleScope.launch {
                    delay(500)
                    val current =
                        (session.messages.value.firstOrNull { it.id == id } as? ChatMessage.User)?.message
                    if (current?.delivery == Delivery.PENDING) {
                        sendingLabels.add(id)
                        refresh()
                    }
                }
            }
        }
        val stillPending = messages
            .mapNotNull { (it as? ChatMessage.User)?.message }
            .filter { it.delivery == Delivery.PENDING }
            .map { it.id }
            .toSet()
        sendingLabels.retainAll(stillPending)
        trackedPending.retainAll(stillPending)
    }

    // ---- Resume banner ----

    private fun showResumeBannerIfNeeded() {
        if (!wasResumed || resumeBannerShown) return
        resumeBannerShown = true
        binding.resumeBanner.visibility = View.VISIBLE
        activity.lifecycleScope.launch {
            delay(3_000)
            binding.resumeBanner.visibility = View.GONE
        }
    }

    // ---- List composition + scroll follow ----

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
        msgs.forEach { m ->
            items.add(ListItem.Message(m, showSendingLabel = sendingLabels.contains(m.id)))
        }
        if (!isEnded()) {
            val last = msgs.lastOrNull()
            if (last != null && last.suggestions.isNotEmpty()) {
                items.add(ListItem.Suggestions(last.id, last.suggestions))
            }
        }
        if (latestTyping) {
            val avatar = (msgs.lastOrNull { it is ChatMessage.Agent } as? ChatMessage.Agent)?.message?.avatarUrl
            items.add(ListItem.Typing(avatar))
        }
        return items
    }

    private fun refresh() {
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
                hasNewBelow = binding.list.canScrollVertically(1)
            }
        }
    }

    private fun updateSkeleton() {
        // 06 also gates on !hasFailed (a failed conversation shouldn't sit on a skeleton).
        val show = !session.isReady.value && latestMessages.isEmpty() && !isEnded() && !hasFailed
        binding.skeleton.visibility = if (show) View.VISIBLE else View.GONE
        binding.list.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun isNearBottom(): Boolean {
        val rv = binding.list
        if (!rv.canScrollVertically(1)) return true
        val remaining =
            rv.computeVerticalScrollRange() - rv.computeVerticalScrollExtent() - rv.computeVerticalScrollOffset()
        return remaining <= 80 * activity.resources.displayMetrics.density
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.list.scrollToPosition(count - 1)
    }

    // ---- Composer / send ----

    private fun sendCurrentText() {
        val text = binding.composer.text.toString().trim()
        if (text.isEmpty()) return
        binding.composer.setText("")
        updateSendEnabled()
        pendingUserSendScroll = true
        activity.lifecycleScope.launch { runCatching { session.send(text) } }
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

    private fun startNewConversationInPlace() {
        session.clearChat()
        activity.lifecycleScope.launch { runCatching { session.client.startNewSession() } }
    }
}
