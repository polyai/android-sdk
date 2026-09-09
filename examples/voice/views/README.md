# Voice — tap to call (Views)

The same WebRTC voice call as the Compose example, in classic Android **Views** with `viewBinding`:
one screen, start/end, mute, and an audio-output picker. The view logic lives in
[`CallActivity.kt`](src/main/kotlin/ai/poly/examples/voice/views/CallActivity.kt) over
[`activity_call.xml`](src/main/res/layout/activity_call.xml).

## Run it

First, set your connector in [`VoiceApplication.kt`](src/main/kotlin/ai/poly/examples/voice/views/VoiceApplication.kt)
— your **API key** + **WebRTC token** from Agent Studio (see [Use your own agent](#use-your-own-agent)).
Then open the repo in Android Studio and run the **voice/views** module, or from the repo root:

```bash
./gradlew :examples:voice:views:installDebug
```

```bash
adb shell am start -n ai.poly.examples.voice.views/.CallActivity
```

Run on a **physical device** (Android 7.0+ / API 24). PolyAI's hosts are public, so any normal internet
connection works. (A stock emulator often has a broken DNS resolver — if you see `Unable to resolve
host …`, that's the emulator: relaunch with `-dns-server 8.8.8.8`, or use a real device.)

## What this example demonstrates

- `PolyMessaging.initialize(...)` with both tokens once, at app launch (`VoiceApplication`), then
  `PolyVoice.call(context)` → a `VoiceCall` — no per-call-site config
- Collecting `call.state` lifecycle-aware with `repeatOnLifecycle` to drive the views
- The `RECORD_AUDIO` runtime-permission flow (`registerForActivityResult`) before `start()`
- In-call controls: `setMuted(...)` / `end()`, and `close()` in `onDestroy()`
- The **audio-output picker** — `call.audio` + `setAudioDevice(...)`, rebuilt as buttons on each snapshot
- A **microphone foreground service** (`CallForegroundService`) + wake lock so the call **survives the
  app being backgrounded** (Android otherwise cuts background mic + throttles the media threads ~15s in)

## Permissions

The SDK auto-merges `INTERNET` / `ACCESS_NETWORK_STATE` / `RECORD_AUDIO`; this example's
[manifest](src/main/AndroidManifest.xml) adds the rest so every feature works out of the box:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />                 <!-- requested at runtime -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />            <!-- Bluetooth output -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />           <!-- keep call alive backgrounded -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />           <!-- ongoing-call notification -->
```

## How it works

Each subsection leads with **the SDK call**, then shows **how it's wired into the `Activity`**.

### Initialize once, at app launch — `PolyMessaging.initialize(...)`

Both tokens are set a single time, in [`VoiceApplication.kt`](src/main/kotlin/ai/poly/examples/voice/views/VoiceApplication.kt):

```kotlin
class VoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(
            context = this,
            config = Configuration(
                apiKey = "YOUR_API_KEY", // connector token, sent as X-Token
                webrtcToken = "YOUR_WEBRTC_TOKEN", // the connector's WebRTC token (distinct from apiKey)
            ),
        )
    }
}
```

### Build the call — `PolyVoice.call(...)`

Nothing left to configure — `call(context)` alone reads what `initialize(...)` already stored:

```kotlin
private val call by lazy { PolyVoice.call(this) }

// (the real onDestroy also stops the foreground service — see "Survive the background" below)
override fun onDestroy() { super.onDestroy(); call.close() }
```

### Observe state + audio, lifecycle-aware — `call.state` / `call.audio`

Both are `StateFlow`s. **Combine** them and render from both together, inside `repeatOnLifecycle` so
collection follows the UI lifecycle. This matters: the audio device list is published while the call is
still `Connecting`, but the picker should only show once `Connected` — so the picker has to re-render
when *either* flow changes (the Views analog of Compose recomposing on either). Collecting them
separately would leave the picker hidden after connect.

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(call.state, call.audio) { state, audio -> state to audio }
            .distinctUntilChanged()
            .collect { (state, audio) ->
                render(state)                 // status text + button states
                renderDevices(state, audio)   // rebuild the output picker
            }
    }
}
```

### Grant the mic, then start — `call.start()`

```kotlin
private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) lifecycleScope.launch { runCatching { call.start() } }
}
// on tap:
if (granted) lifecycleScope.launch { runCatching { call.start() } }
else requestMic.launch(Manifest.permission.RECORD_AUDIO)
```

> In the actual code these go through a `startCall()` helper that *also* starts the foreground service —
> see [Survive the background](#survive-the-background--callforegroundservice). It's shown plain here to
> keep the permission flow clear.

### Mute and hang up — `call.setMuted(...)` / `call.end()`

```kotlin
lifecycleScope.launch { call.setMuted(muted) }
lifecycleScope.launch { call.end() }
```

### Audio output picker — `call.audio` + `call.setAudioDevice(...)`

By default the call **follows the connected accessory** (headset/Bluetooth), falling back to the
loudspeaker. `call.audio` snapshots `availableDevices` + `selectedDevice`; rebuild an **Auto** button plus
one per device — tapping a device pins it, **Auto** returns to automatic. The highlight follows the
**confirmed** route from the flow (Bluetooth can take a few seconds), not the tap.

```kotlin
private fun renderDevices(state: CallState, audio: AudioState) {
    val show = state is CallState.Connected && audio.availableDevices.isNotEmpty()
    binding.deviceRow.visibility = if (show) View.VISIBLE else View.GONE
    binding.deviceRow.removeAllViews()
    if (!show) return
    binding.deviceRow.addView(Button(this).apply {                    // "Auto" — follow the accessory/system
        text = "Auto"; alpha = if (autoOutput) 1f else 0.4f
        setOnClickListener { autoOutput = true; lifecycleScope.launch { call.setAudioDevice(null) } }
    })
    audio.availableDevices.forEach { device ->
        binding.deviceRow.addView(Button(this).apply {
            text = labelFor(device)                                  // device.type: SPEAKER_PHONE / EARPIECE / …
            alpha = if (!autoOutput && device == audio.selectedDevice) 1f else 0.4f
            setOnClickListener { autoOutput = false; lifecycleScope.launch { call.setAudioDevice(device) } }
        })
    }
}
```

When nothing's plugged in it falls back to the **loudspeaker** (`VoiceOptions(speakerphone = false)` →
earpiece). Bluetooth devices only appear because this example declares **`BLUETOOTH_CONNECT`** in its
[manifest](src/main/AndroidManifest.xml) — the SDK doesn't add that dangerous permission for you.

### Survive the background — `CallForegroundService`

The SDK won't end a call when you background the app, but Android cuts background mic + throttles the media
threads, so without help the call drops ~15s in. This example starts a microphone foreground service (+ a
wake lock — see [`CallForegroundService.kt`](src/main/kotlin/ai/poly/examples/voice/views/CallForegroundService.kt))
**before** `start()`, and stops it when the call ends:

```kotlin
private fun startCall() {
    CallForegroundService.start(this)                 // mic FGS + wake lock keep the call alive backgrounded
    lifecycleScope.launch { runCatching { call.start() } }
}

private fun render(state: CallState) {
    if (state is CallState.Ended || state is CallState.Failed) CallForegroundService.stop(this)
    // …
}
override fun onDestroy() { super.onDestroy(); CallForegroundService.stop(this); call.close() }
```

The required manifest entries (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`, and the `<service android:foregroundServiceType="microphone">`) are in the
[manifest](src/main/AndroidManifest.xml).

## Use your own agent

You need **two credentials**, both on your agent in **[Agent Studio](https://studio.poly.ai) › Connector
Settings**: the **API key** (connector token, `Configuration.apiKey` → `X-Token`) and the **WebRTC token**
(`Configuration.webrtcToken`). Set them once in
[`VoiceApplication.kt`](src/main/kotlin/ai/poly/examples/voice/views/VoiceApplication.kt)'s
`PolyMessaging.initialize(...)` call.

The other two values have sensible defaults, so most agents leave them alone: `environment` defaults to
**`Environment.US`** (add `environment = Environment.UK / .EUW / .cluster("…")` for another region), and
`hostIdentifier` (`X-Host`) defaults to this **app's package name** (override only if your connector is
registered against a specific host).
