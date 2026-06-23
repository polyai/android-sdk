// Copyright PolyAI Limited

package ai.poly.examples.richcontent.compose

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dedicated UI Automator flow test (instrumented; black-box). Drives the
 * example's rich-content path against the live dev backend.
 *
 * Reliable agent behaviors used here (verified via the SDK probe):
 *   - "send me a link to google" -> reply with markdown links ([Google](...))
 *   - the fresh greeting carries an image attachment -> the carousel renders.
 */
@RunWith(AndroidJUnit4::class)
class RichContentComposeFlowTest {

    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Clear the resumable session so the run gets a brand-new greeting
        // (whose image attachment attachmentCarousel relies on). The
        // instrumentation runs inside the app's own process (the app's
        // Application.onCreate has already initialized the SDK), so call the
        // API directly. Do NOT `pm clear` here: force-stopping the target
        // package kills the instrumentation process itself ("Process crashed").
        PolyMessaging.clearResumableSession()
        // Grant POST_NOTIFICATIONS so the NewMessageNotifier's in-app permission
        // dialog never overlays the chat mid-flow.
        device.executeShellCommand(
            "pm grant $APP_PACKAGE android.permission.POST_NOTIFICATIONS",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "launch intent for $APP_PACKAGE — is the app APK installed?"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app window appears",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), 10_000),
        )

        connectFresh()
    }

    // MARK: - Tests

    /**
     * Asking for a link returns markdown; RichText must render it formatted
     * (link text shown, raw markdown syntax gone).
     */
    @Test
    fun richText_linkFormatting() {
        val before = visibleTexts()
        send("send me a link to google")
        assertTrue("agent replies with links", waitForNewReply(before))
        // The reply renders "Google" as link text… (case-sensitive on purpose:
        // the user's own lowercase "google" echo must not satisfy this).
        assertTrue(
            "link text is rendered",
            device.wait(Until.hasObject(By.textContains("Google")), 15_000),
        )
        // …and NOT the raw markdown link syntax.
        assertTrue(
            "markdown link syntax must be parsed away once the reply finishes streaming",
            waitUntilAbsent("](http", 15_000),
        )
        assertFalse(
            "raw markdown brackets must not appear",
            device.hasObject(By.textContains("[Google]")),
        )
    }

    /**
     * The fresh greeting carries an image attachment, so the carousel renders
     * on connect — deterministic (asking the agent for extra content it may or
     * may not return on a given turn is flaky).
     */
    @Test
    fun attachmentCarousel() {
        assertTrue(
            "greeting's image attachment renders in the carousel",
            // testTagsAsResourceId exposes the RAW tag as the resource id (no
            // "pkg:id/" prefix), so match with By.res(String), not By.res(pkg, id).
            device.wait(Until.hasObject(By.res("attachmentCarousel")), 25_000),
        )
    }

    // MARK: - Flow helpers

    /**
     * 03-RichContent auto-connects (no connect screen); wait for the composer.
     * Generous timeout: live dev backend.
     */
    private fun connectFresh() {
        assertNotNull(
            "composer present after connect",
            device.wait(Until.findObject(By.res("composer")), 30_000),
        )
        // Best-effort greeting wait (result discarded): the fresh greeting
        // carries the attachment carousel. Ensures the greeting is on screen —
        // and thus inside any pre-send snapshot — before a test proceeds, so
        // it can't be mistaken later for the agent's reply.
        device.wait(Until.hasObject(By.res("attachmentCarousel")), 30_000)
    }

    /**
     * The Android composer sends via the round Send button next to it
     * (contentDescription "Send" in ChatScreen.InputBar), which is the
     * stable black-box path under UI Automator.
     */
    private fun send(text: String) {
        val composer: UiObject2? =
            device.wait(Until.findObject(By.res("composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        composer.text = text
        val sendButton: UiObject2? =
            device.wait(Until.findObject(By.desc("Send").enabled(true)), 10_000)
        assertNotNull("send button enabled", sendButton)
        sendButton!!.click()
    }

    // MARK: - Query helpers (deadline-based polls are robust to the non-idle app)

    /** Snapshot of the text currently shown in every on-screen TextView. */
    private fun visibleTexts(): Set<String> =
        device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .toSet()

    /**
     * Waits until a new text appears that wasn't on screen before the send.
     * The user's own echoed bubble and its transient "Sending..." delivery
     * label are explicitly excluded so this really waits for the *agent's*
     * reply.
     */
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long = 60_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val texts = runCatching { visibleTexts() }.getOrDefault(emptySet())
            if (texts.any {
                    it.isNotEmpty() &&
                        it !in before &&
                        !it.equals(MESSAGE, ignoreCase = true) &&
                        it != "Sending..."
                }
            ) {
                return true
            }
            SystemClock.sleep(500)
        }
        return false
    }

    /**
     * Polls until no on-screen text contains [substring], e.g. raw markdown
     * that shows briefly while a reply streams, then parses into a link.
     */
    private fun waitUntilAbsent(substring: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!device.hasObject(By.textContains(substring))) return true
            SystemClock.sleep(400)
        }
        return !device.hasObject(By.textContains(substring))
    }

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.richcontent.compose"
        const val MESSAGE = "send me a link to google"
    }
}
