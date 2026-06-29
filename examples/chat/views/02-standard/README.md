# 02-Standard (Views)

The 80% chat in classic Android Views — adds typing indicator, connection banner, suggestion pills, delivery state (`Sending…` + failed retry), end + start new chat, and a failure overlay on top of [`01-hello`](../01-hello/). The Views counterpart of [`examples/compose/02-standard/`](../../compose/02-standard/).

- **Interface:** A single view-binding layout (`res/layout/activity_chat.xml`) holds the banner, message list (`RecyclerView`), the floating "New messages" pill, input bar, chat-ended footer, and the terminal-failure overlay. The "End" affordance is an `ActionBar` options-menu item built in code. Each list row (`item_message.xml`, `item_suggestions.xml`, `item_typing.xml`) is styled per kind in `ChatAdapter`.
- **Lifecycle:** `StandardApplication` (`android.app.Application`) initializes the SDK once at launch. `ChatActivity` is the launched `ComponentActivity`.

Setup and `send()` are unchanged from [`01-hello`](../01-hello/) — read it first. This README only covers what's new.

## Run it

Open the repo in Android Studio and run the module, or from the repo root:

```bash
./gradlew :examples:views:02-standard:installDebug
```

Then launch `ChatActivity`. Set your API key in `src/main/kotlin/ai/poly/examples/standard/views/StandardApplication.kt` (the `API_KEY` constant is currently `"YOUR_API_KEY"`); the committed default is `Environment.US` — add `.cluster("dev")` / a `hostIdentifier` only if needed.

## What this example demonstrates

- Typing indicator — `session.isAgentTyping`, `session.sendTyping()`
- Reconnect banner — `session.connection` (`ConnectionStatus.Reconnecting`)
- Suggestion pills — `ChatMessage.suggestions`, `session.clearSuggestions(messageId)`
- End / start-new chat — `session.end()`, `session.hasEnded`, `session.client.startNewSession()`
- Delivery state + retry — `UserMessage.delivery`, plus an inline retry button on `FAILED` bubbles (`session.send(text)` again)
- Failure overlay — `session.failureReason`, `session.client.resume()`
- Keyboard ride-along — `windowSoftInputMode=adjustResize` + `WindowInsets` padding

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide); this example shows them composed into one activity.

## How it works

Each subsection leads with **the SDK call(s)** (the actual API), then shows **how it's wired into the activity / adapter**.

All flows are collected in one place — `ChatActivity.bind()` launches a `repeatOnLifecycle(STARTED)` block and `collect`s each flow on its own child coroutine:

```kotlin
private fun bind() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { session.messages.collect { messages -> latestMessages = messages; refresh() } }
            launch { session.connection.collect { /* banner */ } }
            launch { session.isAgentTyping.collect { typing -> latestTyping = typing; refresh() } }
            launch { session.hasEnded.collect { /* input bar / ended footer / menu */ } }
            launch { session.failureReason.collect { /* overlay */ } }
        }
    }
}
```

`refresh()` rebuilds a flat `List<ListItem>` (`buildItems()`) and hands it to the `ChatAdapter`.

### Typing indicator — `ChatActivity.kt` + `item_typing.xml`

Listen for the agent + announce your own typing:

```kotlin
session.isAgentTyping       // StateFlow<Boolean> — true while the agent composes;
                            // auto-clears on next agent message or after the typing timeout (~10s)

session.sendTyping()        // suspend; safe every keystroke — SDK throttles STARTED frames
                            // to ≤1 per 3s and auto-emits STOPPED ~5s after your last call
```

In the activity — fire `sendTyping()` on every keystroke, and append a typing-footer row when the flow is true:

```kotlin
binding.composer.doAfterTextChanged { text ->
    updateSendEnabled()
    if (!text.isNullOrEmpty()) lifecycleScope.launch { runCatching { session.sendTyping() } }
}

// In buildItems(), after the messages + suggestions rows:
if (latestTyping) {
    val avatar = (msgs.lastOrNull { it is ChatMessage.Agent } as? ChatMessage.Agent)?.message?.avatarUrl
    items.add(ListItem.Typing(avatar))
}
```

The `TypingHolder` shows the agent avatar plus three animated dots (`ObjectAnimator` on `translationY`, staggered by `startDelay`) in a gray bubble. The animation is started on bind and cancelled in `onViewRecycled`.

**Under the hood:** `isAgentTyping` is SDK-managed — true while the agent composes (driven by its thinking/streaming signals), auto-cleared on the next agent message or after the typing timeout (~10s), so you never run a timer. `sendTyping()` throttles outgoing STARTED frames to ≤1 per 3s and auto-emits STOPPED ~5s after your last call, so it's safe to fire on every keystroke.

*See [Integration guide › Typing](../../../../README.md#typing).*

### Connection banner — `ChatActivity.kt` + `activity_chat.xml`

Show only during transient reconnects:

```kotlin
session.connection   // StateFlow<ConnectionStatus> — subtypes:
                     //   Idle / Connecting / Open / Reconnecting(attempt) /
                     //   Closing / Closed(_) / Failed(reason)
                     // — show a banner only on Reconnecting (transient drops resolve as
                     //   Open → Reconnecting(n) → Open, no Closed flash).
                     //   Failed is terminal — handled by the failure overlay below.
```

In the activity — toggle the banner's visibility:

```kotlin
launch {
    session.connection.collect { status ->
        binding.banner.visibility =
            if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
    }
}
```

The banner (`@id/banner`) is a yellow pill — a small `ProgressBar` spinner plus a "Reconnecting..." label, tinted with `Palette.systemYellow15` (systemYellow @ 15%, defined in `Palette.kt`). It's laid out above the list and starts `gone`.

**Under the hood:** `session.connection` is SDK-driven — a transient drop surfaces as `Open → Reconnecting(n) → Open` (auto-reconnect with backoff and jitter, no `Closed` flash), so you only need to react to `Reconnecting`. `Failed` arrives only after the reconnect budget is exhausted (handled by the failure overlay below).

*See [Integration guide › Connection & reconnect](../../../../README.md#connection--reconnect).*

### Suggestion pills — under the last message

Render + dismiss the agent's quick replies:

```kotlin
message.suggestions   // List<ResponseSuggestion> on a ChatMessage; agent messages carry them.
                      // Each: ResponseSuggestion(messageText: String, ...)
                      // Show pills only on the LAST message; they scroll with history.

session.clearSuggestions(messageId)   // empties them locally so pills vanish before send() resolves

session.send(suggestion.messageText)  // suspend; re-uses your normal send path
```

In the activity — `buildItems()` appends a dedicated `ListItem.Suggestions` row after the last message while the chat is live; the adapter renders it via `SuggestionsHolder`:

```kotlin
// buildItems():
if (!isEnded()) {
    val last = msgs.lastOrNull()
    if (last != null && last.suggestions.isNotEmpty()) {
        items.add(ListItem.Suggestions(last.id, last.suggestions))
    }
}
```

The adapter's `onSuggestionTap` clears then sends — wired once when the adapter is constructed:

```kotlin
private val adapter = ChatAdapter(
    onRetry = { draftId, text ->
        session.removeMessage(draftId)   // drop the failed draft so the retry doesn't duplicate
        lifecycleScope.launch { runCatching { session.send(text) } }
    },
    onSuggestionTap = { messageId, suggestion ->
        session.clearSuggestions(messageId)
        lifecycleScope.launch { runCatching { session.send(suggestion.messageText) } }
    },
)
```

`SuggestionsHolder` lays out one pill `TextView` per suggestion in a `HorizontalScrollView` (`item_suggestions.xml`) — rounded, `Palette.systemBlue` text on a `systemBlue10` background.

**Under the hood:** `suggestions` are quick replies the agent attached to *that* message. `clearSuggestions(messageId)` empties them in the model so the pills vanish before `send(...)` resolves — feels instant. Sending appends the user message as the new last message, so the suggestions row falls out of the rebuilt list naturally.

*See [Integration guide › Suggestions](../../../../README.md#suggestions-quick-replies).*

### End + Start new chat — `ChatActivity.kt`

End the session + start a fresh one:

```kotlin
session.end()    // suspend; user-initiated end; flips hasEnded; no "conversation ended" pill

session.hasEnded   // StateFlow<Boolean> — true after end() OR an agent-/server-initiated end
                   // (server-end also appends a "conversation ended" System message)

session.client.startNewSession()   // suspend; begin a fresh conversation on the same surface
                                    // — ChatSession auto-clears messages + resets hasEnded
                                    // when the session id changes
```

In the activity — the "End" menu item ends, the footer button starts new, and `hasEnded` drives which surface is shown:

```kotlin
override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == MENU_END) {
        lifecycleScope.launch { runCatching { session.end() } }
        return true
    }
    return super.onOptionsItemSelected(item)
}

binding.startNew.setOnClickListener {
    lifecycleScope.launch { runCatching { session.client.startNewSession() } }
}

launch {
    session.hasEnded.collect { ended ->
        endedState = ended
        applyEndedUi()            // swaps the input bar for the ended footer
        invalidateOptionsMenu()   // hides the "End" menu item once ended
        updateSendEnabled()
        refresh()
    }
}

private fun applyEndedUi() {
    val ended = isEnded()   // endedState || closedState
    binding.composer.isEnabled = !ended
    binding.inputContainer.visibility = if (ended) View.GONE else View.VISIBLE
    binding.chatEndedView.visibility = if (ended) View.VISIBLE else View.GONE
}
```

> The examples also treat a **terminal clean-closed connection** as ended (`closedState`, set when
> `session.connection` reaches `ConnectionStatus.Closed` — e.g. the server closing `1000` without a
> `SESSION_END`): the SDK latches its send gate shut and won't reconnect, so the UI shows the same
> ended footer instead of a composer whose sends would silently vanish.

The "End" item is registered in `onCreateOptionsMenu` and hidden in `onPrepareOptionsMenu` when `endedState` is true (the framework re-queries it via `invalidateOptionsMenu()`).

**Under the hood:** `session.end()` flips `hasEnded`. `startNewSession()` creates a fresh session — when the session id changes, `ChatSession` clears `messages` and resets the latched flags for you.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### Delivery state + retry — `ChatAdapter.kt` + `item_message.xml`

Track delivery + retry a failed send:

```kotlin
m.delivery   // Delivery enum (user messages only):
             //   PENDING  — sent optimistically; bubble shows immediately
             //   SENT     — server echoed (matched by local id)
             //   FAILED   — couldn't be confirmed (offline at send time, or the
             //              connection dropped while in flight); show retry affordance

session.send(m.text)            // re-send the same text
```

`MessageHolder` lays the bubble plus a left-side retry button (`@id/retry`, a circular `!` glyph) and a "Sending…/Tap to retry" caption below the bubble (`@id/delivery`). The retry callback is surfaced by the adapter and wired by the activity:

```kotlin
// ChatAdapter.MessageHolder.bind() — user branch:
val failed = m.delivery == Delivery.FAILED
if (failed) {
    b.bubble.background = rounded(Palette.systemRed15, 18f)   // red @ 15% capsule
    b.bubble.setTextColor(Palette.label)
    b.retry.visibility = View.VISIBLE
    b.retry.background = oval(Palette.systemRed)
    b.retry.setOnClickListener { onRetry(m.draftId, m.text) }
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
```

`buildItems()` decides `showSendingLabel = (m is ChatMessage.User && m.message.delivery == Delivery.PENDING)`, and the adapter's `onRetry` drops the failed draft before re-sending so the retry replaces the failed bubble instead of duplicating it.

**Under the hood:** `UserMessage.delivery` is optimistic — `PENDING` immediately, then the SDK matches the server echo (via a local id) → `SENT`; if it can't be confirmed (offline at send time, or the connection drops while it's in flight) it settles on `FAILED`. The SDK does **not** auto-resend — the user retries explicitly via the "Tap to retry" affordance. The retry calls `session.removeMessage(m.draftId)` before `session.send(...)` so the failed bubble is replaced rather than left beside the new attempt.

*See [Integration guide › Delivery state & retry](../../../../README.md#delivery-state--retry).*

### Failure overlay — `ChatActivity.kt` + `activity_chat.xml`

Surface a terminal failure + offer retry:

```kotlin
session.failureReason   // StateFlow<PolyError?> — non-null when the chat can't auto-recover:
                        //   invalid apiKey (initial connect 401/403),
                        //   reconnect budget exhausted,
                        //   session expired (idle past sessionTimeoutSeconds, default 10 min)

session.client.resume()   // suspend; re-attempt the connection from your retry button
```

In the activity — toggle the full-screen overlay and render the reason:

```kotlin
launch {
    session.failureReason.collect { reason ->
        binding.failureOverlay.visibility = if (reason != null) View.VISIBLE else View.GONE
        // PolyError isn't a localized resource, so render debugDescription.
        binding.failureLabel.text = reason?.debugDescription ?: ""
    }
}

binding.reconnect.setOnClickListener {
    lifecycleScope.launch { runCatching { session.client.resume() } }
}
```

The overlay (`@id/failureOverlay`) is a dimmed, clickable card with a "Connection lost" title, the `failureLabel`, and a "Reconnect" button — it sits on top of the chat in the root `FrameLayout`.

**Under the hood:** `failureReason` is set whenever the chat can't auto-recover — an invalid `apiKey` rejected at the initial connect, the auto-reconnect budget exhausted, or the session expiring. Recovery is consumer-driven — call `session.client.resume()` to retry.

*See [Integration guide › Terminal errors](../../../../README.md#terminal-errors).*

### Keyboard handling — `ChatActivity.kt` + manifest

The SDK doesn't get involved here. The activity declares `windowSoftInputMode=adjustResize` and pads the composer container with the larger of the navigation-bar and IME insets, so the input bar rides the keyboard with no manual observers:

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, insets ->
    val bottom = maxOf(
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
    )
    v.updatePadding(bottom = bottom)
    insets
}
```

The list uses a `LinearLayoutManager` with `stackFromEnd = true` so newest content pins to the bottom; a floating "New messages" pill (a `↓` glyph) appears when content arrives while you're scrolled up.

*See [Integration guide › Avatars & keyboard](../../../../README.md#avatars--keyboard).*

## Avatars

Agent avatars are loaded with [Coil](https://coil-kt.github.io/coil/) — `imageView.load(url)` with a `CircleCropTransformation` (clips the avatar to a circle) and `avatar_placeholder` as both placeholder and error drawable. The same loader is used for the message rows (`MessageHolder.loadAvatar`) and the typing footer (`TypingHolder`). The person-in-circle fallback maps to the `avatar_placeholder` vector drawable.

## What this example skips

- attachments, URL cards, call actions → [`03-richcontent/`](../03-richcontent/)
- offline detection, full-screen terminal error → [`04-resilience/`](../04-resilience/)
- live agent handoff → [`05-handoff/`](../05-handoff/)

---

- **Compose counterpart:** [`examples/compose/02-standard/`](../../compose/02-standard/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
