# 01-Hello (Views)

The smallest possible chat in Android Views (XML layouts + view binding) — initialize the SDK, render messages in a `RecyclerView`, send one. The classic-Views counterpart of [`examples/compose/01-hello/`](../../compose/01-hello/).

- **Interface:** XML layouts (`res/layout/activity_chat.xml`, `res/layout/item_message.xml`) + view binding
- **Lifecycle:** `Application.onCreate()` (`HelloApplication`, registered via `android:name` in `AndroidManifest.xml`) + a single launcher `Activity` (`ChatActivity`)

## Run it

Open the repo in Android Studio and run the module, or from the repo root:

```bash
./gradlew :examples:views:01-hello:installDebug
```

Then launch the installed app — `ChatActivity` is the `LAUNCHER` activity. Set your API key in `src/main/kotlin/ai/poly/examples/hello/views/HelloApplication.kt` (the committed default is `Environment.US` — add `.cluster("dev")` / a `hostIdentifier` only if your agent needs it).

## What this example demonstrates

- `PolyMessaging.initialize(context, Configuration(...))` in `HelloApplication.onCreate()`
- `PolyMessaging.chat()` for a session, bound to a `RecyclerView.Adapter` via a lifecycle-aware `StateFlow` collector
- Re-render on every `session.messages` emission
- Auto-scroll as the agent's reply streams in (`adapter.submit(...)` + `RecyclerView.scrollToPosition`)
- Send with `runCatching { session.send(text) }` inside `lifecycleScope.launch { }`
- A "Reconnecting…" banner driven by `session.connection`

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide); this example shows them as one concrete `Activity`.

## How it works

Each subsection leads with **the SDK call** (one line — the actual API), then shows **how it's wired into an Activity**.

### Initialize once at app launch — `HelloApplication.kt`

The SDK is initialized once in `Application.onCreate()` (registered with `android:name=".HelloApplication"` in `AndroidManifest.xml`):

```kotlin
class HelloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(
            this,
            Configuration(apiKey = "YOUR_API_KEY"),   // from Agent Studio → Connector Settings
            // environment defaults to Environment.US — add .cluster("dev") / a hostIdentifier only if needed
        )
    }
}
```

After this, `PolyMessaging.chat()` works from any `Activity` with no arguments.

**Under the hood:** `initialize` just stashes your API key and environment process-wide — no network happens yet. The work starts when you call `chat()`.

*See [Quick start](../../../../README.md#quick-start).*

### Get a session and render messages — `ChatActivity.kt`

Create a session + collect its messages:

```kotlin
// Resume the previous conversation if one exists within the session timeout (default ~10 min),
// else start a fresh one — use start() instead to always start fresh. Keep it as a stored
// property.
private val session: ChatSession by lazy { PolyMessaging.chat() }

session.messages    // StateFlow<List<ChatMessage>> — the whole transcript. Cases:
                    //   ChatMessage.User / ChatMessage.Agent / ChatMessage.System

session.isReady     // StateFlow<Boolean> — false until WebSocket + agent-join complete
```

In an `Activity`:

```kotlin
class ChatActivity : ComponentActivity() {

    private lateinit var binding: ActivityChatBinding
    private val session: ChatSession by lazy { PolyMessaging.chat() }
    private val adapter = MessageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter

        // Collect SDK state, lifecycle-aware.
        // repeatOnLifecycle(STARTED) re-subscribes on resume and tears down on stop.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.messages.collect { messages ->
                        adapter.submit(messages)
                        if (messages.isNotEmpty()) binding.list.scrollToPosition(messages.size - 1)
                    }
                }
                // ...connection collector (below)...
            }
        }
    }
}
```

`messages.collect { … }` subscribes to the transcript; `repeatOnLifecycle(Lifecycle.State.STARTED)` ties the subscription to the foreground lifecycle so it doesn't leak.

**Streaming is on by default** — `Configuration.streamingEnabled` defaults to `true`, so agent replies grow token-by-token (ChatGPT-style). The collector re-runs `adapter.submit(messages)` on every emission — including the ones where only the last agent bubble's text grew — so the cell re-renders as the message fills in. To switch to complete-message bubbles instead, set `streamingEnabled = false` on the `Configuration` you pass to `initialize` in `HelloApplication.kt`. See the root README's [Streaming](../../../../README.md#streaming) section.

**Under the hood:** `chat()` runs the whole REST + WebSocket handshake, agent-join, and resume-or-create for you; `isReady` flips true once it's connected. `messages` is the SDK-maintained transcript (`ChatMessage.User` / `.Agent` / `.System`) that re-emits on every change, so each `collect` hands you the full list to render. `ChatSession` is collected and mutated from the main thread.

*See [Integration guide › The core pattern](../../../../README.md#the-core-pattern-render-messages-yourself).*

### Branch over the message and style the bubble — `ChatActivity.kt`

The transcript is a sealed type — render it by exhaustively matching the case:

```kotlin
session.messages    // every emission re-binds the RecyclerView:
                    //   new bubble appended, or the last bubble's text grew during streaming
```

In the adapter, branch in `onBindViewHolder` over `ChatMessage` (no `else` — a future case fails to compile until you handle it):

```kotlin
fun bind(message: ChatMessage) {
    when (message) {
        is ChatMessage.User -> {
            b.bubble.text = message.message.text
            b.bubble.setBackgroundColor(Color.parseColor("#2563EB"))   // sent bubble, right-aligned
            b.bubble.setTextColor(Color.WHITE)
            // dim while the message is still in flight (Delivery.PENDING)
            b.bubble.alpha = if (message.message.delivery == Delivery.PENDING) 0.5f else 1f
            b.root.gravity = Gravity.END
        }
        is ChatMessage.Agent -> {
            b.bubble.text = message.message.text
            b.bubble.setBackgroundColor(Color.parseColor("#E5E7EB"))   // agent bubble, left-aligned
            b.bubble.setTextColor(Color.BLACK)
            b.root.gravity = Gravity.START
        }
        is ChatMessage.System -> {
            b.bubble.text = message.message.event.reason ?: "—"        // centered status text
            b.bubble.setBackgroundColor(Color.TRANSPARENT)
            b.bubble.setTextColor(Color.GRAY)
            b.root.gravity = Gravity.CENTER
        }
    }
}
```

Each cell is `item_message.xml` — a `LinearLayout` wrapping a single `bubble` `TextView`; the holder flips `root.gravity` to lay the bubble out left (agent), right (user), or center (system). To keep this rung minimal the colors are inlined hex literals (`#2563EB` blue, `#E5E7EB` gray) rather than going through the module's `Palette.kt` bridge that the later rungs use for the system palette.

`MessageAdapter.submit(newItems)` swaps the backing list and calls `notifyDataSetChanged()` — the simplest possible re-render. (The later rungs keep this same full-refresh adapter; swapping in `DiffUtil` / per-item updates is left as a production optimization.)

**Under the hood:** with `streamingEnabled = true` (the default), `ChatSession` extends the last `ChatMessage.Agent` message's `text` on every chunk and re-emits `messages`. The collector calls `adapter.submit(messages)` and then `scrollToPosition(messages.size - 1)`, so the list tracks the reply as it grows.

*See [Integration guide › Streaming](../../../../README.md#streaming).*

### Send a message — `ChatActivity.kt`

Send a user message (optimistic):

```kotlin
session.send(text)   // suspend fun; throws PolyError. The bubble appears in `messages`
                     // immediately as Delivery.PENDING, then settles into SENT or FAILED.
```

In an `Activity`, wire the Send button:

```kotlin
binding.send.setOnClickListener {
    val text = binding.composer.text.toString().trim()
    if (text.isNotEmpty()) {
        binding.composer.setText("")
        lifecycleScope.launch { runCatching { session.send(text) } }
    }
}
```

`runCatching { session.send(text) }` inside `lifecycleScope.launch { }` runs `send` on a coroutine and swallows the throw — `send` is a `suspend` function (the bubble's `Delivery` state reflects success/failure either way).

**Under the hood:** `send(text)` is optimistic — the bubble appears in `messages` immediately while the SDK manages delivery and the server echo behind the scenes. Collect and call the session from the main thread.

*See [Integration guide › The core pattern](../../../../README.md#the-core-pattern-render-messages-yourself).*

### Show a "Reconnecting…" banner — `ChatActivity.kt`

The one connection signal worth surfacing even in the minimal rung:

```kotlin
session.connection   // StateFlow<ConnectionStatus> — Connecting / Open / Reconnecting(attempt) / Failed(reason) / …
                     // Show a banner only while it's ConnectionStatus.Reconnecting.
```

In an `Activity`, alongside the `messages` collector:

```kotlin
launch {
    session.connection.collect { status ->
        binding.banner.visibility =
            if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
    }
}
```

`banner` is a yellow `TextView` at the top of `activity_chat.xml` (`#FFF3CD`, "Reconnecting…", `visibility="gone"` by default). A socket drop goes `Open → Reconnecting(n) → Open`, so the banner shows and clears without a `Closed` flash.

**Under the hood:** `connection` tracks the *socket*, not whether the *phone* lost Wi-Fi. The SDK reconnects with backoff + jitter and resumes the session; you only render the state.

*See [Integration guide › Connection & reconnect](../../../../README.md#connection--reconnect).*

## Layout & insets note

`AndroidManifest.xml` sets `android:windowSoftInputMode="adjustResize"` on `ChatActivity` so the composer rides above the keyboard. Because the app targets `targetSdk = 36` and draws edge-to-edge, `ChatActivity` also installs a `ViewCompat.setOnApplyWindowInsetsListener` on the root view that pads the bottom by `max(navigationBars, ime)`, keeping the composer above the gesture nav bar when closed and the keyboard when open.

## What this example skips

- typing indicator, delivery-state retry button, suggestions, end button → [`02-Standard/`](../../views/02-standard/)
- attachments, URL cards, call actions → [`03-richcontent/`](../03-richcontent/)
- offline detection, full-screen terminal error → [`04-resilience/`](../04-resilience/)
- live agent handoff → [`05-handoff/`](../05-handoff/)

> Note: this minimal rung does **not** surface `session.failureReason` (terminal errors like an invalid API key) — there's no error dialog. Adding a retry surface driven by `session.failureReason` + `session.client.resume()` is covered in the root README's [Terminal errors](../../../../README.md#terminal-errors) section and the [`04-resilience/`](../04-resilience/) rung.

---

- **Compose counterpart:** [`examples/compose/01-hello/`](../../compose/01-hello/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
