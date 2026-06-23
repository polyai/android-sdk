// Copyright PolyAI Limited

package ai.poly.examples.resilience.views

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented flow tests (black-box UI Automator), driven against the live dev
 * backend.
 *
 * 04's headline features (offline banner, loading skeleton, terminal error screen) need network
 * control a UI test cannot drive, so those are covered by unit tests; here we assert the chat
 * works end-to-end on top of all the resilience scaffolding: greeting + send/reply.
 */
@RunWith(AndroidJUnit4::class)
class ResilienceViewsFlowTest {

    private lateinit var device: UiDevice

    /**
     * Launch fresh, then `connectFresh()`. Clear the resumable session before connecting; the
     * instrumentation runs inside the app's own process (the Application has already initialized
     * the SDK), so call the same API directly. Do NOT `pm clear` here: force-stopping the target
     * package kills the instrumentation process itself.
     */
    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        PolyMessaging.clearResumableSession()

        val context = InstrumentationRegistry.getInstrumentation().context
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
     * The greeting renders after the socket opens (skeleton -> chat), proving the resilience
     * scaffolding doesn't block a normal happy-path connect.
     */
    @Test
    fun greeting_renders() {
        assertTrue(
            "greeting renders after connect",
            textExists("Webchat", 25_000) || textExists("Welcome", 1_000),
        )
    }

    /** Sending a message renders the user bubble and an agent reply. */
    @Test
    fun send_and_receiveReply() {
        val before = textSnapshot()
        send(SENT_MESSAGE)
        assertTrue("user message renders", textExists(SENT_MESSAGE, 15_000))
        assertTrue("agent reply appears", waitForNewReply(before))
    }

    // ----- Flow helpers -----

    /**
     * 04 auto-connects (no connect screen) — just wait for the composer (`@id/composer` in
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
    private fun textSnapshot(): Set<String> =
        device.findObjects(By.clazz("android.widget.TextView"))
            // The live UI mutates underneath us (typing dots, banners) — tolerate stale nodes.
            .mapNotNull { node -> runCatching { node.text }.getOrNull() }
            .toSet()

    /** Case-insensitive contains. */
    private fun textExists(substring: String, timeoutMs: Long): Boolean =
        device.wait(
            Until.hasObject(By.pkg(APP_PACKAGE).text(containsIgnoreCase(substring))),
            timeoutMs,
        )

    /**
     * Deadline-poll until a new non-empty text not in the pre-send snapshot appears. Additionally
     * excludes the echo of the sent message and the
     * transient delivery captions ("Sending...", "Tap to retry") the user bubble can flash while
     * pending (ChatAdapter.kt) — they are not a reply.
     */
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long = REPLY_TIMEOUT_MS): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val now = textSnapshot()
            val hasNew = now.any { text ->
                text.isNotEmpty() &&
                    text !in before &&
                    text !in TRANSIENT_DELIVERY_LABELS &&
                    !text.contains(SENT_MESSAGE, ignoreCase = true)
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
        /** The module's applicationId/namespace (build.gradle.kts). */
        const val APP_PACKAGE = "ai.poly.examples.resilience.views"

        /** Generous live-backend budgets: 30s connect / 60s reply. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 60_000L

        /** The message the UI Automator driver sends. */
        const val SENT_MESSAGE = "hello from uiautomator"

        /** Pending-delivery captions rendered under a user bubble (ChatAdapter.kt). */
        val TRANSIENT_DELIVERY_LABELS = setOf("Sending...", "Tap to retry")
    }
}
