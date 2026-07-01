# 04-Resilience (Compose)

A device-offline banner, a pre-handshake loading skeleton, and a full-screen terminal-error screen on top of [`03-RichContent`](../03-richcontent/).

Setup, scaffolding, and everything inherited from 03 (rich text, attachments, URL cards, call actions) are unchanged — read [`02-Standard`](../02-standard/) and [`03-RichContent`](../03-richcontent/) first. This README covers only what 04 adds. (04 drops 03's new-message notifier to stay focused on resilience.)

## Run it

Open the repo in Android Studio and run the `:examples:compose:04-resilience` module, or from the repo root:

```bash
./gradlew :examples:compose:04-resilience:installDebug
```

Then launch the installed app. Set your API key in `src/main/kotlin/ai/poly/examples/resilience/compose/ResilienceApplication.kt` (currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- Track **device** connectivity with `ConnectivityManager` independently of the SDK's socket state
- Stack a red "offline" banner above the SDK's yellow "reconnecting" banner — both can show at once
- Gate a pulsing loading skeleton on `!session.isReady && session.messages.isEmpty()`
- Replace the entire chat surface with a full-screen retry screen when `session.failureReason` is non-null
- Recover via `session.client.resume()` from the terminal screen

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide); this example shows them as one concrete screen.

## How it works

Each subsection leads with **the SDK signal** (the actual API), then shows **how it's wired into `ChatScreen`**.

### Device-offline banner — `components/OfflineBanner.kt` + `NetworkMonitor.kt`

Track the OS network path separately from the SDK's socket and render a red bar above the yellow reconnect banner:

```kotlin
session.connection   // ConnectionStatus — Idle/Connecting/Open/Closing/Closed/Reconnecting/Failed (StateFlow)
```

`isOnline` is your own state, sourced from `ConnectivityManager`:

```kotlin
class NetworkMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _isOnline = MutableStateFlow(/* current default-network reachability */)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _isOnline.value = true }
        override fun onLost(network: Network) { _isOnline.value = false }
    }
    fun start() { cm?.registerDefaultNetworkCallback(callback); /* + seed initial value */ }
    fun stop() { cm?.unregisterNetworkCallback(callback) }
}
```

In `ChatScreen` — register it for the lifetime of the screen and stack the two banners:

```kotlin
val networkMonitor = remember { NetworkMonitor(context) }
DisposableEffect(networkMonitor) { networkMonitor.start(); onDispose { networkMonitor.stop() } }
val isOnline by networkMonitor.isOnline.collectAsStateWithLifecycle()

Column {
    OfflineBanner(isOnline)        // OS-level offline pill (red) — stacks ABOVE…
    ConnectionBanner(connection)   // …the SDK's reconnect pill (yellow). Both can be visible.
    // …message list + composer…
}
```

**Under the hood:** when the OS reports no usable default network, the SDK's reachability watcher drops its dead socket and `session.connection` flips to `Reconnecting`. The two banners measure different things — the offline pill is the device, the reconnect pill is the socket — so it's meaningful to show both.

*See [Integration guide › Connection & reconnect](../../../../README.md#connection--reconnect).*

### Loading skeleton — `components/LoadingSkeleton.kt`

Show pulsing placeholder rows only while the WebSocket is opening. Mid-session reconnects already have messages in memory, so they skip the skeleton.

```kotlin
session.isReady    // false until the SDK has finished its handshake and can send (StateFlow<Boolean>)
session.messages   // non-empty on a mid-session reconnect → skip the skeleton entirely
```

In `ChatScreen` — gate the skeleton inside the list:

```kotlin
val isReady by session.isReady.collectAsStateWithLifecycle()

LazyColumn {
    if (!isReady && messages.isEmpty()) {
        item { LoadingSkeleton() }
    } else {
        items(messages, key = { it.id }) { MessageBubbleView(it, /* … */) }
        if (isTyping) item { TypingIndicator(avatarUrl = lastAgentAvatar) }
    }
}
```

**Under the hood:** `isReady` flips `true` only when the WebSocket reaches Open — after the REST call has returned a session id AND the socket handshake completes. On a cold relaunch with a stored session within the timeout, the server replays the transcript over the socket after it opens — so the skeleton shows briefly while connecting, then clears into the restored messages. The `messages.isEmpty()` half of the gate matters for mid-session reconnects, where the transcript is already in memory while `isReady` is `false`.

> **Streaming:** agent replies grow token-by-token by default (`Configuration.streamingEnabled = true` — ChatGPT-style). Set `streamingEnabled = false` to render completed bubbles only. See the root README's [*Streaming*](../../../../README.md#streaming) section and [`07-Playground/`](../07-playground/) for a live toggle.

*See [Integration guide › Loading & empty states](../../../../README.md#loading--empty-states).*

### Terminal error screen — `TerminalErrorScreen.kt`

Once the SDK has given up reconnecting, replace the whole chat with a single retry button. The chat is useless in this state until the user explicitly retries.

```kotlin
session.failureReason     // PolyError? — non-null after the reconnect budget is exhausted (StateFlow)
session.client.resume()   // re-arm the connection from the retry button
```

In `ChatScreen` — gate the entire surface on it:

```kotlin
val reason = failureReason
if (reason != null) {
    TerminalErrorScreen(reason = reason, onRetry = { scope.launch { runCatching { session.client.resume() } } })
} else {
    // …offline banner + connection banner + list + composer…
}
```

The screen uses `reason.debugDescription` for the subtitle — its structural case name (`auth(unauthorized)`, `session(sessionExpired)`, …) pinpoints the exact failure. In your own app you'd more likely show the user-facing `reason.message` (e.g. "Connection lost — please try reconnecting.").

**Under the hood:** `failureReason` is set only after the SDK's exponential-backoff reconnect ladder is exhausted, or on a terminal session error (auth, session-expired, session-ended). Transient blips don't trip it — those just flip `connection` to `Reconnecting` and back. That's why this screen is full-screen and gated on `failureReason` rather than on `connection`.

*See [Integration guide › Terminal errors](../../../../README.md#terminal-errors).*

## Try this on the emulator

| Action | What you should see |
|---|---|
| Toggle the network mid-chat (`adb shell svc wifi disable` / airplane mode) | Red offline banner above a yellow reconnect banner; messages stay composable; re-enable → both clear |
| Launch with the network off | Loading skeleton (pulsing rows) + offline banner; restore network → skeleton clears into the conversation |
| Cold launch with a stored session within ~10 min | Brief skeleton while connecting → the server replays the restored transcript into the conversation |

## What this example skips

- live agent handoff → [`05-Handoff/`](../05-handoff/)
- resume / start-new on a dedicated connect screen, in-place restart → [`06-FullReference/`](../06-fullreference/)
- runtime configuration, raw transport, diagnostics → 07-Playground (see [`../07-playground/`](../07-playground/))

---

- **Views counterpart:** [`../../views/04-resilience/`](../../views/04-resilience/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
