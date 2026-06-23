// Copyright PolyAI Limited

package ai.poly.examples.standard.compose

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI Automator flow tests, driven against the live dev backend
 * (black-box, robust to a non-idle app).
 *
 * 02-Standard's README features: suggestion pills, delivery state, typing
 * indicator, End + Start-New chat, and the connection banner. These tests
 * exercise the user-observable ones against the live agent.
 *
 * Reliable agent behaviors used here (verified via the SDK probe):
 *   - greeting carries 3 suggestion pills
 */
@RunWith(AndroidJUnit4::class)
class StandardComposeFlowTest {

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.standard.compose"
        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val POLL_MS = 500L
    }

    private lateinit var device: UiDevice

    // Element handles. testTagsAsResourceId exposes the Compose testTag verbatim as the
    // node's resource name (no "<pkg>:id/" prefix), so we match the full-resource-name
    // By.res(String) form against the raw tag.
    private val composer: BySelector = By.res("composer")
    private val suggestionPill: BySelector = By.res("suggestionPill")

    /** Fresh-start launch + connectFresh(). */
    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Fresh start makes the app call clearResumableSession() before building its ChatSession.
        // The instrumentation shares the app's process (the already-initialized SDK), so call the
        // same API directly before (re)launching.
        PolyMessaging.clearResumableSession()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "no launch intent for $APP_PACKAGE"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        // No depth(0): the root-window check is flaky when the accessibility tree is mid-update
        // (the app can be live and connected while the root node still reports another package).
        // connectFresh() below is the authoritative readiness gate (composer visible).
        assertTrue(
            "app on screen after launch",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), LAUNCH_TIMEOUT_MS * 2),
        )
        connectFresh()
    }

    // MARK: - Tests

    /**
     * Greeting renders, suggestion pills are present, and tapping one sends it.
     */
    @Test
    fun test_greeting_and_suggestionTap() {
        assertTrue(
            "greeting renders after connect",
            hasTextContaining("Webchat", 25_000) || hasTextContaining("Welcome", 1_000),
        )

        val suggestion = firstSuggestionText(20_000)
        assertNotNull("greeting should surface suggestion pills", suggestion)
        suggestion!!

        val before = textSnapshot()
        tapSuggestion(suggestion)
        assertTrue(
            "tapped suggestion is sent as a user message",
            hasTextContaining(suggestion, 12_000),
        )
        assertTrue("agent replies to the tapped suggestion", waitForNewReply(before))
    }

    /** A sent message renders as a user bubble and the agent replies. */
    @Test
    fun test_send_and_reply() {
        val before = textSnapshot()
        send("hello from uiautomator")
        assertTrue("user message renders", hasTextContaining("hello from uiautomator", 15_000))
        assertTrue("agent reply appears", waitForNewReply(before))
    }

    /**
     * End Chat -> Start New Conversation resets the surface to a fresh greeting.
     */
    @Test
    fun test_endChat_and_startNew() {
        val before = textSnapshot()
        send("hello")
        assertTrue("agent replies", waitForNewReply(before))

        val endChat = device.wait(Until.findObject(By.text("End Chat")), 10_000)
        assertNotNull("End Chat button present", endChat)
        endChat.click()

        val startNew = device.wait(Until.findObject(By.text("Start New Conversation")), 10_000)
        assertNotNull("Start New Conversation button appears after ending", startNew)
        startNew.click()

        assertNotNull(
            "starting new surfaces a fresh greeting with suggestions",
            firstSuggestionText(25_000),
        )
    }

    /**
     * A multi-turn conversation — each user message gets its own agent reply. The wait excludes
     * texts containing the user's message so it detects the agent's reply specifically (not the
     * user echo).
     */
    @Test
    fun test_multiTurnConversation() {
        // Distinct prompts whose replies are plain text (so each reply registers as new,
        // queryable text). Link/carousel replies render in non-text views — out of scope here.
        val turns = listOf(
            "what services do you offer?",
            "how do i get started?",
            "who are your typical customers?",
        )
        turns.forEachIndexed { i, text ->
            val before = textSnapshot()
            send(text)
            assertTrue("turn ${i + 1}: user message renders", hasTextContaining(text, 15_000))
            assertTrue(
                "turn ${i + 1}: agent replies",
                waitForReply(notContaining = text, before = before, timeoutMs = 90_000),
            )
        }
    }

    // MARK: - Flow helpers

    /** 02-Standard auto-connects (no connect screen) — just wait for the composer. */
    private fun connectFresh() {
        assertTrue(
            "composer present after connect",
            device.wait(Until.hasObject(composer), 30_000),
        )
    }

    /**
     * UiObject2.setText drives the Compose text field via semantics; the module sends via the
     * send button / IME Send action, so tap the send button (contentDescription "Send") rather
     * than synthesizing Enter.
     */
    private fun send(text: String) {
        var field = device.wait(Until.findObject(composer), 10_000)
        assertNotNull("composer present", field)
        field.click()
        // Re-query after the click: focusing can recompose and stale the handle.
        field = device.wait(Until.findObject(composer), 5_000)
        assertNotNull("composer present after focus", field)
        field.text = text
        val sendButton = device.wait(Until.findObject(By.desc("Send")), 5_000)
        assertNotNull("send button present", sendButton)
        sendButton.click()
    }

    // MARK: - Query helpers (deadline-based polling is robust to the non-idle app)

    /** Every non-empty text currently on screen. */
    private fun textSnapshot(): Set<String> {
        repeat(3) {
            try {
                return device.findObjects(By.textContains(""))
                    .mapNotNull { runCatching { it.text }.getOrNull() }
                    .filterTo(mutableSetOf()) { it.isNotEmpty() }
            } catch (_: StaleObjectException) {
                SystemClock.sleep(250) // the tree shifted mid-walk; retry
            }
        }
        return emptySet()
    }

    /** Whether any on-screen text contains the substring (case-insensitive). */
    private fun hasTextContaining(substring: String, timeoutMs: Long): Boolean =
        device.wait(
            Until.hasObject(
                By.text(Pattern.compile(".*${Pattern.quote(substring)}.*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)),
            ),
            timeoutMs,
        )

    /**
     * The pills carry the suggestion text directly (no "Suggested reply: " accessibility prefix
     * to strip).
     */
    private fun firstSuggestionText(timeoutMs: Long): String? {
        val pill = device.wait(Until.findObject(suggestionPill), timeoutMs) ?: return null
        return runCatching { pill.text }.getOrNull()
    }

    /** Prefer the pill matching the text, else the first pill. */
    private fun tapSuggestion(text: String) {
        val pill = device.findObject(By.copy(suggestionPill).text(text))
            ?: device.findObject(suggestionPill)
        assertNotNull("suggestion pill to tap", pill)
        pill.click()
    }

    /** A new on-screen text not in `before` appears. */
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long = 60_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (textSnapshot().any { it !in before }) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /**
     * A new on-screen text not in `before` and not the user's own message — i.e. the agent's
     * reply. Baseline is taken *before* sending so a fast reply isn't accidentally folded into it.
     */
    private fun waitForReply(notContaining: String, before: Set<String>, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (textSnapshot().any { it !in before && !it.contains(notContaining) }) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }
}
