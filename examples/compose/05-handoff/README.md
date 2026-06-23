# 05-Handoff (Compose)

Live-agent handoff on top of [`04-Resilience`](../04-resilience/). Queue / accept / fail / agent-joined events become centered system pills in the transcript; live-agent bubbles get distinct styling.

Setup, scaffolding, and everything inherited from 04 (offline banner, loading skeleton, failure handling) are unchanged — read [`02-Standard`](../02-standard/) through [`04-Resilience`](../04-resilience/) first. This README covers only what 05 adds. (05 also softens 04's full-screen terminal-error screen into a centered "Connection lost" overlay.)

## Run it

Open the repo in Android Studio and run the `:examples:compose:05-handoff` module, or from the repo root:

```bash
./gradlew :examples:compose:05-handoff:installDebug
```

Then launch the installed app. Set your API key in `src/main/kotlin/ai/poly/examples/handoff/compose/HandoffApplication.kt` (currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- Render handoff progress (queue / accept / fail / timeout) as centered `System` pills
- Tint a live-agent bubble teal and tag the caption with `· live agent`
- Subscribe to raw `client.events` for app side effects (set the title, deep-link a route)
- Reuse `session.isAgentTyping` for live-agent typing — no new flag needed
- Let `LiveAgentLeft` flip `session.hasEnded` naturally so the existing chat-ended footer takes over

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../README.md#integration-guide); this example shows them as one concrete screen.

## How it works

Each subsection leads with **the SDK signal** (the actual API), then shows **how it's wired into a composable**.

### Handoff status pills — `components/MessageBubbleView.kt`

Match what `02-Standard` already does for `System` rows: render every handoff transition as a centered chat pill, not a header banner. `ChatSession` does the heavy lifting — you just `when` over the system event:

The SDK signal:

```kotlin
session.messages                  // includes ChatMessage.System for every handoff transition

// SystemEvent cases you'll typically handle:
// LiveAgentJoined(name)          // a human agent picked up
// QueueStatus(position, displayMessage)
// HandoffStarted
// HandoffAccepted
// HandoffFailed(reasonText)
// HandoffTimeout
// LiveAgentLeft(reasonText)      // terminal — hasEnded follows
```

In a composable (the `System` branch of `MessageBubbleView`):

```kotlin
is ChatMessage.System -> {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = systemText(message.message.event),
            fontSize = 12.sp,
            color = SecondaryLabel,
            modifier = Modifier.clip(CircleShape).background(SystemGray6)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

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

*See [Integration guide › Live agent handoff](../../../README.md#live-agent-handoff).*

### Live-agent bubble styling — `components/MessageBubbleView.kt`

Live-agent replies are ordinary `Agent` messages — the same rendering path as Poly agent replies. Switch on `AgentMessage.agentKind` to tint them and tag the caption:

The SDK signal:

```kotlin
AgentMessage.agentKind   // AgentKind.POLY (default) or AgentKind.LIVE — live = a human handled by handoff
AgentMessage.agentName   // optional display name (used for the caption)
```

In a composable (the `Agent` branch of `MessageBubbleView`):

```kotlin
val isLive = m.agentKind == AgentKind.LIVE

BubbleAvatar(url = m.avatarUrl, isLive = isLive)   // teal ring when isLive

if (!name.isNullOrEmpty()) {
    Text(
        if (isLive) "$name · live agent" else name,
        fontSize = 11.sp,
        color = if (isLive) SystemTeal else SecondaryLabel,
    )
}
RichText(
    text = m.text,
    modifier = Modifier
        .clip(RoundedCornerShape(18.dp))
        .background(if (isLive) SystemTeal.copy(alpha = 0.18f) else SystemGray5)
        .then(if (isLive) Modifier.border(1.dp, SystemTeal.copy(alpha = 0.5f), RoundedCornerShape(18.dp)) else Modifier)
        .padding(horizontal = 14.dp, vertical = 10.dp),
)
```

**Under the hood:** the SDK normalises live-agent replies into the same `AgentMessage` shape as Poly replies — only `agentKind` and (usually) `avatarUrl` / `agentName` differ. Live-agent typing reuses `session.isAgentTyping`, so the typing indicator works during handoff with no extra wiring.

> **Streaming:** agent replies grow token-by-token by default (`Configuration.streamingEnabled = true` — ChatGPT-style). Live-agent messages flow through the same path, so streaming works for them too. See the root README's [*Streaming*](../../../README.md#streaming) section and [`07-Playground/`](../07-playground/) for a live toggle.

*See [Integration guide › Live agent handoff](../../../README.md#live-agent-handoff).*

### Side effects via raw `client.events` — `ChatScreen.kt`

Rendering reads `session.messages`; raw events are only needed for app-side effects like updating the title or deep-linking a route the backend hands you:

The SDK signal:

```kotlin
session.client.events   // SharedFlow<MessagingEvent> — the raw, decoded stream

// Events you'll typically act on in handoff:
// LiveAgentJoined(payload)         // update title / show banner
// ClientHandoffRequired(payload)   // deep-link payload.route (the agent told you to)
// LiveAgentLeft                    // clear the title (hasEnded does the rest)
// SessionStart                     // fresh session → reset title
```

In `ChatScreen` — collect for the lifetime of the screen:

```kotlin
var connectedAgentName by remember { mutableStateOf<String?>(null) }

LaunchedEffect(session) {
    session.client.events.collect { event ->
        when (event) {
            is MessagingEvent.LiveAgentJoined -> connectedAgentName = event.payload.agentName
            is MessagingEvent.ClientHandoffRequired -> {
                // Optionally deep-link to the route URL if it parses as http(s).
                val uri = event.payload.route?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (uri?.scheme?.startsWith("http") == true) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
            is MessagingEvent.LiveAgentLeft, is MessagingEvent.SessionStart -> connectedAgentName = null
            else -> Unit  // everything renderable already flows through session.messages
        }
    }
}

TopAppBar(title = { Text(connectedAgentName?.takeIf { it.isNotEmpty() } ?: "Chat") })
```

**Under the hood:** `client.events` is the same typed, decoded stream the SDK uses internally — subscribing adds no transport overhead, and is the right place for imperative side effects (title, analytics, deep-linking) that aren't a function of `messages`. Anything renderable already comes through `session.messages` / `session.isAgentTyping`, so don't drive the bubble list off this stream.

*See [Integration guide › Live agent handoff](../../../README.md#live-agent-handoff).*

### Why `LiveAgentLeft` needs no special handling

It's terminal — `session.hasEnded` flips true and the existing chat-ended footer takes over (with "This live chat has ended..." wording when the most recent system message is a `LiveAgentLeft`). The title returns to "Chat" (handled in the events collector above) so a stale agent name doesn't linger into the next conversation.

**Under the hood:** `LiveAgentLeft` is a terminal transition — the SDK itself flips `hasEnded`, so no app-side bookkeeping is needed to close out the conversation.

## Try this on the emulator

| Action | What you should see |
|---|---|
| Ask the dev agent to "speak to salesforce" | "Transferring to a live agent..." pill, then "Transfer failed" + a recovery prompt |
| Pick another provider (e.g. Zendesk) | "Transferring to a live agent..." then "Queued..." pills while the queue waits |
| Kill the network until reconnects exhaust | Centered "Connection lost" card over the chat (no longer full-screen like 04) |
| End Chat mid-queue | "This conversation has ended..." footer; Start New Conversation resets the title |

## What this example skips

- production-style resume / start-new flow with a dedicated connect screen → [`../06-fullreference/`](../06-fullreference/)
- runtime configuration, raw transport experiments, diagnostics → 07-Playground (see [`../07-playground/`](../07-playground/))

---

- **Views counterpart:** [`../../views/05-handoff/`](../../views/05-handoff/)
- **SDK reference:** root [README → Integration guide](../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../README.md#install)
