// Copyright PolyAI Limited

package ai.poly.examples.standard.views

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI Automator flow tests for the standard Views app, driven against
 * the live dev backend.
 *
 * 02 introduces the "80% app" surface, so this covers what its README
 * describes: greeting + suggestion pills (typing throttle, delivery dots) and
 * the End -> Start New Conversation flow.
 *
 * UI Automator is a black-box driver that doesn't wait for an idle app, so
 * every assertion is a deadline-based poll.
 *
 * Query mapping (view id, per activity_chat.xml / item_suggestions.xml):
 *   - composer            -> By.res(pkg, "composer")     (EditText)
 *   - send button         -> By.res(pkg, "send")         (ImageView; disabled until text)
 *   - suggestion pill     -> TextView pills inside By.res(pkg, "suggestionsStack")
 *   - End                 -> action-bar menu item with text "End"
 *   - Start New Conversation -> By.res(pkg, "startNew")
 *   - visible text labels -> By.clazz("android.widget.TextView")
 */
@RunWith(AndroidJUnit4::class)
class StandardViewsFlowTest {

    private lateinit var device: UiDevice

    // Fresh start + launch + connectFresh().
    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Clear any resumable session so each test gets a brand-new greeting +
        // suggestions instead of resuming a prior conversation. The
        // instrumentation shares the app's process — StandardApplication.onCreate
        // has already initialized the SDK by the time this runs — so call the
        // same public API directly. (No credential involved; the SDK reads its
        // own stored configuration.)
        PolyMessaging.clearResumableSession()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "no launch intent for $APP_PACKAGE — is the app APK installed?"
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

    // Greeting renders, suggestion pills are present, and tapping one sends it
    // and produces a reply (delivery + suggestions in one path).
    @Test
    fun greeting_and_suggestionTap() {
        assertTrue(
            "greeting renders after connect",
            device.wait(Until.hasObject(By.text(GREETING)), 30_000),
        )
        val suggestion = firstSuggestionLabel(20_000)
        assertNotNull("greeting should surface suggestion pills", suggestion)

        val before = textSnapshot()
        tapSuggestion(suggestion!!)
        // The tap clears the pill row synchronously (clearSuggestions), so the
        // text found here is the user bubble.
        assertTrue(
            "tapped suggestion is sent as a user message",
            device.wait(Until.hasObject(By.textContains(suggestion)), 12_000),
        )
        assertTrue("agent replies to the suggestion", waitForReply(before, suggestion))
    }

    // Typing into the composer and sending renders the user bubble and a reply.
    @Test
    fun send_and_receiveReply() {
        val before = textSnapshot()
        send(MESSAGE)
        assertTrue(
            "user message renders",
            device.wait(Until.hasObject(By.textContains(MESSAGE)), 15_000),
        )
        assertTrue("agent reply appears", waitForReply(before, MESSAGE))
    }

    // End flips to the ended footer; Start New Conversation resets it.
    @Test
    fun endChat_and_startNew() {
        val before = textSnapshot()
        send("hello")
        assertTrue("agent replies", waitForReply(before, "hello"))

        // The action-bar menu item titled "End". The action bar theme applies
        // textAllCaps, and UI Automator surfaces the TRANSFORMED text ("END"),
        // so match case-insensitively (uiautomator-dump verified).
        val endButton = device.wait(Until.findObject(By.text(Pattern.compile("(?i)end"))), 8_000)
        assertNotNull("End button present", endButton)
        endButton!!.click()

        val startNew = device.wait(Until.findObject(By.res(APP_PACKAGE, "startNew")), 10_000)
        assertNotNull("ended footer with Start New Conversation appears", startNew)
        startNew!!.click()

        assertNotNull(
            "start-new surfaces a fresh greeting with suggestions",
            firstSuggestionLabel(25_000),
        )
    }

    // A multi-turn conversation — each user message gets its own agent reply.
    @Test
    fun multiTurnConversation() {
        // Distinct prompts whose replies are plain text (so each reply registers
        // as a new, queryable TextView — link/carousel replies render in
        // non-text views and are covered by their own rungs' tests).
        val turns = listOf(
            "what services do you offer?",
            "how do i get started?",
            "who are your typical customers?",
        )
        turns.forEachIndexed { i, text ->
            val before = textSnapshot()
            send(text)
            assertTrue(
                "turn ${i + 1}: user message renders",
                device.wait(Until.hasObject(By.textContains(text)), 15_000),
            )
            // Wait for a reply not containing the sent text, with a 90s budget.
            assertTrue("turn ${i + 1}: agent replies", waitForReply(before, text, 90_000))
        }
    }

    // MARK: - Flow helpers

    // 02 auto-connects (no connect screen) — just wait for the composer.
    private fun connectFresh() {
        assertTrue(
            "composer present after auto-connect",
            device.wait(Until.hasObject(By.res(APP_PACKAGE, "composer")), 30_000),
        )
    }

    // Tap the composer, type, tap send. The send button is disabled until the
    // field has text, so wait for it to enable after setText.
    private fun send(text: String) {
        var composer = device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        // Re-find after the click (focus can refresh the node) before setText.
        composer = device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 5_000)
        assertNotNull("composer present after focus", composer)
        composer!!.text = text
        val sendButton =
            device.wait(Until.findObject(By.res(APP_PACKAGE, "send").enabled(true)), 5_000)
        assertNotNull("send button enabled", sendButton)
        sendButton!!.click()
    }

    // MARK: - Query helpers (deadline polls are robust to the non-idle app)

    // Every visible text label. Matches TextViews only (buttons/EditTexts
    // excluded).
    private fun textSnapshot(): Set<String> = visibleTexts().toSet()

    private fun visibleTexts(): List<String> =
        try {
            device.findObjects(By.clazz("android.widget.TextView")).mapNotNull {
                try {
                    it.text
                } catch (_: Exception) { // node went stale mid-walk (live app)
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

    // The pills are plain TextViews inside the "suggestionsStack" row (no
    // accessibility prefix to strip).
    private fun firstSuggestionLabel(timeoutMs: Long): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val label = runCatching {
                device.findObject(By.res(APP_PACKAGE, "suggestionsStack"))
                    ?.findObjects(By.clazz("android.widget.TextView"))
                    ?.firstNotNullOfOrNull { pill -> pill.text?.takeIf { it.isNotEmpty() } }
            }.getOrNull()
            if (label != null) return label
            SystemClock.sleep(500)
        }
        return null
    }

    // Tap the pill by its text, falling back to the first pill in the row.
    private fun tapSuggestion(text: String) {
        val stack = device.findObject(By.res(APP_PACKAGE, "suggestionsStack"))
        assertNotNull("suggestions row present", stack)
        val pill: UiObject2? = stack!!.findObject(By.text(text))
            ?: stack.findObjects(By.clazz("android.widget.TextView")).firstOrNull()
        assertNotNull("suggestion pill to tap", pill)
        pill!!.click()
    }

    // A new on-screen text not in `before` and not the user's own message —
    // i.e. the agent's reply. The baseline is taken *before* sending so a fast
    // reply isn't accidentally folded into it. Always excludes the just-sent
    // text (exact match — bubbles render the sent text verbatim) and the
    // transient delivery captions, so the user echo can never satisfy the wait.
    private fun waitForReply(
        before: Set<String>,
        userText: String,
        timeoutMs: Long = 60_000,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val now = visibleTexts()
            if (now.any {
                    it.isNotEmpty() && it !in before && it != userText && it !in TRANSIENT_CAPTIONS
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
        const val APP_PACKAGE = "ai.poly.examples.standard.views"
        const val MESSAGE = "hello from uiautomator"

        // Any node whose text contains "Webchat" or "Welcome" (case-insensitive,
        // multi-line).
        val GREETING: Pattern = Pattern.compile("(?si).*(webchat|welcome).*")

        // Delivery-state captions (item_message.xml `delivery`) that appear under
        // the user bubble while it is pending/failed — never an agent reply.
        val TRANSIENT_CAPTIONS = setOf("Sending...", "Tap to retry")
    }
}
