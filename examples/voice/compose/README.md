# Voice — tap to call (Compose)

The smallest thing that makes a **WebRTC voice call** to a PolyAI agent with `ai.poly:voice`: one
screen, one button, plus mute and an audio-output picker — all in one
[`MainActivity.kt`](src/main/kotlin/ai/poly/examples/voice/compose/MainActivity.kt) (~200 lines).

## Run it

First, set your connector in the `PolyVoice.call(...)` block at the top of `MainActivity.kt` — your **API
key** + **WebRTC token** from Agent Studio (see [Use your own agent](#use-your-own-agent)). Then open the
repo in Android Studio and run the **voice/compose** module, or from the repo root:

```bash
./gradlew :examples:voice:compose:installDebug
```

`installDebug` only installs the APK — tap the launcher icon, or launch it directly:

```bash
adb shell am start -n ai.poly.examples.voice.compose/.MainActivity
```

Run on a **physical device** (Android 7.0+ / API 24). PolyAI's hosts are public, so any normal internet
connection works.

> A stock emulator often ships with a **broken DNS resolver** (can't resolve any hostname) — if you see
> an `Unable to resolve host` error for a `*.poly.ai` address, that's the emulator, not the SDK. Restart
> it with `emulator -avd <name> -dns-server 8.8.8.8`, or just use a real device.

## What this example demonstrates

- `PolyVoice.call(context, config, options)` → a `VoiceCall`
- Observing `call.state: StateFlow<CallState>` (`Idle → Connecting → Connected → Ended / Failed`)
- The `RECORD_AUDIO` runtime-permission flow before `start()`
- In-call controls: `setMuted(...)` / `end()`, and `close()` on teardown
- The **audio-output picker** — `call.audio` + `setAudioDevice(...)` to switch speaker / earpiece /
  headset / Bluetooth mid-call
- A **microphone foreground service** (`CallForegroundService`) + wake lock so the call **survives the
  app being backgrounded** — without it Android cuts background mic + throttles the media threads and the
  call drops after ~15s

The SDK surface behind each pattern is in the
[PolyAI Voice guide](../../../polyvoice/README.md); this example shows it as one concrete file.

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

Each subsection leads with **the SDK call** (the actual API), then shows **how it's wired into a
`@Composable`**.

### Build the call — `PolyVoice.call(...)`

A call is self-contained: it creates its own session, independent of any chat. Hold it with `remember`
and close it when the screen leaves the composition.

```kotlin
val call = remember {
    PolyVoice.call(
        context = context,
        config = Configuration(
            apiKey = "YOUR_API_KEY", // connector token, sent as X-Token
        ),
        options = VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"), // the connector's WebRTC token (distinct from apiKey)
    )
}
// (the real teardown also stops the foreground service — see "Survive the background" below)
DisposableEffect(Unit) { onDispose { call.close() } }
```

### Observe the call state — `call.state`

`state` is a `StateFlow<CallState>` — collect it to drive the UI.

```kotlin
val state by call.state.collectAsStateWithLifecycle()
// CallState.Idle → Connecting → Connected → Ended / Failed(error: PolyError.Voice)
```

### Grant the mic, then start — `call.start()`

`start()` needs the `RECORD_AUDIO` runtime permission; request it first, then start from a coroutine.

```kotlin
val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) scope.launch { runCatching { call.start() } }
}
// on tap:
if (granted) scope.launch { runCatching { call.start() } } else requestMic.launch(Manifest.permission.RECORD_AUDIO)
```

> In the actual code these go through a `startCall()` helper that *also* starts the foreground service —
> see [Survive the background](#survive-the-background--callforegroundservice). It's shown plain here to
> keep the permission flow clear.

### Mute and hang up — `call.setMuted(...)` / `call.end()`

```kotlin
scope.launch { call.setMuted(true) }  // mute the mic
scope.launch { call.end() }           // hang up and release the mic
```

### Audio output picker — `call.audio` + `call.setAudioDevice(...)`

By default the call **follows the connected accessory** (headset/Bluetooth), falling back to the
loudspeaker. `call.audio` is a `StateFlow<AudioState>` — a snapshot of the outputs available now and the
active one. Render an **Auto** chip plus one per device: tapping a device pins it; **Auto** returns to
automatic (`setAudioDevice(null)`). The selection confirms **asynchronously** via `call.audio` (Bluetooth
can take a few seconds), so the highlight follows the flow, not the tap.

```kotlin
val audio by call.audio.collectAsStateWithLifecycle()

FilterChip(selected = autoOutput, label = { Text("Auto") },
    onClick = { autoOutput = true; scope.launch { call.setAudioDevice(null) } })   // follow the accessory/system
audio.availableDevices.forEach { device ->
    FilterChip(
        selected = !autoOutput && device == audio.selectedDevice,
        onClick = { autoOutput = false; scope.launch { call.setAudioDevice(device) } }, // pin this device
        label = { Text(labelFor(device)) }, // device.type: SPEAKER_PHONE / EARPIECE / WIRED_HEADSET / BLUETOOTH
    )
}
```

When nothing's plugged in it falls back to the **loudspeaker** (`VoiceOptions(speakerphone = false)` →
earpiece). Bluetooth devices only appear because this example declares **`BLUETOOTH_CONNECT`** in its
[manifest](src/main/AndroidManifest.xml) — the SDK doesn't add that dangerous permission for you.

### Survive the background — `CallForegroundService`

The SDK won't end a call when you background the app, but Android cuts background mic + throttles the media
threads, so without help the call drops ~15s in. This example starts a microphone foreground service (+ a
wake lock — see [`CallForegroundService.kt`](src/main/kotlin/ai/poly/examples/voice/compose/CallForegroundService.kt))
**before** `start()`, and stops it when the call ends:

```kotlin
fun startCall() {
    CallForegroundService.start(context)              // mic FGS + wake lock keep the call alive backgrounded
    scope.launch { runCatching { call.start() } }
}

// stop it when the call leaves an active state, and on teardown:
LaunchedEffect(state) {
    if (state is CallState.Ended || state is CallState.Failed) CallForegroundService.stop(context)
}
DisposableEffect(Unit) { onDispose { CallForegroundService.stop(context); call.close() } }
```

The required manifest entries (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`, and the `<service android:foregroundServiceType="microphone">`) are in the
[manifest](src/main/AndroidManifest.xml).

## Use your own agent

You need **two credentials**, both on your agent in **[Agent Studio](https://studio.poly.ai) › Connector
Settings**: the **API key** (connector token) and the **WebRTC token**. Set them in the
`PolyVoice.call(...)` block at the top of `MainActivity.kt`:

```kotlin
PolyVoice.call(
    context = context,
    config = Configuration(
        apiKey = "YOUR_API_KEY",  // connector token, sent as X-Token — required
        // environment defaults to Environment.US — set .UK / .EUW / .cluster("…") only if your agent is elsewhere
        // hostIdentifier defaults to this app's package name (sent as X-Host) — override only if your
        // connector is registered against a specific host
    ),
    options = VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"), // gateway token; omit if one token does both
)
```

`environment` (US) and `hostIdentifier` (your app's package name) have sensible defaults, so most agents
need only the two tokens — add `environment` / `hostIdentifier` to the `Configuration` only if your agent
lives in another region or your connector is registered against a specific host.
