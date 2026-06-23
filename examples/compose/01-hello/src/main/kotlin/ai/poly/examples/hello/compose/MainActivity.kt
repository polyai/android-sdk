// Copyright PolyAI Limited

package ai.poly.examples.hello.compose

import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.Delivery
import ai.poly.messaging.PolyMessaging
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Rung 01 — the smallest thing that works: `chat()`, render `session.messages`, `send()`.
 * The `01-Hello` quick start.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { ChatScreen() } } }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ChatScreen() {
    // One ChatSession per chat surface. chat() resumes a recent session or creates one.
    val session: ChatSession = remember { PolyMessaging.chat() }
    val messages by session.messages.collectAsStateWithLifecycle()
    val hasEnded by session.hasEnded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScopeCompat()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Two scroll signals: a new bubble arrives (messages.size grows) or the last bubble's text
    // grows in place while streaming (size unchanged) — keying on both keeps the reply in view.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.scrollToBottomEdge()
    }

    // contentWindowInsets(0) so the content manages the bottom inset itself — the composer
    // sits above the gesture nav bar (and the keyboard) via windowInsetsPadding below.
    Scaffold(
        // Expose testTags as view resource-ids so black-box UI Automator flow tests can find them.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = { TopAppBar(title = { Text("Poly Hello") }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
                items(messages, key = { it.id }) { MessageRow(it) }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // Lift above the nav bar (keyboard closed) or the keyboard (open) — union = max, no double-count.
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).testTag("composer"),
                    placeholder = { Text("Message") },
                )
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            input = ""
                            scope.launch { runCatching { session.send(text) } }
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp).testTag("sendButton"),
                    // Gate on hasEnded only — sending stays available while offline/reconnecting.
                    enabled = !hasEnded,
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    when (message) {
        is ChatMessage.User -> Bubble(message.message.text, isUser = true, faded = message.message.delivery == Delivery.PENDING)
        is ChatMessage.Agent -> Bubble(message.message.text, isUser = false)
        is ChatMessage.System -> Text(
            message.message.event.reason ?: "—",
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            textAlign = TextAlign.Center,
            color = Color.Gray,
        )
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean, faded: Boolean = false) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            Modifier
                .background(
                    if (isUser) Color(0xFF2563EB).copy(alpha = if (faded) 0.5f else 1f) else Color(0xFFE5E7EB),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (isUser) Color.White else Color.Black,
        )
    }
}

@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()

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
