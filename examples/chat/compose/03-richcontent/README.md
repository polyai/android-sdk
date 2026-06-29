# 03-RichContent (Compose)

Adds image attachments, URL link cards, `tel:` call actions, and Markdown rendering on top of [`02-Standard`](../02-standard/).

Setup, scaffolding, and everything inherited from 02 (typing, suggestions, delivery, reconnect, end + start-new) are unchanged — read [`02-Standard`](../02-standard/) first. This README covers only what 03 adds.

## Run it

Open the repo in Android Studio and run the `:examples:compose:03-richcontent` module, or from the repo root:

```bash
./gradlew :examples:compose:03-richcontent:installDebug
```

Then launch the installed app (the launcher `MainActivity`, which hosts `ChatScreen`). Set your API key in `src/main/kotlin/ai/poly/examples/richcontent/compose/RichContentApplication.kt` (currently `"YOUR_API_KEY"`); the committed default targets `Environment.US`.

## What this example demonstrates

- Image attachments — `AgentMessage.attachments` filtered by `contentType == AttachmentContentType.IMAGE`
- URL link cards — same `attachments` array filtered by `.URL`
- `tel:` call buttons — `AgentMessage.callActions`
- Markdown **and** a small HTML subset — `AgentMessage.text` (Markdown, plus tags like `<br>` normalized to match the web chat widget)
- Forward-compat: drop `.UNKNOWN` content types silently
- New-message banners (local workaround) — a local notification when the agent replies while the chat isn't on screen (default `WHEN_BACKGROUNDED`; configurable via `NotificationPolicy`) (`components/NewMessageNotifier.kt`)

**The SDK decodes the data; it never fetches bytes or dials phones.** You own image loading, caching, retry, link-opening, and the `tel:` URI. This example does all of that with Coil + `Intent`s.

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide).

## How it works

Each subsection leads with **the SDK data** (the actual API), then shows **how it's wired into the agent bubble** (`components/MessageBubbleView.kt`, the `ChatMessage.Agent` branch).

### Render image attachments — `components/AttachmentCarousel.kt`

What the SDK gives you:

```kotlin
m.attachments   // List<Attachment> — agent messages only. Each carries:
                //   contentType      — AttachmentContentType.IMAGE / .URL / .UNKNOWN (drop .UNKNOWN)
                //   contentUrl       — URI? — where the asset lives
                //   previewImageUrl  — URI? — smaller preview (often null for raw images)
                //   title / callToActionText — String?
                // The SDK never fetches bytes — you load the URL with Coil.
```

In the agent branch — render only `.IMAGE` attachments as a horizontal carousel. Each card loads `previewImageUrl ?: contentUrl` and opens `contentUrl` on tap:

```kotlin
val images = m.attachments.filter { it.contentType == AttachmentContentType.IMAGE }
if (images.isNotEmpty()) {
    AttachmentCarousel(images)
}
```

`AttachmentCarousel` is a `Row(Modifier.horizontalScroll(...))` of 220×140 cards. Image loading goes through `RetryableAsyncImage` (`components/RetryableAsyncImage.kt`) — a thin wrapper over Coil's `SubcomposeAsyncImage` that adds tap-to-retry plus a 5-second auto-retry, keyed on a `loadId` so each retry forces a fresh fetch past the negative cache.

**Under the hood:** the SDK decodes the agent's `attachments` array and groups them onto the same `AgentMessage` as the text. No background fetch happens — you load `contentUrl` / `previewImageUrl` yourself.

*See [Integration guide › Attachments, link cards & call buttons](../../../../README.md#attachments-link-cards--call-buttons).*

### Render URL link cards — `components/UrlCard.kt`

Same `attachments`, filtered by `.URL`:

```kotlin
att.contentUrl        // URI? — destination link
att.previewImageUrl   // URI? — preview image
att.title             // String? — card headline
att.callToActionText  // String? — button label, e.g. "Read more"
```

In the agent branch — a horizontal carousel of `UrlCard`s (260-wide), mirroring the image row:

```kotlin
val urls = m.attachments.filter { it.contentType == AttachmentContentType.URL }
if (urls.isNotEmpty()) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        urls.forEach { UrlCard(it) }
    }
}
```

`UrlCard` shows the preview image (when present), title, and CTA, and opens `contentUrl` with `Intent(ACTION_VIEW)` on tap.

**Under the hood:** same decoded `Attachment` data — the SDK hands you the URL + preview + title, and leaves the card layout and link-opening entirely to your code. The example puts `clickable(enabled = contentUrl != null)` on the whole card (rather than a link inside it) so the tap target covers the full card and you can intercept (e.g. route in-app) before launching the `Intent`.

*See [Integration guide › Attachments, link cards & call buttons](../../../../README.md#attachments-link-cards--call-buttons).*

### Render `tel:` call buttons — `components/CallActionButton.kt`

```kotlin
m.callActions   // List<ChatCallAction> — agent messages only. Each:
                //   title          — String — button label, e.g. "Call now"
                //   contactNumber  — String — may be display-formatted ("+1 (555) 123-4567")
                // The SDK never dials — you build the tel: URI and open it.
```

In the agent branch:

```kotlin
if (m.callActions.isNotEmpty()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.callActions.forEach { CallActionButton(it) }
    }
}
```

`CallActionButton` sanitizes the number (digits + leading `+`), builds `tel:<digits>`, and hands it to the dialer via `Intent(ACTION_DIAL)` (no `CALL_PHONE` permission — the dialer pre-fills).

*See [Integration guide › Attachments, link cards & call buttons](../../../../README.md#attachments-link-cards--call-buttons).*

### Render Markdown — `components/RichText.kt`

```kotlin
m.text   // String — the agent's text, raw. Usually Markdown (**bold**, *italic*, `code`,
         // [links](url)) — but it can also carry a small subset of HTML (most often <br>),
         // because the backend serves the SAME message to the web chat widget. The SDK does
         // not strip or convert it. Grows in place while streaming and can briefly hold
         // half-open Markdown (e.g. a trailing **) — the parser tolerates that.
```

Compose has no built-in Markdown renderer, so `RichText` does the work itself: it first runs `normalizeAgentHtml` (the same DOMPurify allow-list the web widget uses — `<br>`→newline, `<b>/<strong>`→`**`, `<a href>`→`[text](url)`, lists→bullets, drop other tags, decode entities), then parses Markdown `[text](url)` + bare `https://…` URLs and folds `**bold**` / `*italic*` / `` `code` `` into a Compose `AnnotatedString` with `LinkAnnotation`s (blue + underlined, opened by the system handler):

```kotlin
// Inside the agent bubble:
if (m.text.isNotEmpty()) {
    RichText(
        text = m.text,
        modifier = Modifier
            .widthIn(max = maxBubbleWidth)
            .clip(RoundedCornerShape(18.dp))
            .background(SystemGray5)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
```

**Under the hood:** the SDK passes the agent's text through untouched. The parser tolerates half-open Markdown (an unclosed `**`) by leaving it literal until the next streaming chunk closes it. Links whose URL fails to parse (e.g. one containing a space) fall back to plain text.

> **Why normalize HTML?** Agent content is authored once and rendered on both web and mobile. The web widget pipes it through `marked` + DOMPurify, so a reply can reach mobile with literal `<br>` tags. `normalizeAgentHtml` mirrors that allow-list so the bubble matches the web. The minimal [`01-Hello`](../01-hello/) / [`02-Standard`](../02-standard/) examples skip this (plain `Text`), so they show `<br>` raw.

*See [Integration guide › Rich text & links](../../../../README.md#rich-text--links).*

### Bubble layout — compose everything

The agent bubble stacks: **agent name → rich text → image carousel → URL card carousel → call actions → suggestions**. `.UNKNOWN` content types are dropped silently for forward-compat (the SDK's slot for content kinds it doesn't model yet).

```kotlin
is ChatMessage.Agent -> {
    val m = message.message
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (m.text.isNotEmpty()) { /* RichText render */ }

        val images = m.attachments.filter { it.contentType == AttachmentContentType.IMAGE }
        if (images.isNotEmpty()) { AttachmentCarousel(images) }

        val urls = m.attachments.filter { it.contentType == AttachmentContentType.URL }
        if (urls.isNotEmpty()) { /* horizontalScroll Row of UrlCards */ }

        if (m.callActions.isNotEmpty()) { /* Column of CallActionButtons */ }
    }
}
```

**Under the hood:** the SDK delivers text, attachments, and call actions on one assembled `AgentMessage` — no separate events to coordinate.

### In-app new-message banners (local workaround) — `components/NewMessageNotifier.kt`

Pop a local-notification banner when the agent replies. ⚠️ **Local-notification workaround, not remote push** — there's no FCM here, so once the OS kills the process nothing arrives.

**When it fires is controlled by a `NotificationPolicy` enum** (flip it at the call site):

| `NotificationPolicy` | Behaviour |
| --- | --- |
| `WHEN_BACKGROUNDED` *(default)* | Banner **only while the chat isn't on screen** — nothing pops while you're reading the conversation. |
| `ALWAYS` | Banner on every new agent message, even with the chat open. |
| `NEVER` | Off. |

> This is a deliberate example default. `WHEN_BACKGROUNDED` matches how real chat apps behave — no banner for a message you're already looking at.

The SDK signal:

```kotlin
session.client.events   // SharedFlow<MessagingEvent> — completed-message events (full text + stable messageId)
```

In the chat screen — one composable call:

```kotlin
NewMessageNotifier(session, NotificationPolicy.WHEN_BACKGROUNDED)   // components/NewMessageNotifier.kt — renders no UI
```

It requests `POST_NOTIFICATIONS` (API 33+), creates a notification channel, and observes `client.events` for completed `AgentMessage` / `LiveAgentMessage` events (full text + stable `messageId`, not chunks). Collection is gated on the **`CREATED`** lifecycle (not `STARTED`) so a reply that lands while the chat is backgrounded can still raise a banner; on each event it checks whether the chat is currently on screen (`lifecycle.currentState.isAtLeast(STARTED)`) and — for `WHEN_BACKGROUNDED` — stays quiet if it is. Already-shown ids are skipped (persisted in `SharedPreferences`, so resume/relaunch replays don't re-fire), and a `NotificationCompat` banner is posted otherwise. On Android 13+ (API 33) the banner only appears if the user grants the `POST_NOTIFICATIONS` prompt — if it's denied, `post()` silently no-ops and no banner shows.

*See [Integration guide › In-app new-message alerts (local-only workaround)](../../../../README.md#in-app-new-message-alerts-local-only-workaround).*

## What this example skips

- offline detection, loading skeleton, full-screen terminal error → [`04-Resilience/`](../04-resilience/)
- live agent handoff → [`05-Handoff/`](../05-handoff/)

---

- **Views counterpart:** [`../../views/03-richcontent/`](../../views/03-richcontent/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
