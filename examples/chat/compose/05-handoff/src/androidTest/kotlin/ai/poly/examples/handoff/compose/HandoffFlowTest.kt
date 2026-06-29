// Copyright PolyAI Limited

package ai.poly.examples.handoff.compose

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI Automator flows for the handoff example, run black-box against the LIVE dev
 * backend (out-of-process queries robust to a non-idle app).
 *
 * Reliable agent behaviors (verified via the SDK probe):
 *   - greeting carries 3 suggestion pills (and an image attachment)
 *   - "send me a link to google"  -> markdown links ([Google](...))
 *
 * NOTE on handoff: requesting a human triggers a real backend transfer whose timing is
 * uncontrollable, so the full handoff state machine is covered by the deterministic
 * SDK-level ChatSessionTests, not by this UI flow.
 */
@RunWith(AndroidJUnit4::class)
class HandoffFlowTest {

    private lateinit var device: UiDevice

    /**
     * Launches with a guaranteed-fresh conversation, then `connectFresh()`. The instrumentation
     * shares the app process — and `HandoffApplication.onCreate` has already initialized the SDK —
     * so we call `PolyMessaging.clearResumableSession()` directly before launching the activity.
     */
    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        PolyMessaging.clearResumableSession()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "launch intent for $APP_PACKAGE"
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

    @Test
    fun greeting_and_suggestionTap() {
        assertTrue(
            "greeting renders after connect",
            textExists("Webchat", 25_000) || textExists("Welcome", 1_000),
        )
        val suggestion = firstSuggestionText(20_000)
            ?: return fail("greeting should surface suggestion pills")
        val before = textSnapshot()
        tapSuggestion(suggestion)
        assertTrue(
            "tapped suggestion is sent as a user message",
            textExists(suggestion, 12_000),
        )
        assertTrue("agent replies to the suggestion", waitForNewReply(before))
    }

    @Test
    fun richText_linkFormatting() {
        val before = textSnapshot()
        send("send me a link to google")
        assertTrue("agent replies with links", waitForNewReply(before, 60_000))
        assertTrue("link text is rendered", textExists("Google", 15_000))
        assertTrue(
            "markdown link syntax must be parsed away once streaming settles",
            waitUntilAbsent("](http", 15_000),
        )
        assertFalse("raw markdown brackets must not appear", anyText("[Google]"))
    }

    @Test
    fun attachmentCarousel() {
        // The fresh greeting carries an image attachment, so the carousel renders on connect —
        // deterministic (asking the agent for extra content it may or may not return is flaky).
        assertTrue(
            "greeting's image attachment renders in the carousel",
            device.wait(Until.hasObject(By.res("attachmentCarousel")), 25_000),
        )
    }

    /** End Chat flips to the ended footer; Start New resets. */
    @Test
    fun endChat_and_startNew() {
        val before = textSnapshot()
        send("hello")
        assertTrue("agent replies", waitForNewReply(before))

        val endButton = device.wait(Until.findObject(By.text("End Chat")), 8_000)
        assertNotNull("End Chat button present", endButton)
        endButton!!.click()

        val startNew = device.wait(Until.findObject(By.text("Start New Conversation")), 10_000)
        assertNotNull("ended footer with Start New Conversation appears", startNew)
        startNew!!.click()

        // Fresh conversation: a new greeting/suggestions surface.
        assertNotNull(
            "start-new surfaces a fresh greeting with suggestions",
            firstSuggestionText(25_000),
        )
    }

    // MARK: - Flow helpers

    /** 05 auto-connects (no connect screen): just wait for the composer. */
    private fun connectFresh() {
        assertTrue(
            "composer present after auto-connect",
            device.wait(Until.hasObject(By.res("composer")), 30_000),
        )
    }

    /**
     * Sets the composer text via semantics, then taps the send button (the blue circle,
     * contentDescription "Send").
     */
    private fun send(text: String) {
        val composer = device.wait(Until.findObject(By.res("composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        // Re-find after the click (focus/IME can invalidate the cached node).
        device.findObject(By.res("composer")).text = text
        val sendButton = device.wait(Until.findObject(By.desc("Send")), 5_000)
        assertNotNull("send button present", sendButton)
        sendButton!!.click()
    }

    // MARK: - Query helpers

    /** Every non-empty text currently on screen. */
    private fun textSnapshot(): Set<String> = visibleTexts().toSet()

    private fun visibleTexts(): List<String> =
        try {
            device.findObjects(By.textContains(""))
                .mapNotNull { obj -> runCatching { obj.text }.getOrNull() }
                .filter { it.isNotEmpty() }
        } catch (_: StaleObjectException) {
            emptyList() // a frame churned mid-query; the caller's poll loop retries
        }

    /** True if any on-screen text contains [substring] (case-insensitive). */
    private fun anyText(substring: String): Boolean =
        visibleTexts().any { it.contains(substring, ignoreCase = true) }

    /** Deadline-polls for a text containing [substring]. */
    private fun textExists(substring: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (anyText(substring)) return true
            SystemClock.sleep(400)
        }
        return anyText(substring)
    }

    /**
     * Polls until no on-screen text contains [substring], e.g. raw markdown that shows briefly
     * while a reply streams, then parses into a link.
     */
    private fun waitUntilAbsent(substring: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!anyText(substring)) return true
            SystemClock.sleep(400)
        }
        return !anyText(substring)
    }

    /**
     * The pill's text IS the suggestion — no "Suggested reply: " accessibility-label prefix to
     * strip.
     */
    private fun firstSuggestionText(timeoutMs: Long): String? =
        device.wait(Until.findObject(By.res("suggestionPill")), timeoutMs)
            ?.let { pill -> runCatching { pill.text }.getOrNull() }

    /** Prefer the pill with the exact text, else the first pill. */
    private fun tapSuggestion(text: String) {
        val pill = device.findObject(By.res("suggestionPill").text(text))
            ?: device.findObject(By.res("suggestionPill"))
        assertNotNull("suggestion pill present", pill)
        pill!!.click()
    }

    /** Waits for a new non-empty text not in the snapshot. */
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long = 60_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (visibleTexts().any { it !in before }) return true
            SystemClock.sleep(500)
        }
        return false
    }

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.handoff.compose"
    }
}
