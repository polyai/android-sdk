// Copyright PolyAI Limited

package ai.poly.examples.handoff.views

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
import java.util.regex.Pattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented flow tests for the handoff Views app (black-box UI Automator),
 * driven against the LIVE dev backend.
 *
 * 05 is a "complete" example, so it exercises every feature its README (and the levels it builds
 * on) describes: greeting + suggestion pills, tapping a suggestion, markdown/link formatting, and
 * End -> Start New Conversation.
 *
 * Reliable agent behaviors (verified via the SDK probe):
 *   - greeting carries 3 suggestion pills
 *   - "send me a link to google"  -> markdown links ([Google](...))
 *   - "speak to salesforce"       -> Transferring...
 *
 * The Android Views composition: auto-connect, a RecyclerView transcript
 * (`@id/list`), a suggestions row of pill TextViews inside `@id/suggestionsStack`, the action-bar
 * "End" menu item, and the `@id/startNew` "Start New Conversation" ended-footer button.
 */
@RunWith(AndroidJUnit4::class)
class HandoffViewsFlowTest {

    private lateinit var device: UiDevice

    /**
     * Launch with a guaranteed-fresh conversation, then `connectFresh()`. The app has no
     * launch-argument hook, but the instrumentation shares the app process —
     * `HandoffApplication.onCreate` has already initialized the SDK — so we call the SDK static
     * (`clearResumableSession`) directly before launching the activity.
     */
    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        PolyMessaging.clearResumableSession()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
        assertNotNull("launch intent for $APP_PACKAGE — is the app APK installed?", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app window appears",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), 10_000),
        )

        connectFresh()
    }

    // ----- Tests -----

    /**
     * The greeting renders after connect, surfaces suggestion pills, and tapping a pill sends it
     * as a user message that the agent replies to.
     */
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
        // The pill's text is in `before` (Android pills are TextViews), so the user-bubble echo
        // cannot satisfy this — only a genuine agent reply can.
        assertTrue("agent replies to the suggestion", waitForNewReply(before, sent = suggestion))
    }

    /**
     * "send me a link to google" yields a reply whose markdown links render as link text, with
     * the raw markdown syntax parsed away once streaming settles.
     */
    @Test
    fun richText_linkFormatting() {
        val before = textSnapshot()
        val prompt = "send me a link to google"
        send(prompt)
        assertTrue("agent replies with links", waitForNewReply(before, sent = prompt, timeoutMs = 60_000))
        assertTrue("link text is rendered", textExists("Google", 15_000))
        assertTrue(
            "markdown link syntax must be parsed away once streaming settles",
            waitUntilAbsent("](http", 15_000),
        )
        assertFalse("raw markdown brackets must not appear", anyText("[Google]"))
    }

    // NOTE: the attachment carousel is not asserted in this live UI test — attachment rendering
    // is covered by the SDK-level ChatSessionTests and the compose carousel UI test; the Views
    // ChatAdapter renders the same data.

    // NOTE on handoff: requesting a human ("speak to salesforce") triggers a
    // real backend handoff whose timing is uncontrollable (the dev transfer attempt keeps the
    // agent "typing" ~30s). The full handoff state machine (Transferring -> Transfer failed /
    // timed out / queue, plus live-agent join/message/leave) is covered deterministically by the
    // SDK-level ChatSessionTests, not by this UI flow.

    /**
     * End flips to the ended footer; Start New Conversation resets it (fresh greeting +
     * suggestions).
     */
    @Test
    fun endChat_and_startNew() {
        val before = textSnapshot()
        send("hello")
        assertTrue("agent replies", waitForNewReply(before, sent = "hello"))

        // The "End" control is the action-bar menu item (ChatActivity onCreateOptionsMenu,
        // SHOW_AS_ACTION_ALWAYS) — the platform action bar may upper-case the title, so match
        // case-insensitively.
        val endButton = device.wait(
            Until.findObject(By.pkg(APP_PACKAGE).text(exactIgnoreCase("End"))),
            8_000,
        ) ?: device.findObject(By.desc("End"))
        assertNotNull("End button present", endButton)
        endButton!!.click()

        // The ended footer exposes "Start New Conversation" as @id/startNew (activity_chat.xml).
        val startNew = device.wait(Until.findObject(By.res(APP_PACKAGE, "startNew")), 10_000)
        assertNotNull("ended footer with Start New Conversation appears", startNew)
        assertTrue(
            "footer button is titled Start New Conversation",
            startNew!!.text.equals("Start New Conversation", ignoreCase = true),
        )
        startNew.click()

        // Fresh conversation: a new greeting/suggestions surface.
        assertNotNull(
            "start-new surfaces a fresh greeting with suggestions",
            firstSuggestionText(25_000),
        )
    }

    // ----- Flow helpers -----

    /**
     * 05 auto-connects (no connect screen) — just wait for the composer (`@id/composer` in
     * activity_chat.xml).
     */
    private fun connectFresh() {
        assertTrue(
            "composer present after auto-connect",
            device.wait(Until.hasObject(By.res(APP_PACKAGE, "composer")), CONNECT_TIMEOUT_MS),
        )
    }

    /**
     * Tap the composer, type, tap the send button. The send button is `@id/send` and is disabled
     * until the composer has text, so wait for it to become enabled after setText.
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
     * Every visible TextView text (EditText is its own class, so the composer is excluded).
     */
    private fun textSnapshot(): Set<String> = visibleTexts().toSet()

    private fun visibleTexts(): List<String> =
        try {
            device.findObjects(By.clazz("android.widget.TextView"))
                // The live UI mutates underneath us (typing dots, list refreshes) — tolerate
                // stale nodes; the caller's poll loop retries.
                .mapNotNull { node -> runCatching { node.text }.getOrNull() }
                .filter { it.isNotEmpty() }
        } catch (_: StaleObjectException) {
            emptyList()
        }

    /** Case-insensitive contains. */
    private fun anyText(substring: String): Boolean =
        visibleTexts().any { it.contains(substring, ignoreCase = true) }

    /** Case-insensitive contains, waited up to a timeout. */
    private fun textExists(substring: String, timeoutMs: Long): Boolean =
        device.wait(
            Until.hasObject(By.pkg(APP_PACKAGE).text(containsIgnoreCase(substring))),
            timeoutMs,
        )

    /**
     * Polls until no on-screen text contains [substring] — e.g. raw markdown that shows briefly
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
     * The pills are plain clickable TextViews built in code (ChatAdapter.makePill) with no
     * per-pill id, so we read the first child of the `@id/suggestionsStack` row — its text IS the
     * suggestion. Deadline-polled and stale-tolerant: the RecyclerView rebinds rows while the
     * list refreshes.
     */
    private fun firstSuggestionText(timeoutMs: Long): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val text = runCatching {
                device.findObject(By.res(APP_PACKAGE, "suggestionsStack"))
                    ?.children?.firstOrNull()?.text
            }.getOrNull()
            if (!text.isNullOrEmpty()) return text
            SystemClock.sleep(400)
        }
        return null
    }

    /**
     * Prefer the pill with the exact text, else the first pill. Retried on stale nodes (the row
     * can rebind between find and click).
     */
    private fun tapSuggestion(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val clicked = runCatching {
                val stack = device.findObject(By.res(APP_PACKAGE, "suggestionsStack"))
                    ?: return@runCatching false
                val pill: UiObject2 = stack.findObject(By.text(text))
                    ?: stack.children.firstOrNull()
                    ?: return@runCatching false
                pill.click()
                true
            }.getOrDefault(false)
            if (clicked) return
            SystemClock.sleep(300)
        }
        fail("suggestion pill present and tappable")
    }

    /**
     * Deadline-poll until a new non-empty text not in the pre-send snapshot appears. Additionally
     * excludes the echo of the [sent] message and the transient delivery captions ("Sending...",
     * "Tap to retry") the user bubble can flash while pending (ChatAdapter.kt) — they are not a
     * reply.
     */
    private fun waitForNewReply(
        before: Set<String>,
        sent: String? = null,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val hasNew = visibleTexts().any { text ->
                text !in before &&
                    text !in TRANSIENT_DELIVERY_LABELS &&
                    (sent == null || !text.contains(sent, ignoreCase = true))
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

    private fun exactIgnoreCase(text: String): Pattern =
        Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE)

    private companion object {
        /** The module's applicationId/namespace (build.gradle.kts). */
        const val APP_PACKAGE = "ai.poly.examples.handoff.views"

        /** Generous live-backend budgets: 30s connect / 60s reply. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 60_000L

        /** Pending-delivery captions rendered under a user bubble (ChatAdapter.kt). */
        val TRANSIENT_DELIVERY_LABELS = setOf("Sending...", "Tap to retry")
    }
}
