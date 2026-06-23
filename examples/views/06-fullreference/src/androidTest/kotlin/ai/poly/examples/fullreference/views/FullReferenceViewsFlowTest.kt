// Copyright PolyAI Limited

package ai.poly.examples.fullreference.views

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented flow tests (black-box UI Automator) for the full-reference Views app,
 * driven against the live dev backend.
 *
 * UI Automator is an out-of-process driver that doesn't wait for an idle app, so every assertion
 * is a deadline-based poll.
 *
 * Reliable agent behaviors used here (verified via the SDK probe):
 *   - greeting carries 3 suggestion pills
 *   - "send me a link to google" -> reply with markdown links ([Google](...))
 *   - "end the convo"            -> server SESSION_END -> ended banner + Start New Conversation
 *
 * Notes:
 *   - [connectFresh] starts a brand-new conversation through the connect screen's own buttons
 *     ("Start New Chat" when a session is resumable, first-run "Start Chat" otherwise).
 *   - UiObject2.setText drives the `@id/composer` EditText and `@id/send` is clicked once it
 *     enables (screen_chat.xml ids).
 *   - Suggestion pills are plain TextViews inside `@id/suggestionsScroll` (ChatAdapter.makePill)
 *     with no per-pill identifier, so pills are queried as TextView children of that container
 *     (the pill text IS the suggestion).
 */
@RunWith(AndroidJUnit4::class)
class FullReferenceViewsFlowTest {

    // Pre-grant POST_NOTIFICATIONS so RootActivity.requestNotificationPermissionIfNeeded()'s
    // runtime dialog can never cover the chat.
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private lateinit var device: UiDevice

    /** Launch the app, then `connectFresh()`. */
    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launchApp()
        connectFresh()
    }

    private fun launchApp() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
        assertNotNull("launch intent for $APP_PACKAGE — is the app APK installed?", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app window appears",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), 10_000),
        )
    }

    // ----- Tests -----

    /**
     * Greeting renders, suggestion pills are present, and tapping one sends it as a user message
     * + draws a reply.
     */
    @Test
    fun greeting_and_suggestionTap() {
        assertTrue(
            "greeting renders after connect",
            textExists("Webchat", 25_000) || textExists("Welcome", 1_000),
        )

        val suggestion = firstSuggestionLabel(REPLY_TIMEOUT_MS)
            ?: return fail("greeting should surface suggestion pills")
        val before = textSnapshot()
        tapSuggestion(suggestion)
        assertTrue(
            "tapped suggestion is sent as a user message",
            textExists(suggestion, 12_000),
        )
        assertTrue(
            "agent replies to the tapped suggestion",
            waitForNewReply(before, sent = suggestion),
        )
    }

    /**
     * Asking for a link returns markdown; the bubble must render it formatted (link text shown via
     * ChatAdapter.markdownSpanned, raw markdown syntax gone).
     */
    @Test
    fun richText_linkFormatting() {
        val before = textSnapshot()
        send(LINK_PROMPT)
        assertTrue(
            "agent replies with links",
            waitForNewReply(before, sent = LINK_PROMPT, timeoutMs = 60_000),
        )
        // Case-sensitive on purpose so the capitalized link text can't be satisfied by our own
        // lowercase prompt echo.
        assertTrue(
            "link text is rendered",
            device.wait(Until.hasObject(By.textContains("Google")), 15_000),
        )
        assertTrue(
            "markdown link syntax must be parsed away once the reply finishes streaming",
            waitUntilAbsent("](http", 15_000),
        )
        assertFalse("raw markdown brackets must not appear", anyTextContains("[Google]"))
    }

    // NOTE: the attachment carousel is not asserted in this live flow; attachment rendering is
    // covered by the SDK's ChatSessionTests and the Compose-variant carousel test — the Views
    // ChatAdapter renders the same data.

    // NOTE on handoff: requesting a human ("speak to salesforce") keeps the dev agent
    // "typing" ~30s, so the full handoff state machine (Transferring -> failed / timed out /
    // queue, live-agent join/message/leave) is covered deterministically by the SDK's
    // ChatSessionTests over a mock connection — not re-asserted here.

    /**
     * Start-new resets the conversation surface back to a fresh greeting. Relaunches the activity
     * (CLEAR_TASK recreates RootActivity — the instrumentation process must keep running, so the
     * app process is never killed) and [connectFresh] prefers the connect screen's "Start New
     * Chat" for the fresh conversation.
     */
    @Test
    fun startNewConversation() {
        val before = textSnapshot()
        send("hello there")
        assertTrue("agent replies", waitForNewReply(before, sent = "hello there"))

        launchApp() // relaunch the task
        connectFresh()
        assertNotNull(
            "fresh start surfaces a new greeting with suggestions",
            firstSuggestionLabel(REPLY_TIMEOUT_MS),
        )
    }

    /**
     * "end the convo" is a reliable dev-agent trigger that drives a server SESSION_END, which
     * flips the surface to the ended banner + the in-place Start New Conversation button
     * (screen_chat.xml chatEndedView / @id/startNew).
     */
    @Test
    fun endConversation_viaMessage() {
        send("end the convo")
        assertTrue(
            "asking the agent to end ends the conversation",
            device.wait(Until.hasObject(By.textContains("This conversation has ended")), 60_000),
        )
        assertTrue(
            "ended state offers Start New Conversation",
            device.wait(Until.hasObject(By.text("Start New Conversation")), 10_000),
        )
    }

    // ----- Flow helpers -----

    /**
     * 06 uses a connect screen; tap through it by visible button text. The persisted session may
     * be resumable, so prefer "Start New Chat" (fresh) when it's offered and fall back to the
     * first-run "Start Chat" ("Resume Chat" stays the last-resort fallback).
     */
    private fun connectFresh() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var tapped = false
        while (SystemClock.elapsedRealtime() < deadline && !tapped) {
            val button = device.findObject(By.text("Start New Chat"))
                ?: device.findObject(By.text("Start Chat"))
                ?: device.findObject(By.text("Resume Chat"))
            if (button != null) {
                runCatching { button.click() }
                tapped = true
            } else {
                SystemClock.sleep(500)
            }
        }
        assertTrue("a connect button was offered", tapped)
        assertTrue(
            "composer present after connect",
            device.wait(Until.hasObject(By.res(APP_PACKAGE, "composer")), CONNECT_TIMEOUT_MS),
        )
    }

    /**
     * Tap the composer, type, tap the send button. The send button is `@id/send` and is disabled
     * until the composer has text (ChatScreenController.updateSendEnabled), so wait for it to
     * enable after setText.
     */
    private fun send(text: String) {
        var composer = device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        // Re-query after the click: the keyboard appearing re-layouts and can stale the node.
        composer = device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 5_000)
        assertNotNull("composer present after focus", composer)
        composer!!.text = text

        val sendButton =
            device.wait(Until.findObject(By.res(APP_PACKAGE, "send").enabled(true)), 5_000)
        assertNotNull("send button enabled", sendButton)
        sendButton!!.click()
    }

    // ----- Query helpers -----

    /**
     * Every visible TextView text (EditText is its own class, so the composer is excluded; Buttons
     * likewise).
     */
    private fun textSnapshot(): Set<String> = visibleTexts().toSet()

    private fun visibleTexts(): List<String> =
        try {
            device.findObjects(By.clazz("android.widget.TextView"))
                // The live UI mutates underneath us (typing dots, banners) — tolerate stale nodes.
                .mapNotNull { node -> runCatching { node.text }.getOrNull() }
        } catch (_: Exception) {
            emptyList()
        }

    /** Case-insensitive contains check. */
    private fun textExists(substring: String, timeoutMs: Long): Boolean =
        device.wait(
            Until.hasObject(By.pkg(APP_PACKAGE).text(containsIgnoreCase(substring))),
            timeoutMs,
        )

    private fun anyTextContains(substring: String): Boolean =
        visibleTexts().any { it.contains(substring, ignoreCase = true) }

    /**
     * Polls until no on-screen text contains `substring` — e.g. raw markdown that shows briefly
     * while a reply streams, then parses into a link.
     */
    private fun waitUntilAbsent(substring: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!anyTextContains(substring)) return true
            SystemClock.sleep(400)
        }
        return !anyTextContains(substring)
    }

    /**
     * The Views pills are TextViews inside the `@id/suggestionsScroll` row (item_suggestions.xml),
     * and the pill text IS the suggestion.
     */
    private fun firstSuggestionLabel(timeoutMs: Long): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val label = runCatching { firstSuggestionPill()?.text }.getOrNull()
            if (!label.isNullOrEmpty()) return label
            SystemClock.sleep(500)
        }
        return null
    }

    /** First pill: a TextView child of the suggestions row (scoped, so bubbles can't match). */
    private fun firstSuggestionPill(): UiObject2? =
        device.findObject(By.res(APP_PACKAGE, "suggestionsScroll"))
            ?.findObject(By.clazz("android.widget.TextView"))

    /** Prefer the pill with the exact text, else the first pill. */
    private fun tapSuggestion(text: String) {
        val pill = device.findObject(By.res(APP_PACKAGE, "suggestionsScroll"))
            ?.findObject(By.text(text))
            ?: firstSuggestionPill()
        assertNotNull("suggestion pill to tap", pill)
        pill!!.click()
    }

    /**
     * Deadline-poll until a new non-empty text not in the pre-send snapshot appears. Additionally
     * excludes the echo of the sent message and the
     * transient delivery captions ("Sending...", "Tap to retry") the user bubble can flash while
     * pending (ChatAdapter.kt) — they are not a reply.
     */
    private fun waitForNewReply(
        before: Set<String>,
        sent: String,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = visibleTexts()
            val hasNew = now.any { text ->
                text.isNotEmpty() &&
                    text !in before &&
                    text !in TRANSIENT_DELIVERY_LABELS &&
                    !text.contains(sent, ignoreCase = true)
            }
            if (hasNew) return true
            SystemClock.sleep(500)
        }
        return false
    }

    private fun containsIgnoreCase(substring: String): Pattern =
        Pattern.compile(
            ".*" + Pattern.quote(substring) + ".*",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        )

    private companion object {
        /** The module's applicationId/namespace (build.gradle.kts) — never a credential. */
        const val APP_PACKAGE = "ai.poly.examples.fullreference.views"

        /** Generous live-backend budgets: 25s connect / 60s reply. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 60_000L

        const val LINK_PROMPT = "send me a link to google"

        /** Pending-delivery captions rendered under a user bubble (ChatAdapter.kt). */
        val TRANSIENT_DELIVERY_LABELS = setOf("Sending...", "Tap to retry")
    }
}
