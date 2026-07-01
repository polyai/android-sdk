# 07-Playground (Views)

The developer toolbox on top of [`06-FullReference`](../06-fullreference/) — the same complete chat (`connect → loading → chat → error`, resume-or-start, in-place restart, delivery tracking, suggestions) wrapped in a QA surface for poking at the protocol: runtime `Configuration` via `DevSettings`, a live streaming toggle, raw-transport `getConnection()` pokes (frames + close-code simulations), a filterable event log, live `DevDiagnostics`, and iMessage-style message timestamps.

- **Interface:** classic Android Views (XML layouts + viewBinding); the dialogs are built programmatically.
- **Lifecycle:** a single `RootActivity` owns the `ChatSession` + `DevSettings` + `DevDiagnostics` and swaps the connect / loading / chat / error screens.

Use [`06-FullReference`](../06-fullreference/) to learn the chat. Use this one to test the SDK.

## Run it

Open the repo in Android Studio and run the `:examples:views:07-playground` module, or from the repo root:

```bash
./gradlew :examples:views:07-playground:installDebug
```

Then launch the installed app. Set your API key in `src/main/kotlin/ai/poly/examples/playground/views/PlaygroundApplication.kt` (currently `"YOUR_API_KEY"`); the playground rebuilds a fresh `Configuration` from `DevSettings` on every connect, so `initialize(...)` just primes a sane default (the connect / start paths pass an explicit config built by `DevSettings`).

## What this example demonstrates

- Edit a fresh `Configuration` at runtime via `DevSettings` (a public SDK type) and apply it on the next session
- Toggle `Configuration.streamingEnabled` live and verify token-by-token vs complete-message rendering
- Poke the live WebSocket via `client.getConnection().send(...)` (raw frames) and `.disconnect(code, reason)` (close-code simulations)
- Tap `client.events` for a filterable, copyable log of every typed event
- Subscribe to `client.events` / `client.connectionStatus` / `client.sessionState` for live diagnostic counters
- Render iMessage-style timestamp rows using each `ChatMessage.timestamp`
- New-message banners (local workaround) — a notification when the agent replies; no remote push yet — coming soon (`NewMessageNotifier.kt`)

## How it works

Each subsection leads with **the SDK call(s)** (the actual API), then shows **how it's wired into the activity / dialogs**.

### Runtime configuration via `DevSettings` — `SettingsDialog.kt`

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

In the activity (root holds the single `DevSettings` across every screen):

```kotlin
class RootActivity : ComponentActivity() {
    private val devSettings by lazy { DevSettings(this) }
    private val diagnostics = DevDiagnostics()
    private val logs = mutableListOf<LogEntry>()
    private var session: ChatSession? = null

    private fun configureAndStart(forceFresh: Boolean) {
        // ...short-circuit if a live session already exists...

        val config = devSettings.buildConfiguration()
        val s = if (forceFresh) PolyMessaging.start(config) else PolyMessaging.chat(config)
        diagnostics.attach(s.client, lifecycleScope)
        session = s
        showLoading()
        subscribeLifecycle(s.client)
    }
}
```

`SettingsDialog` edits the knobs (read via their `StateFlow`s + a 1s refresh ticker for the live counters); the connect screen surfaces `devSettings.environmentDisplayName()` and a "⚙︎ Custom dev settings active" badge when `devSettings.hasCustomization` is true.

**Under the hood:** session-creation knobs (environment, streaming — and the server-side greeting they trigger) take effect only on a fresh session, which is why the sheet shows a "⚠︎ Live session active" / "⚠︎ Resumable session exists" mismatch banner with an **"Apply & Start New Session"** button whenever a session is live or resumable. `lastAppliedStreamingEnabled` on `DevSettings` records the value the running session was started with (the Compose sheet uses it to flag *which* knob is out of sync); the Views dialog shows the banner unconditionally whenever `hasAnySession` is true.

*See [Integration guide › Configuration](../../../../README.md#configuration).*

### Streaming toggle — `SettingsDialog.kt`

The Session section of the sheet flips `devSettings.streamingEnabled` (default **on**), which flows into the next `buildConfiguration()`:

The SDK signal:

```kotlin
Configuration.streamingEnabled         // the single switch for token-by-token vs complete-message
settings.streamingEnabled              // the live DevSettings knob (written from the toggle row)
settings.lastAppliedStreamingEnabled   // the value the running session was started with
```

In the sheet — the streaming row is just a generic `toggleRow(...)` helper writing back to `DevSettings`:

```kotlin
stack.addView(toggleRow("Streaming enabled", settings.streamingEnabled.value) { settings.setStreamingEnabled(it) })

// toggleRow(...) — a Switch dropped into a labelled row:
private fun toggleRow(name: String, value: Boolean, onChange: (Boolean) -> Unit): View {
    val toggle = Switch(context).apply {
        isChecked = value
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
    return fieldRow(name, toggle)
}
```

The "Restart to apply" affordance lives in the mismatch-banner card at the top of the sheet (shown whenever `hasAnySession` is true), not in this row's footer.

**Under the hood:** when `streamingEnabled = true` (default), `ChatSession` extends the last agent message's `text` on every chunk and re-publishes `messages` — the adapter rebinds the cell with the longer text. When `false`, the SDK shows the assembled message in one shot and keeps `isAgentTyping == true` while the agent thinks. The chat code is identical either way; the same `messages` list just updates differently.

*See [Integration guide › Streaming](../../../../README.md#streaming).*

### Raw transport: send frames — `SettingsDialog.kt`, `RootActivity.kt`

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

In the activity (root wires each settings button to its raw frame):

```kotlin
private fun presentSettings() {
    SettingsDialog(
        context = this,
        // ...
        onSendHeartbeat = { rawSend(OutgoingEvent.Heartbeat) },
        onSendTypingStart = { rawSend(OutgoingEvent.UserTyping(TypingState.STARTED)) },
        onSendTypingStop = { rawSend(OutgoingEvent.UserTyping(TypingState.STOPPED)) },
        onSendUserEndSession = { rawSend(OutgoingEvent.UserEndConversation) },
        onSendUserLeft = { rawSend(OutgoingEvent.UserLeft) },
        // ...close-code buttons below...
    ).show()
}

private fun rawSend(event: OutgoingEvent) {
    val client = session?.client ?: return
    lifecycleScope.launch {
        client.getConnection().send(event)
        diagnostics.recordOutgoing()   // SDK has no outbound-frame stream — count manually
    }
}
```

**Under the hood:** `UserEndConversation` / `UserLeft` are real frames the backend processes (a server-side `EVENT_TYPE_USER_END_SESSION`); `Heartbeat` and `UserTyping` are protocol bookkeeping. Because the managed `send()` path isn't involved, the SDK won't surface these as messages or delivery events — they're invisible to `session.messages`.

*See [Integration guide › Raw transport](../../../../README.md#advanced-raw-transport).*

### Raw transport: close-code simulations — `SettingsDialog.kt`, `RootActivity.kt`

`disconnect(code, reason)` closes the socket with your chosen code, which the SDK's own `ConnectionService` then classifies. Each close code exercises a different recovery path:

The SDK call:

```kotlin
session.client.getConnection().disconnect(code: Int, reason: String)
```

| Button | Close code | What the SDK does |
|---|---|---|
| Force reconnect · Simulate network drop · Simulate idle timeout | **4002** | transient → reconnect ladder (exponential backoff + jitter), keeps the same `session_id`, replays from the last cursor |
| Simulate server reject | **4001** | invalid session → SDK refetches a fresh session (new access token + `session_id`), then reconnects |
| Clean disconnect | **1000** | terminal → reconnect ladder stops; the conversation is over (the chat swaps to the ended footer) |

> **Platform note:** OkHttp refuses to *send* reserved codes (like **1006**), so force-reconnect / network-drop use **4002** — the SDK's sendable app code that routes through the same reconnect path.

In the activity:

```kotlin
private fun closeWith(code: Int, reason: String) {
    val client = session?.client ?: return
    lifecycleScope.launch { client.getConnection().disconnect(code, reason) }
}

// Wires in presentSettings():
onForceReconnect = { closeWith(4002, "Debug force reconnect") },
onSimulateDrop = { closeWith(4002, "Debug simulated drop") },
onSimulateServerReject = { closeWith(4001, "Debug server-reject simulation") },
onSimulateIdleTimeout = { closeWith(4002, "Debug idle-timeout simulation") },
onDisconnectClean = { closeWith(1000, "Debug clean disconnect") },
```

**Under the hood:** these are **client-side simulations**, not backend round-trips. The `40xx` codes are the SDK's internal vocabulary for classifying the close — the buttons exercise the SDK's own reconnect classification; the server isn't asked to reject or idle-out.

*See [Integration guide › Raw transport](../../../../README.md#advanced-raw-transport).*

### Event log — `EventLogger.kt`, `LogsDialog.kt`

Tap `client.events` for a filterable, copyable record of every typed event. `EventLogger` turns each `MessagingEvent` into a `LogEntry` using the SDK's own `debugSummary` / `debugDetail`:

The SDK signal:

```kotlin
session.client.events     // SharedFlow<MessagingEvent> — the typed, decoded stream
event.debugSummary        // public: one-line human summary
event.debugDetail         // public: multi-line detail
```

In the activity (root subscribes once, in `subscribeLifecycle(...)`):

```kotlin
lifecycleJobs += lifecycleScope.launch {
    client.events.collect { event ->
        if (shouldLog(event)) logs.add(EventLogger.makeEntry(event))
        // ...also drive the loading → chat transitions...
    }
}

private fun shouldLog(event: MessagingEvent): Boolean = when (event) {
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

`LogsDialog` renders rows with a count header ("N entries · M match" while filtering), a filter field, level-coloured monospaced labels that expand to show detail, and a copy button.

**Under the hood:** `client.events` is the same stream the SDK uses internally to drive its own behaviour — tapping it adds no new transport. `debugSummary` / `debugDetail` are public helpers that format envelope + payload in a stable way, so log rows survive SDK upgrades.

### Live diagnostics — `DevDiagnostics.kt`, `DebugStripView.kt`

`DevDiagnostics` subscribes to the same three lifecycle streams and tallies counters — session id, ready state, reconnect cursor (`lastSequence`), frames in/out, streaming chunks, heartbeats, reconnects, last-inbound time, and the negotiated `SessionCapabilities`:

The SDK signals:

```kotlin
client.events                     // tally framesIn, chunksIn, heartbeatsIn, lastSequence
client.connectionStatus           // tally reconnects, current state
client.sessionState               // capture sessionId / ready state
event.envelope?.sequence          // reconnect cursor
payload.capabilities              // server-negotiated SessionCapabilities (on SessionStart)
```

In the activity:

```kotlin
// RootActivity.configureAndStart — attach after the client is built
diagnostics.attach(s.client, lifecycleScope)
```

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
        is MessagingEvent.SessionStart -> {
            _streamingCapability.value = event.payload.capabilities.streaming
            _maxMessageSize.value = event.payload.capabilities.maxMessageSize
        }
        is MessagingEvent.AgentMessageChunk -> _chunksIn.value += 1
        is MessagingEvent.Heartbeat -> _heartbeatsIn.value += 1
        else -> Unit
    }
}
```

```kotlin
// DebugStripView.refresh() — the one-line strip over the chat
framesChip.set(R.drawable.ic_swap_vert, "${d.framesOut.value}→ ←${d.framesIn.value}", DIM_WHITE)
```

**Under the hood:** the SDK has no outbound-frame stream, so `recordOutgoing()` is called by the raw-transport tap to count frames out — every other counter is read off the SDK's published streams. The Views port collects the `StateFlow`s (plus a 1s ticker keeps the visible counters fresh even between events).

### Message timestamps — `MessageTimestamp.kt`, `ChatScreenController.kt`, `ChatAdapter.kt`

Every `ChatMessage` already carries a `timestamp` (epoch millis). When `DevSettings.showMessageTimestamps` is on, the chat's list builder interleaves iMessage-style timestamp rows wherever the gap between two consecutive messages exceeds ~5 minutes. A `Timestamp` case is added to the list's row model and a small `item_timestamp.xml` row renders the label:

The SDK signal:

```kotlin
ChatMessage.timestamp   // epoch millis set when the message was sent / received
```

In the controller (the `ListItem` row model lives in `ChatAdapter.kt`):

```kotlin
sealed interface ListItem {
    data class Timestamp(val messageId: UUID, val epochMillis: Long) : ListItem
    data class Message(val message: ChatMessage, val showSendingLabel: Boolean) : ListItem
    data class Suggestions(val messageId: UUID, val suggestions: List<ResponseSuggestion>) : ListItem
    data class Typing(val avatarUrl: URI?) : ListItem
}

private fun buildItems(): List<ListItem> {
    val msgs = latestMessages
    val items = mutableListOf<ListItem>()
    var previous: Long? = null
    msgs.forEach { m ->
        if (showTimestamps && MessageTimestamp.shouldInsertSeparator(previous, m.timestamp)) {
            items.add(ListItem.Timestamp(m.id, m.timestamp))
        }
        items.add(ListItem.Message(m, showSendingLabel = sendingLabels.contains(m.id)))
        previous = m.timestamp
    }
    // ...suggestions + typing rows...
    return items
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

### In-app new-message banners (local workaround) — `NewMessageNotifier.kt`

Pop a notification banner when the agent replies — handy for confirming chunked/streaming replies land a single alert. ⚠️ **Local-notification workaround, not remote push** (no FCM yet — **coming soon**). It posts via `NotificationManager` under a `NotificationPolicy` (the default stays quiet while the chat is on screen); once Android kills the process nothing arrives — real delivery needs FCM + a server-side push integration the SDK doesn't provide yet.

The SDK signal:

```kotlin
session.client.events   // completed-message events (full text + stable messageId)
```

In the controller — own one per chat surface and start it once the session exists:

```kotlin
private val notifier = NewMessageNotifier(activity)

fun start() {
    // ...layout / adapter / bind...
    notifierJob = notifier.start(activity.lifecycleScope, activity.lifecycle, session, NotificationPolicy.WHEN_BACKGROUNDED)
}
```

**Under the hood:** three steps. (1) *Permission + channel* — `start()` creates the `poly.newMessages` `NotificationChannel` (display name "New messages", API 26+); `post()` no-ops without `POST_NOTIFICATIONS` on API 33+, which the host Activity requests via `requestNotificationPermissionIfNeeded()`. (2) *Listen + dedupe* — collection runs under `repeatOnLifecycle(Lifecycle.State.CREATED)` (not STARTED, so replies that land while backgrounded still banner) over `session.client.events`, mapping the *completed* `AgentMessage`/`LiveAgentMessage` payloads (full text + stable `messageId`, not chunks) and skipping ids already in the SharedPreferences-persisted `NotifiedMessageStore` — resume/relaunch replays don't re-notify. (3) *Gate + post* — under the default `WHEN_BACKGROUNDED` policy it posts only when the chat is NOT on screen (lifecycle below `STARTED` (not visible); `ALWAYS` posts regardless), then `markShown(id)` persists the id either way so a later replay can't re-fire.

Toggle streaming in the Settings sheet to confirm it stays one banner per reply (the bubble `id` is stable across chunks).

*See [Integration guide › In-app new-message alerts (local-only workaround)](../../../../README.md#in-app-new-message-alerts-local-only-workaround).*

## What this example is for

- protocol smoke tests against dev / staging / cluster / custom environments
- reconnect and close-code experiments (4002 / 4001 / 1000)
- progressive-streaming verification with the live toggle
- inspecting raw event payloads while keeping `ChatSession`'s UI behaviour visible

---

- **Compose counterpart:** [`examples/compose/07-playground/`](../../compose/07-playground/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
