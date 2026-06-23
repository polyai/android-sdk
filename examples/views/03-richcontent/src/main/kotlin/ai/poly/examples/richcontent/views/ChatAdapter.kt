// Copyright PolyAI Limited

package ai.poly.examples.richcontent.views

import ai.poly.examples.richcontent.views.databinding.ItemMessageBinding
import ai.poly.examples.richcontent.views.databinding.ItemSuggestionsBinding
import ai.poly.examples.richcontent.views.databinding.ItemTypingBinding
import ai.poly.messaging.Attachment
import ai.poly.messaging.AttachmentContentType
import ai.poly.messaging.ChatCallAction
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.Delivery
import ai.poly.messaging.ResponseSuggestion
import ai.poly.messaging.SystemEvent
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import java.net.URI
import java.util.UUID

/** One row of the chat list: a message, a suggestions pill-row, or the typing indicator. */
sealed interface ListItem {
    data class Message(val message: ChatMessage, val showSendingLabel: Boolean) : ListItem
    data class Suggestions(val messageId: UUID, val suggestions: List<ResponseSuggestion>) : ListItem
    data class Typing(val avatarUrl: URI?) : ListItem
}

/**
 * RecyclerView adapter: message bubbles, a suggestions row under the last agent message, and a
 * typing-indicator footer row.
 */
class ChatAdapter(
    private val onRetry: (draftId: String, text: String) -> Unit,
    private val onSuggestionTap: (UUID, ResponseSuggestion) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ListItem>()

    fun submit(newItems: List<ListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Message -> TYPE_MESSAGE
        is ListItem.Suggestions -> TYPE_SUGGESTIONS
        is ListItem.Typing -> TYPE_TYPING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MESSAGE -> MessageHolder(ItemMessageBinding.inflate(inflater, parent, false))
            TYPE_SUGGESTIONS -> SuggestionsHolder(ItemSuggestionsBinding.inflate(inflater, parent, false))
            else -> TypingHolder(ItemTypingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Message -> (holder as MessageHolder).bind(item, onRetry)
            is ListItem.Suggestions -> (holder as SuggestionsHolder).bind(item, onSuggestionTap)
            is ListItem.Typing -> (holder as TypingHolder).bind(item.avatarUrl)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is TypingHolder) holder.stop()
    }

    // ---- Message ----

    class MessageHolder(private val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root) {
        private val density = b.root.resources.displayMetrics.density
        // Pin each carousel to 0.85 of the content width so cards show side-by-side and scroll.
        private val carouselWidthPx = (b.root.resources.displayMetrics.widthPixels * 0.85f).toInt()

        fun bind(item: ListItem.Message, onRetry: (draftId: String, text: String) -> Unit) {
            // Cap the bubble at ~75% of the row width so it never spans edge-to-edge.
            b.bubble.maxWidth = (b.root.resources.displayMetrics.widthPixels * 0.75f).toInt()
            // Reset reused views.
            b.name.visibility = View.GONE
            b.retry.visibility = View.GONE
            b.avatar.visibility = View.GONE
            b.delivery.visibility = View.GONE
            b.bubble.visibility = View.VISIBLE
            b.bubble.movementMethod = null
            b.bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            resetCarousel(b.imageCarousel, b.imageStack)
            resetCarousel(b.urlCarousel, b.urlStack)
            b.callActions.visibility = View.GONE
            b.callActions.removeAllViews()
            b.bubble.setOnClickListener(null) // cleared on reuse; re-armed below only for a failed message
            b.bubble.isClickable = false

            when (val message = item.message) {
                is ChatMessage.User -> {
                    val m = message.message
                    b.bubble.text = m.text
                    val failed = m.delivery == Delivery.FAILED
                    if (failed) {
                        b.bubble.background = rounded(Palette.systemRed15, 18f)
                        b.bubble.setTextColor(Palette.label)
                        b.retry.visibility = View.VISIBLE
                        b.retry.background = oval(Palette.systemRed)
                        b.retry.setOnClickListener { onRetry(m.draftId, m.text) }
                        b.bubble.setOnClickListener { onRetry(m.draftId, m.text) } // tap the bubble too
                        b.delivery.visibility = View.VISIBLE
                        b.delivery.text = "Tap to retry"
                        b.delivery.setTextColor(Palette.systemRed)
                    } else {
                        b.bubble.background = rounded(Palette.systemBlue, 18f)
                        b.bubble.setTextColor(Palette.white)
                        if (item.showSendingLabel && m.delivery == Delivery.PENDING) {
                            b.delivery.visibility = View.VISIBLE
                            b.delivery.text = "Sending..."
                            b.delivery.setTextColor(Palette.secondaryLabel)
                        }
                    }
                    b.row.gravity = Gravity.END
                    b.outer.gravity = Gravity.END
                    b.bubbleRow.gravity = Gravity.BOTTOM
                }

                is ChatMessage.Agent -> {
                    val m = message.message
                    // 1. Rich text (Markdown) — hide the bubble entirely when text is empty.
                    if (m.text.isEmpty()) {
                        b.bubble.visibility = View.GONE
                    } else {
                        b.bubble.text = markdownSpanned(m.text, Palette.systemBlue)
                        b.bubble.movementMethod = LinkMovementMethod.getInstance()
                        b.bubble.setTextColor(Palette.label)
                        b.bubble.background = rounded(Palette.systemGray5, 18f)
                    }
                    b.avatar.visibility = View.VISIBLE
                    b.avatar.load(m.avatarUrl?.toString(), R.drawable.avatar_placeholder, circle = true)
                    val name = m.agentName
                    if (!name.isNullOrEmpty()) {
                        b.name.visibility = View.VISIBLE
                        b.name.text = name
                        b.name.setTextColor(Palette.secondaryLabel)
                    }
                    // 2/3. Image + URL attachments → carousels. .UNKNOWN dropped (forward-compat).
                    bindCarousel(b.imageCarousel, b.imageStack, m.attachments.filter { it.contentType == AttachmentContentType.IMAGE })
                    bindCarousel(b.urlCarousel, b.urlStack, m.attachments.filter { it.contentType == AttachmentContentType.URL })
                    // 4. tel: call actions.
                    bindCallActions(m.callActions)
                    b.row.gravity = Gravity.START
                    b.outer.gravity = Gravity.START
                    b.bubbleRow.gravity = Gravity.TOP
                }

                is ChatMessage.System -> {
                    b.bubble.text = systemText(message.message.event)
                    b.bubble.setTextColor(Palette.secondaryLabel)
                    b.bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    b.bubble.background = rounded(Palette.systemGray6, 14f)
                    b.row.gravity = Gravity.CENTER
                    b.outer.gravity = Gravity.CENTER_HORIZONTAL
                    b.bubbleRow.gravity = Gravity.CENTER
                }
            }
        }

        private fun resetCarousel(scroll: HorizontalScrollView, stack: LinearLayout) {
            stack.removeAllViews()
            scroll.visibility = View.GONE
            scroll.scrollX = 0
        }

        private fun bindCarousel(scroll: HorizontalScrollView, stack: LinearLayout, attachments: List<Attachment>) {
            stack.removeAllViews()
            if (attachments.isEmpty()) {
                scroll.visibility = View.GONE
                return
            }
            scroll.visibility = View.VISIBLE
            scroll.layoutParams = scroll.layoutParams.apply { width = carouselWidthPx }
            attachments.forEachIndexed { i, att -> stack.addView(makeCard(att, last = i == attachments.lastIndex)) }
        }

        private fun makeCard(att: Attachment, last: Boolean): View {
            val ctx = b.root.context
            fun dp(v: Int) = (v * density).toInt()
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Palette.systemGray6, 12f)
                outlineProvider = roundedOutline(12f)
                clipToOutline = true
                isClickable = att.contentUrl != null
                setOnClickListener { openUri(ctx, att.contentUrl) }
            }
            card.addView(RetryableImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(140))
                setBackgroundColor(Palette.systemGray6)
                load((att.previewImageUrl ?: att.contentUrl)?.toString(), R.drawable.ic_image)
            })
            val title = att.title
            if (!title.isNullOrEmpty()) {
                val text = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                text.addView(TextView(ctx).apply {
                    this.text = title
                    setTextColor(Palette.label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                })
                val cta = att.callToActionText
                if (!cta.isNullOrEmpty()) {
                    text.addView(TextView(ctx).apply {
                        this.text = cta
                        setTextColor(Palette.systemBlue)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
                    })
                }
                card.addView(text)
            }
            card.layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (!last) marginEnd = dp(10)
            }
            return card
        }

        private fun bindCallActions(actions: List<ChatCallAction>) {
            b.callActions.removeAllViews()
            if (actions.isEmpty()) {
                b.callActions.visibility = View.GONE
                return
            }
            b.callActions.visibility = View.VISIBLE
            actions.forEachIndexed { i, action -> b.callActions.addView(makeCallButton(action, last = i == actions.lastIndex)) }
        }

        private fun makeCallButton(action: ChatCallAction, last: Boolean): View {
            val ctx = b.root.context
            fun dp(v: Int) = (v * density).toInt()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Palette.systemGreen, 10f)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                setOnClickListener {
                    val digits = action.contactNumber.filter { it.isDigit() || it == '+' }
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))) }
                }
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_call)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
            })
            row.addView(TextView(ctx).apply {
                text = action.title.ifEmpty { action.contactNumber }
                setTextColor(Palette.white)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
            })
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { if (!last) bottomMargin = dp(6) }
            return row
        }

        private fun roundedOutline(radiusDp: Float) = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusDp * density)
            }
        }

        private fun openUri(ctx: Context, uri: URI?) {
            if (uri == null) return
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))) }
        }

        private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * density
        }

        private fun oval(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

        private fun systemText(event: SystemEvent): String = when (event) {
            is SystemEvent.ConversationEnded -> "Conversation ended"
            is SystemEvent.LiveAgentJoined -> "${event.name ?: "An agent"} joined"
            is SystemEvent.LiveAgentLeft -> "Agent left"
            is SystemEvent.HandoffStarted -> "Transferring to a live agent…"
            is SystemEvent.HandoffAccepted -> "An agent will be with you shortly"
            is SystemEvent.HandoffFailed -> "Transfer failed"
            is SystemEvent.HandoffTimeout -> "No agents available"
            is SystemEvent.QueueStatus -> event.position?.let { "Position #$it in queue" } ?: "Queued…"
            is SystemEvent.ServerMessage -> event.text
            is SystemEvent.AgentLeft -> ""
            is SystemEvent.HandoffRequired -> ""
            is SystemEvent.IdleWarning -> ""
        }
    }

    // ---- Suggestions ----

    class SuggestionsHolder(private val b: ItemSuggestionsBinding) : RecyclerView.ViewHolder(b.root) {
        private val density = b.root.resources.displayMetrics.density

        fun bind(item: ListItem.Suggestions, onTap: (UUID, ResponseSuggestion) -> Unit) {
            b.suggestionsStack.removeAllViews()
            item.suggestions.forEach { suggestion ->
                b.suggestionsStack.addView(makePill(suggestion, onTap, item.messageId))
            }
        }

        private fun makePill(
            suggestion: ResponseSuggestion,
            onTap: (UUID, ResponseSuggestion) -> Unit,
            messageId: UUID,
        ): TextView {
            val pill = TextView(b.root.context).apply {
                text = suggestion.messageText
                maxLines = 1
                setTextColor(Palette.systemBlue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Palette.systemBlue10)
                    cornerRadius = 100f * density
                }
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { onTap(messageId, suggestion) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (8 * density).toInt() }
            pill.layoutParams = lp
            return pill
        }
    }

    // ---- Typing ----

    class TypingHolder(private val b: ItemTypingBinding) : RecyclerView.ViewHolder(b.root) {
        private val density = b.root.resources.displayMetrics.density
        private val animators = mutableListOf<ObjectAnimator>()

        fun bind(avatarUrl: URI?) {
            if (avatarUrl != null) {
                b.typingAvatar.load(avatarUrl.toString()) {
                    placeholder(R.drawable.avatar_placeholder)
                    error(R.drawable.avatar_placeholder)
                    transformations(CircleCropTransformation()) // clip the avatar to a circle
                }
            } else {
                b.typingAvatar.setImageResource(R.drawable.avatar_placeholder)
            }
            if (animators.isEmpty()) start()
        }

        private fun start() {
            val dots = listOf(b.dot1, b.dot2, b.dot3)
            dots.forEachIndexed { i, dot ->
                val anim = ObjectAnimator.ofFloat(dot, "translationY", 0f, -6f * density).apply {
                    duration = 500
                    startDelay = i * 200L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
                animators.add(anim)
                anim.start()
            }
        }

        fun stop() {
            animators.forEach { it.cancel() }
            animators.clear()
            listOf(b.dot1, b.dot2, b.dot3).forEach { it.translationY = 0f }
        }
    }

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_SUGGESTIONS = 1
        private const val TYPE_TYPING = 2
    }
}
