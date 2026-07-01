// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

import ai.poly.examples.playground.compose.components.LoadingSkeleton
import ai.poly.examples.playground.compose.components.TimestampSeparator
import ai.poly.examples.playground.compose.components.MessageBubbleView
import ai.poly.examples.playground.compose.components.TypingIndicator
import ai.poly.messaging.ChatMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Matches the web's MAX_MESSAGE_LENGTH cap. */
private const val MAX_MESSAGE_LENGTH = 500

/**
 * The message list with follow-scroll + "New messages" pill, the offline / reconnecting bars
 * pinned ABOVE the composer (06 moves them to the bottom), the chat-ended banner, and the growing
 * composer. Sending stays optimistic — the input is enabled while offline / reconnecting / failed;
 * only a chat that has ended disables it.
 */
@Composable
fun ChatView(
    messages: List<ChatMessage>,
    sendingLabels: Set<UUID>,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    isAgentTyping: Boolean,
    agentAvatarUrl: URI?,
    chatEnded: Boolean,
    isReconnecting: Boolean,
    isConnected: Boolean,
    isReady: Boolean,
    isOnline: Boolean,
    hasFailed: Boolean,
    showTimestamps: Boolean = false,
    onSend: (String) -> Unit,
    onSuggestionTap: (String, UUID) -> Unit,
    onRetry: (String, String?) -> Unit,
    onStartNewConversation: () -> Unit,
    onTyping: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    val inputDisabled = chatEnded
    val sendDisabled = inputDisabled || messageText.trim().isEmpty()
    // disabledReason priority order (accessibility hint only).
    val disabledReason = when {
        !isOnline -> "You're offline."
        hasFailed -> "Connection failed. Pull to retry."
        !isConnected -> "Connecting…"
        !isReady -> "Session not ready."
        chatEnded -> "Chat ended."
        else -> ""
    }
    val shouldShowSkeleton = messages.isEmpty() && !isAgentTyping && !chatEnded && !hasFailed

    // F1: follow the bottom only while the user is parked there; otherwise surface a pill.
    val followBottomState = remember { mutableStateOf(true) }
    var followBottom by followBottomState
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it } // a scroll just settled
            .collect {
                // STICKY follow (F1): a settle AT the bottom resumes following; a settle short
                // of it never cancels it — our own follow animation can land mid-stream just above
                // a freshly-grown bottom, and treating that as "scrolled away" raised the pill and
                // stopped the follow. Only a real user drag away (below) stops it.
                if (!listState.canScrollForward) followBottomState.value = true
            }
    }
    // The user pulling the list up, away from the bottom, is the ONLY thing that stops following.
    val followNested = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y > 0f && listState.canScrollForward) {
                    followBottomState.value = false
                }
                return Offset.Zero
            }
        }
    }
    var hasNewBelow by remember { mutableStateOf(false) }

    fun scrollToBottom() {
        scope.launch {
            listState.scrollToBottomEdge()
        }
    }

    // Staggered scrolls on mount: messages may already exist or still be streaming in.
    LaunchedEffect(Unit) {
        for (delayMs in listOf(200L, 500L, 1_000L)) {
            launch {
                delay(delayMs)
                listState.scrollToBottomEdge()
            }
        }
    }

    // New content of ANY kind: count, streaming text growth, typing, sending labels,
    // suggestions, attachments.
    val last = messages.lastOrNull()
    val lastTextLength = last?.text?.length ?: 0
    val lastSuggestionCount = last?.suggestions?.size ?: 0
    val lastAttachmentCount = last?.attachments?.size ?: 0
    LaunchedEffect(messages.size, lastTextLength, isAgentTyping, lastAttachmentCount) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (followBottom) {
            listState.scrollToBottomEdge()
            // Re-run after a beat so it catches the layout settling as a streaming bubble grows.
            delay(100)
            listState.scrollToBottomEdge()
        } else {
            hasNewBelow = true
        }
    }
    // Sending labels / suggestion changes only nudge the follow-scroll — they never raise
    // the pill.
    LaunchedEffect(sendingLabels, lastSuggestionCount) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (followBottom) {
            delay(100)
            listState.scrollToBottomEdge()
        }
    }
    LaunchedEffect(followBottom) { if (followBottom) hasNewBelow = false }

    // Re-pin to the latest message when returning to the foreground (e.g. tapping the new-message
    // notification) -- a reply may have arrived while backgrounded and a scroll issued then does not
    // always stick across the resume. Honour follow-state so a user who scrolled up keeps their place.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (followBottom) scope.launch {
            // Scroll a few times across the settle window so it still lands at the true bottom after
            // the list re-lays-out and the keyboard (if it reopens) finishes animating.
            repeat(4) {
                val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.scrollToItem(last)
                runCatching { listState.scrollBy(100_000f) }
                delay(120)
            }
        }
        onPauseOrDispose { }
    }

    // Keyboard opening: re-scroll so the latest messages stay above the IME while following
    // (with a 0.15s delayed scroll).
    var isInputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(isInputFocused) {
        if (isInputFocused && followBottom) {
            delay(150)
            listState.scrollToBottomEdge()
        }
    }

    // Announce new agent replies to accessibility services.
    LaunchedEffect(messages.size) {
        val m = (messages.lastOrNull() as? ChatMessage.Agent)?.message ?: return@LaunchedEffect
        if (m.text.isNotEmpty()) {
            view.announceForAccessibility("${m.agentName ?: "Agent"} says: ${m.text}")
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val containerWidth: Dp = maxWidth
            LazyColumn(
                state = listState,
                // Region label without merging children.
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(followNested)
                    .semantics {
                        isTraversalGroup = true
                        contentDescription = "Chat conversation"
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (shouldShowSkeleton) {
                    item { Box(Modifier.padding(top = 4.dp)) { LoadingSkeleton() } }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Timestamp separator above a message that starts a new time group
                        // (and above the very first message).
                        if (showTimestamps &&
                            MessageTimestamp.shouldInsertSeparator(
                                previousMillis = if (index > 0) messages[index - 1].timestamp else null,
                                currentMillis = msg.timestamp,
                            )
                        ) {
                            TimestampSeparator(msg.timestamp)
                        }
                        MessageBubbleView(
                            message = msg,
                            containerWidth = containerWidth,
                            showSendingLabel = sendingLabels.contains(msg.id),
                            showSuggestions = msg.id == messages.lastOrNull()?.id &&
                                msg.suggestions.isNotEmpty() && !chatEnded,
                            showTimestamp = showTimestamps,
                            onSuggestionTap = { text -> onSuggestionTap(text, msg.id) },
                            onRetry = onRetry,
                        )
                    }
                }
                if (isAgentTyping) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .semantics { contentDescription = "Agent is typing" },
                        ) {
                            TypingIndicator(avatarUrl = agentAvatarUrl)
                        }
                    }
                }
            }

            if (hasNewBelow) {
                NewMessagesPill(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    onClick = {
                        scrollToBottom()
                        hasNewBelow = false
                        followBottom = true
                    },
                )
            }
        }

        // 06 pins the status bars to the BOTTOM, directly above the composer.
        if (!isOnline) OfflineBar()
        if (isReconnecting) ReconnectingBar()
        HorizontalDivider(color = SystemGray5)
        if (chatEnded) {
            ChatEndedBanner(onStartNewConversation)
        } else {
            InputBar(
                messageText = messageText,
                onMessageTextChange = onMessageTextChange,
                inputDisabled = inputDisabled,
                disabledReason = disabledReason,
                sendDisabled = sendDisabled,
                focusRequester = focusRequester,
                onFocusChanged = { isInputFocused = it },
                onSendTrimmed = { trimmed ->
                    followBottom = true // follow the agent's reply while we wait
                    onSend(trimmed)
                },
                onTyping = onTyping,
            )
        }
    }
}

@Composable
private fun OfflineBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SystemRed.copy(alpha = 0.18f))
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription =
                    "You're offline. Messages will not be delivered until the connection is restored."
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_wifi_off),
            contentDescription = null,
            tint = SystemRed,
            modifier = Modifier.size(14.dp),
        )
        Text("You're offline", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SystemRed)
    }
}

@Composable
private fun ReconnectingBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SystemYellow.copy(alpha = 0.15f))
            .padding(vertical = 6.dp)
            .semantics { contentDescription = "Reconnecting" },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = SecondaryLabel)
        Text("Reconnecting...", fontSize = 12.sp, color = SecondaryLabel)
    }
}

@Composable
private fun ChatEndedBanner(onStartNewConversation: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarBackground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "This conversation has ended. Please start a new chat to continue.",
            fontSize = 15.sp,
            color = SecondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Button(
            onClick = onStartNewConversation,
            colors = ButtonDefaults.buttonColors(containerColor = SystemBlue),
        ) {
            Text("Start New Conversation", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InputBar(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    inputDisabled: Boolean,
    disabledReason: String,
    sendDisabled: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onSendTrimmed: (String) -> Unit,
    onTyping: () -> Unit,
) {
    fun submit(raw: String) {
        val trimmed = raw.trim()
        onMessageTextChange("")
        if (!inputDisabled && trimmed.isNotEmpty()) onSendTrimmed(trimmed)
        focusRequester.requestFocus() // keep typing flow going
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarBackground)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(SystemGray6)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (messageText.isEmpty()) {
                Text("Message...", fontSize = 17.sp, color = SecondaryLabel)
            }
            BasicTextField(
                value = messageText,
                onValueChange = { newValue ->
                    // Growing field: Return inserts '\n' — detect a trailing newline and treat
                    // it as a send; otherwise cap the length and broadcast typing.
                    if (newValue.endsWith("\n")) {
                        submit(newValue.dropLast(1))
                    } else {
                        onMessageTextChange(
                            if (newValue.length > MAX_MESSAGE_LENGTH) newValue.take(MAX_MESSAGE_LENGTH) else newValue,
                        )
                        if (newValue.isNotEmpty()) onTyping()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // UI Automator handle (By.res) on the composer field.
                    .testTag("composer")
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .semantics {
                        contentDescription = "composer"
                        // Accessibility hint: the disabled reason, else the typing prompt.
                        stateDescription = if (inputDisabled) "Input disabled. $disabledReason" else "Type a message"
                    },
                enabled = !inputDisabled,
                maxLines = 5,
                textStyle = TextStyle(fontSize = 17.sp, color = Color.Black),
                cursorBrush = SolidColor(SystemBlue),
            )
        }

        // Send button — blue when enabled, gray when disabled.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (sendDisabled) SystemGray else SystemBlue)
                .clickable(enabled = !sendDisabled) { submit(messageText) }
                .semantics { contentDescription = "Send message" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_upward),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun NewMessagesPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(SystemBlue)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Scroll to newest messages" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_arrow_down),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Text("New messages", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/**
 * Scroll so the BOTTOM of the last item is visible. A streaming agent bubble grows taller as text
 * arrives; `animateScrollToItem(index)` alone only pins the item's TOP to the viewport top, so once
 * the bubble outgrows the viewport the newest text streams in below the fold and the view appears to
 * "stop following." Overshoot by however much the last item overflows the viewport to pin its bottom.
 */
private suspend fun LazyListState.scrollToBottomEdge() {
    val lastIndex = (layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    // Bring the last item into view first (cheap if already visible) so it can be measured;
    // an overflow computed off a not-yet-laid-out item is 0 and would only scroll to its top.
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) scrollToItem(lastIndex)
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val overflow = layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == lastIndex }
        ?.let { (it.size - viewportHeight).coerceAtLeast(0) }
        ?: 0
    animateScrollToItem(lastIndex, overflow)
}
