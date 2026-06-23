# Examples

Each example is a runnable app that builds on the previous one. Every rung ships a Jetpack
**Compose** variant and a classic Android **Views** (XML + view binding) variant — Views L*N*
covers the same features as Compose L*N*, only the UI binding differs.

| Level | What it covers | Compose | Views |
|---|---|---|---|
| **01-Hello** | `initialize`, `chat()`, render `session.messages`, `send()` | [`compose/01-hello/`](compose/01-hello/) | [`views/01-hello/`](views/01-hello/) |
| **02-Standard** | typing indicator, connection banner, suggestion pills, delivery state, end + start new chat, failure retry | [`compose/02-standard/`](compose/02-standard/) | [`views/02-standard/`](views/02-standard/) |
| **03-RichContent** | image attachments, URL cards, `tel:` call actions, Markdown/link parsing, retryable image loading | [`compose/03-richcontent/`](compose/03-richcontent/) | [`views/03-richcontent/`](views/03-richcontent/) |
| **04-Resilience** | offline banner (connectivity-aware), loading skeleton, terminal-error screen with manual retry | [`compose/04-resilience/`](compose/04-resilience/) | [`views/04-resilience/`](views/04-resilience/) |
| **05-Handoff** | live-agent handoff: raw event side effects, handoff status pills, live-agent bubble styling | [`compose/05-handoff/`](compose/05-handoff/) | [`views/05-handoff/`](views/05-handoff/) |
| **06-FullReference** | production-style Resume + Start-New flows (no developer diagnostics) | [`compose/06-fullreference/`](compose/06-fullreference/) | [`views/06-fullreference/`](views/06-fullreference/) |
| **07-Playground** | streaming toggle, raw transport diagnostic tap, event log, runtime `Configuration` knobs (via `DevSettings`), protocol simulations | [`compose/07-playground/`](compose/07-playground/) | [`views/07-playground/`](views/07-playground/) |

The **03**, **06**, and **07** examples also include a foreground-only new-message notification
banner — a local notification when the agent replies while the app is open, in a
`NewMessageNotifier` component. There's deliberately no background path; see the root README's
[In-app new-message alerts (local-only workaround)](../README.md#in-app-new-message-alerts-local-only-workaround).

## Running

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (latest stable) — it bundles
the right JDK and Android SDK — plus **one running device or emulator** (in Android Studio:
*Device Manager → Create/▶ a virtual device*, e.g. a Pixel with API 34+). No API key or other setup
is needed: every example ships pre-wired to a live demo agent (see below).

**Fastest path (recommended):** open this repository in Android Studio, wait for the Gradle sync to
finish, pick an example module (e.g. `examples/compose/01-hello`) in the run-configuration dropdown,
and press **▶ Run**. The app builds, installs, launches, and connects to the demo agent — start typing.

**From the command line** (the modules are registered in the root
[`settings.gradle.kts`](../settings.gradle.kts)):

```bash
./gradlew :examples:compose:01-hello:installDebug   # or :examples:views:01-hello, :examples:compose:02-standard, …
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
agent is on a non-default cluster. See the root README's [Install](../README.md#install) and [Quick start](../README.md#quick-start).
