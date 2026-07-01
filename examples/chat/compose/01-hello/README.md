# 01-Hello (Compose)

The smallest possible chat — initialize the SDK, render messages, send one. About 60 lines of `@Composable` code.

## Run it

Open the repo in Android Studio and run the **01-hello** Compose module, or from the repo root:

```bash
./gradlew :examples:compose:01-hello:installDebug
```

`installDebug` only installs the APK — tap the launcher icon to open it, or launch the launcher `MainActivity` directly:

```bash
adb shell am start -n ai.poly.examples.hello.compose/.MainActivity
```

Set your API key in `HelloApplication.kt` (currently `"YOUR_API_KEY"`); the committed default targets `Environment.US` — add `.cluster("dev")` / a `hostIdentifier` only if you need a non-default cluster.

## What this example demonstrates

- `PolyMessaging.initialize(context, config)` once at launch (in the `Application`)
- `PolyMessaging.chat()` for a session, held with `remember`
- Render `session.messages` with a `LazyColumn` + a `when (message)` over the `ChatMessage` cases
- Auto-scroll as the agent's reply streams in
- Send with `session.send(text)` from a coroutine (`scope.launch { runCatching { session.send(text) } }`)

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide); this example shows them as one concrete file.

## How it works

Each subsection leads with **the SDK call** (one line — the actual API), then shows **how it's wired into a `@Composable`**.

### Initialize once at app launch — `HelloApplication.kt`

Configure the SDK once at launch, from `Application.onCreate()`:

```kotlin
PolyMessaging.initialize(
    context = this,
    config = Configuration(
        apiKey = "YOUR_API_KEY",   // from Agent Studio → Connector Settings
        // environment defaults to Environment.US — add Environment.cluster("dev")
        // / a hostIdentifier only if needed
    ),
)
```

Wire the `Application` into the manifest so `onCreate()` runs before any `Activity`:

```kotlin
class HelloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(context = this, config = Configuration(apiKey = "YOUR_API_KEY"))
    }
}
```

```xml
<application android:name=".HelloApplication" ...>
```

After this, `PolyMessaging.chat()` works from any `Activity` / `@Composable`.

**Under the hood:** `initialize` just stashes your config (API key + environment) process-wide — no network happens yet. The work starts when you call `chat()`.

*See [Quick start](../../../../README.md#quick-start).*

### Get a session and render messages — `MainActivity.kt`

Create a session + collect its messages:

```kotlin
val session = PolyMessaging.chat()    // Resume the previous conversation if one exists within the
                                      // session timeout (default 10 min), else start a fresh one.
                                      // — use PolyMessaging.start() instead to always start fresh.

session.messages                      // StateFlow<List<ChatMessage>> — the whole transcript. Cases:
                                      //   ChatMessage.User / ChatMessage.Agent / ChatMessage.System

session.isReady                       // StateFlow<Boolean> — false until WebSocket + agent-join complete
```

In a `@Composable`:

```kotlin
@Composable
private fun ChatScreen() {
    val session = remember { PolyMessaging.chat() }
    val messages by session.messages.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // ...message list (see "Render each message" below)...
        // ...composer (see "Send a message" below)...
    }
}
```

`remember { … }` keeps **one** session per composition lifecycle. `session.messages` is a `StateFlow`, so `collectAsStateWithLifecycle()` re-renders any read of `messages` when the SDK updates the transcript.

**Streaming is on by default** — `Configuration.streamingEnabled` defaults to `true`, so agent replies grow token-by-token (ChatGPT-style). To switch to complete-message bubbles instead, pass `PolyMessaging.chat(streamingEnabled = false)`. See the root README's [Streaming](../../../../README.md#streaming) section.

**Under the hood:** `chat()` runs the whole REST + WebSocket handshake, agent-join, and resume-or-create for you; `isReady` flips true once it's connected. `messages` is the SDK-maintained transcript (`User` / `Agent` / `System`) that re-emits on every change, so your list just re-renders.

*See [Integration guide › The core pattern](../../../../README.md#the-core-pattern-render-messages-yourself).*

### Render each message — `MainActivity.kt`

A `LazyColumn` over `messages` with a stable `key`, and a `when` over the three `ChatMessage` cases:

```kotlin
LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
    items(messages, key = { it.id }) { MessageRow(it) }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    when (message) {
        is ChatMessage.User   -> Bubble(message.message.text, isUser = true,
                                        faded = message.message.delivery == Delivery.PENDING)
        is ChatMessage.Agent  -> Bubble(message.message.text, isUser = false)
        is ChatMessage.System -> Text(message.message.event.reason ?: "—",
                                      textAlign = TextAlign.Center, color = Color.Gray)
    }
}
```

`key = { it.id }` lets `LazyColumn` diff stably as bubbles arrive. With streaming on, the last `Agent` bubble's `text` grows in place — same item id, so Compose recomposes just that row.

A user bubble is drawn faded while `delivery == Delivery.PENDING`, then settles to full opacity once it's `SENT` — the lightweight version of delivery dots. (The full optimistic/retry treatment is in 02-Standard.)

**Under the hood:** with `streamingEnabled = true` (the default), `ChatSession` extends the last `Agent` message's `text` on every chunk and re-emits `messages`. The `StateFlow` collection recomposes `MessageRow` and the text grows as the reply streams.

*See [Integration guide › Streaming](../../../../README.md#streaming).*

### Scroll as the agent types — `MainActivity.kt`

Signals that trigger an auto-scroll:

```kotlin
messages.size                 // Int — grows when a new bubble (user / agent / system) arrives

messages.lastOrNull()?.text   // String? — grows in place while the last reply streams (size unchanged)
```

In a `@Composable`:

```kotlin
val listState = rememberLazyListState()

// Streaming grows the last bubble's text without changing messages.size.
LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
}

LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
    items(messages, key = { it.id }) { MessageRow(it) }
}
```

Streaming grows the last agent message's `text` in place — `messages.size` doesn't change, so keying the `LaunchedEffect` on `messages.size` alone isn't enough. Adding `messages.lastOrNull()?.text` as a key restarts the effect on every streamed chunk; `animateScrollToItem(messages.size - 1)` re-anchors the last bubble either way.

**Under the hood:** with `streamingEnabled = true` (the default), `ChatSession` extends the last `Agent` message's `text` on every chunk and re-emits `messages`. The `StateFlow` collection recomposes, the `LaunchedEffect` keys change, and the scroll tracks the reply as it grows.

*See [Integration guide › Streaming](../../../../README.md#streaming).*

### Send a message — `MainActivity.kt`

Send a user message (optimistic, suspending):

```kotlin
session.send(text)   // suspend fun; throws PolyError. The bubble appears in `messages`
                     // immediately as PENDING, then settles into SENT or FAILED.
```

In a `@Composable`:

```kotlin
val scope = rememberCoroutineScope()
val hasEnded by session.hasEnded.collectAsStateWithLifecycle()

Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
              placeholder = { Text("Message") })
    Button(
        onClick = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                input = ""
                scope.launch { runCatching { session.send(text) } }
            }
        },
        enabled = !hasEnded,
    ) { Text("Send") }
}
```

`send(text)` is a `suspend fun`, so it's launched on a coroutine scope; `runCatching { … }` swallows the throw — the optimistic bubble already reflects success/failure. Sending stays available even while offline or reconnecting — gate only on `hasEnded` (and empty text), **not** on connection readiness. A message typed before the socket is up is queued and delivered once it connects. `hasEnded` flips true after `session.end()`. (The Views counterpart makes the same choice.)

**Under the hood:** `send(text)` is optimistic — the bubble appears in `messages` immediately while the SDK manages delivery and the server echo behind the scenes.

*See [Integration guide › The core pattern](../../../../README.md#the-core-pattern-render-messages-yourself).*

### Keyboard & insets — `MainActivity.kt`

This screen owns its bottom inset instead of leaning on the framework, so the composer lifts above the gesture nav bar (keyboard closed) or above the keyboard (open):

```kotlin
Scaffold(
    topBar = { TopAppBar(title = { Text("Poly Hello") }) },   // framework ActionBar-style title
    contentWindowInsets = WindowInsets(0, 0, 0, 0),           // content manages the bottom inset itself
) { padding ->
    // ...
    Row(
        Modifier
            .fillMaxWidth()
            // union = max of the two, so closed-keyboard nav-bar and open-keyboard ime never double-count
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) { /* TextField + Send */ }
}
```

`WindowInsets.ime` follows the keyboard, `navigationBars` keeps the composer off the gesture bar, and `union` takes the larger of the two so the field never jumps.

*See [Integration guide › Avatars & keyboard](../../../../README.md#avatars--keyboard).*

## What this example skips

- typing indicator, connection banner, avatars, delivery dots, suggestions/quick replies, end button → 02-Standard (see [`../02-standard/`](../02-standard/))
- attachments, URL cards, call actions → 03-RichContent (see [`../03-richcontent/`](../03-richcontent/))
- offline detection, full-screen terminal error → 04-Resilience (see [`../04-resilience/`](../04-resilience/))
- live agent handoff → 05-Handoff (see [`../05-handoff/`](../05-handoff/))

It also doesn't surface a **terminal-error dialog** for a bad API key — `session.failureReason` (and `session.client.resume()` for retry) is wired up in [`../02-standard/`](../02-standard/). See [Terminal errors](../../../../README.md#terminal-errors) for the pattern.

---

- **Views counterpart:** [`../../views/01-hello/`](../../views/01-hello/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
