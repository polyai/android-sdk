// Copyright PolyAI Limited

package ai.poly.examples.resilience.compose

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
 * End-to-end instrumented flow test (black-box UI Automator), driven against the live backend.
 *
 * 04-Resilience's headline features — the offline banner, loading skeleton, and terminal error
 * screen — all require network manipulation a UI test cannot drive. Those paths are covered
 * deterministically in unit tests. Here we assert the one thing that is
 * observable end-to-end: the resilient chat still connects and round-trips a message against the
 * live agent.
 */
@RunWith(AndroidJUnit4::class)
class ResilienceComposeFlowTest {

    private lateinit var device: UiDevice

    /**
     * Launch fresh, then `connectFresh()`. The app calls `PolyMessaging.clearResumableSession()`;
     * the instrumentation runs inside the app's own process (the app's Application.onCreate
     * has already initialized the SDK), so call the same API directly. Do NOT `pm clear` here:
     * force-stopping the target package kills the instrumentation process itself
     * ("Process crashed").
     */
    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        PolyMessaging.clearResumableSession()

        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
        assertNotNull("launch intent for $APP_PACKAGE", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app window appears",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), 10_000),
        )

        connectFresh()
    }

    // ----- Test -----

    /**
     * connect → greeting → send → user bubble → agent reply.
     */
    @Test
    fun send_and_reply() {
        assertTrue(
            "greeting renders after connect",
            textExists("Webchat", 25_000) || textExists("Welcome", 1_000),
        )

        val before = textSnapshot()
        send(SENT_MESSAGE)
        assertTrue("user message renders", textExists(SENT_MESSAGE, 15_000))
        assertTrue("agent reply appears", waitForNewReply(before))
    }

    // ----- Flow helpers -----

    /**
     * 04-Resilience auto-connects (no connect screen) — wait for the
     * composer. The "composer" testTag is surfaced as a
     * resource-id via testTagsAsResourceId.
     */
    private fun connectFresh() {
        assertTrue(
            "composer present after connect",
            // Compose testTags surfaced via testTagsAsResourceId are raw ids (no "pkg:id/"
            // prefix), so match with By.res(String), not By.res(pkg, id).
            device.wait(Until.hasObject(By.res("composer")), CONNECT_TIMEOUT_MS),
        )
    }

    /**
     * The composer sends via the arrow send button (contentDescription "Send") — UiObject2.setText
     * drives the compose text field through semantics, and the explicit button click is sturdier
     * than relying on a hardware-Enter newline reaching the trailing-newline send path.
     */
    private fun send(text: String) {
        var composer = device.wait(Until.findObject(By.res("composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        // Re-query after the click: the keyboard appearing can re-layout and stale the node.
        composer = device.wait(Until.findObject(By.res("composer")), 5_000)
        assertNotNull("composer present after focus", composer)
        composer!!.text = text

        val sendButton = device.wait(Until.findObject(By.desc("Send")), 5_000)
        assertNotNull("send button present", sendButton)
        sendButton!!.click()
    }

    // ----- Query helpers (deadline-based polling is robust to the non-idle app) -----

    /** Every visible text in the app window. */
    private fun textSnapshot(): Set<String> =
        device.findObjects(By.pkg(APP_PACKAGE).textContains(""))
            // The live UI mutates underneath us (streaming, banners) — tolerate stale nodes.
            .mapNotNull { node -> runCatching { node.text }.getOrNull() }
            .toSet()

    /** Case-insensitive contains. */
    private fun textExists(substring: String, timeoutMs: Long): Boolean =
        device.wait(
            Until.hasObject(By.pkg(APP_PACKAGE).text(containsIgnoreCase(substring))),
            timeoutMs,
        )

    /**
     * A new non-user text appears (robust to the exact
     * reply content). Additionally excludes the transient delivery labels ("Sending...",
     * "Tap to retry") the bubble can flash while the echo is pending — they are not a reply.
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
        const val APP_PACKAGE = "ai.poly.examples.resilience.compose"

        /** Generous live-backend budgets: 30s connect / 60s reply. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 60_000L

        /** Names the actual driver. */
        const val SENT_MESSAGE = "hello from uiautomator"

        /** Pending-delivery labels rendered under a user bubble (MessageBubbleView.kt). */
        val TRANSIENT_DELIVERY_LABELS = setOf("Sending...", "Tap to retry")
    }
}
