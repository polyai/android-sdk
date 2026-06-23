# 02-Standard (Compose)

The 80% chat — adds typing indicator, connection banner, suggestion pills, delivery state (`Sending…` / `Failed` + retry), end + start new chat, and a failure overlay on top of [`01-Hello`](../01-hello/).

Setup, rendering, and `send()` are unchanged from [`01-Hello`](../01-hello/) — read it first. This README only covers what's new.

## Run it

Open the repo in Android Studio and run the `:examples:compose:02-standard` module, or from the repo root:

```bash
./gradlew :examples:compose:02-standard:installDebug
```

Then launch the installed app (the launcher `MainActivity`, which hosts `ChatScreen`).

Set your API key in `src/main/kotlin/ai/poly/examples/standard/compose/StandardApplication.kt` (currently `"YOUR_API_KEY"`). The committed default targets `Environment.US` — add `.cluster("dev")` / a `hostIdentifier` only if you need a non-default cluster.

## What this example demonstrates

- Typing indicator — `session.isAgentTyping`, `session.sendTyping()`
- Reconnect banner — `session.connection` (`ConnectionStatus.Reconnecting`)
- Suggestion pills — `AgentMessage.suggestions`, `session.clearSuggestions(id)`
- End / start-new chat — `session.end()`, `session.hasEnded`, `session.client.startNewSession()`
- Delivery state + retry — `UserMessage.delivery`, `session.removeMessage(draftId)`
- Failure overlay — `session.failureReason`, `session.client.resume()`
- Keyboard handling — `WindowInsets.ime` + `windowInsetsPadding` (no manual layout math)

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../README.md#integration-guide); this example shows them composed into one chat screen.

## How it works

`ChatScreen()` is a single `@Composable` that collects every piece of SDK state off the one `ChatSession` and re-renders on change:

```kotlin
// One ChatSession per chat surface. chat() resumes a recent session or creates one.
val session = remember { PolyMessaging.chat() }
val messages by session.messages.collectAsStateWithLifecycle()
val connection by session.connection.collectAsStateWithLifecycle()
val isTyping by session.isAgentTyping.collectAsStateWithLifecycle()
val hasEndedState by session.hasEnded.collectAsStateWithLifecycle()
// Also treat a terminal clean-closed connection as ended (see "Under the hood" below).
val hasEnded = hasEndedState || connection is ConnectionStatus.Closed
val failureReason by session.failureReason.collectAsStateWithLifecycle()
```

`PolyMessaging.chat()` is held in `remember` so it survives recomposition; each `StateFlow` collected with `collectAsStateWithLifecycle()` re-renders the screen when it changes. Suspending calls (`send`, `sendTyping`, `end`, `resume`, …) run on a `rememberCoroutineScope()` wrapped in `runCatching { }` to swallow transient errors.

**Under the hood:** a clean terminal close (the server closing with 1000 but no `SESSION_END`) latches the SDK's send gate shut and never reconnects, so `ConnectionStatus.Closed` is folded into `hasEnded` — that shows the ended footer instead of leaving a composer whose sends silently no-op.

Each subsection leads with **the SDK call(s)** (the actual API), then shows **how it's wired into the chat screen**.

### Typing indicator — `components/TypingIndicator.kt`

Listen for the agent + announce your own typing:

```kotlin
session.isAgentTyping     // StateFlow<Boolean> — true while the agent composes;
                          // auto-clears on next agent message or after the typing timeout (~10s)

session.sendTyping()      // suspend fun — safe every keystroke; the SDK throttles STARTED frames
                          // to ≤1 per 3s and auto-emits STOPPED ~5s after your last call

// The avatar next to the dots tracks the most recent agent message:
val lastAgent = messages.lastOrNull { it is ChatMessage.Agent } as? ChatMessage.Agent
val lastAgentAvatar = lastAgent?.message?.avatarUrl
```

In the composable — the dots ride at the bottom of the message list and typing is broadcast on every input change:

```kotlin
if (isTyping) {
    item {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TypingIndicator(avatarUrl = lastAgentAvatar)   // align with the message bubbles
        }
    }
}

// In the composer's onValueChange, after capping the text:
scope.launch { runCatching { session.sendTyping() } }
```

`TypingIndicator` is your own small composable (`components/TypingIndicator.kt`) — the agent avatar next to three dots that bob up-and-down in a staggered `infiniteRepeatable` loop inside a gray rounded bubble.

**Under the hood:** `isAgentTyping` is SDK-managed — true while the agent composes (driven by its thinking/streaming signals), auto-cleared on the next agent message or after the typing timeout (~10s), so you never run a timer. `sendTyping()` throttles outgoing STARTED frames to ≤1 per 3s and auto-emits STOPPED ~5s after your last call, so it's safe to fire on every keystroke.

*See [Integration guide › Typing](../../../README.md#typing).*

### Connection banner — `components/ConnectionBanner.kt`

Show only during transient reconnects:

```kotlin
session.connection   // StateFlow<ConnectionStatus> — a sealed type:
                     //   Idle / Connecting / Open / Reconnecting(attempt) /
                     //   Closing / Closed(...) / Failed(reason)
                     // — show a banner only on Reconnecting (transient drops resolve as
                     //   Open → Reconnecting(n) → Open, no Closed flash).
                     //   Failed is terminal — handled by the failure overlay below.
```

`ConnectionBanner` renders only on `Reconnecting` and stays empty otherwise:

```kotlin
@Composable
fun ConnectionBanner(status: ConnectionStatus) {
    if (status is ConnectionStatus.Reconnecting) {
        Row(
            Modifier.fillMaxWidth()
                .background(SystemYellow.copy(alpha = 0.15f))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SecondaryLabel)
            Text("Reconnecting...", Modifier.padding(start = 8.dp), fontSize = 12.sp, color = SecondaryLabel)
        }
    }
}
```

It sits at the top of the `Column`, above the message list, so it pushes the chat down only while a reconnect is in flight.

**Under the hood:** `session.connection` is SDK-driven — a transient drop surfaces as `Open → Reconnecting(n) → Open` (auto-reconnect with backoff and jitter, no `Closed` flash), so you only need a banner on `Reconnecting`. `Failed` arrives only after the reconnect budget is exhausted (handled by the failure overlay below).

*See [Integration guide › Connection & reconnect](../../../README.md#connection--reconnect).*

### Suggestion pills — under the last agent message

Render + dismiss the agent's quick replies:

```kotlin
agent.suggestions   // List<ResponseSuggestion> — agent messages only (user/system don't have these)
                    // Each: ResponseSuggestion(messageText: String, ...)
                    // Show pills only on the LAST agent message; they scroll with history.

session.clearSuggestions(message.id)   // message.id is a UUID; empties them locally so pills vanish before send() resolves

session.send(suggestion.messageText)   // suspend fun — re-uses the normal send path
```

In `MessageBubbleView`, pills render under the agent bubble; `ChatScreen` decides which row is "last" and clears on tap:

```kotlin
// ChatScreen — per item:
MessageBubbleView(
    message = msg,
    // Pills attach under the last message and clear as soon as the user sends.
    showSuggestions = !hasEnded && msg.id == messages.lastOrNull()?.id,
    onSuggestionTap = { text ->
        session.clearSuggestions(msg.id)
        scope.launch { runCatching { session.send(text) } }
    },
)

// MessageBubbleView — inside the Agent branch:
if (showSuggestions && m.suggestions.isNotEmpty()) {
    SuggestionRow(
        suggestions = m.suggestions.map { it.messageText },
        onTap = { onSuggestionTap(it) },
    )
}
```

`SuggestionRow` (`components/SuggestionRow.kt`) is a `horizontalScroll` row of blue-tinted capsule pills. Pills sit with the reply that offered them and scroll with the conversation. They show only while the agent's message is the last one — as soon as the user sends, their message becomes last (`showSuggestions` goes false) and the pills disappear until the agent replies again.

**Under the hood:** `AgentMessage.suggestions` are quick replies the agent attached to *that* message (agent messages only). `clearSuggestions(id)` empties them in the model so the pills vanish before `send(...)` resolves — feels instant.

*See [Integration guide › Suggestions](../../../README.md#suggestions-quick-replies).*

### End chat + Start new chat — `ChatScreen.kt`

End the session + start a fresh one:

```kotlin
session.end()    // suspend fun — user-initiated end; flips hasEnded; no "conversation ended" pill

session.hasEnded // StateFlow<Boolean> — true after end() OR an agent-/server-initiated end
                 //   (server-end also appends a "Conversation ended" System message)

session.client.startNewSession()   // suspend fun — begin a fresh conversation on the same surface
                                   //   — ChatSession auto-clears messages + resets hasEnded
                                   //   when the session id changes
```

The "End Chat" action lives in the `TopAppBar`; when the session ends, the composer is swapped for a "start new" footer:

```kotlin
TopAppBar(
    title = { Text("Chat") },
    actions = {
        if (!hasEnded) {
            TextButton(onClick = { scope.launch { runCatching { session.end() } } }) { Text("End Chat") }
        }
    },
)

// Below the list:
if (hasEnded) {
    ChatEndedFooter(onStartNew = {
        scope.launch { runCatching { session.client.startNewSession() } }
    })
} else {
    InputBar(/* ... */)
}
```

`ChatEndedFooter` shows "This conversation has ended. Please start a new chat to continue." with a "Start New Conversation" button. The End Chat action lives in the `TopAppBar`'s trailing `actions`.

**Under the hood:** `session.end()` flips `hasEnded`. `startNewSession()` creates a fresh session — when the session id changes, `ChatSession` clears `messages` and resets the latched flags for you, so no view bookkeeping needed.

*See [Integration guide › Starting, resuming & ending a session](../../../README.md#starting-resuming--ending-a-session).*

### Delivery state + retry — inside the user bubble (`components/MessageBubbleView.kt`)

Track delivery + retry a failed send:

```kotlin
m.delivery   // Delivery enum (user messages only):
             //   PENDING — sent optimistically; bubble shows immediately
             //   SENT    — server echoed (matched by local id)
             //   FAILED  — retries (up to 3×) exhausted; show "Tap to retry"

session.removeMessage(m.draftId)   // drop the failed draft so the retry doesn't duplicate

session.send(m.text)   // suspend fun — re-send the same text on retry
```

In `MessageBubbleView`, the user bubble restyles per state and a tap re-sends:

```kotlin
is ChatMessage.User -> {
    val m = message.message
    val failed = m.delivery == Delivery.FAILED
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.Bottom) {
        if (failed) RetryButton(onClick = { onRetry(m.draftId, m.text) })   // red "!" circle
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = m.text,
                color = if (failed) Color.Black else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (failed) SystemRed.copy(alpha = 0.15f) else SystemBlue),
            )
            if (showSendingLabel && m.delivery == Delivery.PENDING) {
                Text("Sending...", fontSize = 11.sp, color = SecondaryLabel)
            } else if (failed) {
                Text("Tap to retry", fontSize = 11.sp, color = SystemRed)
            }
        }
    }
}

// ChatScreen wires the retry:
onRetry = { draftId, text ->
    session.removeMessage(draftId)
    scope.launch { runCatching { session.send(text) } }
}
```

`ChatScreen` computes `showSendingLabel(msg)` (true only while a user message is `PENDING`). The retry control is a small red circle with a white "!", drawn as a shape since there's no system glyph.

**Tip:** delay the "Sending..." label by ~500 ms so fast confirmations don't flash it.

**Under the hood:** `UserMessage.delivery` is optimistic — `PENDING` immediately, then the SDK matches the server echo (via a local id) → `SENT`; if no echo arrives after retries (up to 3×) it settles on `FAILED`. You only render it; `removeMessage(draftId)` drops the failed draft so a retry doesn't leave a duplicate bubble.

*See [Integration guide › Delivery state & retry](../../../README.md#delivery-state--retry).*

### Failure overlay — `ChatScreen.kt`

Surface a terminal failure + offer retry:

```kotlin
session.failureReason   // StateFlow<PolyError?> — non-null when the chat can't auto-recover:
                        //   invalid apiKey (initial connect 401/403),
                        //   reconnect budget exhausted,
                        //   session expired (idle past sessionTimeoutSeconds, default 10 min)

session.client.resume() // suspend fun — re-attempt the connection from the overlay's retry button
```

The overlay is the last child of the root `Box`, so it floats on top of the chat:

```kotlin
failureReason?.let { reason ->
    FailureOverlay(
        reason = reason,
        onReconnect = { scope.launch { runCatching { session.client.resume() } } },
    )
}

// FailureOverlay:
Text("Connection lost", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
// PolyError isn't a friendly localized type — use its debug description.
Text(reason.debugDescription, fontSize = 12.sp, color = SecondaryLabel)
Button(onClick = onReconnect) { Text("Reconnect") }
```

**Under the hood:** `failureReason` is set whenever the chat can't auto-recover — an invalid `apiKey` rejected at the initial connect, the auto-reconnect budget exhausted, or the session expiring. Recovery is consumer-driven — call `session.client.resume()` to retry.

*See [Integration guide › Terminal errors](../../../README.md#terminal-errors).*

### Keyboard handling — `ChatScreen.kt`

The SDK doesn't get involved here — it's pure Compose insets. Instead of `adjustResize` plus layout math, the composer reads the window insets directly and lifts above whichever is taller, the keyboard (IME) or the nav bar:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .background(BarBackground)
        // Lift above the nav bar (keyboard closed) or the keyboard (open) — union = max.
        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        .padding(horizontal = 16.dp, vertical = 8.dp),
) { /* composer + send button */ }
```

The `Scaffold` sets `contentWindowInsets = WindowInsets(0, 0, 0, 0)` so the content owns the bottom inset itself. The send button is a blue circle with an up-arrow vector (`R.drawable.ic_arrow_upward`).

*See [Integration guide › Avatars & keyboard](../../../README.md#avatars--keyboard).*

## What this example skips

- attachments, URL cards, call actions → [`03-richcontent/`](../03-richcontent/)
- offline detection, full-screen terminal error → [`04-resilience/`](../04-resilience/)
- live agent handoff → [`05-handoff/`](../05-handoff/)

---

- **Views counterpart:** [`examples/views/02-standard/`](../../views/02-standard/)
- **SDK reference:** root [README → Integration guide](../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../README.md#install)
