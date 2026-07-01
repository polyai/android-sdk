# 05-Handoff (Views)

Live-agent handoff on top of [`04-Resilience`](../04-resilience/). Queue / accept / fail / agent-joined events become centered system pills in the transcript; live-agent bubbles get distinct styling. The Views counterpart of [`examples/compose/05-handoff/`](../../compose/05-handoff/).

Setup, scaffolding, and everything inherited from 04 (offline banner, loading skeleton, failure handling) are unchanged — read [`02-Standard`](../02-standard/) through [`04-Resilience`](../04-resilience/) first. This README covers only what 05 adds. (05 also splits 04's full-screen failure handling in two — see below.)

## Run it

Open the repo in Android Studio and run the `:examples:views:05-handoff` module, or from the repo root:

```bash
./gradlew :examples:views:05-handoff:installDebug
```

Then launch `ChatActivity`. Set your API key in `src/main/kotlin/ai/poly/examples/handoff/views/HandoffApplication.kt` (the `API_KEY` constant is currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- Render handoff progress (queue / accept / fail / timeout) as centered `System` pill rows
- Tint a live-agent bubble teal and tag the caption with `· live agent` (suffix-only teal)
- Subscribe to raw `client.events` for app side effects (action-bar title, deep-link a route)
- Reuse `session.isAgentTyping` for live-agent typing — no new flag needed
- Let `LiveAgentLeft` flip `session.hasEnded` naturally so the existing chat-ended footer takes over
- Split failures: terminal errors (auth / config / dead session) get a full-bleed "Something went wrong" screen with **Start New Chat**; reconnect exhaustion gets a centered "Connection lost" card with **Reconnect**

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide).

## How it works

Each subsection leads with **the SDK signal** (the actual API), then shows **how it's wired in**.

### Handoff status pills — `ChatAdapter.kt`

Match what `02-Standard` already does for `System` rows: render every handoff transition as a centered chat pill, not a header banner. `ChatSession` does the heavy lifting — you just `when` over the system event:

```kotlin
session.messages                  // includes ChatMessage.System for every handoff transition

// SystemEvent cases you'll typically handle:
// LiveAgentJoined(name)          // a human agent picked up
// QueueStatus(position, displayMessage)
// HandoffStarted / HandoffAccepted / HandoffFailed(reasonText) / HandoffTimeout
// LiveAgentLeft                  // terminal — hasEnded follows
```

In the adapter's system branch (the pill reuses the cell's bubble — colors + corner radius swap, row centered):

```kotlin
private fun systemText(event: SystemEvent): String = when (event) {
    is SystemEvent.LiveAgentJoined -> "${event.name ?: "An agent"} joined"
    is SystemEvent.QueueStatus ->
        event.displayMessage?.takeIf { it.isNotEmpty() }
            ?: event.position?.let { "Position #$it in queue" } ?: "Queued..."
    is SystemEvent.HandoffStarted -> "Transferring to a live agent..."
    is SystemEvent.HandoffAccepted -> "An agent will be with you shortly"
    is SystemEvent.HandoffFailed -> event.reasonText?.let { "Transfer failed: $it" } ?: "Transfer failed"
    is SystemEvent.HandoffTimeout -> "No agents available"
    else -> "This conversation has ended"
}
```

**Under the hood:** the SDK converts every handoff transition — agent-triggered handoff, queue status, accepted / failed / timeout, live-agent joined / left — into a `ChatMessage.System` appended to `session.messages`. They interleave with `User` / `Agent` bubbles in the timeline, so you only render the `System` branch and the order comes out right for free.

*See [Integration guide › Live agent handoff](../../../../README.md#live-agent-handoff).*

### Live-agent bubble styling — `ChatAdapter.kt`

Live-agent replies are ordinary `Agent` messages — the same rendering path as Poly agent replies. Switch on `AgentMessage.agentKind` to tint them and tag the caption:

```kotlin
AgentMessage.agentKind   // AgentKind.POLY (default) or AgentKind.LIVE — live = a human handled by handoff
AgentMessage.agentName   // optional display name (used for the caption)
```

In the agent branch:

```kotlin
val isLive = m.agentKind == AgentKind.LIVE

// Teal ring around the avatar (1.5pt teal).
if (isLive) b.avatar.foreground = ovalStroke(Palette.systemTeal, 1.5f)

// "{name} · live agent" — only the suffix is teal.
val full = "$name · live agent"
b.name.text = SpannableString(full).apply {
    setSpan(ForegroundColorSpan(Palette.systemTeal), name.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

// Teal-tinted bubble fill (no border).
b.bubble.background = rounded(if (isLive) Palette.systemTeal18 else Palette.systemGray5, 18f)
```

**Under the hood:** the SDK normalises live-agent replies into the same `AgentMessage` shape as Poly replies — only `agentKind` and (usually) `avatarUrl` / `agentName` differ. Live-agent typing reuses `session.isAgentTyping`, so the typing indicator works during handoff with no extra wiring.

> **Streaming:** agent replies grow token-by-token by default (`Configuration.streamingEnabled = true` — ChatGPT-style). Live-agent messages flow through the same path, so streaming works for them too. See the root README's [*Streaming*](../../../../README.md#streaming) section and [`07-Playground/`](../07-playground/) for a live toggle.

> Reminder from `03-RichContent`: set `movementMethod = LinkMovementMethod.getInstance()` on the bubble `TextView` — link spans render styled but ignore taps without it.

*See [Integration guide › Live agent handoff](../../../../README.md#live-agent-handoff).*

### Side effects via raw `client.events` — `ChatActivity.kt`

Rendering reads `session.messages`; raw events are only needed for app-side effects like updating the action-bar title or deep-linking a route the backend hands you:

```kotlin
session.client.events   // SharedFlow<MessagingEvent> — the raw, decoded stream
```

In the Activity — collected for the Activity's lifetime via `lifecycleScope`, so the collector is cancelled automatically when the Activity is destroyed:

```kotlin
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
            val uri = event.payload.route?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri?.scheme?.startsWith("http") == true) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
        is MessagingEvent.LiveAgentLeft -> actionBar?.title = "Chat"
        is MessagingEvent.SessionStart -> actionBar?.title = "Chat"
        else -> Unit  // everything renderable already flows through session.messages
    }
}
```

**Under the hood:** `client.events` is the same typed, decoded stream the SDK uses internally — subscribing adds no transport overhead, and is the right place for imperative side effects (title, analytics, deep-linking) that aren't a function of `messages`. Anything renderable already comes through `session.messages` / `session.isAgentTyping`, so don't drive the list off this stream.

*See [Integration guide › Live agent handoff](../../../../README.md#live-agent-handoff).*

### Terminal vs reconnectable failures — `ChatActivity.kt`

05 splits `session.failureReason` in two (04 sent everything to one full-screen overlay):

```kotlin
session.failureReason.collect { reason ->
    if (reason == null) { /* hide both */ return@collect }
    if (isTerminal(reason)) {
        // auth / config / dead session — reconnect won't fix it.
        binding.terminalScreen.visibility = View.VISIBLE   // "Something went wrong" + Start New Chat
    } else {
        // reconnect exhaustion — a manual retry can.
        binding.failureOverlay.visibility = View.VISIBLE   // "Connection lost" card + Reconnect
    }
}

/** Auth/config/expired-session errors are terminal — show the big screen, not the reconnect card. */
private fun isTerminal(error: PolyError): Boolean {
    if (error.isRetryable) return false
    return when (error) {
        is PolyError.InvalidConfiguration, is PolyError.Auth -> true
        is PolyError.Session.SessionExpired, is PolyError.Session.SessionEnded,
        is PolyError.Session.SessionCreationFailed -> true
        else -> false
    }
}
```

### Why `LiveAgentLeft` needs no special handling

It's terminal — `session.hasEnded` flips true and the existing chat-ended footer takes over. The title returns to "Chat" (handled in the events collector above) so a stale agent name doesn't linger into the next conversation.

**Under the hood:** `LiveAgentLeft` is a terminal transition — the SDK itself flips `hasEnded`, so no app-side bookkeeping is needed to close out the conversation.

## Try this on the emulator

| Action | What you should see |
|---|---|
| Ask the dev agent to "speak to salesforce" | "Transferring to a live agent..." pill, then "Transfer failed" + a recovery prompt |
| Pick another provider (e.g. Zendesk) | "Transferring to a live agent..." then "Queued..." pills while the queue waits |
| Kill the network until reconnects exhaust | Centered "Connection lost" card + Reconnect (the terminal screen is reserved for auth/config errors) |
| End the chat mid-queue | "This conversation has ended. Please start a new chat to continue." footer; Start New Conversation resets the title |

## What this example skips

- production-style resume / start-new flow with a dedicated connect screen → [`06-FullReference/`](../06-fullreference/)
- runtime configuration, raw transport experiments, diagnostics → 07-Playground (see [`../07-playground/`](../07-playground/))

---

- **Compose counterpart:** [`../../compose/05-handoff/`](../../compose/05-handoff/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
