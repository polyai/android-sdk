// Copyright PolyAI Limited

package ai.poly.examples.resilience.compose

import ai.poly.examples.resilience.compose.components.ConnectionBanner
import ai.poly.examples.resilience.compose.components.LoadingSkeleton
import ai.poly.examples.resilience.compose.components.MessageBubbleView
import ai.poly.examples.resilience.compose.components.OfflineBanner
import ai.poly.examples.resilience.compose.components.TypingIndicator
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ConnectionStatus
import ai.poly.messaging.Delivery
import ai.poly.messaging.PolyMessaging
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Matches the web's MAX_MESSAGE_LENGTH cap (F2/F3). */
private const val MAX_MESSAGE_LENGTH = 500

/**
 * The `04-Resilience` chat screen. Adds, over
 * 03-RichContent: a device-offline banner stacked above the SDK's reconnect banner, a pre-handshake
 * loading skeleton, and a full-screen terminal-error screen (with manual retry) when the SDK has
 * exhausted its reconnect budget. (04 drops the 03 new-message notifier.)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen() {
    // One ChatSession per chat surface. chat() resumes a recent session or creates one.
    val session = remember { PolyMessaging.chat() }
    val messages by session.messages.collectAsStateWithLifecycle()
    val connection by session.connection.collectAsStateWithLifecycle()
    val isTyping by session.isAgentTyping.collectAsStateWithLifecycle()
    val hasEndedState by session.hasEnded.collectAsStateWithLifecycle()
    // A clean terminal close (the server closing 1000 without a SESSION_END) latches the
    // SDK's send gate shut and never reconnects — treat it as ended so the composer
    // doesn't silently no-op (the backend sometimes ends conversations this way).
    val hasEnded = hasEndedState || connection is ConnectionStatus.Closed
    val failureReason by session.failureReason.collectAsStateWithLifecycle()
    // isReady stays false until the REST + WebSocket handshake completes; gates the loading skeleton.
    val isReady by session.isReady.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Track *device* connectivity (ConnectivityManager), independent of the SDK socket — drives the
    // red offline banner. Registered while this screen is composed.
    val context = LocalContext.current
    val networkMonitor = remember { NetworkMonitor(context) }
    DisposableEffect(networkMonitor) {
        networkMonitor.start()
        onDispose { networkMonitor.stop() }
    }
    val isOnline by networkMonitor.isOnline.collectAsStateWithLifecycle()

    // The typing indicator + the avatar track the most recent agent message.
    val lastAgent = messages.lastOrNull { it is ChatMessage.Agent } as? ChatMessage.Agent
    val lastAgentAvatar = lastAgent?.message?.avatarUrl
    // Streaming grows the last agent bubble's text in place (size unchanged), so follow its length.
    val lastAgentTextLen = lastAgent?.message?.text?.length ?: 0

    // F1: follow the bottom only while the user is parked there; otherwise surface a pill.
    // `followBottom` is updated ONLY when a *user* scroll settles (recording whether they ended at
    // the exact bottom). Streaming growth / new messages never flip it, so auto-scroll keeps the
    // stream pinned when at the bottom yet never fights a user who has scrolled up to read history.
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

    LaunchedEffect(messages.size, lastAgentTextLen, isTyping) {
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
    LaunchedEffect(followBottom) { if (followBottom) hasNewBelow = false }

    val sendDisabled = input.trim().isEmpty() || hasEnded

    val sendText: (String) -> Unit = { raw ->
        val trimmed = raw.trim()
        input = ""
        if (trimmed.isNotEmpty() && !hasEnded) {
            followBottom = true // re-arm auto-follow so the sent message + reply scroll in
            scope.launch { runCatching { session.send(trimmed) } }
        }
    }
    val send: () -> Unit = { sendText(input) }
    val onInputChange: (String) -> Unit = { newValue ->
        // Growing field: Return inserts '\n' — detect a
        // trailing newline and treat it as a send; otherwise cap the length and broadcast typing.
        if (newValue.endsWith("\n")) {
            sendText(newValue.dropLast(1))
        } else {
            input = if (newValue.length > MAX_MESSAGE_LENGTH) newValue.take(MAX_MESSAGE_LENGTH) else newValue
            // Only broadcast typing for real keystrokes (when the value is non-empty).
            if (input.isNotEmpty()) scope.launch { runCatching { session.sendTyping() } }
        }
    }

    Scaffold(
        // Expose compose testTags as resource-ids so the black-box UI Automator flow test can
        // match By.res(...) for its test handles.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                actions = {
                    // Hidden on the terminal-error screen — there's nothing to end there.
                    if (!hasEnded && failureReason == null) {
                        TextButton(
                            onClick = { scope.launch { runCatching { session.end() } } },
                            colors = ButtonDefaults.textButtonColors(contentColor = SystemBlue),
                        ) {
                            Text("End Chat")
                        }
                    }
                },
            )
        },
        // The content owns the bottom inset (composer rises above the nav bar / keyboard).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val reason = failureReason
            if (reason != null) {
                // Terminal state: the SDK exhausted its reconnect budget. Replace the entire chat
                // surface with a full-screen retry CTA.
                TerminalErrorScreen(
                    reason = reason,
                    onRetry = { scope.launch { runCatching { session.client.resume() } } },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    // OS-level offline pill (red), stacked ABOVE the SDK's reconnect banner (yellow).
                    // Both can be visible at once — they measure different things.
                    OfflineBanner(isOnline)
                    ConnectionBanner(connection)

                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val containerWidth = maxWidth
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().nestedScroll(followNested),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            if (!isReady && messages.isEmpty()) {
                                // Pre-handshake on a cold start: show the skeleton until isReady flips
                                // or the first message lands. Warm resumes already have messages → skip.
                                item { LoadingSkeleton() }
                            } else {
                                items(messages, key = { it.id }) { msg ->
                                    MessageBubbleView(
                                        message = msg,
                                        containerWidth = containerWidth,
                                        onRetry = { draftId, text ->
                                            // Drop the failed draft first so the retry doesn't leave a duplicate bubble.
                                            session.removeMessage(draftId)
                                            scope.launch { runCatching { session.send(text) } }
                                        },
                                        showSendingLabel = showSendingLabel(msg),
                                        // Pills attach under the last message and clear as soon as the user sends.
                                        showSuggestions = !hasEnded && msg.id == messages.lastOrNull()?.id,
                                        onSuggestionTap = { text ->
                                            session.clearSuggestions(msg.id)
                                            scope.launch { runCatching { session.send(text) } }
                                        },
                                    )
                                }
                                if (isTyping) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                            TypingIndicator(avatarUrl = lastAgentAvatar)
                                        }
                                    }
                                }
                            }
                        }

                        if (hasNewBelow) {
                            NewMessagesPill(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                                onClick = {
                                    followBottom = true
                                    scope.launch {
                                        listState.scrollToBottomEdge()
                                    }
                                    hasNewBelow = false
                                },
                            )
                        }
                    }

                    if (hasEnded) {
                        ChatEndedFooter(onStartNew = {
                            scope.launch { runCatching { session.client.startNewSession() } }
                        })
                    } else {
                        InputBar(
                            input = input,
                            onInputChange = onInputChange,
                            onSend = send,
                            sendDisabled = sendDisabled,
                        )
                    }
                }
            }
        }
    }
}

private fun showSendingLabel(message: ChatMessage): Boolean =
    message is ChatMessage.User && message.message.delivery == Delivery.PENDING

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    sendDisabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarBackground)
            // Lift above the nav bar (keyboard closed) or the keyboard (open) — union = max.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // A plain rounded composer (systemGray6), growing up to 5 lines. Return sends.
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(SystemGray6)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (input.isEmpty()) {
                Text("Message...", fontSize = 17.sp, color = SecondaryLabel)
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                // The "composer" test handle.
                modifier = Modifier.fillMaxWidth().testTag("composer"),
                maxLines = 5,
                textStyle = TextStyle(fontSize = 17.sp, color = Color.Black),
                cursorBrush = SolidColor(SystemBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
        }

        // arrow.up.circle.fill — blue when enabled, gray when disabled.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (sendDisabled) SystemGray else SystemBlue)
                .clickable(enabled = !sendDisabled, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_upward),
                contentDescription = "Send",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ChatEndedFooter(onStartNew: () -> Unit) {
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
            onClick = onStartNew,
            colors = ButtonDefaults.buttonColors(containerColor = SystemBlue),
        ) {
            Text("Start New Conversation", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
        Text("↓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
