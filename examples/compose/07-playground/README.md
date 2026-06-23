# 07-Playground (Compose)

The developer toolbox on top of [`06-FullReference`](../06-fullreference/) — the same complete chat (`connect → loading → chat → error`, resume-or-start, in-place restart, streaming-aware scroll, delivery tracking) wrapped in a QA surface for poking at the protocol: runtime `Configuration` via `DevSettings`, a live streaming toggle, raw-transport `getConnection()` pokes (frames + close-code simulations), a filterable event log, live `DevDiagnostics`, and grouped message timestamps.

Use [`06-FullReference`](../06-fullreference/) to learn the chat. Use this one to test the SDK.

## Run it

Open the repo in Android Studio and run the `:examples:compose:07-playground` module, or from the repo root:

```bash
./gradlew :examples:compose:07-playground:installDebug
```

Then launch the installed app. Set your API key in `src/main/kotlin/ai/poly/examples/playground/compose/PlaygroundApplication.kt` (currently `"YOUR_API_KEY"`); the playground rebuilds a fresh `Configuration` from `DevSettings` on every connect, so `initialize(...)` just primes a sane default (and seeds the environment picker).

## What this example demonstrates

- Edit a fresh `Configuration` at runtime via `DevSettings` (a public SDK type) and apply it on the next session
- Toggle `Configuration.streamingEnabled` live and verify token-by-token vs complete-message rendering
- Poke the live WebSocket via `client.getConnection().send(...)` (raw frames) and `.disconnect(code, reason)` (close-code simulations)
- Tap `client.events` for a filterable, copyable log of every typed event
- Subscribe to `client.events` / `client.connectionStatus` / `client.sessionState` for live diagnostic counters
- Render grouped timestamp separators using each `ChatMessage.timestamp`
- New-message banners (local workaround) — a notification when the agent replies; no remote push yet — coming soon (`components/NewMessageNotifier.kt`)

## How it works

Each subsection leads with **the SDK call(s)** (the actual API), then shows **how it's wired into a view**.

### Runtime configuration via `DevSettings` — `SettingsSheet.kt`

`DevSettings` is a **public SDK type** — an `open class` backed by `SharedPreferences`. Construct it with a `Context` after `initialize(...)`; it reads the API key from the global config and seeds its environment from there, so it bakes in no credentials. Edit the `StateFlow` knobs live; `buildConfiguration()` folds them into a `Configuration` the SDK consumes on the **next** session.

The SDK calls:

```kotlin
DevSettings(context)                  // a SharedPreferences-backed bag of StateFlow runtime knobs
settings.environmentKind              // US / UK / EUW / CLUSTER / CUSTOM
settings.streamingEnabled             // session-creation knob — flip resets text-by-token rendering
settings.heartbeatIntervalSeconds     // 0 = "use the SDK default"
settings.sessionTimeoutSeconds        // 0 = "use the SDK default"
settings.maxReconnectAttempts         // 0 = "use the SDK default"

settings.buildConfiguration()         // → Configuration consumed on the next session
PolyMessaging.chat(config)
PolyMessaging.start(config)
```

In a view (settings sheet):

```kotlin
val kind by settings.environmentKind.collectAsStateWithLifecycle()
DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
    DevSettings.EnvironmentKind.entries.forEach { k ->
        DropdownMenuItem(text = { Text(k.displayName) }, onClick = { settings.setEnvironmentKind(k) })
    }
}
// ...timing knobs, log level, custom URL fields...
```

```kotlin
// AppRoot.configureAndStart — buildConfiguration → applied on the next session
val config = devSettings.buildConfiguration()
val s = if (forceFresh) PolyMessaging.start(config) else PolyMessaging.chat(config)
diagnostics.attach(s.client, scope)
session = s
```

**Under the hood:** session-creation knobs (environment, streaming — and the server-side greeting they trigger) take effect only on a fresh session, which is why the sheet shows "Apply & Start New Session" whenever a session is live or resumable. `lastAppliedStreamingEnabled` on `DevSettings` lets the UI flag when the running session is out of sync with the current knobs.

*See [Integration guide › Configuration](../../../README.md#configuration).*

### Streaming toggle — `SettingsSheet.kt`

The Session section of the sheet flips `settings.streamingEnabled` (default **on**), which flows into the next `buildConfiguration()`:

The SDK signal:

```kotlin
Configuration.streamingEnabled         // the single switch for token-by-token vs complete-message
settings.streamingEnabled              // the live DevSettings knob
settings.lastAppliedStreamingEnabled   // the value the running session was started with
```

In a view:

```kotlin
ToggleRow("Streaming enabled", streamingEnabled) { settings.setStreamingEnabled(it) }
if (hasAnySession && streamingEnabled != lastApplied) {
    Text("Won't apply to current session — start fresh to take effect.", color = SystemOrange)
}
```

**Under the hood:** when `streamingEnabled = true` (default), `ChatSession` extends the last agent message's `text` on every chunk and re-publishes `messages` — your `Text(message.text)` re-renders in place. When `false`, the SDK shows the assembled message in one shot and keeps `isAgentTyping == true` while the agent thinks. The chat view code is identical either way; the same `messages` list just updates differently.

*See [Integration guide › Streaming](../../../README.md#streaming).*

### Raw transport: send frames — `SettingsSheet.kt`, `AppRoot.kt`

`getConnection()` hands you the *same* live `Connection` the SDK is already running on. Frames sent through it **bypass the managed `send()` path** — no delivery tracking, no retry, no `local_id` correlation, no `MessagePending` / `MessageConfirmed`. It's for protocol-level pokes, not normal sending.

The SDK call:

```kotlin
session.client.getConnection().send(event: OutgoingEvent)   // raw frame injection
```

Buttons in the Settings sheet inject these frames:

| Button | Frame |
|---|---|
| Send HEARTBEAT | `OutgoingEvent.Heartbeat` |
| Send USER_TYPING (started / stopped) | `OutgoingEvent.UserTyping(STARTED)` / `(STOPPED)` |
| Send USER_END_SESSION | `OutgoingEvent.UserEndConversation` |
| Send USER_LEFT | `OutgoingEvent.UserLeft` |

In a view:

```kotlin
// AppRoot wires Settings buttons to rawSend(...)
SettingsSheet(
    settings = devSettings,
    // ...
    onSendHeartbeat = { rawSend(OutgoingEvent.Heartbeat) },
    onSendTypingStart = { rawSend(OutgoingEvent.UserTyping(TypingState.STARTED)) },
    onSendTypingStop = { rawSend(OutgoingEvent.UserTyping(TypingState.STOPPED)) },
    onSendUserEndSession = { rawSend(OutgoingEvent.UserEndConversation) },
    onSendUserLeft = { rawSend(OutgoingEvent.UserLeft) },
)

fun rawSend(event: OutgoingEvent) {
    val c = session?.client ?: return
    scope.launch {
        c.getConnection().send(event)
        diagnostics.recordOutgoing()   // SDK has no outbound-frame stream — count manually
    }
}
```

**Under the hood:** `UserEndConversation` / `UserLeft` are real frames the backend processes (a server-side `EVENT_TYPE_USER_END_SESSION`); `Heartbeat` and `UserTyping` are protocol bookkeeping. Because the managed `send()` path isn't involved, the SDK won't surface these as messages or delivery events — they're invisible to `session.messages`.

*See [Integration guide › Raw transport](../../../README.md#advanced-raw-transport).*

### Raw transport: close-code simulations — `SettingsSheet.kt`, `AppRoot.kt`

`disconnect(code, reason)` closes the socket with your chosen code, which the SDK's own `ConnectionService` then classifies. Each close code exercises a different recovery path:

The SDK call:

```kotlin
session.client.getConnection().disconnect(code: Int, reason: String)
```

| Button | Close code | What the SDK does |
|---|---|---|
| Force reconnect | **4002** | transient → reconnect ladder (exponential backoff + jitter), keeps the same `session_id`, replays from the last cursor |
| Simulate network drop | **4002** | same path as Force reconnect — a distinct button so you can exercise the transient-recovery flow under a "lost connection" framing |
| Simulate idle timeout | **4002** | same path again — labelled separately to mirror the idle-timeout scenario in the UI |
| Simulate server reject | **4001** | invalid session → SDK refetches a fresh session (new access token + `session_id`), then reconnects |
| Clean disconnect | **1000** | terminal → reconnect ladder stops; the conversation is over (the chat swaps to the ended footer) |

The first three buttons are distinct UI affordances that all route through close code **4002** — same recovery path, different scenario labels.

> **Platform note:** OkHttp refuses to *send* reserved close codes (such as **1006**), so force-reconnect / network-drop use **4002** — the SDK's sendable app code that routes through the same reconnect path.

In a view:

```kotlin
fun closeWith(code: Int, reason: String) {
    val c = session?.client ?: return
    scope.launch { c.getConnection().disconnect(code, reason) }
}

// SettingsSheet wires each button:
onForceReconnect = { forceReconnect() },                                        // 4002
onSimulateDrop = { simulateNetworkDrop() },                                     // 4002
onSimulateIdleTimeout = { closeWith(4002, "Debug idle-timeout simulation") },
onSimulateServerReject = { closeWith(4001, "Debug server-reject simulation") },
onDisconnectClean = { closeWith(1000, "Debug clean disconnect") },
```

**Under the hood:** these are **client-side simulations**, not backend round-trips. The `40xx` codes are the SDK's internal vocabulary for classifying the close — the buttons exercise the SDK's own reconnect classification; the server isn't asked to reject or idle-out.

*See [Integration guide › Raw transport](../../../README.md#advanced-raw-transport).*

### Event log — `EventLogger.kt`, `LogsSheet.kt`

Tap `client.events` for a filterable, copyable record of every typed event. `EventLogger` turns each `MessagingEvent` into a `LogEntry` using the SDK's own `debugSummary` / `debugDetail`:

The SDK signal:

```kotlin
session.client.events     // SharedFlow<MessagingEvent> — the typed, decoded stream
event.debugSummary        // public: one-line human summary
event.debugDetail         // public: multi-line detail
```

In a view:

```kotlin
// AppRoot.kt — tap the same stream that drives lifecycle, skip the noisy ones
scope.launch {
    client.events.collect { event ->
        if (shouldLog(event)) logs.add(EventLogger.makeEntry(event))
        // ...also drive the loading → chat transitions...
    }
}

fun shouldLog(event: MessagingEvent): Boolean = when (event) {
    is MessagingEvent.MessagePending, is MessagingEvent.MessageConfirmed, is MessagingEvent.MessageFailed,
    is MessagingEvent.Heartbeat, is MessagingEvent.UserTyping, is MessagingEvent.UserEndSession,
    is MessagingEvent.RequestPolyAgentJoin,
    -> false   // drop high-frequency / optimistic noise
    else -> true
}
```

```kotlin
// EventLogger.kt — SDK event → log row
fun makeEntry(event: MessagingEvent): LogEntry =
    makeEntry(event.debugSummary, event.debugDetail)
```

**Under the hood:** `client.events` is the same stream the SDK uses internally to drive its own behaviour — tapping it adds no new transport. `debugSummary` / `debugDetail` are public helpers that format envelope + payload in a stable way, so log rows survive SDK upgrades.

### Live diagnostics — `DevDiagnostics.kt`, `components/DebugStrip.kt`

`DevDiagnostics` subscribes to the same three lifecycle streams and tallies counters — session id, ready state, reconnect cursor (`lastSequence`), frames in/out, streaming chunks, heartbeats, reconnects, last-inbound time, and the negotiated `SessionCapabilities`:

The SDK signals:

```kotlin
client.events                     // tally framesIn, chunksIn, heartbeatsIn, lastSequence
client.connectionStatus           // tally reconnects, current state
client.sessionState               // capture sessionId / ready state
event.envelope?.sequence          // reconnect cursor
payload.capabilities              // server-negotiated SessionCapabilities (on SessionStart)
```

In a view:

```kotlin
// DevDiagnostics.kt — subscribe once, tally from the SDK streams
fun attach(client: PolyMessagingClient, scope: CoroutineScope) {
    reset()
    eventJob = scope.launch { client.events.collect { consume(it) } }
    // ...also connectionStatus + sessionState...
}

private fun consume(event: MessagingEvent) {
    _framesIn.value += 1
    _lastInboundAt.value = System.currentTimeMillis()
    event.envelope?.sequence?.let { if (it > _lastSequence.value) _lastSequence.value = it }
    when (event) {
        is MessagingEvent.SessionStart -> _streamingCapability.value = event.payload.capabilities.streaming
        is MessagingEvent.AgentMessageChunk -> _chunksIn.value += 1
        is MessagingEvent.Heartbeat -> _heartbeatsIn.value += 1
        else -> Unit
    }
}
```

```kotlin
// DebugStrip.kt — always-on one-line chip over the chat
Chip(icon = R.drawable.ic_tag, text = "seq $lastSequence")
Chip(icon = R.drawable.ic_swap_vert, text = "$framesOut→ ←$framesIn")
```

**Under the hood:** the SDK has no outbound-frame stream, so `recordOutgoing()` is called by the raw-transport tap to count frames out — every other counter is read off the SDK's published streams. The full read-out lives in the Settings sheet's Diagnostics section; `DebugStrip` shows the headline numbers gated by `DevSettings.showDebugStrip`.

### Message timestamps — `MessageTimestamp.kt`, `components/TimestampSeparator.kt`

Every `ChatMessage` already carries a `timestamp` (epoch millis). When `DevSettings.showMessageTimestamps` is on, the chat view inserts a centered separator wherever the gap between two consecutive messages exceeds ~5 minutes:

The SDK signal:

```kotlin
ChatMessage.timestamp   // epoch millis set when the message was sent / received
```

In a view:

```kotlin
// ChatView.kt — insert a separator when the time gap is large enough
itemsIndexed(messages, key = { _, m -> m.id }) { index, msg ->
    if (showTimestamps &&
        MessageTimestamp.shouldInsertSeparator(
            previousMillis = if (index > 0) messages[index - 1].timestamp else null,
            currentMillis = msg.timestamp,
        )
    ) {
        TimestampSeparator(msg.timestamp)
    }
    MessageBubbleView(message = msg, showTimestamp = showTimestamps, /* ... */)
}
```

```kotlin
// MessageTimestamp.kt — the grouping rule (true also for the first message)
fun shouldInsertSeparator(previousMillis: Long?, currentMillis: Long): Boolean {
    if (previousMillis == null) return true
    return currentMillis - previousMillis > GROUP_GAP_MILLIS   // ~5 * 60 * 1000
}
```

**Under the hood:** the timestamp is already on every `ChatMessage` the SDK publishes — there's no extra subscription. `MessageTimestamp` owns the grouping rule and the locale-aware formatters (time today, "Yesterday 3:42 PM", weekday this week, month/day this year, else full date).

### In-app new-message banners (local workaround) — `components/NewMessageNotifier.kt`

Pop a notification banner when the agent replies — handy for confirming chunked/streaming replies land a single alert. ⚠️ **Local-notification workaround, not remote push** (no FCM yet — **coming soon**). It posts via `NotificationManager` under a `NotificationPolicy` (the default stays quiet while the chat is on screen); once Android kills the process nothing arrives — real delivery needs FCM + a server-side push integration the SDK doesn't provide yet.

The SDK signal:

```kotlin
session.client.events   // completed-message events (full text + stable messageId)
```

In a view — one composable on the chat screen:

```kotlin
NewMessageNotifier(session, policy = NotificationPolicy.WHEN_BACKGROUNDED)
```

**Under the hood.**

*In a nutshell* — request `POST_NOTIFICATIONS` (API 33+) and create the channel, watch `client.events` for completed agent messages, skip already-shown ones via the persisted store, and post through `NotificationManager` only when the policy allows it. (Full generic walkthrough in the [integration guide](../../../README.md#in-app-new-message-alerts-local-only-workaround), linked below.)

*In detail*, mapped to this example's code:

**1. Permission + channel** — request `POST_NOTIFICATIONS` on API 33+ and create the high-importance channel up front.

**2. Listen + dedupe** — collect `session.client.events` under `repeatOnLifecycle(Lifecycle.State.CREATED)` (not STARTED, so a reply that lands while the chat is backgrounded still banners), keep only the *completed*-message events (full text + a stable `messageId`, not chunks), and skip anything already in the **persisted** (SharedPreferences) store:

```kotlin
lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
    session.client.events.collect { event ->
        // MessagingEvent.AgentMessage / LiveAgentMessage → (messageId, agentName, text); chunks ignored
        if (store.contains(id)) return@collect   // persisted → resume/relaunch replays don't re-fire
        // …gate + post (below)…
    }
}
```

**3. Gate + post** — banner only when the policy is `ALWAYS` or the chat is off screen (lifecycle below `STARTED` (not visible)), then mark the id shown either way so a later replay can't re-notify:

```kotlin
val onScreen = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
if (policy == NotificationPolicy.ALWAYS || !onScreen) post(context, id, title, body)
store.markShown(id)
```

Toggle streaming in the Settings sheet to confirm it stays one banner per reply (the bubble `id` is stable across chunks).

*See [Integration guide › In-app new-message alerts (local-only workaround)](../../../README.md#in-app-new-message-alerts-local-only-workaround).*

## What this example is for

- protocol smoke tests against dev / staging / cluster / custom environments
- reconnect and close-code experiments (4002 / 4001 / 1000)
- progressive-streaming verification with the live toggle
- inspecting raw event payloads while keeping `ChatSession`'s UI behaviour visible

---

- **Views counterpart:** [`examples/views/07-playground/`](../../views/07-playground/)
- **SDK reference:** root [README → Integration guide](../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../README.md#install)
