# Examples

Each example is a runnable app that builds on the previous one. Every rung ships a Jetpack
**Compose** variant and a classic Android **Views** (XML + view binding) variant — Views L*N*
covers the same features as Compose L*N*, only the UI binding differs. The ladder below is for chat;
the standalone voice examples are linked after it.

| Level | What it covers | Compose | Views |
|---|---|---|---|
| **01-Hello** | `initialize`, `chat()`, render `session.messages`, `send()` | [`chat/compose/01-hello/`](chat/compose/01-hello/) | [`chat/views/01-hello/`](chat/views/01-hello/) |
| **02-Standard** | typing indicator, connection banner, suggestion pills, delivery state, end + start new chat, failure retry | [`chat/compose/02-standard/`](chat/compose/02-standard/) | [`chat/views/02-standard/`](chat/views/02-standard/) |
| **03-RichContent** | image attachments, URL cards, `tel:` call actions, Markdown/link parsing, retryable image loading | [`chat/compose/03-richcontent/`](chat/compose/03-richcontent/) | [`chat/views/03-richcontent/`](chat/views/03-richcontent/) |
| **04-Resilience** | offline banner (connectivity-aware), loading skeleton, terminal-error screen with manual retry | [`chat/compose/04-resilience/`](chat/compose/04-resilience/) | [`chat/views/04-resilience/`](chat/views/04-resilience/) |
| **05-Handoff** | live-agent handoff: raw event side effects, handoff status pills, live-agent bubble styling | [`chat/compose/05-handoff/`](chat/compose/05-handoff/) | [`chat/views/05-handoff/`](chat/views/05-handoff/) |
| **06-FullReference** | production-style Resume + Start-New flows (no developer diagnostics) | [`chat/compose/06-fullreference/`](chat/compose/06-fullreference/) | [`chat/views/06-fullreference/`](chat/views/06-fullreference/) |
| **07-Playground** | streaming toggle, raw transport diagnostic tap, event log, runtime `Configuration` knobs (via `DevSettings`), protocol simulations | [`chat/compose/07-playground/`](chat/compose/07-playground/) | [`chat/views/07-playground/`](chat/views/07-playground/) |

The **03**, **06**, and **07** examples also include a foreground-only new-message notification
banner — a local notification when the agent replies while the app is open, in a
`NewMessageNotifier` component. There's deliberately no background path; see the root README's
[In-app new-message alerts (local-only workaround)](../README.md#in-app-new-message-alerts-local-only-workaround).

## Running

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (latest stable) — it bundles
the right JDK and Android SDK — plus **one running device or emulator** (in Android Studio:
*Device Manager → Create/▶ a virtual device*, e.g. a Pixel with API 34+), plus your agent credentials
(see below).

**Fastest path (recommended):** open this repository in Android Studio, wait for the Gradle sync to
finish, pick an example module (e.g. `examples/chat/compose/01-hello`) in the run-configuration dropdown,
and press **▶ Run**. The app builds, installs, launches, and connects to your configured agent.

**From the command line** (the modules are registered in the root
[`settings.gradle.kts`](../settings.gradle.kts)):

```bash
./gradlew :examples:chat:compose:01-hello:installDebug
# or :examples:chat:views:01-hello, :examples:voice:compose, …
```

Then open the installed app from the launcher (Compose examples launch `MainActivity`; Views examples
launch `ChatActivity` (01-05) or `RootActivity` (06-07)). The Android Studio ▶ Run button does this
launch step for you.

Views 06-07 use a single `RootActivity` container that owns one `ChatSession` and swaps the
connect / loading / chat / error screens (which is why they launch `RootActivity`, not
`ChatActivity`).

Set your API key where each example calls `PolyMessaging.initialize(...)` — in its `Application` class
(`<Level>Application`, e.g. `HelloApplication`, `RichContentApplication`), currently `"YOUR_API_KEY"`. The
environment defaults to `Environment.US`; add `Environment.cluster("dev")` / a `hostIdentifier` only if your
agent is on a non-default cluster. Voice examples also require `webrtcToken` in `VoiceApplication`.
See the root README's [Install](../README.md#install) and [Quick start](../README.md#quick-start).

Runnable voice demos: [`voice/compose`](voice/compose/) · [`voice/views`](voice/views/). See the
[full voice guide](../polyvoice/README.md) for permissions, audio routing, background calls, and R8.
