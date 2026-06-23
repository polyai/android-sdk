// Copyright PolyAI Limited

package ai.poly.examples.handoff.compose.components

import ai.poly.examples.handoff.compose.SecondaryLabel
import ai.poly.examples.handoff.compose.SystemBlue
import ai.poly.examples.handoff.compose.SystemGray3
import ai.poly.examples.handoff.compose.SystemGray5
import ai.poly.examples.handoff.compose.SystemGray6
import ai.poly.examples.handoff.compose.SystemRed
import ai.poly.examples.handoff.compose.SystemTeal
import ai.poly.examples.handoff.compose.R
import ai.poly.messaging.AgentKind
import ai.poly.messaging.AttachmentContentType
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.Delivery
import ai.poly.messaging.SystemEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI

/**
 * Renders one chat row by message kind: a trailing blue user bubble (with delivery state + retry),
 * a leading agent bubble (with avatar, optional name, and quick-reply pills), or a centered gray
 * system pill. Live-agent bubbles get teal styling + a "· live agent" name tag, so users can tell
 * a human is on the other end.
 *
 * @param containerWidth width of the enclosing list; bubbles cap at ~75% of it so long
 *   messages wrap instead of spanning edge-to-edge (tracks rotation / split screen).
 */
@Composable
fun MessageBubbleView(
    message: ChatMessage,
    containerWidth: Dp = 0.dp,
    onRetry: (draftId: String, text: String) -> Unit = { _, _ -> },
    showSendingLabel: Boolean = false,
    showSuggestions: Boolean = false,
    onSuggestionTap: (String) -> Unit = {},
) {
    val maxBubbleWidth = if (containerWidth > 0.dp) containerWidth * 0.75f else Dp.Unspecified

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        when (message) {
            is ChatMessage.User -> {
                val m = message.message
                val failed = m.delivery == Delivery.FAILED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (failed) {
                        RetryButton(onClick = { onRetry(m.draftId, m.text) })
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = m.text,
                            color = if (failed) Color.Black else Color.White,
                            modifier = Modifier
                                .widthIn(max = maxBubbleWidth)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (failed) SystemRed.copy(alpha = 0.15f) else SystemBlue)
                                // Tapping the failed bubble itself also retries (not just the "!" button).
                                .then(if (failed) Modifier.clickable { onRetry(m.draftId, m.text) } else Modifier)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                        if (showSendingLabel && m.delivery == Delivery.PENDING) {
                            Text("Sending...", fontSize = 11.sp, color = SecondaryLabel)
                        } else if (failed) {
                            Text("Tap to retry", fontSize = 11.sp, color = SystemRed)
                        }
                    }
                }
            }

            is ChatMessage.Agent -> {
                val m = message.message
                // Live-agent replies are ordinary agent messages — only agentKind differs.
                val isLive = m.agentKind == AgentKind.LIVE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BubbleAvatar(url = m.avatarUrl, isLive = isLive)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val name = m.agentName
                        if (!name.isNullOrEmpty()) {
                            Text(
                                text = if (isLive) "$name · live agent" else name,
                                fontSize = 11.sp,
                                color = if (isLive) SystemTeal else SecondaryLabel,
                            )
                        }
                        // 1. Rich text (Markdown — bold, italic, code, links). Teal-tinted
                        //    + teal-stroked when a live agent sent it.
                        if (m.text.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth()) {
                                RichText(
                                    text = m.text,
                                    color = Color.Black,
                                    modifier = Modifier
                                        .widthIn(max = maxBubbleWidth)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isLive) SystemTeal.copy(alpha = 0.18f) else SystemGray5)
                                        .then(
                                            if (isLive) {
                                                Modifier.border(1.dp, SystemTeal.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                                Spacer(Modifier.weight(1f)) // Spacer(minLength: 60)
                            }
                        }
                        // 2. Attachments. URL link-cards get their own carousel (below); everything
                        //    else — images plus any unmodelled kinds — renders in the image carousel
                        //    (images = filter != .url).
                        val images = m.attachments.filter { it.contentType != AttachmentContentType.URL }
                        if (images.isNotEmpty()) {
                            AttachmentCarousel(images)
                        }
                        // 3. URL attachments → horizontal carousel of cards.
                        val urls = m.attachments.filter { it.contentType == AttachmentContentType.URL }
                        if (urls.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                urls.forEach { UrlCard(it) }
                            }
                        }
                        // 4. tel: call actions → a column of green buttons (centered).
                        if (m.callActions.isNotEmpty()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                m.callActions.forEach { CallActionButton(it) }
                            }
                        }
                        // 5. Quick-reply suggestions, under the last agent message.
                        if (showSuggestions && m.suggestions.isNotEmpty()) {
                            Box(Modifier.padding(top = 4.dp)) {
                                SuggestionRow(
                                    suggestions = m.suggestions.map { it.messageText },
                                    onTap = { onSuggestionTap(it) },
                                )
                            }
                        }
                    }
                }
            }

            is ChatMessage.System -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = systemText(message.message.event),
                        fontSize = 12.sp,
                        color = SecondaryLabel,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SystemGray6)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * The bubble avatar — like [AgentAvatar] but live-aware: a teal ring around the circle and a
 * teal person fallback glyph when a live agent sent the message.
 */
@Composable
private fun BubbleAvatar(url: URI?, isLive: Boolean) {
    val size = 28.dp
    val ring = if (isLive) Modifier.border(1.5.dp, SystemTeal, CircleShape) else Modifier
    if (url != null) {
        RetryableAsyncImage(
            url = url.toString(),
            modifier = Modifier.size(size).clip(CircleShape).then(ring),
            placeholder = { BubbleAvatarFallback(isLive, size) },
            fallback = { BubbleAvatarFallback(isLive, size) },
        )
    } else {
        Box(ring) { BubbleAvatarFallback(isLive, size) }
    }
}

/** A person glyph (teal) for live agents, a person-in-circle glyph (gray) otherwise. */
@Composable
private fun BubbleAvatarFallback(isLive: Boolean, size: Dp) {
    Icon(
        painter = painterResource(if (isLive) R.drawable.ic_person else R.drawable.ic_account_circle),
        contentDescription = null,
        tint = if (isLive) SystemTeal else SystemGray3,
        modifier = Modifier.size(size),
    )
}

/** A red circle with a white "!" the user taps to retry. */
@Composable
private fun RetryButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(SystemRed)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Retry sending message" },
        contentAlignment = Alignment.Center,
    ) {
        Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Maps a [SystemEvent] to its display label. */
private fun systemText(event: SystemEvent): String = when (event) {
    is SystemEvent.ConversationEnded -> "This conversation has ended"
    is SystemEvent.AgentLeft -> "Agent left"
    is SystemEvent.LiveAgentJoined -> "${event.name ?: "An agent"} joined"
    is SystemEvent.LiveAgentLeft -> "Agent left"
    is SystemEvent.QueueStatus ->
        event.displayMessage?.takeIf { it.isNotEmpty() }
            ?: event.position?.let { "Position #$it in queue" }
            ?: "Queued..."
    is SystemEvent.HandoffStarted -> "Transferring to a live agent..."
    is SystemEvent.HandoffRequired ->
        if (event.reasonText.isEmpty()) "Switching support channel" else "Switching to ${event.reasonText}"
    is SystemEvent.HandoffAccepted -> "An agent will be with you shortly"
    is SystemEvent.HandoffFailed ->
        event.reasonText?.let { "Transfer failed: $it" } ?: "Transfer failed"
    is SystemEvent.HandoffTimeout -> "No agents available"
    is SystemEvent.IdleWarning -> "Session will expire soon"
    is SystemEvent.ServerMessage -> event.text
}
