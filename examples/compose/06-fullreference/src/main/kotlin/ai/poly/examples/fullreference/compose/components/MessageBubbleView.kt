// Copyright PolyAI Limited

package ai.poly.examples.fullreference.compose.components

import ai.poly.examples.fullreference.compose.SecondaryLabel
import ai.poly.examples.fullreference.compose.SystemBlue
import ai.poly.examples.fullreference.compose.SystemGray5
import ai.poly.examples.fullreference.compose.SystemGray6
import ai.poly.examples.fullreference.compose.SystemOrange
import ai.poly.examples.fullreference.compose.SystemRed
import ai.poly.examples.fullreference.compose.R
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.Delivery
import ai.poly.messaging.SystemEvent
import ai.poly.messaging.SystemMessageLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders one chat row by message kind.
 * 06 simplifies relative to 05: no live-agent styling (production look), every attachment kind
 * renders through the picture-card [AttachmentCarousel], and system pills pick up level-based
 * colors (info / warning / error) plus a tappable link pill for `handoffRequired` routes.
 *
 * @param containerWidth width of the enclosing list; bubbles cap at ~75% of it so long
 *   messages wrap instead of spanning edge-to-edge (tracks rotation / split screen).
 */
@Composable
fun MessageBubbleView(
    message: ChatMessage,
    containerWidth: Dp = 0.dp,
    showSendingLabel: Boolean = false,
    showSuggestions: Boolean = false,
    onSuggestionTap: (String) -> Unit = {},
    onRetry: (String, String?) -> Unit = { _, _ -> },
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
                        RetryButton(onClick = { onRetry(m.text, m.draftId) })
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
                                .then(if (failed) Modifier.clickable { onRetry(m.text, m.draftId) } else Modifier)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentAvatar(url = m.avatarUrl)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Renders for ANY non-null name, including "" —
                        // an empty caption still reserves its line above the bubble.
                        val name = m.agentName
                        if (name != null) {
                            Text(name, fontSize = 11.sp, color = SecondaryLabel)
                        }
                        if (m.text.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth()) {
                                RichText(
                                    text = m.text,
                                    color = Color.Black,
                                    modifier = Modifier
                                        .widthIn(max = maxBubbleWidth)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(SystemGray5)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                                Spacer(Modifier.weight(1f)) // Spacer(minLength: 60)
                            }
                        }
                        // 06: EVERY attachment kind renders in the picture-card carousel —
                        // the dedicated URL card is gone.
                        if (m.attachments.isNotEmpty()) {
                            AttachmentCarousel(m.attachments)
                        }
                        if (m.callActions.isNotEmpty()) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                m.callActions.forEach { CallActionButton(it) }
                            }
                        }
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
                    SystemPill(message.message.event)
                }
            }
        }
    }
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

// ---- System pill (06: level-styled + a tappable handoffRequired link pill) ----

private data class LevelStyle(val foreground: Color, val background: Color)

private val InfoStyle = LevelStyle(SecondaryLabel, SystemGray6)
private val WarningStyle = LevelStyle(SystemOrange, SystemOrange.copy(alpha = 0.12f))
private val ErrorStyle = LevelStyle(SystemRed, SystemRed.copy(alpha = 0.12f))

@Composable
private fun SystemPill(event: SystemEvent) {
    when (event) {
        is SystemEvent.HandoffRequired -> HandoffRequiredPill(route = event.reasonText)
        else -> PillLabel(text = event.displayText(), style = event.levelStyle())
    }
}

@Composable
private fun PillLabel(text: String, style: LevelStyle) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = style.foreground,
        modifier = Modifier
            .clip(CircleShape)
            .background(style.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * `handoffRequired` carries a route: http(s) routes become a tappable link pill that opens
 * externally; anything else renders as a "Contact Support" pill with the raw route below.
 */
@Composable
private fun HandoffRequiredPill(route: String) {
    val context = LocalContext.current
    val uri = runCatching { java.net.URI(route) }.getOrNull()
    if (uri?.scheme?.startsWith("http") == true) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(SystemGray6)
                .clickable { openUri(context, uri) }
                .semantics { contentDescription = "Open handoff link" }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_open_external),
                contentDescription = null,
                tint = SystemBlue,
                modifier = Modifier.size(14.dp),
            )
            Text(route, fontSize = 12.sp, color = SystemBlue)
        }
    } else {
        Column(
            modifier = Modifier
                .clip(CircleShape)
                .background(SystemGray6)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Contact Support", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SecondaryLabel)
            if (route.isNotEmpty()) {
                Text(route, fontSize = 12.sp, color = SecondaryLabel)
            }
        }
    }
}

/** The `SystemEvent.displayText` mapping. */
private fun SystemEvent.displayText(): String = when (this) {
    is SystemEvent.ConversationEnded -> "This conversation has ended"
    is SystemEvent.AgentLeft, is SystemEvent.LiveAgentLeft -> ""
    is SystemEvent.LiveAgentJoined -> "Connected with ${name ?: "an agent"}"
    is SystemEvent.QueueStatus -> displayMessage ?: "Queue position: ${position ?: 0}"
    is SystemEvent.HandoffStarted -> "Transferring you to an agent..."
    is SystemEvent.HandoffRequired -> "Transfer required: $reasonText"
    is SystemEvent.HandoffAccepted -> "An agent will be with you shortly"
    is SystemEvent.HandoffFailed -> "Transfer failed: ${reasonText ?: "unknown"}"
    is SystemEvent.HandoffTimeout -> "Transfer timed out"
    is SystemEvent.IdleWarning -> "Session will expire soon"
    is SystemEvent.ServerMessage -> text
}

private fun SystemEvent.levelStyle(): LevelStyle = when (this) {
    is SystemEvent.ServerMessage -> when (level) {
        SystemMessageLevel.INFO -> InfoStyle
        SystemMessageLevel.WARNING -> WarningStyle
        SystemMessageLevel.ERROR -> ErrorStyle
    }
    is SystemEvent.HandoffFailed, is SystemEvent.HandoffTimeout -> ErrorStyle
    is SystemEvent.IdleWarning -> WarningStyle
    else -> InfoStyle
}
