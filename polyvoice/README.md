# PolyAI Voice (`ai.poly:voice`)

Live, two-way WebRTC voice calls to a PolyAI agent — the companion artifact to
[`ai.poly:messaging`](../README.md). It ships **separately** so chat-only apps stay lean (the call path
pulls in the native libwebrtc audio engine), and it **reuses the messaging `Configuration`** plus the
same `CallState` / `PolyError.Voice` / `Environment` vocabulary — no new concepts.

> New here? The [root README](../README.md#voice-calling-aipolyvoice) has the 60-second overview; this is
> the complete guide.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.poly:messaging:0.9.0")
    implementation("ai.poly:voice:0.9.0")
}
```

## Quickstart

The call needs the **`RECORD_AUDIO`** runtime permission. The SDK declares it in its manifest, but —
like any dangerous permission — your app must request the grant from the user before starting a call.

```kotlin
import ai.poly.voice.PolyVoice

val call = PolyVoice.call(
    context,
    Configuration(apiKey = "YOUR_API_KEY"),            // connector token — from Agent Studio › Connector Settings
    VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"),   // WebRTC token — same place (see Credentials below)
)

// Observe the call lifecycle (Idle → Connecting → Connected → Ended / Failed).
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        call.state.collect { state ->
            when (state) {
                is CallState.Connected -> showInCallUi()
                is CallState.Failed -> showError(state.error)   // a PolyError.Voice
                is CallState.Ended -> dismissInCallUi()
                else -> Unit
            }
        }
    }
}

// After RECORD_AUDIO is granted:
lifecycleScope.launch { call.start() }   // suspends until the call is connecting; throws on setup failure

// In-call controls:
call.setMuted(true)   // mute the mic
call.end()            // hang up and release the mic
```

`CallState`, `PolyError.Voice.*`, `Configuration`, and `Environment` are the same types from
`ai.poly:messaging` — no new vocabulary. Java callers get `Executor` + `Callback<Unit>` overloads of
`start` / `end` / `setMuted`, mirroring the chat API.

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
| **WebRTC token** — `VoiceOptions.webrtcToken` | the **gateway auth token** for the media connection — a **distinct** token from the API key | **Yes** | the offer `authToken` + ICE-servers fetch |

```kotlin
PolyVoice.call(
    context,
    Configuration(apiKey = "YOUR_API_KEY"),          // X-Token — required
    VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"), // required — distinct from the API key
)
```

Both are **always required and always distinct**: the API key authenticates the *connector*, the WebRTC
token authenticates the *media gateway*. (The example apps set both.)

> The two tokens sit in different places on purpose: the **API key** authenticates the *connector* and
> is shared by chat and voice, so it lives on the shared `Configuration`; the **WebRTC token**
> authenticates the *voice gateway* only, so it's a required voice-side credential on `VoiceOptions`
> rather than dead weight on the chat config.

Two more values have sensible defaults, so **most apps don't set them** — but good to know:

- **`environment`** defaults to **`Environment.US`** (PolyAI's US cluster). Set `.UK` / `.EUW` — or
  `.cluster("…")` for a named cluster — only if your agent lives in another region.
- **`hostIdentifier`** (sent as `X-Host`) defaults to your **app's package name** (`applicationId`).
  Override it only if your connector is registered against a specific host in Agent Studio:
  `Configuration(apiKey = "…", hostIdentifier = "https://your-site.com")`.

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

> **Custom / self-hosted gateway.** The WebRTC gateway host is derived from your `Environment`. If you
> run a dev or self-hosted gateway, set `VoiceOptions.signalingHost` (no scheme, e.g.
> `"webrtc-gateway.example.com"`) — it's **required** with `Environment.Custom`, since the gateway
> host can't be derived from a custom messaging endpoint.

---

**Runnable examples** — a one-screen tap-to-call demo with the audio-output picker, in both toolkits:
[`examples/voice/compose`](../examples/voice/compose/) · [`examples/voice/views`](../examples/voice/views/).
Drop your connector token + WebRTC token into the `PolyVoice.call(...)` block and run.
