// Copyright PolyAI Limited

package ai.poly.examples.fullreference.compose

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
 * End-to-end UI Automator flow tests for the full-reference app, driven against the
 * live dev backend.
 *
 * UI Automator is a black-box driver that doesn't wait for an idle app, so every
 * assertion is a deadline-based poll.
 *
 * Reliable agent behaviors used here (verified via the SDK probe):
 *   - greeting carries 3 suggestion pills (and an image attachment)
 *   - "send me a link to google" -> reply with markdown links ([Google](...))
 *   - "end the convo"            -> server SESSION_END
 *
 * Notes (documented inline where they apply):
 *   - [connectFresh] starts a brand-new conversation through the connect screen's
 *     own buttons ("Start New Chat" when a session is resumable, "Start Chat"
 *     otherwise).
 *   - The composer is driven via UiObject2.setText + the explicit send button
 *     (contentDescription "Send message" in ChatView.InputBar).
 */
@RunWith(AndroidJUnit4::class)
class FullReferenceFlowTest {

    // Pre-grant POST_NOTIFICATIONS so NewMessageNotifier's runtime permission dialog
    // can't cover the chat.
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private lateinit var device: UiDevice

    // Launch, then connectFresh().
    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "no launch intent for $APP_PACKAGE"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app appears on screen",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), 10_000),
        )
        connectFresh()
    }

    // MARK: - Tests

    // Greeting renders, suggestion pills are present, and tapping one sends it.
    @Test
    fun greeting_and_suggestionTap() {
        assertTrue(
            "greeting renders after connect",
            device.wait(Until.hasObject(By.text(GREETING)), 30_000),
        )

        val suggestion = firstSuggestionLabel(20_000)
            ?: return fail("greeting should surface suggestion pills")
        val before = textSnapshot()
        tapSuggestion(suggestion)
        assertTrue(
            "tapped suggestion is sent as a user message",
            device.wait(Until.hasObject(By.textContains(suggestion)), 12_000),
        )
        assertTrue(
            "agent replies to the tapped suggestion",
            waitForNewReply(before, sent = suggestion),
        )
    }

    // Asking for a link returns markdown; RichText must render it formatted
    // (link text shown, raw markdown syntax gone).
    @Test
    fun richText_linkFormatting() {
        val before = textSnapshot()
        send(LINK_PROMPT)
        assertTrue("agent replies with links", waitForNewReply(before, sent = LINK_PROMPT, timeoutMs = 60_000))
        // The reply renders "Google" as link text... (case-sensitive on purpose —
        // this can't match our own lowercase prompt).
        assertTrue(
            "link text is rendered",
            device.wait(Until.hasObject(By.textContains("Google")), 15_000),
        )
        // ...and NOT the raw markdown link syntax.
        assertTrue(
            "markdown link syntax must be parsed away once the reply finishes streaming",
            waitUntilAbsent("](http", 15_000),
        )
        assertFalse("raw markdown brackets must not appear", anyTextContains("[Google]"))
    }

    // The fresh greeting carries an image attachment, so the carousel renders on
    // connect — deterministic (asking the agent for extra content is flaky).
    @Test
    fun attachmentCarousel() {
        assertTrue(
            "greeting's image attachment renders in the carousel",
            device.wait(Until.hasObject(By.res("attachmentCarousel")), 25_000),
        )
    }

    // NOTE on handoff: the live dev transfer keeps the agent "typing" for ~30s, so
    // the full handoff state machine is covered deterministically by the SDK's
    // ChatSessionTests over a MockConnection — not re-asserted here.

    // Start-new resets the conversation surface back to a fresh greeting via the
    // always-available connect-screen reset — Back to the connect screen, then
    // "Start New Chat" — and confirm a brand-new greeting + suggestions appear.
    @Test
    fun startNewConversation() {
        val before = textSnapshot()
        send("hello there")
        assertTrue("agent replies", waitForNewReply(before, sent = "hello there"))

        val back = device.wait(Until.findObject(By.desc("Back")), 10_000)
        assertNotNull("top-bar Back returns to the connect screen", back)
        back!!.click()
        val startNew = device.wait(Until.findObject(By.text("Start New Chat")), 10_000)
        assertNotNull("connect screen offers Start New Chat while a session is active", startNew)
        startNew!!.click()

        assertTrue(
            "composer present after fresh start",
            device.wait(Until.hasObject(By.res("composer")), 30_000),
        )
        assertNotNull(
            "fresh start surfaces a new greeting with suggestions",
            device.wait(Until.findObject(By.res("suggestionPill")), 25_000),
        )
    }

    // "end the convo" is a reliable dev-agent trigger that drives a server
    // SESSION_END, which flips the surface to the ended banner + Start New
    // Conversation.
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

    // MARK: - Flow helpers

    // 06 uses a connect screen; tap through it by visible button text. The
    // persisted session may be resumable, so prefer "Start New Chat" (fresh) when
    // it's offered and fall back to the first-run "Start Chat" otherwise.
    private fun connectFresh() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var tapped = false
        while (SystemClock.elapsedRealtime() < deadline && !tapped) {
            val button = device.findObject(By.text("Start New Chat"))
                ?: device.findObject(By.text("Start Chat"))
                ?: device.findObject(By.text("Resume Chat")) // last-resort fallback
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
            device.wait(Until.hasObject(By.res("composer")), 30_000),
        )
    }

    // Set the composer text via semantics and click the send button.
    private fun send(text: String) {
        val composer = device.wait(Until.findObject(By.res("composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        // Re-find after the click (focus recomposes the node) before setText —
        // UiObject2.setText drives the compose BasicTextField via its semantics.
        val focused = device.wait(Until.findObject(By.res("composer")), 5_000)
        assertNotNull("composer present after focus", focused)
        focused!!.text = text
        // The send button enables once the composer is non-empty (ChatView.InputBar).
        val sendButton: UiObject2? = device.wait(Until.findObject(By.desc("Send message")), 5_000)
        assertNotNull("send button present", sendButton)
        sendButton!!.click()
    }

    // MARK: - Query helpers (deadline polls are robust to the non-idle app)

    // Snapshot of every visible text.
    private fun textSnapshot(): Set<String> = visibleTexts().toSet()

    private fun visibleTexts(): List<String> =
        try {
            device.findObjects(By.text(ANY_TEXT)).mapNotNull {
                try {
                    it.text
                } catch (_: Exception) { // node went stale mid-walk (live app)
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

    private fun anyTextContains(substring: String): Boolean =
        visibleTexts().any { it.contains(substring, ignoreCase = true) }

    // Polls until no on-screen text contains `substring` — e.g. raw markdown that
    // shows briefly while a reply streams, then parses into a link.
    private fun waitUntilAbsent(substring: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!anyTextContains(substring)) return true
            SystemClock.sleep(400)
        }
        return !anyTextContains(substring)
    }

    // The pill's semantics text IS the suggestion (the "Suggested reply: " prefix
    // lives on its contentDescription instead).
    private fun firstSuggestionLabel(timeoutMs: Long): String? {
        val pill = device.wait(Until.findObject(By.res("suggestionPill")), timeoutMs)
            ?: return null
        val text = runCatching { pill.text }.getOrNull()
        if (!text.isNullOrEmpty()) return text
        val desc = runCatching { pill.contentDescription }.getOrNull() ?: return null
        return desc.removePrefix("Suggested reply: ").ifEmpty { null }
    }

    private fun tapSuggestion(text: String) {
        val byDesc = device.findObject(By.desc("Suggested reply: $text"))
        if (byDesc != null) {
            byDesc.click()
        } else {
            device.findObject(By.res("suggestionPill"))?.click()
        }
    }

    // Returns true when a NEW text appears that wasn't in the snapshot. The
    // just-sent user bubble (and its transient "Sending..." label) are excluded so
    // only the agent's reply counts.
    private fun waitForNewReply(
        before: Set<String>,
        sent: String,
        timeoutMs: Long = 60_000,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = visibleTexts()
            if (now.any {
                    it.isNotEmpty() && it !in before &&
                        !it.contains(sent, ignoreCase = true) &&
                        it != "Sending..."
                }
            ) {
                return true
            }
            SystemClock.sleep(500)
        }
        return false
    }

    private companion object {
        // The module's applicationId (build.gradle.kts) — never a credential.
        const val APP_PACKAGE = "ai.poly.examples.fullreference.compose"
        const val LINK_PROMPT = "send me a link to google"

        // Any node with text containing "Webchat" or "Welcome" (case-insensitive,
        // multi-line).
        val GREETING: Pattern = Pattern.compile("(?si).*(webchat|welcome).*")

        // Any node exposing non-empty text.
        val ANY_TEXT: Pattern = Pattern.compile("(?s).+")
    }
}
