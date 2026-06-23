# 04-Resilience (Views)

A device-offline banner, a pre-handshake loading skeleton, and a full-screen terminal-error screen on top of [`03-RichContent`](../03-richcontent/). The Views counterpart of [`examples/compose/04-resilience/`](../../compose/04-resilience/).

Setup, scaffolding, and everything inherited from 03 (rich text, attachments, URL cards, call actions) are unchanged — read [`02-Standard`](../02-standard/) and [`03-RichContent`](../03-richcontent/) first. This README covers only what 04 adds. (04 drops 03's new-message notifier to stay focused on resilience.)

## Run it

Open the repo in Android Studio and run the `:examples:views:04-resilience` module, or from the repo root:

```bash
./gradlew :examples:views:04-resilience:installDebug
```

Then launch `ChatActivity`. Set your API key in `src/main/kotlin/ai/poly/examples/resilience/views/ResilienceApplication.kt` (the `API_KEY` constant is currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- Track **device** connectivity with `ConnectivityManager` independently of the SDK's socket state
- Stack a red "offline" banner above the SDK's yellow "reconnecting" banner — both can show at once
- Show a pulsing loading skeleton while `!session.isReady && session.messages.isEmpty()` (and hide the list)
- Replace the entire chat surface with a full-screen retry screen when `session.failureReason` is non-null
- Recover via `session.client.resume()` from the terminal screen — re-establishes the dead socket against the **same** session, so the transcript survives (see the note below)

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../README.md#integration-guide).

## How it works

Each subsection leads with **the SDK signal** (the actual API), then shows **how it's wired into `ChatActivity`**.

### Device-offline banner — `NetworkMonitor.kt` + the `offlineBanner` bar

The SDK signal:

```kotlin
session.connection   // ConnectionStatus — Idle/Connecting/Open/Closing/Closed/Reconnecting/Failed (StateFlow)
```

`isOnline` is your own state. `NetworkMonitor` wraps `ConnectivityManager.NetworkCallback`, exposing it as a `StateFlow`:

```kotlin
class NetworkMonitor(context: Context) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _isOnline.value = true }
        override fun onLost(network: Network) { _isOnline.value = false }
    }
    fun start() { cm?.registerDefaultNetworkCallback(callback); /* + seed initial value */ }
    fun stop() { cm?.unregisterNetworkCallback(callback) }
}
```

`ChatActivity` starts it in `onCreate` (stops in `onDestroy`) and toggles a red bar — laid out in `activity_chat.xml` directly **above** the SDK's yellow reconnect banner — from `isOnline`, while `session.connection` drives the reconnect banner:

```kotlin
networkMonitor.start()
// in bind():
launch { networkMonitor.isOnline.collect { online ->
    binding.offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
} }
launch { session.connection.collect { status ->
    binding.banner.visibility =
        if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
} }
```

**Under the hood:** when the OS reports no usable default network, the SDK's reachability watcher drops its dead socket and `session.connection` flips to `Reconnecting`. The two banners measure different things — the offline pill is the device, the reconnect pill is the socket — so it's meaningful to show both.

*See [Integration guide › Connection & reconnect](../../../README.md#connection--reconnect).*

### Loading skeleton — `LoadingSkeletonView.kt`

A custom view of three pulsing rounded rows (220 / 260 / 190 dp, `systemGray5`), self-managing its pulse (alpha 1↔0.43 over 1.1s while attached + visible). `ChatActivity` shows it — and hides the `RecyclerView` — while the WebSocket is still opening and nothing has arrived:

```kotlin
session.isReady   // false until the SDK finishes its handshake (StateFlow<Boolean>)
session.messages  // non-empty across a mid-session reconnect → skip the skeleton

private fun updateSkeleton() {
    val show = !latestReady && latestMessages.isEmpty()
    binding.skeleton.visibility = if (show) View.VISIBLE else View.GONE
    binding.list.visibility = if (show) View.GONE else View.VISIBLE
}
```

**Under the hood:** `isReady` stays `false` until the REST + WebSocket handshake completes. On a mid-session reconnect, `messages` is already in memory while `isReady` drops to `false` — the `messages.isEmpty()` half of the gate is what skips the skeleton there. On a cross-launch resume the SDK persists only the session id/token (in `SharedPreferences`), not the messages — so at cold launch `messages` is empty and `isReady` is `false`, and the skeleton shows until the socket opens. The restored transcript is replayed by the server after `isReady` flips, which clears the gate. This resume only works inside the session-resume window (the SDK's ~10-min session timeout, matching the backend's WebSocket idle timeout — see [Starting, resuming & ending a session](../../../README.md#starting-resuming--ending-a-session)); past it, `chat()` starts fresh and the skeleton clears into an empty chat instead.

> **Streaming:** agent replies grow token-by-token by default (`Configuration.streamingEnabled = true` — ChatGPT-style). Set `streamingEnabled(false)` to render completed bubbles only. See the root README's [*Streaming*](../../../README.md#streaming) section and [`07-Playground`](../07-playground/) for a live toggle.

*See [Integration guide › Loading & empty states](../../../README.md#loading--empty-states).*

### Terminal error screen — the `failureOverlay`

Once the SDK gives up reconnecting, a full-screen overlay (`activity_chat.xml`'s `failureOverlay`: an orange warning triangle, "Couldn't connect", the reason, and a **Reconnect** button pinned near the bottom) replaces the chat. `ChatActivity` toggles it on `failureReason` and steps the End action aside while it shows:

```kotlin
session.failureReason   // PolyError? — non-null after the reconnect budget is exhausted (StateFlow)

launch { session.failureReason.collect { reason ->
    failureActive = reason != null
    binding.failureOverlay.visibility = if (reason != null) View.VISIBLE else View.GONE
    binding.failureLabel.text = reason?.debugDescription ?: ""
    invalidateOptionsMenu()   // hide the End action while the overlay is up
} }
// the Reconnect button:
binding.reconnect.setOnClickListener { lifecycleScope.launch { runCatching { session.client.resume() } } }
```

**Under the hood:** `failureReason` is set only after the SDK's exponential-backoff reconnect ladder is exhausted, or on a terminal session error. Transient blips just flip `connection` to `Reconnecting` and back — that's why this screen is full-screen and gated on `failureReason`.

> **`resume()` vs `startNewSession()` here:** `resume()` re-establishes a terminally-`Failed` socket against the **same** session (resetting the reconnect budget) so the existing transcript is kept — this example uses it deliberately to show same-session recovery. The root README's [resume() row](../../../README.md#starting-resuming--ending-a-session) recommends `startNewSession()` (or a fresh `chat()`/`start()`) for a generic user-facing "Try Again" when you'd rather hand the user a clean conversation. Pick whichever matches your product; [`06-FullReference`](../06-fullreference/) shows both on a dedicated connect screen.

*See [Integration guide › Terminal errors](../../../README.md#terminal-errors).*

## Try this on the emulator

| Action | What you should see |
|---|---|
| Toggle airplane mode mid-chat (`adb shell cmd connectivity airplane-mode enable`) | Red offline banner above a yellow reconnect banner; messages stay composable; toggle off → both clear |
| Launch with the network off | Loading skeleton (pulsing rows) → eventually the full-screen "Couldn't connect" → tap Reconnect once back online |
| Cold launch with a stored session inside the resume window | Brief skeleton while the socket reconnects → server replays the restored transcript (the window is the SDK's session-resume timeout, ~10 min — it matches the backend's WebSocket idle timeout; see [Starting, resuming & ending a session](../../../README.md#starting-resuming--ending-a-session)) |

## What this example skips

- live agent handoff → [`05-Handoff/`](../05-handoff/)
- resume / start-new on a dedicated connect screen, in-place restart → [`06-FullReference/`](../06-fullreference/)
- runtime configuration, raw transport, diagnostics → [`07-Playground/`](../07-playground/)

---

- **Compose counterpart:** [`examples/compose/04-resilience/`](../../compose/04-resilience/)
- **SDK reference:** root [README → Integration guide](../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../README.md#install)
