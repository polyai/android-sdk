# 06-FullReference (Views)

The full production reference on top of [`05-Handoff`](../05-handoff/): the same flow a shipping app would use — **configure → resume-or-start → chat → end** — with a dedicated connect screen, a loading screen, an error screen, and no dev diagnostics. The Views twin of [`examples/compose/06-fullreference/`](../../compose/06-fullreference/). (06 also brings back 03's new-message notifier.)

Everything inherited from earlier rungs still applies — read [`02-Standard`](../02-standard/) through [`05-Handoff`](../05-handoff/) first. This README covers only what 06 adds.

## Run it

Open the repo in Android Studio and run the `:examples:views:06-fullreference` module, or from the repo root:

```bash
./gradlew :examples:views:06-fullreference:installDebug
```

Then launch `RootActivity`. Set your API key in `src/main/kotlin/ai/poly/examples/fullreference/views/FullReferenceApplication.kt` (the `API_KEY` constant is currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- A container Activity (`RootActivity`) that owns the single `ChatSession` and swaps **connect / loading / chat / error** screens
- Lifecycle transitions driven off the client's raw streams: `client.events`, `client.connectionStatus`, `client.sessionState`
- Production **resume / start-new**: `PolyMessaging.chat()`, `PolyMessaging.start()`, `PolyMessaging.hasResumableSession()`
- A fresh transcript over the same client: `ChatSession(existing.client)` + `client.startNewSession()`
- A recoverable error screen with a "Go Back" route to connect (not a latched terminal flag)
- The back chevron pauses back to connect **without** ending the session; the ✕ ends it for good (destructive confirm)
- In-place start-new from the chat-ended footer via `session.clearChat()` + `session.client.startNewSession()` — no screen change
- A "Resumed previous conversation" banner driven by `SessionStatus.RESTORED`
- 500ms-delayed "Sending..." labels; failed sends retried via `draftId` (`session.removeMessage` then re-send)
- System pills with level styling (info / warning / error) and a tappable `handoffRequired` link bubble
- In-app new-message banners with a `NotificationPolicy` (quiet while the chat is on screen)

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide); this example shows them as one concrete multi-screen app.

## How it works

Each subsection leads with **the SDK call** (the actual API), then shows **how it's wired into the activity / controller**.

### The screen container — `RootActivity.kt`

```kotlin
private fun configureAndStart(forceFresh: Boolean) {
    // existing session + forceFresh → a fresh transcript over the SAME client:
    session = ChatSession(existing.client)
    lifecycleScope.launch { existing.client.startNewSession() }
    // no session → resume-or-start on the no-arg facade:
    val s = if (forceFresh) PolyMessaging.start() else PolyMessaging.chat()
    subscribeLifecycle(s.client)
}

private fun subscribeLifecycle(client: PolyMessagingClient) {
    // sessionStart while loading → chat; Disconnected(error) while loading → error
    // connectionStatus Failed while loading → error
    // sessionState: RESTORED → wasResumed; isReady → chat; isError → error
}
```

The collectors are tied to the **client** (not the ChatSession), so an in-place start-new keeps them alive — the fresh session's `sessionStart` flips the UI back to chat.

**Under the hood:** `PolyMessaging.initialize(...)` (once, in `FullReferenceApplication`) stashes the API key and environment process-wide — no network happens yet — so the no-arg facade calls (`chat()`, `start()`, `hasResumableSession()`) reuse that config from any screen. Loading → chat is gated on `state.isReady` (or a `SessionStart` event) and only fires *while still loading*, so a mid-chat reconnect never throws the user back to the loading screen. Errors are routed via `showError(...)` only while loading, for the same reason — a transient blip after the chat is up just flips `connection` and recovers itself. `state.status == SessionStatus.RESTORED` is how you know it's a warm resume.

*See [Integration guide › Quick start](../../../../README.md#quick-start) and [Integration guide › Connection & reconnect](../../../../README.md#connection--reconnect).*

### Recoverable error screen — `RootActivity.kt` + `screen_error.xml`

06's terminal state is not a latched failure flag — it's `Screen.ERROR` set by the lifecycle collectors above. "Go Back" routes to connect:

The SDK signal that fed it:

```kotlin
client.sessionState   // state.isError on session-creation failure
// (and ConnectionStatus.Failed on connectionStatus, Disconnected(error) on events)
```

In the activity:

```kotlin
private fun showError(message: String) {
    screen = Screen.ERROR
    val b = ScreenErrorBinding.inflate(LayoutInflater.from(this), binding.container, false)
    b.errorMessage.text = message
    styleProminent(b.goBack)
    b.goBack.setOnClickListener { showConnect() }
    transition(b.root)
}
```

The error subtitles use `PolyError.debugDescription` for a developer-readable reason.

**Under the hood:** routing errors only while `LOADING` means the error screen is recoverable by design — Go Back returns to connect and the user can retry. The `sessionState` collector also flips `ERROR → chat` when `state.isReady` arrives, so a session that recovers on its own re-enters the chat. If you want a non-recoverable terminal screen instead (the `04-Resilience` pattern), bind to `session.failureReason` directly.

*See [Integration guide › Terminal errors](../../../../README.md#terminal-errors).*

### Resume-or-start picker — `RootActivity.kt` + `screen_connect.xml`

The connect screen picks button labels off `hasResumableSession()`:

The SDK call:

```kotlin
PolyMessaging.hasResumableSession()   // true if a stored session is within the timeout
```

In the activity — `showConnect()` picks the primary label off `hasActiveSession || canResume` and only shows the secondary "Start New Chat" button when resume is offered:

```kotlin
private fun showConnect() {
    screen = Screen.CONNECT
    val b = ScreenConnectBinding.inflate(LayoutInflater.from(this), binding.container, false)
    val hasActiveSession = session != null
    val canResume = PolyMessaging.hasResumableSession()
    val primaryShowsResume = hasActiveSession || canResume
    b.primaryAction.text = if (primaryShowsResume) "Resume Chat" else "Start Chat"
    // ...icon + styling...
    b.primaryAction.setOnClickListener { configureAndStart(forceFresh = false) }
    b.startNewAction.visibility = if (primaryShowsResume) View.VISIBLE else View.GONE
    b.startNewAction.setOnClickListener { configureAndStart(forceFresh = true) }
    if (hasActiveSession) {
        b.statusCaption.text = "Your conversation is still active"
        b.statusCaption.visibility = View.VISIBLE
    } else if (canResume) {
        b.statusCaption.text = "A previous conversation is available to resume"
        b.statusCaption.visibility = View.VISIBLE
    }
    transition(b.root)
}
```

**Under the hood:** `hasResumableSession()` is a side-effect-free on-disk check (no network), so it's safe to call every time the connect screen is shown — keeping the buttons honest as the user moves between connect / chat / connect.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### Nav-bar End vs back — `RootActivity.kt`

The root owns both nav-bar affordances. The ✕ options-menu item **ends** the session (`session.end()`) after a confirm dialog and returns to connect; the home chevron **pauses** to connect *without* ending — the session stays alive for the user to come back to.

The SDK call:

```kotlin
session.end()   // permanent; the conversation cannot be resumed after this
```

In the activity:

```kotlin
override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
    android.R.id.home -> { showConnect(); true } // pause back WITHOUT ending
    MENU_END -> { confirmEnd(); true }           // end for good (AlertDialog confirm)
    else -> super.onOptionsItemSelected(item)
}

private fun endConversation() {
    val pending = session
    lifecycleScope.launch {
        runCatching { pending?.end() }
        session = null
        wasResumed = false
        showConnect()
    }
}
```

**Under the hood:** awaiting `end()` before clearing the local `session` ensures the server has acknowledged the teardown before the connect screen re-probes `hasResumableSession()` — otherwise you can get a phantom "Resume" button for a session that's already dead.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### In-place start-new — `ChatScreenController.kt`

When the chat ends, the chat-ended footer's "Start New Conversation" button (`screen_chat.xml`) offers a new conversation without bouncing through the connect screen:

The SDK calls:

```kotlin
session.clearChat()                  // wipe the local transcript immediately
session.client.startNewSession()     // ends the current server session, starts a fresh one on the SAME client
```

In the controller:

```kotlin
private fun startNewConversationInPlace() {
    session.clearChat()
    activity.lifecycleScope.launch { runCatching { session.client.startNewSession() } }
}
```

**Under the hood:** `startNewSession()` reuses the existing client, so the lifecycle collectors in `RootActivity` (tied to that client) don't need re-arming — they flip the root back to chat once the new session is ready. `ChatSession` detects the new session id and resets its latched flags, so the screen converges without leaving chat.

*See [Integration guide › Starting, resuming & ending a session](../../../../README.md#starting-resuming--ending-a-session).*

### The chat surface — `ChatScreenController.kt`

The rung-05 chat, re-packaged as a controller the Root hands a session to, plus: the resume banner (top of the banner stack, ~3s), the 500ms `sendingLabels` machinery, `draftId`-based retry, a skeleton gate that also yields to failures (`!isReady && messages.isEmpty && !hasEnded && !hasFailed`), and 03's new-message notifier (`NewMessageNotifier.kt`) running under `NotificationPolicy.WHEN_BACKGROUNDED` so it stays quiet while the chat is on screen.

**Under the hood:** `NewMessageNotifier.start(...)` collects `session.client.events` under `repeatOnLifecycle(Lifecycle.State.CREATED)` (CREATED, not STARTED, so a reply that lands while the chat is backgrounded still raises a banner). For each `AgentMessage` / `LiveAgentMessage` it (1) drops anything already seen — it dedupes on the server `messageId`, persisted in `SharedPreferences`, so a resume/relaunch replay never re-fires; (2) under `WHEN_BACKGROUNDED`, posts only when the chat isn't on screen (lifecycle below `STARTED`), so you're never banner-spammed for a conversation you're already reading; and (3) marks the id handled either way. On API 33+ the host Activity must request `POST_NOTIFICATIONS` first (only when the policy isn't `NEVER`).

*See [Integration guide › In-app new-message alerts (local-only workaround)](../../../../README.md#in-app-new-message-alerts-local-only-workaround).*

### Delayed "Sending..." label — `ChatScreenController.kt`

The SDK already tracks real delivery (`PENDING → SENT`); the ~500ms delay is purely app-side polish on *showing* the label, so fast confirmations never flash it:

The SDK signal:

```kotlin
UserMessage.delivery   // PENDING / SENT / FAILED (PENDING on optimistic send)
```

In the controller — `syncSendingLabels()` runs on every `messages` emission:

```kotlin
private val sendingLabels = mutableSetOf<UUID>()
private val trackedPending = mutableSetOf<UUID>()

private fun syncSendingLabels(messages: List<ChatMessage>) {
    for (m in messages) {
        val u = (m as? ChatMessage.User)?.message ?: continue
        if (u.delivery == Delivery.PENDING && trackedPending.add(u.id)) {
            val id = u.id
            activity.lifecycleScope.launch {
                delay(500)
                // Only show the label if the draft is STILL pending after 500ms.
                val current =
                    (session.messages.value.firstOrNull { it.id == id } as? ChatMessage.User)?.message
                if (current?.delivery == Delivery.PENDING) {
                    sendingLabels.add(id)
                    refresh()
                }
            }
        }
    }
    // Drop ids that left PENDING — keeps the sets in sync with reality.
    val stillPending = messages
        .mapNotNull { (it as? ChatMessage.User)?.message }
        .filter { it.delivery == Delivery.PENDING }
        .map { it.id }
        .toSet()
    sendingLabels.retainAll(stillPending)
    trackedPending.retainAll(stillPending)
}
```

**Under the hood:** this only gates *display* — the SDK still reports `PENDING` immediately and `SENT` the moment the server confirms. The bubble is in `messages` from the first frame either way; the label (`showSendingLabel` on the row's `ListItem.Message`) is the only thing this code controls.

*See [Integration guide › Delivery state & retry](../../../../README.md#delivery-state--retry).*

### Retry removes the failed draft, then re-sends — `ChatScreenController.kt` + `ChatAdapter.kt`

A failed optimistic message stays in `messages` as a real draft keyed by its `draftId`. Retry drops the stale bubble first, then re-sends the text:

The SDK calls:

```kotlin
session.removeMessage(draftId)   // drop the failed optimistic draft from messages
session.send(text)               // re-send as a fresh draft (new id)
```

In the controller — `ChatAdapter` wires the red retry button on `FAILED` bubbles to its `onRetry` callback with the bubble's text + `draftId`:

```kotlin
private val adapter = ChatAdapter(
    onRetry = { text, draftId ->
        if (draftId != null) session.removeMessage(draftId)
        activity.lifecycleScope.launch { runCatching { session.send(text) } }
    },
    // ...onSuggestionTap...
)
```

**Under the hood:** without `removeMessage`, retrying would leave a "Failed" bubble next to the new attempt — `send()` always creates a fresh draft id rather than mutating the old one. Dropping the failed draft first is what keeps the transcript clean.

*See [Integration guide › Delivery state & retry](../../../../README.md#delivery-state--retry).*

### System pills with levels — `ChatAdapter.kt`

`ServerMessage` colors by `SystemMessageLevel`; `handoffFailed`/`handoffTimeout` render error-red, `idleWarning` warning-orange. An `handoffRequired` with an http(s) route becomes a tappable blue bubble that opens it; anything else shows "Contact Support".

*See [Integration guide › Live agent handoff](../../../../README.md#live-agent-handoff).*

## Try this on the emulator

| Action | What you should see |
|---|---|
| Launch fresh → Start Chat | Loading → chat with the greeting |
| ‹ back → Resume Chat | Straight back into the same transcript |
| ‹ back → Start New Chat | A brand-new conversation over the same client |
| ✕ → End Conversation | Confirm dialog → session ends → connect (no resume offered) |
| Agent ends the chat → Start New Conversation | Chat-ended footer → a fresh transcript over the same client, no screen change |
| Force-stop, relaunch → Resume Chat | Prior transcript + a 3s "Resumed previous conversation" banner |
| Background the app, have the agent reply | A system notification with the reply |

## What this example skips

- runtime configuration, raw transport experiments, diagnostics, event log, message timestamps → 07-Playground (see [`../07-playground/`](../07-playground/))

> **Streaming:** agent replies stream token-by-token by default (the bubble grows as chunks land), with no extra code here — the chat surface just binds to `messages`. The live `streamingEnabled` toggle to compare against complete-message bubbles lives in 07-Playground. See [Integration guide › Streaming](../../../../README.md#streaming).

---

- **Compose counterpart:** [`../../compose/06-fullreference/`](../../compose/06-fullreference/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
