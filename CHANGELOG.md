# Changelog

All notable changes to the PolyMessaging Android SDK are documented here.
This project adheres to [Semantic Versioning](https://semver.org). While the SDK
is pre-1.0, breaking changes bump the **minor** version.

## [Unreleased]

### Changed
- **Voice calls now run over `webrtc-bridge`** instead of `webrtc-gateway` (MES-1658). The gateway
  path is gone, not deprecated — it no longer works.

  **No code change is required, and the published API is byte-identical** (`apiCheck` against the
  previous dump is clean): `PolyVoice.call`, `VoiceOptions`, `VoiceCall`, `CallState`, mute, audio
  routing and every error case are unchanged, and a call still links to the same messaging session.
  `start()` still returns with the call `Connecting`; observe `state` for `Connected` as before.

  What moved underneath: the call is provisioned with `POST /api/v1/call` (the WebRTC token becomes
  a Bearer credential), SDP travels over HTTPS non-trickle, the **bridge** mints the call id (so
  provision now runs before the messaging link), a second negotiation starts the agent's audio, a
  control socket carries barge-in and re-pull, and `DELETE /api/v1/call/{id}` replaces the close
  frame.
- `VoiceOptions.signalingHost` now overrides the **bridge** host rather than the gateway host. Same
  parameter, same meaning ("point voice at this deployment"), new target.
- `IceServer.DEFAULT` is now Cloudflare STUN (`stun.cloudflare.com`). Media terminates at
  Cloudflare's edge on this path, so the old `stun.l.google.com` default went out with the gateway.

### Removed
- The gateway call pipeline: `CallCoordinator`, `SignalingProtocol`/`SignalMessage`, the gateway
  host resolution in `VoiceHosts`, and `VoiceRestApi.fetchIceServers` (ICE servers now arrive in the
  bridge's provision response). All internal.
- Trickle-ICE members of the internal `WebRtcPeer` port (`addRemoteIceCandidate`, `PeerEvent.LocalIce`),
  replaced by `awaitIceGathering`, `localDescriptionSdp`, `audioMid`, `acceptRemoteOffer` and
  `setRemoteAudioEnabled`.

## [0.9.0] - 2026-06-30

Adds the `ai.poly:voice` WebRTC voice-calling SDK (first publish) alongside `ai.poly:messaging:0.9.0`.

### Added
- **Voice calling (`ai.poly:voice`):** live, two-way WebRTC voice calls to a PolyAI agent — call
  lifecycle (`CallState`), mute, accessory-aware audio-output routing with mid-call switching
  (speaker / earpiece / wired / Bluetooth), audio-focus interruption handling, signaling reconnect,
  and a foreground-service recipe for background calls. Separate artifact; reuses the messaging
  `Configuration`. See [`polyvoice/README.md`](polyvoice/README.md).
- **`PolyError.Voice`** error cases (e.g. `Disconnected`, `Interrupted`) on the shared error type.

## [0.8.0] - 2026-06-16

First public release on Maven Central (`ai.poly:messaging:0.8.0`).

### Fixed
- **Send delivery / retry:** user-message sends now use an explicit manual-retry model instead of
  auto-resending on reconnect. A message is put on the wire only when the socket is open; if it
  can't be confirmed (offline at send time, or the connection drops while it's in flight) it settles
  on `Delivery.FAILED` for the consumer to retry. This removes three reconnect-window defects seen on
  device: the same message being delivered multiple times when the socket recovered; offline sends
  burning the reconnect budget and tripping the terminal breaker (so the connection couldn't recover
  when the network returned); and messages that were actually delivered+echoed being marked
  `FAILED` by a wall-clock timer that raced the post-reconnect echo. Delivery is now exactly-once by
  construction — one retry = one send.
- **R8/minify:** consumers with `minifyEnabled` failed to build — `androidx.security-crypto`
  (the SDK's session-store dependency) pulls Google Tink, whose bytecode references ErrorProne's
  compile-only annotations, and R8 fails on the dangling references. The AAR's shipped
  `consumer-rules.pro` now carries the four `-dontwarn com.google.errorprone.annotations.*`
  suppressions; a fully minified + resource-shrunk consumer build was verified live on device.
- **Java interop:** `addEventListener` / `addConnectionStatusListener` / `addSessionStateListener`
  now guarantee the underlying flow subscription is live when the call returns
  (`CoroutineStart.UNDISPATCHED`). Previously the collector attached asynchronously, so an event
  emitted right after registration could be silently lost (`events` has replay = 0) — observed
  live by the consumer-project exerciser; Java callers have no `onSubscription` to handshake with.
- **Java interop:** the `StateFlow` property getters on `ChatSession` (and `PolyCall.state`)
  now carry `...Flow` JVM names (`getMessagesFlow()`, `getConnectionFlow()`, `isReadyFlow()`, …)
  so the snapshot bridges (`getMessages()`, `getConnection()`, `isReady()`, …) are callable from
  Java — previously the two differed only by return type, which javac cannot disambiguate, making
  every session-state read ambiguous from Java. Kotlin call sites are unaffected (property access
  syntax); Kotlin consumers compiled against an earlier unpublished AAR must recompile.

Initial release of the PolyAI Messaging Android SDK. Kotlin-first, Java-compatible, Android-native — fully managed
chat over the PolyAI Messaging API: token auth, session create/resume, WebSocket
lifecycle, heartbeat, reconnection with cursor-based replay, streaming chunk
reassembly, optimistic send with delivery tracking, and live-agent handoff, exposed
through a `StateFlow`-backed bindable `ChatSession` for Jetpack Compose and Android
Views. minSdk 24; Java consumers supported.

The example apps (compose + views, rungs 01–07) demonstrate retry UX: a failed message shows
"Tap to retry", and tapping either the retry control or the failed bubble itself drops the failed
draft (`session.removeMessage(draftId)`) and re-sends, so the retry replaces the bubble rather than
duplicating it.
