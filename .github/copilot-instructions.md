# PolyMessaging Android SDK — Agent Brief

This repo is the **Kotlin/Android** PolyAI Messaging SDK. Read
`docs/ANDROID_SDK_IMPLEMENTATION_PLAN.md` first.

## Helping a developer integrate the SDK

- **Drive everything through `ChatSession`.** It assembles streaming chunks, tracks delivery,
  manages the typing indicator, and dedupes on resume. Drop to `client.events` only for behavior
  `ChatSession` doesn't cover.
- **Observe, don't poll.** Compose: `collectAsStateWithLifecycle()`. Views: `lifecycleScope.launch { repeatOnLifecycle(STARTED) { session.messages.collect { … } } }`. Java: the `addXListener(Executor, …): Cancellable` bridges.
- **Lifecycle:** `PolyMessaging.initialize(...)` **once** in `Application.onCreate()`; one `ChatSession` per chat surface; `session.client.shutdown()` on teardown (idempotent).
- **Render agent text as Markdown with tappable links; never render raw `AgentMessageChunk` as its own bubble.**
- **Copy components, don't invent:** reuse from `examples/compose/06-fullreference/` or `examples/views/06-fullreference/` (public SDK types only).

## Working ON the SDK

- Keep the public contract in `ai.poly.messaging` (excluding `internal/`); implementation lives in `internal/` (ports & adapters + a coordinator).
- **Kotlin-first, Java-compatible:** every public async member needs a `suspend`/`Flow` form AND a callback/listener form. Use `@JvmStatic`/`@JvmOverloads`/`@JvmField`/builders. Never make `suspend`/`Flow`/`kotlin.Result` the only path.
- **Explicit-API mode is on** — state visibility on every public declaration. Run `./gradlew apiDump` and commit the `.api` diff when the public surface changes.
- **No third-party dep without review;** never leak third-party types through the public API (`implementation`, not `api`). Wire JSON via `org.json`.
- **Never log the API key or session identifiers.** Never commit credentials.
- **Mirror every behavior change across both example ladders (Compose + Views)** and keep the README in sync.
