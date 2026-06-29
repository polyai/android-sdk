// Copyright PolyAI Limited

package ai.poly.examples.richcontent.views

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.Build
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
 * rich-content example against the live dev backend: auto-connect → send →
 * Markdown link rendering in the agent bubble.
 *
 * Reliable agent behavior (verified via the SDK probe):
 *   • "send me a link to google" -> markdown links ([Google](…))
 */
@RunWith(AndroidJUnit4::class)
class RichContentViewsFlowTest {

    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Clear the resumable session so the run gets a brand-new greeting. The
        // instrumentation runs inside the app's own process (the Application has
        // already initialized the SDK), so call the API directly. Do NOT
        // `pm clear` here: force-stopping the target package
        // kills the instrumentation process itself ("Process crashed").
        PolyMessaging.clearResumableSession()

        // ChatActivity asks for
        // POST_NOTIFICATIONS in onCreate on API 33+ — pre-grant it so the system
        // dialog never covers the composer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.executeShellCommand(
                "pm grant $APP_PACKAGE android.permission.POST_NOTIFICATIONS",
            )
        }

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
    }

    /**
     * Asking for a link returns markdown; the bubble must render it formatted
     * (link text shown, raw markdown syntax gone).
     */
    @Test
    fun richText_linkFormatting() {
        connectFresh()

        val before = visibleTexts()
        send(MESSAGE)

        assertTrue("agent replies with links", waitForNewReply(before, timeoutMs = 60_000))
        // Assert a label containing "Google" (case-insensitive), additionally
        // excluding the just-sent user bubble ("…link to google") so the check
        // proves the AGENT's rendered link text, not our own message.
        assertTrue(
            "link text is rendered",
            pollUntil(15_000) {
                visibleTexts().any { it.contains("Google", ignoreCase = true) && !it.contains(MESSAGE) }
            },
        )
        assertTrue(
            "markdown link syntax must be parsed away once streaming settles",
            pollUntil(15_000) { visibleTexts().none { it.contains("](http", ignoreCase = true) } },
        )
        assertFalse(
            "raw markdown brackets must not appear",
            visibleTexts().any { it.contains("[Google]", ignoreCase = true) },
        )
    }

    // NOTE: the attachment carousel is not asserted in the live flow test —
    // the scrolling container is not a queryable accessibility element while
    // the remote image hasn't loaded. Attachment rendering is covered by
    // ChatSessionTests, and the `imageCarousel`/`urlCarousel` rows render the
    // same data.

    // MARK: - Flow helpers

    /** 03 auto-connects (no connect screen) — just wait for the composer. */
    private fun connectFresh() {
        val composer: UiObject2? =
            device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 30_000)
        assertNotNull("composer present after auto-connect", composer)
        // Let the fresh-start greeting land before snapshotting, so it can't be
        // mistaken for the reply (this just de-flakes it).
        // Best-effort: discard the result.
        pollUntil(30_000) { visibleTexts().any { it.isNotEmpty() && it !in CHROME_TEXTS } }
    }

    /** Composer is an EditText, send is the round arrow ImageView. */
    private fun send(text: String) {
        val composer: UiObject2? =
            device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        composer.text = text
        val send: UiObject2? = device.wait(Until.findObject(By.res(APP_PACKAGE, "send")), 15_000)
        assertNotNull("send button present", send)
        send!!.click()
    }

    // MARK: - Query helpers

    /** A NEW text not seen before sending. */
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long): Boolean =
        pollUntil(timeoutMs) {
            visibleTexts().any {
                it.isNotEmpty() && it !in before && !it.contains(MESSAGE, ignoreCase = true)
            }
        }

    /** Snapshots the visible text labels on screen. */
    private fun visibleTexts(): Set<String> =
        device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .toSet()

    /** Deadline-based polling (no long sleeps). */
    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            SystemClock.sleep(400)
        }
        return runCatching(condition).getOrDefault(false)
    }

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.richcontent.views"
        const val MESSAGE = "send me a link to google"

        /** Static chrome present before any message: action-bar title + "End" menu item. */
        val CHROME_TEXTS = setOf("Chat", "End")
    }
}
