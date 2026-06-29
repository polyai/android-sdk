# 03-RichContent (Views)

Adds image attachments, URL link cards, `tel:` call actions, and Markdown rendering on top of [`02-Standard`](../02-standard/). The Views counterpart of [`examples/compose/03-richcontent/`](../../compose/03-richcontent/).

Setup, scaffolding, and everything inherited from 02 (typing, suggestions, delivery, reconnect, end + start-new) are unchanged — read [`02-Standard`](../02-standard/) first. This README covers only what 03 adds.

## Run it

Open the repo in Android Studio and run the module, or from the repo root:

```bash
./gradlew :examples:views:03-richcontent:installDebug
```

Then launch `ChatActivity`. Set your API key in `src/main/kotlin/ai/poly/examples/richcontent/views/RichContentApplication.kt` (the `API_KEY` constant is currently `"YOUR_API_KEY"`); the committed default is `Environment.US`.

## What this example demonstrates

- Image attachments — `AgentMessage.attachments` filtered by `contentType == AttachmentContentType.IMAGE`
- URL link cards — same `attachments` array filtered by `.URL`
- `tel:` call buttons — `AgentMessage.callActions`
- Markdown **+ a small HTML subset** (e.g. `<br>`) rendered in a **`TextView` + `LinkMovementMethod`** so links are tappable
- Forward-compat: drop `.UNKNOWN` content types silently
- New-message banners (local workaround) — a local notification when the agent replies while the chat isn't on screen (default `WHEN_BACKGROUNDED`; configurable via `NotificationPolicy`) (`NewMessageNotifier.kt`)

**The SDK decodes the data; it never fetches bytes or dials phones.** You own image loading, caching, retry, link-opening, and the `tel:` URI. This example does all of that with Coil + `Intent`s.

The SDK invariants behind each pattern are in the root README's [Integration guide](../../../../README.md#integration-guide).

## How it works

Each subsection leads with **the SDK data** (the actual API), then shows **how it's wired into `ChatAdapter.MessageHolder`** (the agent branch).

### Render image attachments — the cell's image carousel

What the SDK gives you:

```kotlin
m.attachments   // List<Attachment> — agent messages only. Each carries:
                //   contentType      — AttachmentContentType.IMAGE / .URL / .UNKNOWN (drop .UNKNOWN)
                //   contentUrl       — URI? — where the asset lives
                //   previewImageUrl  — URI? — smaller preview (often null for raw images)
                //   title / callToActionText — String?
                // The SDK never fetches bytes — you load the URL with Coil.
```

In the adapter — `MessageHolder` builds a horizontally scrolling carousel of 220×140 cards from the filtered list. Each card loads `previewImageUrl ?: contentUrl` and opens `contentUrl` on tap:

```kotlin
// MessageHolder.bind() — agent branch:
bindCarousel(b.imageCarousel, b.imageStack, m.attachments.filter { it.contentType == AttachmentContentType.IMAGE })
bindCarousel(b.urlCarousel, b.urlStack, m.attachments.filter { it.contentType == AttachmentContentType.URL })  // URL cards (next section)
bindCallActions(m.callActions)                                                                                 // tel: buttons (next-next)
// `.UNKNOWN` attachments are intentionally dropped — forward-compat
```

`bindCarousel` pins the `HorizontalScrollView` to ~0.85 of the row width (so cards show side-by-side and scroll) and adds one card per attachment; `makeCard` builds a `systemGray6`, corner-clipped `LinearLayout` whose preview is a `RetryableImageView` and whose whole body opens `contentUrl` via `Intent(ACTION_VIEW)`.

**Under the hood:** the SDK decodes the agent's `attachments` array and groups them onto the same `AgentMessage` as the text. No background fetch happens — `makeCard` hands each `previewImageUrl ?: contentUrl` to a `RetryableImageView` (`RetryableImageView.kt`), which loads via Coil with tap-to-retry + a 5-second auto-retry.

*See [Integration guide › Attachments, link cards & call buttons](../../../../README.md#attachments-link-cards--call-buttons).*

### Render URL link cards — same `attachments`, filtered by `.URL`

```kotlin
att.contentUrl        // URI? — destination link
att.previewImageUrl   // URI? — preview image
att.title             // String? — card headline
att.callToActionText  // String? — button label, e.g. "Read more"
```

URL attachments reuse the **same carousel + card** as the image row — there's no separate card type. You just pass the `.URL`-filtered list to a second carousel:

```kotlin
bindCarousel(b.urlCarousel, b.urlStack, m.attachments.filter { it.contentType == AttachmentContentType.URL })
```

**Under the hood:** same decoded `Attachment` data — the SDK hands you the URL + preview + title; the card lays the preview image on top of the title + CTA, and the whole card opens `contentUrl` on tap.

*See [Integration guide › Attachments, link cards & call buttons](../../../../README.md#attachments-link-cards--call-buttons).*

### Render `tel:` call buttons — `AgentMessage.callActions`

```kotlin
m.callActions   // List<ChatCallAction> — agent messages only. Each:
                //   title          — String — button label, e.g. "Call now"
                //   contactNumber  — String — may be display-formatted ("+1 (555) 123-4567")
                // The SDK never dials — you build the tel: URI and open it.
```

`MessageHolder.bindCallActions` adds a green button per action to a vertical container; `makeCallButton` sanitizes the number and opens the dialer:

```kotlin
val digits = action.contactNumber.filter { it.isDigit() || it == '+' }
context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
```

**Under the hood:** the SDK delivers `ChatCallAction` as decoded data — `title` + `contactNumber`. Dialling is your code: sanitise the number (digits + leading `+`), build `tel:<digits>`, and `ACTION_DIAL` opens the dialer pre-filled (no `CALL_PHONE` permission needed). The label falls back to the number when `title` is empty.

*See [Integration guide › Attachments, link cards & call buttons](../../../../README.md#attachments-link-cards--call-buttons).*

### Render Markdown (tappable links) — `AgentMessage.text`

```kotlin
m.text   // String — the agent's text, raw. Usually Markdown (**bold**, *italic*, `code`,
         // [links](url)) — but it can carry a small HTML subset (most often <br>) because the
         // backend serves the SAME message to the web chat widget. The SDK does not strip or
         // convert it, so this example normalizes that subset before parsing. Grows in place
         // while streaming and can briefly hold half-open Markdown — the parser tolerates that.
```

**Set a `LinkMovementMethod` on the bubble `TextView`** so the `URLSpan`s the parser emits become tappable (and open in the browser).

`RichTextSpans.kt` normalizes HTML + parses Markdown into a `Spanned`: it applies an HTML allow-list (`<br>`→newline, `<b>/<strong>`→`**`, `<a href>`→`[text](url)`, lists→bullets, drop other tags, decode entities), then folds `[text](url)` links (blue + underlined `URLSpan`) and `**bold**` / `*italic*` / `` `code` `` into a `SpannableStringBuilder`. Bare `https://…` URLs are **not** auto-linkified (only explicit Markdown links — the Compose counterpart's `RichText` adds bare-URL linkifying):

```kotlin
// MessageHolder.bind() — agent branch:
b.bubble.text = markdownSpanned(m.text, Palette.systemBlue)
b.bubble.movementMethod = LinkMovementMethod.getInstance()
b.bubble.visibility = if (m.text.isEmpty()) View.GONE else View.VISIBLE
```

**Under the hood:** the SDK passes the agent's text through untouched. Streaming chunks update `m.text` in place; the parser leaves half-open Markdown (an unclosed `**`) as literal text until the next chunk closes it.

> **Why normalize HTML?** Agent content is authored once and rendered on both web and mobile. The web widget pipes it through `marked` + DOMPurify, so a reply can reach mobile with literal `<br>` tags. `normalizeAgentHtml` mirrors that allow-list so the bubble matches the web. The minimal [`01-Hello`](../01-hello/) / [`02-Standard`](../02-standard/) examples skip this (plain text), so they show `<br>` raw.

*See [Integration guide › Rich text & links](../../../../README.md#rich-text--links).*

### Bubble layout — compose everything

`item_message.xml`'s outer column lays out **agent name → bubble row → image carousel → URL link-cards → call actions → delivery caption**, and `MessageHolder` hides each rich row when its data is empty (and hides the bubble itself when the agent text is empty). `.UNKNOWN` attachment types are dropped silently for forward-compat.

**Under the hood:** the SDK delivers text, attachments, and call actions on one assembled `AgentMessage` — no separate events to coordinate.

### In-app new-message banners (local workaround) — `NewMessageNotifier.kt`

Pop a local-notification banner when the agent replies. ⚠️ **Local-notification workaround, not remote push** — there's no FCM here, so once the OS kills the process nothing arrives.

**When it fires is controlled by a `NotificationPolicy` enum** (flip it at the call site in `ChatActivity`):

| `NotificationPolicy` | Behaviour |
| --- | --- |
| `WHEN_BACKGROUNDED` *(default)* | Banner **only while the chat isn't on screen** — nothing pops while you're reading the conversation. |
| `ALWAYS` | Banner on every new agent message, even with the chat open. |
| `NEVER` | Off (and the activity skips requesting `POST_NOTIFICATIONS`). |

> This is a deliberate example default. `WHEN_BACKGROUNDED` matches how real chat apps behave — no banner for a message you're already looking at.

The SDK signal:

```kotlin
session.client.events   // SharedFlow<MessagingEvent> — completed-message events (full text + stable messageId)
```

In the activity — own one and start it once the session exists (the activity requests `POST_NOTIFICATIONS` on API 33+ unless the policy is `NEVER`):

```kotlin
private val messageNotifier by lazy { NewMessageNotifier(this) }

// onCreate, after bind():
val notificationPolicy = NotificationPolicy.WHEN_BACKGROUNDED
messageNotifier.start(lifecycleScope, lifecycle, session, notificationPolicy)
```

`start(...)` creates a notification channel and runs `repeatOnLifecycle(CREATED) { session.client.events.collect { … } }` — gated on `CREATED` (not `STARTED`) so a reply that lands while the chat is backgrounded can still raise a banner. It maps completed `AgentMessage` / `LiveAgentMessage` events to a banner, checks whether the chat is currently on screen (`lifecycle.currentState.isAtLeast(STARTED)`) and — for `WHEN_BACKGROUNDED` — stays quiet if it is, and skips ids already in the persisted (`SharedPreferences`) store so resume/relaunch replays don't re-fire.

*See [Integration guide › In-app new-message alerts (local-only workaround)](../../../../README.md#in-app-new-message-alerts-local-only-workaround).*

## What this example skips

- offline detection, loading skeleton, full-screen terminal error → [`04-resilience/`](../04-resilience/)
- live agent handoff → [`05-handoff/`](../05-handoff/)

---

- **Compose counterpart:** [`examples/compose/03-richcontent/`](../../compose/03-richcontent/)
- **SDK reference:** root [README → Integration guide](../../../../README.md#integration-guide)
- **Install the package:** root [README → Install](../../../../README.md#install)
