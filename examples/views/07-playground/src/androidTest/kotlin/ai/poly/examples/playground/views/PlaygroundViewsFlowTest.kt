// Copyright PolyAI Limited

package ai.poly.examples.playground.views

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented flow tests (black-box UI Automator), driven against the LIVE dev
 * backend: connecting, the greeting, send/reply, and the Dev Settings panel opening from the
 * connect screen and closing back to it.
 *
 * Notable behaviors, on purpose:
 *  - No fresh-start launch argument, so a resumable session may exist when a test starts. The
 *    connect helper and the connect-screen assertions accept "Start Chat" / "Resume Chat" /
 *    "Start New Chat" by visible button text.
 *  - The Dev Settings gear is an action-bar icon item (RootActivity MENU_GEAR, shown as action
 *    with an icon); its UI Automator handle is the content description "Dev Settings" the
 *    framework derives from the menu title.
 *  - The panel itself is a full-screen Dialog with its own title bar (SettingsDialog.kt), so we
 *    assert the title text "Dev Settings" plus its "Done" button.
 */
@RunWith(AndroidJUnit4::class)
class PlaygroundViewsFlowTest {

    // The chat screen requests POST_NOTIFICATIONS on first entry (RootActivity.showChat ->
    // NewMessageNotifier); granting it up front keeps the system permission dialog from
    // covering the composer mid-test.
    @get:Rule
    val grantNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Fresh launch of the app under test. */
    @Before
    fun launchApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "Launch intent for $APP_PACKAGE — is the app installed?"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app window appeared",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), LAUNCH_TIMEOUT_MS),
        )
    }

    // ----- Tests -----

    /**
     * The greeting renders after connecting, confirming the playground connects to the live
     * backend.
     */
    @Test
    fun greeting_renders() {
        connect()
        assertTrue(
            "greeting renders after connect",
            textExists("Webchat", 25_000) || textExists("Welcome", 1_000),
        )
    }

    /**
     * Sending a message renders the user bubble and a NEW agent reply (a text that wasn't on
     * screen before the send).
     */
    @Test
    fun send_and_receiveReply() {
        connect()
        val before = textSnapshot()
        send(SENT_MESSAGE)
        assertTrue("user message renders", textExists(SENT_MESSAGE, 15_000))
        assertTrue("agent reply appears", waitForNewReply(before))
    }

    /**
     * The Dev Settings gear on the connect screen opens the dev toolbox (title "Dev Settings" +
     * Done) and Done dismisses it back to the connect screen. This is driven on the connect
     * screen — in chat the gear collapses into the overflow menu, and "Apply & Start New
     * Session" only shows once a session exists; that new-chat path is covered by 05/06's
     * start-new flow + the session unit tests.
     */
    @Test
    fun devSettingsPanel_opensAndCloses() {
        val gear = device.wait(Until.findObject(By.desc("Dev Settings")), 15_000)
        assertNotNull("Dev Settings gear is available on the connect screen", gear)
        gear!!.click()

        // The panel is a Dialog with a title-bar TextView "Dev Settings" and a "Done" button
        // (SettingsDialog.kt). The gear itself is icon-only (desc, not text), so the text match
        // can only be the panel title.
        assertTrue(
            "the Dev Settings panel opened",
            device.wait(Until.hasObject(By.text("Dev Settings")), 10_000),
        )
        val done = device.wait(Until.findObject(By.text("Done")), 5_000)
        assertNotNull("the panel has a Done button", done)
        done!!.click()

        // Without a fresh-start hook the primary may read "Resume Chat" instead — accept either
        // connect-screen primary.
        assertNotNull(
            "dismissing returns to the connect screen",
            waitForAny(10_000, By.text("Start Chat"), By.text("Resume Chat")),
        )
    }

    // ----- Flow helpers -----

    /**
     * Tap through the connect screen by visible button text (Start / Start New / Resume —
     * screen_connect.xml), then await the composer (`@id/composer`).
     */
    private fun connect() {
        device.wait(Until.findObject(By.text("Start Chat")), 8_000)?.click()
            ?: device.wait(Until.findObject(By.text("Start New Chat")), 3_000)?.click()
            ?: device.wait(Until.findObject(By.text("Resume Chat")), 3_000)?.click()
        assertTrue(
            "composer present after connect",
            device.wait(Until.hasObject(By.res(APP_PACKAGE, "composer")), CONNECT_TIMEOUT_MS),
        )
    }

    /**
     * Tap the composer, set the text, tap the send button. The send button is `@id/send` and is
     * disabled until the composer has text (ChatScreenController.updateSendEnabled), so wait for
     * it to become enabled after setText.
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

    /** Case-insensitive contains match against visible text. */
    private fun textExists(substring: String, timeoutMs: Long): Boolean =
        device.wait(
            Until.hasObject(By.pkg(APP_PACKAGE).text(containsIgnoreCase(substring))),
            timeoutMs,
        )

    /**
     * Deadline-poll until a new non-empty text not in the pre-send snapshot appears.
     * Additionally excludes the echo of the sent message, the
     * transient delivery captions a pending user bubble can flash ("Sending..." / "Tap to
     * retry", ChatAdapter.kt), and the chrome banners/pill (screen_chat.xml) — none of those
     * are an agent reply.
     */
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long = REPLY_TIMEOUT_MS): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val now = textSnapshot()
            val hasNew = now.any { text ->
                text.isNotEmpty() &&
                    text !in before &&
                    text !in NON_REPLY_TEXTS &&
                    !text.contains(SENT_MESSAGE, ignoreCase = true)
            }
            if (hasNew) return true
            SystemClock.sleep(500)
        }
        return false
    }

    /** Deadline-poll for the first object matching ANY of [selectors] (no long sleeps). */
    private fun waitForAny(timeoutMs: Long, vararg selectors: BySelector): UiObject2? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            for (selector in selectors) {
                device.findObject(selector)?.let { return it }
            }
            SystemClock.sleep(250)
        }
        return null
    }

    private fun containsIgnoreCase(substring: String): Pattern =
        Pattern.compile(
            ".*" + Pattern.quote(substring) + ".*",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        )

    private companion object {
        /** The module's applicationId/namespace (build.gradle.kts). */
        const val APP_PACKAGE = "ai.poly.examples.playground.views"

        const val LAUNCH_TIMEOUT_MS = 25_000L

        /** Generous live-backend budgets: 60s connect / 60s reply. */
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val REPLY_TIMEOUT_MS = 60_000L

        const val SENT_MESSAGE = "hello from uiautomator"

        /**
         * Texts that can newly appear after a send but are NOT an agent reply: the pending
         * user bubble's delivery captions (ChatAdapter.kt) and the chrome banners/pill
         * (screen_chat.xml).
         */
        val NON_REPLY_TEXTS = setOf(
            "Sending...",
            "Tap to retry",
            "Resumed previous conversation",
            "Reconnecting...",
            "You're offline",
            "↓  New messages",
        )
    }
}
