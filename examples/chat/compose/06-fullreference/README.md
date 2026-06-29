# 06-FullReference (Compose)

The full production reference on top of [`05-Handoff`](../05-handoff/): the same flow a shipping app would use — **configure → resume-or-start → chat → end** — with a dedicated connect screen, a loading screen, an error screen, and no dev diagnostics. (06 also brings back 03's new-message notifier.)

Everything inherited from earlier rungs (rich content, handoff pills, offline/reconnect states) still applies — read [`02-Standard`](../02-standard/) through [`05-Handoff`](../05-handoff/) first. This README covers only what 06 adds.

## Run it

Open the repo in Android Studio and run the `:examples:compose:06-fullreference` module, or from the repo root:

```bash
./gradlew :examples:compose:06-fullreference:installDebug
```

Then launch the installed app. Set your API key in `src/main/kotlin/ai/poly/examples/fullreference/compose/FullReferenceApplication.kt` (currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- A four-screen router: **connect → loading → chat → error**, driven by the SDK's three lifecycle streams (`client.events`, `client.connectionStatus`, `client.sessionState`)
- A recoverable error screen with a **Go Back** route to connect (not a latched terminal flag)
- Production **resume / start-new** flows on the no-arg facade: `PolyMessaging.chat()`, `PolyMessaging.start()`, `PolyMessaging.hasResumableSession()`
- A fresh transcript over the same client with `ChatSession(existing.client)` + `client.startNewSession()`
- In-place start-new via `session.clearChat()` + `session.client.startNewSession()` — no screen change
- A "Resumed previous conversation" banner driven by `SessionStatus.RESTORED`
- A destructive **End Conversation** confirm (ends the session for good — no resume after)
- Delayed "Sending..." labels (500ms) so fast confirmations don't flash
- Failed sends retried via `draftId`: `session.removeMessage(draftId)` then re-send
- Streaming-aware scroll: re-anchor on text-length growth, not just `messages.size`
- In-app new-message banners with a `NotificationPolicy` (quiet while the chat is on screen)

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide).

## How it works

### The screen router — `AppRoot.kt`

One small state machine decides what's on screen; the SDK's raw streams drive it:

```kotlin
sealed interface AppScreen {
    data object Connect : AppScreen
    data object Loading : AppScreen
    data object Chat : AppScreen
    data class Error(val message: String) : AppScreen
}

// Resume vs start-fresh is the only difference — config came from initialize(...) once.
val s = if (forceFresh) PolyMessaging.start() else PolyMessaging.chat()
screen = AppScreen.Loading

scope.launch {
    s.client.events.collect { event ->
        if (event is MessagingEvent.SessionStart && screen == AppScreen.Loading) screen = AppScreen.Chat
        if (event is MessagingEvent.Disconnected && screen == AppScreen.Loading) {
            event.error?.let { screen = AppScreen.Error("Couldn't connect.\n${it.debugDescription}") }
        }
    }
}
scope.launch {
    s.client.connectionStatus.collect { status ->
        if (status is ConnectionStatus.Failed && screen == AppScreen.Loading) {
            screen = AppScreen.Error("Connection failed.\n${status.reason?.debugDescription ?: "Unknown failure"}")
        }
    }
}
scope.launch {
    s.client.sessionState.collect { state ->
        if (state.status == SessionStatus.RESTORED) wasResumed = true
        if (state.isReady && (screen == AppScreen.Loading || screen.isError)) screen = AppScreen.Chat
        if (state.isError && screen == AppScreen.Loading) {
            screen = AppScreen.Error(state.errorMessage ?: "Couldn't start the session.")
        }
    }
}
```

**Under the hood:** three lifecycle streams cover the router — `client.sessionState` is the SDK's own lifecycle state (loading / ready / restored / error), `client.connectionStatus` the transport (`Failed` routes to the error screen while loading), `client.events` the decoded wire stream. Between them the router never needs private hooks; transitions only fire *while still loading*, so a mid-chat blip never throws the user back.

*See [Integration guide › Connection & reconnect](../../../../README.md#connection--reconnect).*

### Recoverable error screen — `ErrorScreen.kt`

06's terminal state is not a latched failure flag — it's just `AppScreen.Error(message)` set by the lifecycle subscriptions above. **Go Back** routes to `AppScreen.Connect`, where the user can resume or start fresh:

```kotlin
ErrorScreen(
    message = current.message,
    onBack = { screen = AppScreen.Connect },
)
```

The error subtitles use `PolyError.debugDescription` rather than a localized message — it gives the actual case text.

**Under the hood:** routing errors only while `Loading` means the error screen is recoverable by design — a transient blip after the chat is up just flips `connection` and recovers itself. If you want a non-recoverable terminal screen instead (the `04-Resilience` pattern), bind to `session.failureReason` directly.

*See [Integration guide › Terminal errors](../../../../README.md#terminal-errors).*

### Start New with an active session — `AppRoot.kt`

```kotlin
// A fresh, empty transcript bound to the same connection:
session = ChatSession(existing.client)
scope.launch { existing.client.startNewSession() }
```

**Under the hood:** `ChatSession` is just an observable view over a client. Constructing a new one gives you an empty `messages` list; `startNewSession()` ends the old conversation server-side, creates a new session, and reconnects — the new transcript fills from the fresh `sessionStart`.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### In-place start-new — `AppRoot.kt`

When the chat ends, the chat-ended footer's **Start New Conversation** button offers a fresh conversation without bouncing through the connect screen:

```kotlin
fun startNewConversationInPlace() {
    val s = session ?: return
    s.clearChat()                                           // wipe the local transcript immediately
    scope.launch { runCatching { s.client.startNewSession() } }
}
```

**Under the hood:** `clearChat()` and `startNewSession()` work on the same client — so the lifecycle subscriptions (tied to that client) don't need re-arming. `ChatSession` detects the new session id and resets its latched flags, so the screen converges on a clean conversation without leaving `AppScreen.Chat`.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### End → back to connect — `AppRoot.kt`

`end()` tears down the server-side session for good (it can't be resumed afterwards). The destructive `AlertDialog` confirm guards it, then we drop the local `session` and route back to connect:

```kotlin
fun endConversation() {
    val pending = session
    scope.launch {
        runCatching { pending?.end() }
        session = null
        wasResumed = false
        screen = AppScreen.Connect
    }
}
```

**Under the hood:** awaiting `end()` before clearing the local `session` ensures the server has acknowledged the teardown before the connect screen re-probes `hasResumableSession()` — otherwise you can get a phantom "Resume" button for a session that's already dead.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### Resume across launches — `ConnectView.kt`

```kotlin
PolyMessaging.hasResumableSession()   // a stored session id within the resume window?
PolyMessaging.chat()                  // resumes it (or creates one if not)
PolyMessaging.start()                 // always starts fresh
```

The primary button flips between **Resume Chat** and **Start Chat** based on `hasActiveSession || canResume`; a secondary **Start New Chat** appears only when there's something to abandon. `hasResumableSession()` is a side-effect-free on-disk check (no network), so it's safe to call on every recomposition.

*See [Integration guide › Quick start](../../../../README.md#quick-start) and [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### Delayed "Sending..." label — `AppRoot.kt` (`ChatScreenHost`)

The SDK already tracks real delivery (`Delivery.PENDING` → `SENT`); the 500ms delay is purely app-side polish on *showing* the label, so fast confirmations never flash it:

```kotlin
var sendingLabels by remember { mutableStateOf<Set<UUID>>(emptySet()) }
val trackedPending = remember { mutableSetOf<UUID>() }

LaunchedEffect(messages) {
    for (m in messages) {
        val u = (m as? ChatMessage.User)?.message ?: continue
        if (u.delivery == Delivery.PENDING && trackedPending.add(u.id)) {
            scope.launch {
                delay(500)
                // Only show the label if the draft is STILL pending after 500ms.
                val current = (session.messages.value.firstOrNull { it.id == u.id } as? ChatMessage.User)?.message
                if (current?.delivery == Delivery.PENDING) sendingLabels = sendingLabels + u.id
            }
        }
    }
    // Drop ids that left PENDING — keeps the sets in sync with reality.
    val stillPending = messages.mapNotNull { (it as? ChatMessage.User)?.message }
        .filter { it.delivery == Delivery.PENDING }.map { it.id }.toSet()
    sendingLabels = sendingLabels intersect stillPending
    trackedPending.retainAll(stillPending)
}
```

**Under the hood:** this only gates *display* — the SDK still reports `PENDING` immediately and `SENT` the moment the server confirms. The bubble is in `messages` from the first frame either way; the label is the only thing this code controls.

*See [Integration guide › Delivery state & retry](../../../../README.md#delivery-state--retry).*

### Retry removes the failed draft, then re-sends — `AppRoot.kt`

A failed optimistic message stays in `messages` as a real draft keyed by its `draftId`. Retry drops the stale bubble first, then re-sends the text:

```kotlin
onRetry = { text, draftId ->
    if (draftId != null) session.removeMessage(draftId)
    onSend(text)
}
```

**Under the hood:** without `removeMessage`, retrying would leave a "Failed" bubble next to the new attempt — `send()` always creates a fresh draft id rather than mutating the old one. Dropping the failed draft first is what keeps the transcript clean.

*See [Integration guide › Delivery state & retry](../../../../README.md#delivery-state--retry).*

### Streaming-aware scroll — `ChatView.kt`

Streaming agent replies grow the *text* of an existing bubble without changing `messages.size`, so a naive "scroll on new message" misses them. Derive scalar signals for everything that can change content height and key one `LaunchedEffect` on all of them:

The SDK signals:

```kotlin
messages.size                          // new bubble arrived
messages.lastOrNull()?.text?.length    // streaming grew the last bubble's text in place
session.isAgentTyping                  // typing dots appeared / disappeared
messages.lastOrNull()?.suggestions     // suggestion pills appeared / changed
messages.lastOrNull()?.attachments     // attachments arrived
```

In the view:

```kotlin
// New content of ANY kind: count, streaming text growth, typing, sending labels,
// suggestions, attachments.
val last = messages.lastOrNull()
val lastTextLength = last?.text?.length ?: 0
val lastSuggestionCount = last?.suggestions?.size ?: 0
val lastAttachmentCount = last?.attachments?.size ?: 0
LaunchedEffect(messages.size, lastTextLength, isAgentTyping, sendingLabels, lastSuggestionCount, lastAttachmentCount) {
    if (messages.isEmpty()) return@LaunchedEffect
    if (followBottom) {
        listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        // Re-run after a beat so it catches the layout settling as a streaming bubble grows.
        delay(100)
        listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    } else {
        hasNewBelow = true
    }
}
```

> **Streaming:** agent replies grow token-by-token by default (`Configuration.streamingEnabled = true` — ChatGPT-style). The `lastTextLength` key above is what keeps the scroll pinned to the bottom while the text grows. See the root README's [*Streaming*](../../../../README.md#streaming) section and [`07-Playground`](../07-playground/) for a live toggle.

**Under the hood:** with `streamingEnabled = true` (the default), `ChatSession` extends the last agent message's `text` on every chunk and re-publishes `messages`. Keying the effect on `lastTextLength` (a derived `Int`) gives Compose a stable, scalar signal to drive the scroll — without it, a streaming reply would slide off the bottom of the screen as it grows.

*See [Integration guide › Streaming](../../../../README.md#streaming).*

### In-app new-message banners — `NewMessageNotifier.kt`

06 brings back 03's local-notification banner, wired in as `NewMessageNotifier(session, policy = NotificationPolicy.WHEN_BACKGROUNDED)`. It is a local-notification workaround, not remote push — there's no FCM here, so once the OS kills the process nothing arrives.

```kotlin
@Composable
fun NewMessageNotifier(session: ChatSession, policy: NotificationPolicy = NotificationPolicy.WHEN_BACKGROUNDED)
```

**Under the hood:**

- **Permission prompt** — on API 33+ (`Build.VERSION_CODES.TIRAMISU`) it requests `Manifest.permission.POST_NOTIFICATIONS` via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`; on older versions, or if denied, it silently no-ops.
- **Notification channel** — a `NotificationChannel` (`IMPORTANCE_HIGH`) is created once on first composition, required for any banner on API 26+.
- **CREATED-lifecycle collection** — it collects `session.client.events` under `repeatOnLifecycle(Lifecycle.State.CREATED)`, not `STARTED`, so a reply that lands while the chat is backgrounded still raises a banner. `WHEN_BACKGROUNDED` then suppresses the banner whenever the chat is actually visible (`currentState.isAtLeast(Lifecycle.State.STARTED)`) — STARTED, not RESUMED, so a reply that arrives while the shade is down or a dialog is up doesn't banner over a chat you're reading.
- **Persisted dedupe** — completed messages only (chunks ignored); each is keyed by its server `messageId` in a bounded `SharedPreferences`-backed store, so the SDK's replay-on-resume doesn't re-fire old banners across relaunches.

*See [Integration guide › In-app new-message alerts (local-only workaround)](../../../../README.md#in-app-new-message-alerts-local-only-workaround).*

### Everything else

- **Bottom status bars** — 06 moves the offline / reconnecting bars to sit directly above the composer.
- **Optimistic composing** — the input stays enabled while offline / reconnecting / failed; only an ended conversation disables it (`hasEnded`, or a terminal clean-closed connection the SDK will never reconnect).
- **System pills with levels** — `ServerMessage` colors by `SystemMessageLevel`; `handoffRequired` with an http(s) route renders as a tappable link pill.
- **Notifier** — local new-message banners are covered above in [*In-app new-message banners*](#in-app-new-message-banners--newmessagenotifierkt).

## Try this on the emulator

| Action | What you should see |
|---|---|
| Launch fresh → Start Chat | Loading → chat with the greeting |
| Back chevron → Resume Chat | Straight back into the same transcript |
| Back chevron → Start New Chat | A brand-new conversation over the same client |
| ✕ → End Conversation | Confirm dialog → session ends → connect screen (no resume offered) |
| Force-stop, relaunch → Resume Chat | Prior transcript restored + a 3s "Resumed previous conversation" banner |
| Background the app, have the agent reply | A system notification with the reply (default policy stays quiet on screen) |

## What this example skips

- runtime configuration knobs (`DevSettings`), raw transport tap, live diagnostics, event log, message timestamps → [`../07-playground/`](../07-playground/)

---

- **Views counterpart:** [`../../views/06-fullreference/`](../../views/06-fullreference/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
