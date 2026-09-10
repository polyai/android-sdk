# PolyAI Voice (`ai.poly:voice`)

Live, two-way WebRTC voice calls to a PolyAI agent — the companion artifact to
[`ai.poly:messaging`](../README.md). It ships **separately** so chat-only apps stay lean (the call path
pulls in the native libwebrtc audio engine), and it **reuses the messaging `Configuration`** plus the
same `CallState` / `PolyError.Voice` / `Environment` vocabulary — no new concepts.

> New here? The [root README](../README.md#voice-calling-aipolyvoice) has the 60-second overview; this is
> the complete guide.

## Install

The `0.11.0` artifacts are compiled with Kotlin 2.4. In a fresh Android Studio project, first set the
Kotlin plugin to 2.4.10 (or newer):

```toml
# gradle/libs.versions.toml
[versions]
kotlin = "2.4.10"
```

Then add these three lines to the app module—not the top-level project file:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("ai.poly:messaging:0.11.0")
    implementation("ai.poly:voice:0.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}
```

`mavenCentral()` is already present in new Android Studio projects. If your project removed it, restore
it under `dependencyResolutionManagement.repositories` in `settings.gradle.kts`.

## Quick start

The following is a complete foreground-only calling app for the current Android Studio **Empty
Activity** Compose template. Create a project with minimum SDK 24 or newer, update Kotlin and add the
dependencies above, then replace the generated activity as described below. No manifest changes or
extra application class are needed for this first call.

### Paste one file — `MainActivity.kt`

Open the `MainActivity.kt` Android Studio generated. Keep its first `package …` line, delete
**everything below that line**, and paste the block below directly after it. The block deliberately
uses no project-specific package, theme, or resource names.

Replace the two credential placeholders, run on a physical device, tap **Start call**, and allow
microphone access. Both credentials come from **Agent Studio › Connector Settings** and are required
and distinct.

> This first call intentionally works only while the app is in the foreground. Once it works, add the
> [foreground service](#backgrounding--foreground-service) before testing background calls.

```kotlin
// Paste below the package line already in your MainActivity.kt.
import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyMessaging
import ai.poly.messaging.voice.CallState
import ai.poly.voice.PolyVoice
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // For this quick test, initialize before Compose creates the VoiceCall.
        PolyMessaging.initialize(
            context = this,
            config = Configuration(
                apiKey = "YOUR_CONNECTOR_TOKEN",
                webrtcToken = "YOUR_WEB_CALLING_TOKEN",
            ),
        )

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { CallScreen() }
            }
        }
    }
}

@Composable
private fun CallScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val call = remember { PolyVoice.call(context.applicationContext) }
    val state by call.state.collectAsStateWithLifecycle()
    var muted by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    DisposableEffect(call) {
        onDispose { call.close() }
    }

    fun startCall() {
        permissionDenied = false
        muted = false
        scope.launch { runCatching { call.start() } }
    }

    val requestMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCall() else permissionDenied = true
    }

    fun toggleCall() {
        when (state) {
            is CallState.Connecting, is CallState.Connected -> scope.launch { call.end() }
            else -> {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) startCall()
                else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val status = if (permissionDenied) {
        "Microphone permission is required"
    } else {
        when (val current = state) {
            is CallState.Idle -> "Tap to call the agent"
            is CallState.Connecting -> "Connecting…"
            is CallState.Connected -> "Connected — say hello 👋"
            is CallState.Ended -> "Call ended"
            is CallState.Failed -> "Failed: ${current.error.message}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PolyAI Voice", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = status,
            color = if (state is CallState.Failed || permissionDenied) Color.Red else Color.Unspecified,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = ::toggleCall,
            enabled = state !is CallState.Connecting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (state is CallState.Connected) "End call" else "Start call")
        }
        if (state is CallState.Connected) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    muted = !muted
                    scope.launch { call.setMuted(muted) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (muted) "Unmute" else "Mute")
            }
        }
    }
}
```

The SDK's manifest automatically contributes `INTERNET`, `ACCESS_NETWORK_STATE`, and `RECORD_AUDIO`,
so the generated app manifest stays unchanged. The activity still has to request the dangerous
`RECORD_AUDIO` permission at runtime; the pasted code does that before `start()`.

The screen holds one `VoiceCall`, observes `call.state`, and calls `close()` when it leaves the
composition. The visible lifecycle is `Idle → Connecting → Connected → Ended` or `Failed`; `start()`
returning means setup is underway, not that the call is already connected.

For a production app, move `PolyMessaging.initialize(...)` to your `Application.onCreate()` so it
runs once per process. Keeping it in the activity is intentional here: it makes the first test a
single-file paste, though it runs again if Android recreates the activity.

### Sync and run

Click **Sync Project with Gradle Files**, select a physical Android device, and run the app. The first
tap asks for microphone access; after granting it, the status should move through **Connecting** to
**Connected**.

If Gradle reports `Unresolved reference 'implementation'`, the dependency lines were added to the
top-level `build.gradle.kts`; move them into `app/build.gradle.kts` inside its `dependencies` block.
If it reports incompatible Kotlin metadata version `2.4.0`, update the Kotlin version in
`gradle/libs.versions.toml` as shown under [Install](#install), then sync again.

`CallState`, `PolyError.Voice.*`, `Configuration`, and `Environment` are shared with
`ai.poly:messaging`. Need a different connector for one call? Pass a `Configuration` explicitly:
`PolyVoice.call(context, config)`.

## Permissions

The SDK's manifest **auto-merges** the three permissions every call needs — you don't declare these:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />   <!-- runtime grant required -->
```

`RECORD_AUDIO` is a **runtime** permission — request it before `start()` (the call fails fast with
`PolyError.Voice.MediaFailed` if it's missing). Add the rest **only for the optional features you use**:

```xml
<!-- Bluetooth audio output — so BT headsets show up in call.audio (see "Audio output") -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Keep a call alive while the app is backgrounded (see "Backgrounding & foreground service") -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />   <!-- ongoing-call notification -->
```

## Credentials & configuration

A voice call needs **two credentials**, both found on your agent in **[Agent Studio](https://studio.poly.ai) ›
Connector Settings** (the same connector you use for chat):

| Value | What it is | Required? | Sent as |
|---|---|---|---|
| **API key** — `Configuration.apiKey` | your **connector token** | **Yes** | `X-Token` (authenticates the call) |
| **WebRTC token** — `Configuration.webrtcToken` | the **auth token** for the media connection — a **distinct** token from the API key | **Yes** | `Authorization: Bearer` when the call is provisioned |

Set both once on `Configuration` at `PolyMessaging.initialize(...)` — the same call chat already needs
— and every `PolyVoice.call(...)` site picks them up automatically:

```kotlin
PolyMessaging.initialize(
    context,
    Configuration(apiKey = "YOUR_API_KEY", webrtcToken = "YOUR_WEBRTC_TOKEN"), // both required, distinct
)
val call = PolyVoice.call(context)
```

For a call that needs a different connector than the one `initialize(...)` set, pass an explicit
`Configuration` with both credentials:

```kotlin
PolyVoice.call(
    context,
    Configuration(apiKey = "YOUR_API_KEY", webrtcToken = "YOUR_WEBRTC_TOKEN"),
)
```

Both are **always required and always distinct**: the API key authenticates the *connector*, the WebRTC
token authenticates the *media backend*. `PolyVoice.call(...)` throws `PolyError.InvalidConfiguration`
if `Configuration.webrtcToken` is not set. (The example apps set both credentials on `Configuration`.)

Two more values have sensible defaults, so **most apps don't set them** — but good to know:

- **`environment`** defaults to **`Environment.US`** (PolyAI's US cluster). Set `.UK` / `.EUW` — or
  `.cluster("…")` for a named cluster — only if your agent lives in another region.
- **`hostIdentifier`** (sent as `X-Host`) defaults to your **app's package name** (`applicationId`).
  Override it only if your connector is registered against a specific host in Agent Studio:
  `Configuration(apiKey = "…", hostIdentifier = "https://your-site.com")`.

## How a call connects

Calls are placed over PolyAI's **`webrtc-bridge`**. The older `webrtc-gateway` path was removed in
MES-1658 — it is no longer operable, so there is nothing to choose between and **no API change**:
the same `PolyVoice.call(context, config, options)` with the same two credentials.

What changed underneath, in case you're debugging a call:

| | before (gateway) | now (bridge) |
|---|---|---|
| Call setup | one signalling WebSocket | `POST /api/v1/call`, then SDP over HTTPS |
| Credential | token inside the SDP offer | `Authorization: Bearer` on provision |
| Call id | minted by this SDK | minted by the bridge (`call-<8 hex>`) |
| ICE | trickled after the offer | gathered **before** the offer is sent |
| Agent audio | arrived on the first answer | a second negotiation after connect |
| Media terminates at | PolyAI's gateway | Cloudflare's edge |
| STUN fallback | `stun.l.google.com` | `stun.cloudflare.com` |

Everything you bind to is unchanged: `VoiceCall`, `CallState`, mute, audio routing and errors, and
the call still links to the same messaging session, so the agent transcript is the same.

`start()` still returns as soon as the call is under way, with the state `Connecting`; observe
`state` for `Connected` exactly as before. The agent-track negotiation that starts the agent's audio
runs after that, on your behalf.

> **Custom / self-hosted:** `VoiceOptions.signalingHost` now names the **bridge** host (required with
> `Environment.Custom`).

## Audio output (speaker / earpiece / headset / Bluetooth)

By default the call **follows the connected accessory** — a wired or Bluetooth headset is used
automatically (and auto-switches when you plug/unplug one mid-call); when nothing's connected it falls
back to the **loudspeaker** (`VoiceOptions(speakerphone = false)` falls back to the earpiece instead). To
let users **pin a specific output**, observe `call.audio` and call `setAudioDevice` — and pass `null` to
return to automatic:

```kotlin
// A consistent snapshot: the outputs available now + the active one.
lifecycleScope.launch {
    call.audio.collect { state ->
        renderPicker(state.availableDevices, selected = state.selectedDevice)
    }
}

call.setAudioDevice(speakerDevice)  // route to a device from availableDevices
call.setAudioDevice(null)           // revert to automatic routing (wired > Bluetooth > earpiece/speaker)
```

- `AudioDevice.type` is one of `EARPIECE` / `SPEAKER_PHONE` / `WIRED_HEADSET` / `BLUETOOTH`; `name` is a
  picker-friendly label. The list updates live as headsets connect/disconnect.
- **Switching is asynchronous** — Bluetooth can take a few seconds to engage. Drive your UI off
  `call.audio`, not off `setAudioDevice` returning. Selecting an unavailable device is a no-op.
- **Bluetooth needs `BLUETOOTH_CONNECT`.** The library does **not** declare this runtime permission for
  you; add `<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />` to your app and
  request the grant if you want Bluetooth outputs to appear. Without it, Bluetooth is simply absent — no
  crash. Java callers get `setAudioDevice(device, executor, callback)` and
  `addAudioListener(executor, listener)`.

## Interruptions (incoming calls, other apps)

The SDK manages audio focus for you — it takes focus on `start()` and releases it on `end()`/teardown.
It also **reacts to losing focus** while a call is live, so you don't have to:

- A **transient** loss (a notification, a navigation prompt) **mutes the mic** for the duration and
  restores it automatically when focus returns — the call stays `Connected`. Nothing to handle.
- A **permanent** loss (the user answers an **incoming phone call**, or another app starts an exclusive
  audio session) **ends the call**: it surfaces as `CallState.Failed(PolyError.Voice.Interrupted)` and
  the mic is released.

So all you do is observe `state` and tell the user — the mic is already released for you:

```kotlin
lifecycleScope.launch {
    call.state.collect { state ->
        if (state is CallState.Failed && state.error is PolyError.Voice.Interrupted) {
            showBanner("Call interrupted — tap to call again") // e.g. an incoming phone call ended it
        }
    }
}
```

## Backgrounding & foreground service

A `VoiceCall` is a plain object on its own coroutine scope — it is **not** tied to your Activity/Fragment
lifecycle, so the SDK won't end a call just because your UI is backgrounded (keep a reference to it; observe
`state` with `collectAsStateWithLifecycle` for the UI). **However**, when your app goes to the background
Android 9+ **cuts mic capture** and **throttles the WebRTC media/network threads**, so the connection
silently dies within ~15s and the SDK reports `CallState.Failed(PolyError.Voice.Disconnected)`. To keep a
call alive in the background you need **two things** while the call is active:

1. A **microphone foreground service** — grants background mic + keeps the process foregrounded.
2. A **partial wake lock** — keeps the CPU running for the media/network threads.

```xml
<service android:name=".CallForegroundService" android:foregroundServiceType="microphone" android:exported="false" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />               <!-- API 28+ -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />    <!-- API 34+ -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

```kotlin
// in the service's onStartCommand, after startForeground(...):
wakeLock = getSystemService(PowerManager::class.java)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yourapp:voice-call").apply { acquire() }
// release it in onDestroy()
```

Start the service **before** `call.start()` and stop it when the call ends. The SDK is headless and
deliberately **doesn't impose** a service (it has no notification UI) — that's the consumer app's call. A
foreground-only call works fine without any of this. **Both voice examples ship a complete, working
`CallForegroundService`** ([compose](../examples/voice/compose/) · [views](../examples/voice/views/)) — copy it.

## R8 / ProGuard

**No keep rules needed in your app.** The `ai.poly:voice` AAR ships **consumer R8 rules** (applied
automatically) that keep `org.webrtc.**` — libwebrtc is reached by name over JNI from native code, which
R8 can't see, so stripping it would crash the audio engine. If you maintain a global `proguard-rules.pro`
that's unusually aggressive, the shipped consumer rules still protect the SDK; you don't add anything.

> **Custom / self-hosted bridge.** The `webrtc-bridge` host is derived from your `Environment`. If you
> run a dev or self-hosted bridge, set `VoiceOptions.signalingHost` (no scheme, e.g.
> `"webrtc-bridge.example.com"`) — it's **required** with `Environment.Custom`, since the bridge
> host can't be derived from a custom messaging endpoint.

---

**Runnable examples** — a one-screen tap-to-call demo with the audio-output picker, in both toolkits:
[`examples/voice/compose`](../examples/voice/compose/) · [`examples/voice/views`](../examples/voice/views/).
Drop your connector token + WebRTC token into the `PolyMessaging.initialize(...)` call in the
example's `Application` class and run.
