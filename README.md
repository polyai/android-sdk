# PolyMessaging Android SDK

[![CI](https://github.com/polyai/android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/polyai/android-sdk/actions/workflows/ci.yml)
![API](https://img.shields.io/badge/API-24%2B-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2%2B-blueviolet)
![Maven Central](https://img.shields.io/badge/Maven%20Central-ai.poly%3Amessaging-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
[![Develop with Claude Code](https://img.shields.io/badge/Develop%20with-Claude%20Code-d97757)](https://claude.ai/download)

Add AI-powered **chat** and **voice** to your Android app. The SDK is **headless** — it handles token
auth, the WebSocket, streaming, reconnection, delivery tracking, and live-agent handoff. You bring the UI.

- **[Quick start](#quick-start)** — paste an `Activity`/`@Composable` and you have chat.
- **[Integration guide](#integration-guide)** — observe one object, `ChatSession`.
- **[Voice calling](#voice-calling-aipolyvoice)** — live WebRTC voice calls with `ai.poly:voice`.

Reference: [Configuration](#configuration) · [Error handling](#error-handling) · [How it works](#how-it-works) · [Raw transport](#advanced-raw-transport) · [Example apps](#example-apps).

> **Kotlin-first, Java-compatible.** Every async call has a Kotlin `suspend`/`Flow` form **and** a
> Java-friendly callback/listener form. Nothing core is Kotlin-only.

## Features

| | Feature | Description |
|---|---|---|
| 💬 | Messaging | WebSocket transport, typed Kotlin events (sealed classes) |
| ⚡ | Streaming | Real-time chunks, rendered token-by-token |
| 🔄 | Reconnection | Backoff + jitter, session resume, drops dead sockets when the OS reports offline |
| 🔐 | Auth | Token acquisition, session lifecycle, fully managed |
| 👤 | Live agent | Handoff, queue status, typing |
| 💡 | Suggestions | Quick-reply pills |
| 📎 | Attachments | Images, link cards, CTA phone buttons |
| 📡 | Delivery tracking | Optimistic → confirmed → failed, per message |
| 🔧 | Escape hatch | Raw WebSocket transport |
| 📞 | Voice calling | Live two-way WebRTC calls + audio-output routing (`ai.poly:voice`) |

## Install

The SDK is published to **Maven Central** as [`ai.poly:messaging`](https://central.sonatype.com/artifact/ai.poly/messaging). Ensure `mavenCentral()` is in your repositories (it's there by default in new Android projects):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Then declare the dependency — the three forms below are equivalent (same artifact, different Gradle syntax), so use whichever your project prefers.

### Gradle — Kotlin DSL (recommended)

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.poly:messaging:0.9.0")
}
```

### Gradle — Groovy DSL

```groovy
// build.gradle
dependencies {
    implementation 'ai.poly:messaging:0.9.0'
}
```

### Version catalog (`libs.versions.toml`)

```toml
[versions]
polyMessaging = "0.9.0"

[libraries]
poly-messaging = { module = "ai.poly:messaging", version.ref = "polyMessaging" }
```

```kotlin
dependencies { implementation(libs.poly.messaging) }
```

> Your app's package name (`applicationId`) is sent as the `X-Host` header — it must match the host
> registered in Agent Studio for your API key.

---

# Quick start

The smallest thing that works: initialize once, create a `ChatSession`, render `messages`, call `send`. Set your `apiKey`, run, and you have chat — no helper files to copy. The only import is `ai.poly.messaging.*`.

The SDK is initialized once in `Application.onCreate()` (registered via `android:name` in your manifest). No network happens at init — `chat()` / `start()` does the work later. `chat()` returns a `ChatSession`; you render `session.messages` and call `session.send(...)`.

### Jetpack Compose

```kotlin
// HelloApplication.kt — initialize once, in Application.onCreate.
// Register it with android:name=".HelloApplication" in AndroidManifest.xml.
import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyMessaging
import android.app.Application

class HelloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(
            this,
            Configuration(apiKey = "YOUR_API_KEY"),   // Agent Studio → Connector Settings
        )
    }
}
```

> **Where to initialize.** A fresh *Empty Activity* project has no `Application` class — create
> `HelloApplication` as above and register it with `android:name=".HelloApplication"` in
> `AndroidManifest.xml`. For a quick test you can instead call `PolyMessaging.initialize(...)` at the
> top of `MainActivity.onCreate()` (simplest; it re-runs on activity recreation, so prefer
> `Application.onCreate()` for real apps).

> **Quick-start dependencies.** Beyond the SDK itself (`implementation("ai.poly:messaging:0.9.0")`), the
> only line a Compose app must add for the snippet below is **lifecycle-runtime-compose** (for
> `collectAsStateWithLifecycle`):
>
> ```kotlin
> implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
> ```
>
> Everything else the snippet uses already comes with a standard Compose *Empty Activity* project
> (Material 3, `androidx.activity:activity-compose`, the Compose UI + `foundation` for `LazyColumn`) or
> transitively from the SDK (Kotlin coroutines — `launch`/`rememberCoroutineScope`). Nothing else to add.

```kotlin
// MainActivity.kt
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.PolyMessaging
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { ChatScreen() } } }
    }
}

@Composable
fun ChatScreen() {
    // One ChatSession per chat surface. chat() resumes a recent conversation or starts fresh.
    val session: ChatSession = remember { PolyMessaging.chat() }
    // collectAsStateWithLifecycle subscribes to the session's StateFlow, lifecycle-aware.
    val messages by session.messages.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f)) {
            items(messages, key = { it.id }) { message ->
                // Tell sides apart: your messages align right, the agent's left.
                val mine = message is ChatMessage.User
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Text(
                        message.text ?: "",
                        Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart),
                    )
                }
            }
        }
        Row(Modifier.padding(8.dp)) {
            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f))
            Button(onClick = {
                val body = input.trim()
                if (body.isNotEmpty()) {
                    input = ""
                    scope.launch { runCatching { session.send(body) } }
                }
            }) { Text("Send") }
        }
    }
}
```

> **Data:** `session.messages` is a `StateFlow<List<ChatMessage>>` — a sealed type (`ChatMessage.User` / `.Agent` / `.System`). The base class exposes `text`, `id`, `timestamp`, `delivery`, `attachments`, and `suggestions`, so you can read `message.text` directly off any message — here we branch only on `is ChatMessage.User` to align your messages right and the agent's left so they're easy to tell apart. Insets are handled with `imePadding()` (Compose) so the composer lifts above the keyboard.

### Android Views (XML)

```kotlin
// HelloApplication.kt — same init point as Compose; initialize once in Application.onCreate.
// Register it via android:name in AndroidManifest.xml.
import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyMessaging
import android.app.Application

class HelloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the SDK once at launch. No network happens here — chat() / start() does the work later.
        PolyMessaging.initialize(
            this,
            Configuration(apiKey = "YOUR_API_KEY"),   // Agent Studio → Connector Settings
        )
    }
}
```

```xml
<!-- AndroidManifest.xml — register the Application and set the launcher Activity.
     windowSoftInputMode="adjustResize" lets the composer ride above the keyboard. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".HelloApplication"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"> <!-- or your app theme -->
        <activity
            android:name=".ChatActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

```xml
<!-- res/layout/activity_chat.xml — a list, a composer field, and a Send button. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical">
    <androidx.recyclerview.widget.RecyclerView android:id="@+id/list"
        android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"
        android:padding="8dp" android:clipToPadding="false" />
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
        android:orientation="horizontal" android:padding="8dp" android:gravity="center_vertical">
        <EditText android:id="@+id/composer" android:layout_width="0dp" android:layout_height="wrap_content"
            android:layout_weight="1" android:hint="Message" android:inputType="textShortMessage|textMultiLine" />
        <Button android:id="@+id/send" android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="Send" />
    </LinearLayout>
</LinearLayout>
```

> Enable ViewBinding in your module — `android { buildFeatures { viewBinding = true } }` — the
> `ActivityChatBinding` class below is generated from `activity_chat.xml` under **your** app's namespace.
> The snippet also uses RecyclerView, lifecycle-aware collection, and the inset helpers — a few extra
> Gradle lines if your project doesn't already have them:
> `implementation("androidx.recyclerview:recyclerview:1.3.2")`,
> `implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")`,
> `implementation("androidx.core:core-ktx:1.13.1")`, and
> `implementation("androidx.activity:activity-ktx:1.9.3")` (for `ComponentActivity`).

```kotlin
// ChatActivity.kt
import com.example.yourapp.databinding.ActivityChatBinding // generated — package follows your namespace
import ai.poly.messaging.ChatMessage
import ai.poly.messaging.ChatSession
import ai.poly.messaging.PolyMessaging
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ChatActivity : ComponentActivity() {

    private lateinit var binding: ActivityChatBinding
    // One ChatSession per surface — keep it as a stored property.
    private val session: ChatSession by lazy { PolyMessaging.chat() }
    private val adapter = MessageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep the composer above the gesture nav bar (closed) / keyboard (open) — the
        // Android "safe area" equivalent. The app draws edge-to-edge under targetSdk 35+.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            v.updatePadding(bottom = bottom)
            insets
        }

        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter

        binding.send.setOnClickListener {
            val body = binding.composer.text.toString().trim()
            if (body.isNotEmpty()) {
                binding.composer.setText("")
                lifecycleScope.launch { runCatching { session.send(body) } }
            }
        }

        // Collect SDK state, lifecycle-aware.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.messages.collect { messages ->
                    adapter.submit(messages)
                    if (messages.isNotEmpty()) binding.list.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
        private val items = mutableListOf<ChatMessage>()

        fun submit(newItems: List<ChatMessage>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(24, 16, 24, 16)
            }
            return Holder(tv)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = items[position].text ?: ""
        }

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
```

> **Java?** Use the callback + listener bridges: `session.send(body, executor, callback)` and
> `session.addListener(executor, sessionListener)`. `PolyMessaging.chat()` is a plain static call. Callbacks arrive on the `Executor` you pass (use `ContextCompat.getMainExecutor(context)` to receive them on the main thread).

**Streaming is on by default** — agent replies grow token-by-token (ChatGPT-style). To switch to complete-message bubbles instead, set `streamingEnabled = false` on the `Configuration` you pass to `initialize`:

```kotlin
PolyMessaging.initialize(
    this,
    Configuration(apiKey = "YOUR_API_KEY", streamingEnabled = false),   // off → completed bubbles only
)
```

Full details in [Streaming](#streaming).

> **`chat()` vs `start()`** — `chat()` resumes the previous conversation if one exists (within the ~10-minute server WebSocket idle window), else starts fresh; `start()` always starts fresh. `PolyMessaging.hasResumableSession()` tells you which to offer.
> **Lifecycle:** initialize once at app launch; keep one `ChatSession` per chat surface (a `remember { }` in Compose, a stored property in Views); call `session.close()` when the surface is dismissed to release its observers, and `session.client.shutdown()` when you're done with the client entirely.

*Example app: [`examples/chat/compose/01-hello`](examples/chat/compose/01-hello) and [`examples/chat/views/01-hello`](examples/chat/views/01-hello) are this quick start, runnable as-is. The full ladder — 01-Hello, 02-Standard, 03-RichContent, 04-Resilience, 05-Handoff, 06-FullReference, 07-Playground — is available across both UI toolkits; see [Example apps](#example-apps).*

---

# Integration guide

The SDK is headless: it gives you one observable object — **`ChatSession`** — and your UI is *whatever you build by observing its state.*

## Meet `ChatSession`

`PolyMessaging.chat()` (or `start()`) returns a `ChatSession` — the single object your UI binds to. It assembles streaming, tracks delivery, manages typing, dedups resumes, and surfaces handoff — so your UI only ever reads state and calls methods. State is exposed as `StateFlow`s: Compose collects them with `collectAsStateWithLifecycle()`; Views collect inside `repeatOnLifecycle(Lifecycle.State.STARTED)`. Read and mutate the session from the main thread.

**Data:** every property below is a `StateFlow` (`collectAsStateWithLifecycle()` in Compose, `.collect { … }` in Views). The same getter is also available synchronously (e.g. `session.isReady()` returns `Boolean`) for one-shot reads off the flow.

**State you observe** (read-only):

| Property | Type | What it tells you |
|---|---|---|
| `messages` | `StateFlow<List<ChatMessage>>` | every message in the conversation, in order — `ChatMessage` is a sealed type whose cases are `ChatMessage.User(UserMessage)` (what you sent), `ChatMessage.Agent(AgentMessage)` (what the bot or live human sent back), and `ChatMessage.System(SystemMessage)` (events like "agent joined" or "conversation ended"). Iterate it to render the chat. |
| `isReady` | `StateFlow<Boolean>` | connected and ready to send |
| `connection` | `StateFlow<ConnectionStatus>` | socket state — `Connecting` / `Open` / `Reconnecting(attempt)` / `Failed(reason)` / … |
| `isAgentTyping` | `StateFlow<Boolean>` | show the typing indicator |
| `agentAvatarUrl` | `StateFlow<URI?>` | latest agent / live-agent avatar |
| `hasStarted` | `StateFlow<Boolean>` | the conversation has begun |
| `hasEnded` | `StateFlow<Boolean>` | conversation is over — swap the composer for a "start new" CTA |
| `failureReason` | `StateFlow<PolyError?>` | non-null once the chat hits a terminal failure it can't auto-recover from — invalid API key, reconnect budget exhausted, session expired |

**Methods you call:**

| Member | What it does |
|---|---|
| `suspend send(text)` | send a user message (optimistic — appears immediately as `Delivery.PENDING`) |
| `suspend sendTyping()` | tell the agent you're typing (safe every keystroke; throttled) |
| `suspend end()` | end the conversation |
| `removeMessage(draftId)` | drop a failed draft (call before re-sending on retry) |
| `clearSuggestions(messageId)` | clear one message's quick-reply pills (takes the message's `UUID` `id`) |
| `clearChat()` | wipe the transcript (e.g. before `startNewSession()`) |
| `userMessages` / `agentMessages` / `systemMessages` / `lastAgentMessage` | filtered views of `messages` |
| `close()` | tear down **this** session's observers (cancels its collector scope). Call when the chat surface is dismissed; `client.shutdown()` (below) tears down the whole client |
| `client` | the underlying `PolyMessagingClient` — `events`, `startNewSession()`, `resume()`, `shutdown()`, `getConnection()` |

Every `suspend` method also has a `Callback`/`Executor` overload (`send(text, executor, callback)`, `end(executor, callback)`, …) for Java callers or non-coroutine code; the Kotlin examples below use the `suspend` form from a coroutine scope.

## Starting, resuming & ending a session

`chat()` and `start()` both return a `ChatSession`; the difference is whether they reuse the last conversation:

- **`chat()`** — resume the stored session if it's still valid (within the session timeout, ~10 minutes — matches the backend's WebSocket idle timeout), else start fresh. **This is the default** — conversations survive an app relaunch (the resumable session is persisted in `SharedPreferences`).
- **`start()`** — always discard any stored session and begin a new one. Use it for an explicit "New chat" entry point.

Before showing the chat, you can offer the choice:

```kotlin
val session = if (PolyMessaging.hasResumableSession()) {
    // offer "Resume previous chat?" → PolyMessaging.chat()
    PolyMessaging.chat()
} else {
    PolyMessaging.start()
}
```

Then observe `hasStarted` / `hasEnded` and use these methods on the live session:

| Call | When |
|---|---|
| `session.send(text)` | send a message |
| `session.end()` | end the conversation (flips `hasEnded`) |
| `session.clearChat()` | wipe the on-screen transcript immediately |
| `session.client.startNewSession()` | end the current chat and begin a fresh one **in place** (same `ChatSession` / client) |
| `session.client.resume()` | await the initial `start()`, rethrowing a latched start failure; then re-establish the socket if it has terminally failed (reconnect ladder exhausted). Idempotent — a no-op when the connection is already healthy. For a user-facing "Try Again" after a terminal `ConnectionStatus.Failed`, prefer `startNewSession()` (or a fresh `chat()`/`start()`) so the user gets a clean conversation (see [Connection & reconnect](#connection--reconnect)) |

A user-initiated `end()` flips `hasEnded` with no "conversation ended" pill; an agent- or server-initiated end shows the pill (it arrives as a `ChatMessage.System` message). For a "Start New Chat" button, call `clearChat()` then `startNewSession()`.

> **Lifecycle & cleanup:** call `PolyMessaging.initialize(context, configuration)` once at launch (typically in your `Application.onCreate()`); keep **one** `ChatSession` per chat surface (`remember { … }` in Compose, a `by lazy` / stored property on the Activity/ViewModel in Views) — each new session is a fresh REST handshake; call `session.close()` when the surface is dismissed to cancel that session's observer scope; and call `session.client.shutdown()` when you're tearing down the client for good (idempotent — cancels heartbeat, reconnect, and retry tasks). Collect and mutate `ChatSession` from the main thread.

## The core pattern: render `messages` yourself

`messages` is `List<ChatMessage>`, where `ChatMessage` is a sealed type — `ChatMessage.User(UserMessage)`, `ChatMessage.Agent(AgentMessage)`, `ChatMessage.System(SystemMessage)`, each carrying a stable `id: UUID`. **Every chat UI is the same shape: iterate `messages` and `when` over the case.** This one pattern renders *everything* — text, agent vs user, system pills, handoff, live agents. Because the `when` is exhaustive over a sealed class (no `else`), a future case fails to compile until you handle it.

```kotlin
// Compose — drop this inside your @Composable screen.
// Re-renders automatically whenever the SDK updates `messages` (new message,
// delivery update, streaming text growth) because you collect it with lifecycle.
val messages by session.messages.collectAsStateWithLifecycle()

LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    items(messages, key = { it.id }) { message ->
        when (message) {
            is ChatMessage.User -> {
                val m = message.message                            // your sent bubble
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        m.text,
                        Modifier
                            .background(
                                Color(0xFF2563EB).copy(alpha = if (m.delivery == Delivery.PENDING) 0.5f else 1f),
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color.White,
                    )
                }
            }
            is ChatMessage.Agent -> {
                val m = message.message                            // agent bubble; tint live humans
                Text(
                    m.text,
                    Modifier
                        .background(
                            if (m.agentKind == AgentKind.LIVE) Color(0x2E26A69A) else Color(0xFFE5E7EB),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            is ChatMessage.System -> {
                Text(
                    systemLabel(message.message.event),            // centered status pill
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
```

```kotlin
// Views — a RecyclerView.Adapter, re-bound whenever the SDK updates the transcript.
// Collect `messages` once and hand the new list to the adapter.
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        session.messages.collect { messages ->
            adapter.submit(messages)   // adapter holds the List<ChatMessage>; notify it changed
        }
    }
}

// Adapter — branch in onBindViewHolder over the sealed ChatMessage.
override fun getItemCount(): Int = items.size

override fun onBindViewHolder(holder: Holder, position: Int) {
    when (val message = items[position]) {
        is ChatMessage.User -> holder.bindUser(message.message)     // text + delivery
        is ChatMessage.Agent -> holder.bindAgent(message.message)   // text + agentKind/avatar
        is ChatMessage.System -> holder.bindSystem(systemLabel(message.message.event))
    }
}
```

> **`text` nullability:** the unwrapped values above — `UserMessage.text` / `AgentMessage.text` — are non-null `String`. The top-level convenience `ChatMessage.text` (handy if you don't `when`) is `String?`, so use `?: ""` when reading it directly.

**What each case carries** (the fields you render):

- **`UserMessage`** — `text`, `delivery` (`Delivery.PENDING` / `SENT` / `FAILED`), `draftId`.
- **`AgentMessage`** — `text` (Markdown — render with Markwon or `HtmlCompat`), `agentKind` (`AgentKind.POLY` / `LIVE`), `agentName`, `avatarUrl` (`URI`, load with Coil), `attachments`, `suggestions`, `callActions`.
- **`SystemMessage`** — `event: SystemEvent` (handoff steps, queue status, conversation-ended, …).

`SystemEvent` is what your `systemLabel(…)` switches on (also a sealed class — exhaustive `when`, no `else`):

```kotlin
// A free helper — paste alongside your @Composable / Activity / Adapter.
// Used by both snippets above to label `ChatMessage.System` cases.
fun systemLabel(event: SystemEvent): String = when (event) {
    is SystemEvent.HandoffStarted ->   "Transferring you to an agent…"
    is SystemEvent.HandoffRequired ->  "Agent connection issue: ${event.reasonText}"
    is SystemEvent.QueueStatus ->      event.displayMessage
        ?: event.position?.let { "You're #$it in line" }
        ?: "Waiting…"
    is SystemEvent.HandoffAccepted ->  "An agent will be with you shortly"
    is SystemEvent.LiveAgentJoined ->  "${event.name ?: "An agent"} joined"
    is SystemEvent.LiveAgentLeft,
    is SystemEvent.AgentLeft,
    is SystemEvent.ConversationEnded -> "Conversation ended"
    is SystemEvent.HandoffFailed ->    "Transfer failed: ${event.reasonText ?: "unknown"}"
    is SystemEvent.HandoffTimeout ->   "No agents available right now"
    is SystemEvent.IdleWarning ->      "Connection idle — you may be disconnected soon"
    is SystemEvent.ServerMessage ->    event.text
    // No `else` — `SystemEvent` is fully covered above; future cases
    // will surface as a compiler error so you remember to add them.
}
```

That's the foundation. The rest of this section is just *which field or case* each feature uses.

*Example app:* the full render loop is the heart of [`01-Hello`](examples/chat/compose/01-hello) (Compose `MessageRow`) and its Views twin [`01-Hello`](examples/chat/views/01-hello) — both runnable. [`02-Standard`](examples/chat/compose/02-standard) / [`02-Standard`](examples/chat/views/02-standard) (also runnable) layer delivery state, suggestions, typing, and the system pills on top of the same `when`.

## Adding each feature

Each section: **Data** → Compose → Views → *Example app*. Compose blocks read SDK state with
`collectAsStateWithLifecycle()`; Views blocks collect inside `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { … } }`. Both keep one `val session = PolyMessaging.chat()` and reuse the `when (message)` render from [the core pattern](#the-core-pattern-render-messages-yourself).

### Streaming
The agent's reply arrives as a sequence of chunks. `ChatSession` reassembles them for you and updates `messages` — you never touch chunks directly. You only choose **how a reply appears**, with **one** switch.

**`Configuration.streamingEnabled`** (default `true`) is the single knob — set it once at `initialize(...)` and you're done:

- **`streamingEnabled = true`** (default) → the bubble appears immediately and **grows token-by-token** as chunks land (ChatGPT-style), then settles into the final, fully-formatted message.
- **`streamingEnabled = false`** → the server sends complete messages only; the bubble appears whole when ready. While the agent thinks, `isAgentTyping` is `true` — show the typing dots.

**Data:** `Configuration.streamingEnabled` (default true). True = token-by-token; false = complete bubbles (use `isAgentTyping` for progress). Override per surface: `PolyMessaging.chat(streamingEnabled = false)`.

> **Timing:** while a reply streams, its `suggestions` and `attachments` ride the **completing** chunk — they appear on the message when the stream finishes, a beat *after* its text first shows up in `messages`. Bind to the flow (as all the snippets here do) and they fill in on their own; just don't snapshot a streaming message on first sight of its text and expect pills or images to be there yet.

```kotlin
// Compose — set the knob once at app launch (Application.onCreate).
PolyMessaging.initialize(
    context = this,
    config = Configuration(
        apiKey = "YOUR_API_KEY",
        streamingEnabled = true,        // default — set false for complete messages only
    ),
)

// Then anywhere in your app — no extra args needed.
val session = remember { PolyMessaging.chat() }
```
```kotlin
// Views — same knob, via the Configuration.Builder (also valid in Compose).
PolyMessaging.initialize(
    context = this,
    config = Configuration.Builder("YOUR_API_KEY")
        .streamingEnabled(true)         // default — set false for complete messages only
        .build(),
)

// Then in your Activity/Fragment.
private val session: ChatSession by lazy { PolyMessaging.chat() }
```

**Need to override for one surface?** `chat()` / `start()` accept an optional `streamingEnabled` argument. Pass it only if you want this session to differ from the config default; otherwise leave it off.

```kotlin
val alt = PolyMessaging.chat(streamingEnabled = false)   // this surface only
```

Either way, your render code — the `when` over `messages` from [the core pattern](#the-core-pattern-render-messages-yourself) — doesn't change.

*Example app:* [01-Hello (Compose)](examples/chat/compose/01-hello/) · [01-Hello (Views)](examples/chat/views/01-hello/) — both stream agent replies by default (just `PolyMessaging.chat()` with the default config). For a live toggle to compare with `streamingEnabled = false` side by side, see [07-Playground](examples/chat/compose/07-playground/).

### Connection & reconnect
**Data:** `session.connection` — show a banner only while `ConnectionStatus.Reconnecting` (drops go `Open → Reconnecting(n) → Open`, no `Closed` flash). `session.failureReason` is terminal — recover with `session.client.startNewSession()` (or a fresh `chat()`/`start()`); `resume()` does **not** reconnect. Use `isConnected` / `isReconnecting` / `isFailed` (full list under [Connection states](#connection-states)).

```kotlin
// Compose
@Composable
fun ChatScreen() {
    val session = remember { PolyMessaging.chat() }
    val connection by session.connection.collectAsStateWithLifecycle()
    val failureReason by session.failureReason.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        if (connection is ConnectionStatus.Reconnecting) {
            Text(
                "Reconnecting…",
                Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(6.dp),
                textAlign = TextAlign.Center,
            )
        }
        if (failureReason != null) {
            // Terminal failure — start a fresh session in place (resume() doesn't reconnect).
            TextButton(onClick = { scope.launch { runCatching { session.client.startNewSession() } } }) {
                Text("Try again")
            }
        }

        // ...your existing UI (message list, composer, etc.)...
    }
}
```
```kotlin
// Views
private val session: ChatSession by lazy { PolyMessaging.chat() }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...your existing setup (inflate the banner + retry button, etc.)...

    binding.retry.setOnClickListener {
        // Terminal failure — start a fresh session in place (resume() doesn't reconnect).
        lifecycleScope.launch { runCatching { session.client.startNewSession() } }
    }

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                session.connection.collect { status ->
                    binding.banner.visibility =
                        if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
                }
            }
            launch {
                session.failureReason.collect { reason ->
                    binding.retry.visibility = if (reason != null) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
```
*Example app:* [02-Standard (Compose)](examples/chat/compose/02-standard/) · [02-Standard (Views)](examples/chat/views/02-standard/) · [04-Resilience](examples/chat/compose/04-resilience/).

**Device offline is a separate signal.** `session.connection` tracks the *socket*, not whether the *phone* lost Wi-Fi. For that, register a `ConnectivityManager.NetworkCallback` and show a distinct "You're offline" bar — the two can stack: offline (device) on top, reconnecting (socket) below. See 04-Resilience.

### Terminal errors
**Data:** `session.failureReason` (a `PolyError?`; non-null whenever the chat hits a terminal failure it can't auto-recover from — an invalid `apiKey` rejected at the initial connect, the reconnect budget exhausted (`PolyError.Transport.NetworkError`), or the session expiring (`PolyError.Session.SessionExpired`). The one state that needs the user). `PolyError` isn't a localized resource, so render `failureReason.debugDescription`, not a `localizedMessage`.

```kotlin
// Compose
@Composable
fun ChatScreen() {
    val session = remember { PolyMessaging.chat() }
    val failureReason by session.failureReason.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        // ...your existing chat UI...

        failureReason?.let { reason ->
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Connection lost", fontWeight = FontWeight.SemiBold)
                // PolyError isn't a friendly localized error; use its debug description.
                Text(reason.debugDescription, color = Color.Gray, textAlign = TextAlign.Center)
                // Terminal failure — start a fresh session in place (resume() doesn't reconnect).
                Button(onClick = { scope.launch { runCatching { session.client.startNewSession() } } }) {
                    Text("Try again")
                }
            }
        }
    }
}
```
```kotlin
// Views — a full-screen overlay (binding.failureOverlay) on top of the chat.
private val session: ChatSession by lazy { PolyMessaging.chat() }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...your existing setup (inflate failureOverlay with failureLabel + retry, etc.)...

    binding.retry.setOnClickListener {
        // Terminal failure — start a fresh session in place (resume() doesn't reconnect).
        lifecycleScope.launch { runCatching { session.client.startNewSession() } }
    }

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.failureReason.collect { reason ->
                binding.failureOverlay.visibility = if (reason != null) View.VISIBLE else View.GONE
                binding.failureLabel.text = reason?.debugDescription ?: ""
            }
        }
    }
}
```
*Example app:* [04-Resilience (Compose)](examples/chat/compose/04-resilience/) · [04-Resilience (Views)](examples/chat/views/04-resilience/) (full-screen terminal-error screen) · [06-FullReference (Compose)](examples/chat/compose/06-fullreference/) · [06-FullReference (Views)](examples/chat/views/06-fullreference/) (in a screen state machine).

### Loading & empty states
**Data:** `isReady` (false until connected) + `messages.isEmpty()`. Show a spinner until the first messages arrive, then swap to the transcript.

```kotlin
// Compose
@Composable
fun ChatScreen() {
    val session = remember { PolyMessaging.chat() }
    val isReady by session.isReady.collectAsStateWithLifecycle()
    val messages by session.messages.collectAsStateWithLifecycle()

    if (!isReady && messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        // ...your existing chat UI (message list, composer, etc.)...
    }
}
```
```kotlin
// Views — re-check the loading state whenever either signal changes:
// combine the two StateFlows and toggle the spinner / list.
private val session: ChatSession by lazy { PolyMessaging.chat() }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...your existing setup (inflate the spinner + RecyclerView, etc.)...

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(session.isReady, session.messages) { ready, messages ->
                !ready && messages.isEmpty()
            }.collect { showSpinner ->
                binding.spinner.visibility = if (showSpinner) View.VISIBLE else View.GONE
                binding.list.visibility = if (showSpinner) View.GONE else View.VISIBLE
            }
        }
    }
}
```
*Example app:* [04-Resilience (Compose)](examples/chat/compose/04-resilience/) · [04-Resilience (Views)](examples/chat/views/04-resilience/).

### Delivery state & retry
**Data:** `UserMessage.delivery` is a `Delivery` enum (`PENDING` → `SENT` → `FAILED`). Restyle the bubble per state; on `FAILED`, drop the draft with `removeMessage(draftId)` then re-`send` so you don't duplicate. Tip: delay the "Sending…" label ~500 ms so fast confirmations don't flash it.

> **What the SDK already does for you:** an unconfirmed send is retried automatically every 3 s, up to 3 times, and each retry waits out a mid-reconnect socket (up to 15 s) before transmitting — so `FAILED` only lands after the ladder is exhausted (~12 s fully offline). Your retry button is for *after* that.

```kotlin
// Compose — inside the items {} of your message list (see the core pattern);
// your screen defines val scope = rememberCoroutineScope().
items(messages, key = { it.id }) { message ->
    when (message) {
        is ChatMessage.User -> {
            val m = message.message
            val failed = m.delivery == Delivery.FAILED
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    m.text,
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (failed) Color.Red.copy(alpha = 0.15f) else Color(0xFF2563EB))
                        .padding(10.dp),
                    color = if (failed) Color.Black else Color.White,
                )
                when (m.delivery) {
                    Delivery.FAILED -> TextButton(onClick = {
                        // Drop the draft, then re-send so the transcript doesn't duplicate.
                        session.removeMessage(m.draftId)
                        scope.launch { runCatching { session.send(m.text) } }
                    }) { Text("Tap to retry") }
                    Delivery.PENDING -> Text("Sending…", fontSize = 11.sp, color = Color.Gray)
                    Delivery.SENT -> Unit
                }
            }
        }

        // ...other cases (Agent, System) — see the core pattern...
        is ChatMessage.Agent -> AgentBubble(message.message)
        is ChatMessage.System -> SystemPill(message.message)
    }
}
```
```kotlin
// Views — restyle per delivery state in onBindViewHolder; the retry callback is hoisted to the Activity.
override fun onBindViewHolder(holder: Holder, position: Int) {
    val message = getItem(position)
    holder.binding.delivery.visibility = View.GONE
    if (message is ChatMessage.User) {
        val m = message.message
        holder.binding.bubble.text = m.text
        when (m.delivery) {
            Delivery.PENDING -> {
                holder.binding.delivery.text = "Sending…"
                holder.binding.delivery.visibility = View.VISIBLE
            }
            Delivery.SENT -> holder.binding.delivery.visibility = View.GONE
            Delivery.FAILED -> {
                holder.binding.delivery.text = "Tap to retry"
                holder.binding.delivery.visibility = View.VISIBLE
                holder.binding.retry.setOnClickListener { onRetry(m) }   // onRetry: (UserMessage) -> Unit
            }
        }
    }
}

// In the Activity — drop the draft, then re-send.
private fun retry(message: UserMessage) {
    session.removeMessage(message.draftId)
    lifecycleScope.launch { runCatching { session.send(message.text) } }
}
```
*Example app:* [02-Standard (Compose)](examples/chat/compose/02-standard/) · [02-Standard (Views)](examples/chat/views/02-standard/).

### Typing
**Data:** `isAgentTyping` (+ `agentAvatarUrl`) shows the dots; call `session.sendTyping()` on every keystroke to tell the agent — throttled, auto-STOPPED after idle, and `isAgentTyping` clears on the next agent message.

```kotlin
// Compose
@Composable
fun ChatScreen() {
    val session = remember { PolyMessaging.chat() }
    val isTyping by session.isAgentTyping.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        // ...your existing message list...

        if (isTyping) {
            Text("typing…", Modifier.padding(8.dp), color = Color.Gray)
        }

        TextField(
            value = input,
            onValueChange = {
                input = it
                scope.launch { runCatching { session.sendTyping() } }   // broadcast on each keystroke
            },
            placeholder = { Text("Message") },
        )
    }
}
```
```kotlin
// Views
private val session: ChatSession by lazy { PolyMessaging.chat() }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...your existing setup (inflate the typingLabel + composer, etc.)...

    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.isAgentTyping.collect { typing ->
                binding.typingLabel.visibility = if (typing) View.VISIBLE else View.GONE
            }
        }
    }

    binding.composer.doAfterTextChanged { text ->
        if (!text.isNullOrEmpty()) lifecycleScope.launch { runCatching { session.sendTyping() } }
    }
}
```
*Example app:* [02-Standard (Compose)](examples/chat/compose/02-standard/) · [02-Standard (Views)](examples/chat/views/02-standard/).

### Suggestions (quick replies)
**Data:** `AgentMessage.suggestions` (`List<ResponseSuggestion>`, agent-only). Render under the last message; on tap, `clearSuggestions(messageId)` then `send(suggestion.messageText)`. Only the latest agent message shows pills, and they scroll away with history.

```kotlin
// Compose — a horizontally scrolling row of quick-reply pills under the last agent message;
// your screen defines val scope = rememberCoroutineScope().
val messages by session.messages.collectAsStateWithLifecycle()

LazyColumn {
    items(messages, key = { it.id }) { message ->
        // ...your bubble rendering for this message...

        if (message is ChatMessage.Agent &&
            message.id == messages.lastOrNull()?.id &&
            message.message.suggestions.isNotEmpty()
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message.message.suggestions.forEach { suggestion ->
                    Text(
                        text = suggestion.messageText,
                        color = SystemBlue,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SystemBlue.copy(alpha = 0.1f))
                            .clickable {
                                session.clearSuggestions(message.id)
                                scope.launch { runCatching { session.send(suggestion.messageText) } }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
```
```kotlin
// Views — a suggestions row is its own RecyclerView item under the last agent message.
// The holder fills a horizontal LinearLayout (`suggestionsStack`) with pill TextViews.
class SuggestionsHolder(private val b: ItemSuggestionsBinding) : RecyclerView.ViewHolder(b.root) {
    private val density = b.root.resources.displayMetrics.density

    fun bind(messageId: UUID, suggestions: List<ResponseSuggestion>, onTap: (UUID, ResponseSuggestion) -> Unit) {
        b.suggestionsStack.removeAllViews()
        suggestions.forEach { suggestion ->
            val pill = TextView(b.root.context).apply {
                text = suggestion.messageText
                maxLines = 1
                setTextColor(Palette.systemBlue)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Palette.systemBlue10)
                    cornerRadius = 100f * density
                }
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                setOnClickListener { onTap(messageId, suggestion) }
            }
            b.suggestionsStack.addView(pill)
        }
    }
}

// Build the row only for the last message while the chat is live (see buildItems()):
val last = messages.lastOrNull()
if (!hasEnded && last != null && last.suggestions.isNotEmpty()) {
    items.add(ListItem.Suggestions(last.id, last.suggestions))
}

// Tap handler wired into the adapter — clear, then send:
val onSuggestionTap: (UUID, ResponseSuggestion) -> Unit = { messageId, suggestion ->
    session.clearSuggestions(messageId)
    lifecycleScope.launch { runCatching { session.send(suggestion.messageText) } }
}
```

> `ChatMessage.suggestions` is exposed on the base `ChatMessage` (empty for user/system rows), so the "last message has pills" check works without first matching `ChatMessage.Agent`.

*Example app:* [02-Standard (Compose)](examples/chat/compose/02-standard/) · [02-Standard (Views)](examples/chat/views/02-standard/).

### Rich text & links
**Data:** `AgentMessage.text` is the agent's text, delivered **raw**. It's usually Markdown — `**bold**`, `*italic*`, `` `code` ``, `[links](https://…)` — but it can also contain a small subset of **HTML** (most commonly `<br>` line breaks), because the backend serves the same message to the web chat widget, which renders it as HTML. The SDK never strips or converts it — you render it. Render with [Markwon](https://github.com/noties/Markwon) (Views) or `HtmlCompat.fromHtml` for the HTML subset; on Compose, hand the same Markwon-produced `Spanned` to an `AndroidView(TextView)`, or annotate the string yourself.

> The snippets below use Markwon — one Gradle line if you take that route: `implementation("io.noties.markwon:core:4.6.2")`.

```kotlin
// Compose — Markwon parses Markdown into a Spanned and renders it (with tappable links)
// into an AndroidView-hosted TextView. Reuse one Markwon instance.
val markwon = remember { Markwon.create(context) }

LazyColumn {
    items(messages, key = { it.id }) { message ->
        when (message) {
            is ChatMessage.Agent -> AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply { movementMethod = LinkMovementMethod.getInstance() }
                },
                update = { tv -> markwon.setMarkdown(tv, message.message.text) },
            )

            // ...other cases (User, System) — see the core pattern...
            else -> {}
        }
    }
}
```
```kotlin
// Views — `bubble` is a TextView. Use Markwon so Markdown links are tappable (a plain
// HtmlCompat.fromHtml pass handles the <br> & friends HTML subset if you skip Markwon).
class MessageHolder(private val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root) {
    private val markwon = Markwon.create(b.root.context)

    fun bind(message: ChatMessage) {
        b.bubble.movementMethod = LinkMovementMethod.getInstance() // make links tappable
        when (message) {
            is ChatMessage.Agent -> markwon.setMarkdown(b.bubble, message.message.text)
            // ...other cases (User, System)...
            else -> {}
        }
    }
}
```

> Markdown renderers don't linkify *bare* URLs — add a regex pass (or a Markwon `LinkifyPlugin`) if your agent sends them, and be tolerant of half-open Markdown during progressive streaming.

> **Handling HTML (`<br>` & friends).** Because the same agent text is rendered by the web chat widget as HTML, a reply can arrive with literal tags — e.g. `…how can I help?<br><br>Pick an option:`. Markdown parsers don't convert HTML, so those tags would render raw. The advanced examples (`03-RichContent`, `06-FullReference`, and the rest of `03`–`07`) run a small `normalizeAgentHtml` pass first that mirrors the web widget's DOMPurify allow-list — `a, br, b, i, em, strong, p, ul, ol, li, code` — mapping `<br>`→newline, `<b>`/`<strong>`→`**`, `<i>`/`<em>`→`*`, `<a href>`→`[text](url)`, lists→bullets, decoding HTML entities, and dropping any other tag (`HtmlCompat.fromHtml` is the quick alternative for the HTML subset alone). The minimal 01-Hello / 02-Standard examples deliberately skip it (they render `m.text` plainly to stay minimal), so they show `<br>` raw — port `normalizeAgentHtml` if your agent emits HTML.

*Example app:* [03-RichContent (Compose)](examples/chat/compose/03-richcontent/) · [03-RichContent (Views)](examples/chat/views/03-richcontent/). Note the examples stay dependency-free instead of pulling in Markwon: Compose hand-rolls the same normalize-then-parse pass into an `AnnotatedString` (`components/RichText.kt`), and Views builds the equivalent `Spanned` manually (`RichTextSpans.kt`) — Markwon (above) is the drop-in production alternative.

### Attachments, link cards & call buttons
An agent message can carry images, link preview-cards, and `tel:` call buttons — all on `AgentMessage`. Filter `attachments` by `contentType` and render each kind; drop `UNKNOWN` (it exists for forward-compat).

**Data:** `AgentMessage.attachments` (`List<Attachment>`) and `AgentMessage.callActions` (`List<ChatCallAction>`).
- `Attachment`: `contentType` (`IMAGE` / `URL` / `UNKNOWN`), `contentUrl` (`URI?`), `previewImageUrl`, `title`, `callToActionText`
- `ChatCallAction`: `title`, `contactNumber`

Load images with [Coil](https://coil-kt.github.io/coil/); open URLs with `Intent(ACTION_VIEW)`; dial via `Intent(ACTION_DIAL, Uri.parse("tel:…"))` (digits + leading `+`).

> The snippets below use Coil — `implementation("io.coil-kt:coil-compose:2.7.0")` (Compose) / `implementation("io.coil-kt:coil:2.7.0")` (Views). The avatar snippet in [Avatars & keyboard](#avatars--keyboard) uses the same artifacts.

```kotlin
// Compose — images via Coil (SubcomposeAsyncImage), URL cards + tel: buttons launch Intents.
val context = LocalContext.current

when (message) {
    is ChatMessage.Agent -> {
        val m = message.message
        // Images — a horizontal carousel
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            m.attachments.filter { it.contentType == AttachmentContentType.IMAGE }.forEach { att ->
                SubcomposeAsyncImage(
                    model = att.contentUrl?.toString(),
                    contentDescription = att.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(160.dp, 120.dp).clip(RoundedCornerShape(12.dp)),
                    loading = { Box(Modifier.background(SystemGray5)) },
                    error = { Box(Modifier.background(SystemGray5)) },
                )
            }
        }
        // URL cards — open contentUrl with ACTION_VIEW
        m.attachments.filter { it.contentType == AttachmentContentType.URL }.forEach { att ->
            val url = att.contentUrl ?: return@forEach
            Text(
                text = att.title ?: url.toString(),
                color = SystemBlue,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                },
            )
        }
        // tel: buttons — dial a sanitized number (digits + leading +)
        m.callActions.forEach { action ->
            Button(onClick = {
                val digits = action.contactNumber.filter { it.isDigit() || it == '+' }
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
            }) {
                Text("${action.title} · ${action.contactNumber}")
            }
        }
    }

    // ...other cases (User, System) — see the core pattern...
    else -> {}
}
```
```kotlin
// Views — `imageStack`, `urlStack` + `callsStack` are LinearLayouts on the holder; Coil loads
// images, Intents open URLs and dial tel:. Reset the reused stacks before binding.
fun bind(message: ChatMessage, context: Context) {
    b.imageStack.removeAllViews()
    b.urlStack.removeAllViews()
    b.callsStack.removeAllViews()
    val m = (message as? ChatMessage.Agent)?.message ?: return

    m.attachments.filter { it.contentType == AttachmentContentType.IMAGE }.forEach { att ->
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(120)).apply { marginEnd = dp(8) }
            att.contentUrl?.let { load(it.toString()) }   // coil.load
        }
        b.imageStack.addView(iv)
    }

    // URL cards — open contentUrl with ACTION_VIEW (same routing as the Compose snippet)
    m.attachments.filter { it.contentType == AttachmentContentType.URL }.forEach { att ->
        val url = att.contentUrl ?: return@forEach
        val link = TextView(context).apply {
            text = att.title ?: url.toString()
            setTextColor(Palette.systemBlue)
            setOnClickListener {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
            }
        }
        b.urlStack.addView(link)
    }

    m.callActions.forEach { action ->
        val button = Button(context).apply {
            text = "${action.title} · ${action.contactNumber}"
            setOnClickListener {
                val digits = action.contactNumber.filter { it.isDigit() || it == '+' }
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
            }
        }
        b.callsStack.addView(button)
    }
}
```

Each link card opens `contentUrl` on tap; call buttons dial a sanitized `tel:` (digits + leading `+`). `ACTION_DIAL` opens the dialer pre-filled (no `CALL_PHONE` permission needed); use `ACTION_CALL` only if you want to place the call directly.

*Example app:* [03-RichContent (Compose)](examples/chat/compose/03-richcontent/) · [03-RichContent (Views)](examples/chat/views/03-richcontent/).

### Live agent handoff
**No special listening** — handoff is already in `messages`: progress as `ChatMessage.System` events (your `systemLabel(event)` from the core pattern renders them), live-agent replies as `ChatMessage.Agent` with `agentKind == AgentKind.LIVE`, live typing via `isAgentTyping`. Just tint the live agent so the user can tell a human took over.

```kotlin
// Compose
when (message) {
    is ChatMessage.Agent -> {
        val m = message.message
        val isLive = m.agentKind == AgentKind.LIVE
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isLive) {
                Text("${m.agentName ?: "Agent"} · live agent", fontSize = 11.sp, color = SystemTeal)
            }
            Text(
                text = m.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isLive) SystemTeal.copy(alpha = 0.18f) else SystemGray5)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }

    // ...other cases (User, System) — see the core pattern...
    else -> {}
}
```
```kotlin
// Views — tint the bubble + show a "live agent" name line when agentKind == LIVE.
fun bindAgent(m: AgentMessage) {
    val isLive = m.agentKind == AgentKind.LIVE
    b.bubble.text = m.text
    b.bubble.background = rounded(if (isLive) Palette.systemTeal18 else Palette.systemGray5, 18f)
    if (isLive) {
        b.name.visibility = View.VISIBLE
        b.name.text = "${m.agentName ?: "Agent"} · live agent"
        b.name.setTextColor(Palette.systemTeal)
    } else {
        b.name.visibility = if (m.agentName.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.name.text = m.agentName
    }
}
```

`SystemEvent.LiveAgentLeft` is terminal (the SDK flips `hasEnded`). To deep-link a handoff route, observe [`session.client.events`](#side-effects-clientevents) for `MessagingEvent.ClientHandoffRequired` / `LiveAgentJoined`.

*Example app:* [05-Handoff (Compose)](examples/chat/compose/05-handoff/) · [05-Handoff (Views)](examples/chat/views/05-handoff/).

### Message timestamps
**Data:** `ChatMessage.timestamp` (epoch millis, UTC; also on each `UserMessage` / `AgentMessage` / `SystemMessage`). Format with `DateUtils` or a `java.time` `DateTimeFormatter`.

```kotlin
// Compose
val zone = remember { ZoneId.systemDefault() }
val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

LazyColumn {
    items(messages, key = { it.id }) { message ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(message.text ?: "")                    // ...your bubble rendering...

            val time = Instant.ofEpochMilli(message.timestamp).atZone(zone).format(timeFormat)
            Text(time, fontSize = 11.sp, color = SecondaryLabel) // e.g. "3:42 PM"
        }
    }
}
```
```kotlin
// Views — `bubble` + `timeLabel` on the holder; DateUtils formats the epoch-millis timestamp.
fun bind(message: ChatMessage) {
    b.bubble.text = message.text ?: ""
    b.timeLabel.text = DateUtils.formatDateTime(
        b.root.context,
        message.timestamp,                               // epoch millis
        DateUtils.FORMAT_SHOW_TIME,
    )
}
```

> `java.time` needs API 26+ but the SDK's minSdk is 24 — on lower API levels use `DateUtils` (as in
> the Views snippet and the 07-Playground examples) or enable core-library desugaring.

For a date-grouped separator row (when the gap between consecutive messages crosses a date boundary, insert a row with the date), see the playground.

*Example app:* [07-Playground (Compose)](examples/chat/compose/07-playground/) · [07-Playground (Views)](examples/chat/views/07-playground/).

### Avatars & keyboard
**Data:** `agentAvatarUrl` (latest, on the session) and `AgentMessage.avatarUrl` (per-message, `URI?`); load with Coil. Keyboard handling is yours — `Modifier.imePadding()` / `WindowInsets.ime` (Compose) or `adjustResize` + `WindowInsetsCompat` (Views).

```kotlin
// Compose — Coil's SubcomposeAsyncImage with a circular clip + gray placeholder; imePadding()
// lifts the composer above the keyboard with no manual inset math.
Column(Modifier.fillMaxSize().imePadding()) {
    LazyColumn(Modifier.weight(1f)) {
        items(messages, key = { it.id }) { message ->
            when (message) {
                is ChatMessage.Agent -> {
                    val m = message.message
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        SubcomposeAsyncImage(
                            model = m.avatarUrl?.toString(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape),
                            loading = { Box(Modifier.size(28.dp).background(SystemGray5)) },
                            error = { Box(Modifier.size(28.dp).background(SystemGray5)) },
                        )
                        Text(m.text)                    // ...your bubble rendering...
                    }
                }

                // ...other cases (User, System) — see the core pattern...
                else -> {}
            }
        }
    }
    // InputBar(...) — sits below the list and rides the keyboard via the imePadding() above.
}
```
```kotlin
// Views — Coil loads the per-message avatar with a CircleCrop transform; the input bar pins
// to the keyboard via a WindowInsets listener (no notification observers).
fun bindAgent(m: AgentMessage) {
    b.avatar.visibility = View.VISIBLE
    if (m.avatarUrl != null) {
        b.avatar.load(m.avatarUrl.toString()) {
            placeholder(R.drawable.avatar_placeholder)
            error(R.drawable.avatar_placeholder)
            transformations(CircleCropTransformation())
        }
    } else {
        b.avatar.setImageResource(R.drawable.avatar_placeholder)
    }
    b.bubble.text = m.text
}

// Keyboard pin lives on the Activity. With android:windowSoftInputMode="adjustResize" in the
// manifest, pad the composer container by the max of the nav-bar and IME insets.
ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, insets ->
    val bottom = maxOf(
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
    )
    v.updatePadding(bottom = bottom)
    insets
}
```

The session-level `agentAvatarUrl` (a `StateFlow<URI?>`) is the latest avatar — handy for a header or the typing-indicator row; `AgentMessage.avatarUrl` is the per-bubble one shown above.

*Example app:* [05-Handoff (Compose)](examples/chat/compose/05-handoff/) · [05-Handoff (Views)](examples/chat/views/05-handoff/).

## Side effects: `client.events`

Rendering reads `messages`. For **imperative reactions** — navigate, fire a haptic, log analytics — observe the typed event stream instead. (This is the lower-level API `ChatSession` is built on; reach for it only for side effects.) `client.events` is a `SharedFlow<MessagingEvent>`, so observing it is **multicast** and doesn't disturb the `ChatSession` driving your UI.

**Data:** `session.client.events` is a `SharedFlow<MessagingEvent>` (a Kotlin sealed type). Match on the subclass — e.g. `MessagingEvent.LiveAgentJoined` (`payload.agentName`), `MessagingEvent.ClientHandoffRequired` (`payload.route`), `MessagingEvent.SessionEnd` — and ignore the rest.

> **Your own sends are not echoed.** Messages sent through `session.send` / `client.send` are acknowledged via `MessagingEvent.MessageConfirmed` — they never come back as `MessagingEvent.UserMessage`. (A `UserMessage` echo only occurs for raw frames pushed through the [transport escape hatch](#advanced-raw-transport).) Drive analytics for sent messages off `MessageConfirmed` or the `messages` list, not a `UserMessage` match. The stream has no replay: events emitted before you start collecting are not redelivered — the Java `addEventListener` bridge subscribes synchronously (live when it returns) for exactly this reason.

```kotlin
// Compose — collect inside a LaunchedEffect so the coroutine is cancelled when
// the composable leaves the composition. Key it on `session` (a stable value).
@Composable
fun ChatScreen() {
    val session = remember { PolyMessaging.chat() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    LaunchedEffect(session) {
        session.client.events.collect { event ->
            when (event) {
                is MessagingEvent.LiveAgentJoined -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    analytics.track("handoff", event.payload.agentName)
                }
                is MessagingEvent.ClientHandoffRequired -> {
                    event.payload.route?.let { route ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route)))
                    }
                }
                is MessagingEvent.SessionEnd -> analytics.track("chat_ended")
                else -> {}
            }
        }
    }

    // ...your existing UI (message list, composer, etc.)...
}
```
```kotlin
// Views — collect inside repeatOnLifecycle(STARTED) so it starts/stops with the
// Activity's visible lifecycle (no manual cancel needed).
class ChatActivity : ComponentActivity() {
    private val session: ChatSession by lazy { PolyMessaging.chat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ...your existing setup...

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.client.events.collect { event ->
                    when (event) {
                        is MessagingEvent.LiveAgentJoined -> {
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            analytics.track("handoff", event.payload.agentName)
                        }
                        is MessagingEvent.ClientHandoffRequired -> {
                            event.payload.route?.let { route ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route)))
                            }
                        }
                        is MessagingEvent.SessionEnd -> analytics.track("chat_ended")
                        else -> {}
                    }
                }
            }
        }
    }
}
```

> Tie the collection to the view lifecycle (Compose `LaunchedEffect`, or Views `repeatOnLifecycle`) and subscribe **before** sending — `events` is lazy-start.

*Example app:* the **05-Handoff** app subscribes to `session.client.events` exactly like this — Compose `ChatScreen.kt` collects it in a `LaunchedEffect`, Views `ChatActivity.kt` in a `lifecycleScope.launch` collector — handling `LiveAgentJoined` / `ClientHandoffRequired` side effects (title updates, deep links). [06-FullReference](examples/chat/compose/06-fullreference/) and [07-Playground](examples/chat/compose/07-playground/) also tap `client.events` (`NewMessageNotifier`, `DevDiagnostics`).

### In-app new-message alerts (local-only workaround)

A common ask: pop a notification when the agent replies. ⚠️ **This is a local-notification *workaround*, not remote push** — and there is **no FCM path in the SDK yet (coming soon).** The SDK's realtime connection only delivers **while your app process is running**, so you drive the banner off the **completed-message events** on `client.events` and post an immediate **local** notification with `NotificationManagerCompat`. Delivery therefore degrades with app state:

| App state | Banner? |
|---|---|
| **Foreground** | ✅ fires immediately |
| **Background — brief grace window** | ⚠️ best-effort only, *while the process and socket are still alive* |
| **Suspended / killed by the system** | ❌ never — Android has torn the socket down |

**Choosing *when* it fires — `NotificationPolicy`.** A banner shouldn't interrupt you while you're *reading* the conversation, so the example `NewMessageNotifier` takes a policy (default: quiet while the chat is on screen):

| Policy | Behaviour |
|---|---|
| `WHEN_BACKGROUNDED` *(default)* | No banner while the chat is on screen (`STARTED` -- visible) — only when it isn't. |
| `ALWAYS` | Banner on every new agent message, even with the chat open in the foreground. |
| `NEVER` | Off. |

Flip it at the call site: Compose `NewMessageNotifier(session, NotificationPolicy.WHEN_BACKGROUNDED)`; Views `messageNotifier.start(lifecycleScope, lifecycle, session, NotificationPolicy.WHEN_BACKGROUNDED)`. (The table above is about *deliverability* — whether a banner can show at all; the policy is *your choice* of when to, within that.)

**Real lock-screen delivery when the app is killed needs FCM + a server-side push integration** — device-token (FCM) registration plus a backend that pushes on each new message. That isn't built yet (**coming soon**), and there's no client-only substitute: a `WorkManager` job can poll-and-notify when Android *opportunistically* runs it, but it's best-effort and OS-timed, not instant.

**How it works — the gist.** Request the notification permission up front, watch `client.events` for *completed* agent messages, skip any you've already shown, gate on process state, and post an immediate **local** notification while the app is foreground or inside a short grace window.

> **Foreground + a short grace window — and that's the client-side ceiling.** As long as the process is alive (foreground, or briefly after backgrounding) the socket keeps delivering, so a reply still banners. We never schedule a time-/delay-based notification (it could fire long after the process is gone). Beyond that window — once Android suspends or kills the process — nothing arrives client-side; lock-screen delivery is an **FCM + backend push** feature (not built yet — **coming soon**), see the table above.

**Step by step** — each step shows the idea, then the code. Steps 1–5 are plain `NotificationManagerCompat` + the SDK's `client.events`, so the logic is **identical in Compose and Views**; the only per-framework difference is the wiring (where the collection runs), shown for **both** in *Putting it together* below.

**1. Set up the channel and permission.** Create a notification channel once (required on API 26+), and request the `POST_NOTIFICATIONS` runtime permission (required on API 33+ / Android 13+ — declare it in the manifest and ask at runtime).

```kotlin
// AndroidManifest.xml:  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

// Create the channel once (e.g. in Application.onCreate); no-op below API 26 if you guard it.
val channel = NotificationChannelCompat.Builder("poly_new_messages", NotificationManagerCompat.IMPORTANCE_HIGH)
    .setName("New messages")
    .build()
NotificationManagerCompat.from(context).createNotificationChannel(channel)

// Ask for POST_NOTIFICATIONS up front (API 33+). From a ComponentActivity:
val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted? */ }
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
}
```

**2. Listen for *completed* messages.** Consume `session.client.events` and act only on `MessagingEvent.AgentMessage` / `MessagingEvent.LiveAgentMessage` — each carries the **whole** reply `text` and a stable, server-assigned `messageId`. Ignore the partial `MessagingEvent.AgentMessageChunk`s, so the banner shows the full reply, not the first streamed token.

```kotlin
// client.events is a multicast SharedFlow, so observing it doesn't disturb the ChatSession driving your UI.
session.client.events.collect { event ->
    val msg: Triple<String, String, String>? = when (event) {   // (id, title, body)
        is MessagingEvent.AgentMessage ->
            Triple(event.payload.messageId, event.payload.agentName ?: "New message", event.payload.text)
        is MessagingEvent.LiveAgentMessage ->
            Triple(event.payload.messageId, event.payload.agentName ?: "New message", event.payload.text)
        else -> null                                            // ignore AgentMessageChunk etc.
    } ?: return@collect
    // …steps 3–5 run here, once per completed message…
}
```

**3. Dedupe — persisted.** Keep a **bounded** `SharedPreferences`-backed set of shown `messageId`s. The SDK replays the conversation on resume / reconnect / relaunch, so an *in-memory* guard isn't enough; persist it and replays are silently skipped.

```kotlin
class NotifiedMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("poly.notified", Context.MODE_PRIVATE)
    private val key = "messageIds"
    private val seen = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()

    fun contains(id: String) = id in seen
    fun markShown(id: String) {
        if (!seen.add(id)) return
        // (cap the stored set in real code so it can't grow unbounded)
        prefs.edit().putStringSet(key, seen.toSet()).apply()
    }
}

if (store.contains(msg.first)) return@collect    // skip a message we've already shown
```

**4. Gate on app state *and* policy.** Deliverability (foreground or the brief grace window) caps what *can* show; the `NotificationPolicy` decides whether it *should* (the default `WHEN_BACKGROUNDED` stays quiet while the chat is on screen). Mark every message shown, posted or not, so a resume-replay can't re-notify. Use `ProcessLifecycleOwner` to read whole-app state — it reflects the *process*, not a single Activity.

> `ProcessLifecycleOwner` lives in its own artifact — one extra Gradle line if your project doesn't
> already have it: `implementation("androidx.lifecycle:lifecycle-process:2.8.7")`.

```kotlin
// graceWindowIsOpen: a flag you hold from ON_STOP until ON_START (see "Putting it together").
val onScreen = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) // chat visible
val wantBanner = policy == NotificationPolicy.ALWAYS || !onScreen   // WHEN_BACKGROUNDED: quiet on screen
val canDeliver = onScreen || graceWindowIsOpen                      // suspended → no events arrive anyway
if (wantBanner && canDeliver) { /* step 5: post */ }
store.markShown(msg.first)                                          // mark every message, posted or not
```

**5. Post immediately.** Build the notification and `notify()` it now — **never** schedule it for later (which could fire long after the process is gone) — then mark it shown. Use the `messageId` as the notification id so a re-delivery would just update the same banner.

```kotlin
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
    val notification = NotificationCompat.Builder(context, "poly_new_messages")
        .setSmallIcon(R.drawable.ic_chat)            // a vector drawable
        .setContentTitle(msg.second)
        .setContentText(msg.third)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(msg.first.hashCode(), notification)
}
store.markShown(msg.first)
```

**6. Streaming-safe — no extra code.** With streaming on, the agent message's `messageId` is stable across chunks, so step 3's dedupe yields exactly **one** banner per reply (with the full assembled text).

**Putting it together** — the collection and grace window wire up a little differently per framework:

```kotlin
// Compose — collect in a LaunchedEffect (auto-cancelled with the composable);
// hold the grace flag across app background/foreground via ProcessLifecycleOwner.
LaunchedEffect(session) {
    session.client.events.collect { event -> /* steps 2–5: match → dedupe → gate → post */ }
}
DisposableEffect(Unit) {
    val observer = LifecycleEventObserver { _, e ->
        when (e) {
            Lifecycle.Event.ON_STOP  -> graceWindowIsOpen = true   // brief grace while the socket lives
            Lifecycle.Event.ON_START -> graceWindowIsOpen = false
            else -> {}
        }
    }
    ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
}
```
```kotlin
// Views — collect under repeatOnLifecycle(CREATED), not STARTED, so a reply that lands
// while the chat is backgrounded (during the grace window) is still observed; hold the
// grace flag via a ProcessLifecycleOwner observer registered in Application.onCreate.
class ChatActivity : ComponentActivity() {
    private val session: ChatSession by lazy { PolyMessaging.chat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP  -> graceWindowIsOpen = true
                Lifecycle.Event.ON_START -> graceWindowIsOpen = false
                else -> {}
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                session.client.events.collect { event -> /* steps 2–5: match → dedupe → gate → post */ }
            }
        }
    }
}
```

Subscribe *before* sending — `events` is lazy-start. Runnable in the **03 Rich Content**, **06 Full reference**, and **07 Playground** examples — a drop-in `NewMessageNotifier.kt` (under `components/` in the Compose apps, at the package root in the Views apps) packages all of the above (collection + grace window + `NotificationPolicy`, defaulting to `WHEN_BACKGROUNDED` so it stays quiet while you're on the chat). Still a **local-only workaround** — no remote push (FCM) yet (**coming soon**).

---

# Reference

## Configuration

```kotlin
PolyMessaging.initialize(context, Configuration(apiKey = "YOUR_API_KEY"))
```

`apiKey` is the only required field; everything else has a working default.

| Field | Default | Description |
|---|---|---|
| `apiKey` | — (required) | API key from Agent Studio. Treat as a credential — never log it. |
| `environment` | `Environment.US` | Production region (`Environment.US` / `Environment.UK` / `Environment.EUW`) or escape hatch (see below) |
| `hostIdentifier` | package name | `X-Host` for connector validation; auto-derived from your application's package name |
| `streamingEnabled` | `true` | `true`: agent replies grow token-by-token (ChatGPT-style). `false`: complete-message bubbles only. See [Streaming](#streaming) |
| `logLevel` | `LogLevel.ERROR` | `NONE` \| `ERROR` \| `WARN` \| `INFO` \| `DEBUG` |
| `heartbeatIntervalSeconds` | `null` (30 s) | Override the heartbeat interval; server caps may overrule |
| `sessionTimeoutSeconds` | `null` (600) | Reserved — currently ignored; the SDK fixes the idle-timeout at 600 s (the backend's 10-min WebSocket idle timeout) |
| `maxReconnectAttempts` | `null` (10) | Override the reconnect cap |

**Environments:**

- `Environment.US` (default) — `messaging.us-1.poly.ai`
- `Environment.UK` — `messaging.uk-1.poly.ai`
- `Environment.EUW` — `messaging.euw-1.poly.ai`
- `Environment.cluster("name")` — any other named cluster; resolves to `messaging.<name>.poly.ai`
- `Environment.custom(restBaseUrl, wsBaseUrl)` — override both URLs entirely (proxies, local mocks); both are `java.net.URI`

Most apps don't need to set `environment` at all. Pass it only when targeting a non-US region or a non-production cluster:

```kotlin
// Target a non-US region (or a named cluster) instead of production US:
PolyMessaging.initialize(
    context,
    Configuration(
        apiKey = "YOUR_API_KEY",
        environment = Environment.UK,   // or Environment.cluster("name") for a named cluster
    ),
)
```

A fully-specified configuration (every value here has a working default — set only what you need to override):

```kotlin
PolyMessaging.initialize(
    context,
    Configuration(
        apiKey = "YOUR_API_KEY",
        environment = Environment.US,            // US (default) | UK | EUW | cluster("name") | custom(...)
        hostIdentifier = "com.yourapp.android",  // X-Host for connector validation; defaults to your package name
        streamingEnabled = true,                 // server streams agent replies as chunks
        logLevel = LogLevel.ERROR,               // NONE | ERROR | WARN | INFO | DEBUG
        heartbeatIntervalSeconds = 30,           // server caps may overrule
        sessionTimeoutSeconds = 600,             // reserved — currently ignored; idle timeout is fixed at 600 s
        maxReconnectAttempts = 10,               // reconnect budget before Failed
    ),
)
```

The same configuration is available to Java consumers through the fluent `Configuration.Builder`, which mirrors the named-argument constructor field-for-field:

```java
Configuration config = new Configuration.Builder("YOUR_API_KEY")
    .environment(Environment.US.INSTANCE)      // Environment.UK.INSTANCE / Environment.EUW.INSTANCE / Environment.cluster("name") / Environment.custom(...)
    .hostIdentifier("com.yourapp.android")
    .streamingEnabled(true)
    .logLevel(LogLevel.ERROR)
    .heartbeatIntervalSeconds(30)
    .sessionTimeoutSeconds(600)                // reserved — currently ignored; idle timeout is fixed at 600 s
    .maxReconnectAttempts(10)
    .build();
PolyMessaging.initialize(context, config);
```

## Error handling

`send()` / `end()` throw `PolyError` (a sealed `Exception`). Use the convenience flags, or branch on the nested subclasses:

```kotlin
try {
    session.send(text)
} catch (error: PolyError) {
    when {
        error.isAuthError      -> showError("Authentication failed")
        error.isSessionExpired -> showError("Session timed out — start a new chat")
        error.isRetryable      -> showError("Connection issue — retrying…")
        else                   -> showError("Something went wrong: ${error.debugDescription}")
    }

    // …or branch on the nested subclasses instead:
    when (error) {
        is PolyError.Auth.Unauthorized        -> showError("Invalid API key")
        is PolyError.Session.SessionExpired   -> showError("Session timed out")
        is PolyError.Transport.NetworkError   -> showError("Network: ${error.detail}")
        else                                  -> showError(error.debugDescription)
    }
}
```

Every case `PolyError` can throw, and when:

| Case | Fires when | Retryable |
|---|---|---|
| `PolyError.Auth.TokenAcquisitionFailed` | the access-token request failed | no |
| `PolyError.Auth.Unauthorized` | the API key was rejected (401/403) | no |
| `PolyError.Session.SessionCreationFailed(code)` | the server refused to create a session (`code` says why) | no |
| `PolyError.Session.UnexpectedDisconnect(code, reason)` | the socket dropped unexpectedly | yes |
| `PolyError.Session.MaxReconnectAttemptsExceeded` | reserved — the SDK currently surfaces reconnect exhaustion as `PolyError.Transport.NetworkError` | yes |
| `PolyError.Session.SessionExpired` | the session idled out | no |
| `PolyError.Session.SessionEnded(reason)` | the conversation ended | no |
| `PolyError.Message.DeliveryFailed(draftId)` | a sent message never confirmed after retries | no |
| `PolyError.Message.PayloadTooLarge(maxBytes)` | the message exceeds `max_message_size_bytes` | no |
| `PolyError.Transport.NetworkError(detail)` · `PolyError.Transport.ProtocolError(reason)` | a network / protocol-level failure | yes |
| `PolyError.InvalidConfiguration(detail)` | bad `Configuration` (e.g. empty token) | no |

Convenience flags: `isAuthError`, `isSessionError`, `isTransportError`, `isSessionExpired`, `isRetryable`. Every case also exposes a `debugDescription` for logging.

## Connection states

`session.connection` is a `StateFlow<ConnectionStatus>`. You rarely match it directly — `isConnected` / `isReconnecting` / `isFailed` / `isActive` and `session.failureReason` cover most UIs — but the full set:

| State | Meaning |
|---|---|
| `ConnectionStatus.Idle` | not started yet |
| `ConnectionStatus.Connecting` | opening the socket |
| `ConnectionStatus.Open` | connected and ready (`isConnected`) |
| `ConnectionStatus.Reconnecting(attempt)` | transient drop; auto-retrying (`isReconnecting`) — show a banner |
| `ConnectionStatus.Closing` | shutting down |
| `ConnectionStatus.Closed(event)` | the server cleanly ended the session (`event` is a `ConnectionCloseEvent`) |
| `ConnectionStatus.Failed(reason)` | reconnect budget exhausted (`isFailed`) — recover with `session.client.startNewSession()` |

Helpers on the base class: `isConnected`, `isReconnecting`, `isFailed`, `isActive`, `isTerminal`, and `reconnectAttempt` (the current attempt number, or `null`).

**Data:** observe in Compose with `collectAsStateWithLifecycle()`; in Views collect inside `repeatOnLifecycle(STARTED)`.

```kotlin
// Compose: drive a reconnect banner off the status flow
val connection by session.connection.collectAsStateWithLifecycle()
if (connection.isReconnecting) {
    ReconnectBanner(attempt = connection.reconnectAttempt)
}
```

```kotlin
// Views: same status flow, collected for the lifecycle
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        session.connection.collect { status ->
            binding.banner.visibility =
                if (status is ConnectionStatus.Reconnecting) View.VISIBLE else View.GONE
        }
    }
}
```

## Testing

Three scenarios catch most real-world breakage:

| Scenario | What it exercises |
|---|---|
| Toggle airplane mode mid-chat, then back | fast disconnect → `Reconnecting` → auto-resume on restore |
| Background the app > 5 min, then foreground | idle-timeout vs reconnect-and-resume paths |
| Force-stop and relaunch within the session timeout | `chat()` restores the conversation; `start()` always starts fresh |

## How it works

The SDK implements the [PolyAI Messaging API](https://docs.poly.ai/api-reference/messaging/introduction) — a WebSocket protocol — and manages the whole lifecycle: access-token → session → WebSocket → `REQUEST_POLY_AGENT_JOIN` → event exchange, with heartbeat, dedup, and cursor-based replay handled internally.

**Two consumer layers on one orchestrator. Both work in Jetpack Compose and Android Views** (`ChatSession` exposes `StateFlow` properties; Compose collects them with `collectAsStateWithLifecycle()` and Views with `repeatOnLifecycle(STARTED) { …collect… }` — the only difference is the binding):

```
Your App (Compose or Views)
  └─ ChatSession                              ← observe StateFlow state; recommended
       └─ PolyMessagingClient                 ← raw events / connectionStatus / sessionState flows
            └─ Coordinator                     ← SessionService · ChatService · ConnectionService
                                                 HeartbeatService · NetworkMonitor (ConnectivityManager)
```

| Layer | When to use |
|---|---|
| `ChatSession` | **Recommended, both UI toolkits.** Observe `StateFlow` state; the SDK handles streaming assembly, dedup, delivery, resets. |
| `PolyMessagingClient` | Drive the raw `events` / `sessionState` (`SharedFlow`s) and `connectionStatus` (`StateFlow`) streams and build your own state. |
| `session.client.getConnection()` | Escape hatch — raw WebSocket frames. See [Raw transport](#advanced-raw-transport). |

**Reconnection is automatic:** drops the dead socket shortly after the OS (via `ConnectivityManager`) reports offline; exponential backoff with ±20% jitter (1s → 2s → … → 30s cap); transparent reconnect at the long-lived-socket mark and on session expiry; resumes from the last sequence (`cursor=<n>`) and dedups replayed events by `id`. When the reconnect budget is exhausted, `connection` becomes `Failed` — recover with `session.client.startNewSession()` (or a fresh `chat()`/`start()`).

**Design:** Kotlin-first and Java-friendly; coroutine/`Flow`/`StateFlow` concurrency (no `LiveData`/RxJava required); hexagonal — transports and persistence sit behind ports, so every layer is testable in isolation.

## Advanced: raw transport

`session.client.getConnection()` returns the live `Connection` for custom analytics or proprietary event types:

```kotlin
val raw = session.client.getConnection()

scope.launch {
    raw.send(OutgoingEvent.UserMessage("Hello"))   // typed OutgoingEvent — SDK encodes JSON
    raw.send(OutgoingEvent.Heartbeat)
    raw.sendRaw("""{"type":"EVENT_TYPE_CUSTOM","payload":{}}""")   // arbitrary frame; throws Transport.NotConnected if the socket isn't open
}

scope.launch { raw.rawFrames.collect { frame -> analytics.record(frame) } }   // tap every frame
```

```kotlin
// Compose: tap the raw frame flow, cancelled when the composable leaves the composition
LaunchedEffect(session) {
    session.client.getConnection().rawFrames.collect { frame -> log(frame) }
}
```

```kotlin
// Views: tap the raw frame flow for the lifecycle
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        session.client.getConnection().rawFrames.collect { frame -> log(frame) }
    }
}
```

`send(OutgoingEvent)` (typed — `UserMessage`, `Heartbeat`, `RequestPolyAgentJoin`, `UserTyping`, `UserEndConversation`, `UserLeft`), `sendRaw(String)` (arbitrary JSON — `suspend`; throws `PolyError.Transport.NotConnected` if the socket isn't open), `rawFrames` / `messages` (`Flow`s).

> `sendRaw` bypasses delivery tracking, retry, and `local_id` correlation — no `MessagePending` / `MessageConfirmed`. Use it only when the managed `session.client.send(...)` path doesn't fit.

## Voice calling (`ai.poly:voice`)

Live, two-way WebRTC voice calls to a PolyAI agent ship in a **separate** artifact so chat-only apps
stay lean (the call path pulls in the native libwebrtc audio engine). Add it alongside the messaging
SDK — it reuses the same `Configuration`:

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.poly:messaging:0.9.0")
    implementation("ai.poly:voice:0.9.0")
}
```

```kotlin
import ai.poly.voice.PolyVoice

val call = PolyVoice.call(
    context,
    Configuration(apiKey = "YOUR_API_KEY"),          // connector token (X-Token)
    VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"),  // WebRTC gateway token — distinct, also required
)

// Observe the lifecycle: Idle → Connecting → Connected → Ended / Failed.
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        call.state.collect { state -> render(state) } // state.error is a PolyError.Voice on Failed
    }
}

lifecycleScope.launch { call.start() }   // after RECORD_AUDIO is granted
call.setMuted(true)                      // in-call controls
call.end()
```

`CallState`, `PolyError.Voice`, `Configuration`, and `Environment` are the same types from
`ai.poly:messaging` — no new vocabulary. A call needs **two credentials, both required and distinct**,
from [Agent Studio](https://studio.poly.ai) › Connector Settings: the **API key** (`Configuration.apiKey`,
authenticates the connector) and the **WebRTC token** (`VoiceOptions.webrtcToken`, authenticates the media
gateway). It also needs the **`RECORD_AUDIO`** runtime permission — the SDK declares it; you request the
grant before `start()`.

📖 **Full voice guide → [`polyvoice/README.md`](polyvoice/README.md)** — permissions, audio-output routing
(speaker / earpiece / headset / Bluetooth), interruptions, background calls (foreground service), and R8.
Runnable demos: [`examples/voice/compose`](examples/voice/compose/) ·
[`examples/voice/views`](examples/voice/views/).

## Dev tools (QA)

For internal builds, `DevSettings` is a `SharedPreferences`-backed runtime `Configuration` builder — flip environment, streaming, logging, and other knobs (each exposed as a `StateFlow`, and assembled via `buildConfiguration()`) without rebuilding. The **07-Playground** example pairs it with an on-screen diagnostics strip and event log (`MessagingEvent.debugSummary` / `debugDetail`), plus the [raw transport](#advanced-raw-transport) tap for protocol-level pokes. These are for development/QA — they bake in no credentials and aren't needed in production.

## Example apps

Examples live under [`examples/`](examples/), split by product — **[`chat/`](examples/chat/)**
(`ai.poly:messaging`) and **[`voice/`](examples/voice/)** (`ai.poly:voice`) — each mirrored across
**Jetpack Compose** and **Android Views**. Open a module in Android Studio, set your `apiKey`, and Run.

### Chat (`examples/chat`)

A 7-rung ladder — each level builds on the previous one; see its README for what's new.

| Level | What it adds | Compose · Views |
|---|---|---|
| **01 Hello** | initialize, render, send | [Compose](examples/chat/compose/01-hello/) · [Views](examples/chat/views/01-hello/) |
| **02 Standard** | typing, suggestions, delivery, reconnect, end + start-new | [Compose](examples/chat/compose/02-standard/) · [Views](examples/chat/views/02-standard/) |
| **03 Rich Content** | attachments, link cards, `tel:` actions, Markdown | [Compose](examples/chat/compose/03-richcontent/) · [Views](examples/chat/views/03-richcontent/) |
| **04 Resilience** | offline banner, loading skeleton, terminal error + retry | [Compose](examples/chat/compose/04-resilience/) · [Views](examples/chat/views/04-resilience/) |
| **05 Handoff** | full live-agent ladder | [Compose](examples/chat/compose/05-handoff/) · [Views](examples/chat/views/05-handoff/) |
| **06 Full reference** | production resume + start-new flows | [Compose](examples/chat/compose/06-fullreference/) · [Views](examples/chat/views/06-fullreference/) |
| **07 Playground** | diagnostics, runtime config, streaming toggle | [Compose](examples/chat/compose/07-playground/) · [Views](examples/chat/views/07-playground/) |

### Voice (`examples/voice`)

A one-screen **tap-to-call** demo on `ai.poly:voice` — build a `VoiceCall`, request the mic, start /
mute / end, and switch the audio output (speaker / earpiece / headset / Bluetooth) mid-call. Set your
connector token + WebRTC token in the `PolyVoice.call(...)` block. See [Voice calling](#voice-calling-aipolyvoice).

| Demo | What it shows | Compose · Views |
|---|---|---|
| **Tap to call** | `PolyVoice.call`, `CallState`, mic permission, mute/end, audio-output picker | [Compose](examples/voice/compose/) · [Views](examples/voice/views/) |

## Requirements

| | Minimum |
|---|---|
| Android | **API 24** (Android 7.0) |
| compileSdk | **36** |
| Kotlin | **2.2+** |
| JDK (to build) | **17** |
| Java consumers | Supported |
| Dependencies | None required beyond Kotlin coroutines (image loading via Coil / Markdown via Markwon is your choice in the UI layer) |
| Permissions | None to declare — the SDK's manifest merges `INTERNET` and `ACCESS_NETWORK_STATE` into your app automatically |
| R8 / minify | Zero-config — the AAR ships its consumer rules; `minifyEnabled true` builds and runs out of the box |

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.

Copyright 2026 PolyAI Limited.
