// Copyright PolyAI Limited

package ai.poly.examples.playground.views

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check for the new-message notification (NewMessageNotifier.kt), driven against the
 * live dev backend.
 *
 * Notable behaviors, on purpose:
 *  - No launch-argument notify hook: ChatScreenController mounts the notifier with
 *    `NotificationPolicy.WHEN_BACKGROUNDED` — it posts a notification only when the chat is NOT
 *    on screen (lifecycle below RESUMED; the collector stays alive down to CREATED, so a
 *    backgrounded app still receives the reply). So instead of asserting a *foreground* banner,
 *    this test connects, sends a message via the composer, immediately backgrounds the app
 *    (home), and polls the notification shade for the new-message notification raised by the
 *    agent's reply.
 *  - The notification signal: the notifier titles the alert with the agent display name or
 *    "New message" when there is none — the dev agent has no display name, so "New message" is
 *    the reliable label.
 *  - No fresh-start hook either; the connect helper tolerates Start / Resume / Start New by
 *    button text. The notifier dedupes on server messageId (NotifiedMessageStore), but the reply
 *    to a freshly-sent message always has a new id, so stale state cannot mask it.
 *
 * A kill-and-relaunch dedupe scenario is intentionally NOT covered here: relaunching the host
 * app kills the instrumentation process itself. That scenario is covered at the adb-script level
 * instead.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBannerTest {

    // The notifier silently skips posting without POST_NOTIFICATIONS (API 33+); grant it up
    // front so the permission dialog never covers the test UI.
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

    /**
     * Exercises the notifier's WHEN_BACKGROUNDED policy (see class KDoc): connect, send,
     * background the app, and the agent's reply must raise a "New message" notification in the
     * shade.
     */
    @Test
    fun newMessageNotification_postedWhileBackgrounded() {
        connect()

        // Settle 5s post-connect. The greeting lands while the chat is RESUMED (so it never
        // notifies — the policy marks it shown instead), but this settle ensures it is consumed
        // before we background, keeping the later shade match attributable to the reply. Slept
        // in <=1s steps per the no-long-sleep convention.
        repeat(5) { SystemClock.sleep(1_000) }

        screenshot("1-before-send")
        send(MESSAGE_TEXT)
        // Background IMMEDIATELY after the send click — the optimistic send is already queued
        // and the SDK keeps the socket open off screen. Waiting for the user bubble here loses
        // the race against the (fast, ~2s) dev agent: if the reply completes while the chat is
        // still RESUMED, WHEN_BACKGROUNDED both suppresses and permanently dedupes it
        // (markShown), so no notification can EVER appear and the test times out.
        device.pressHome()
        device.openNotification()
        // Poll the shade for the agent reply's notification (90s deadline).
        val sawNotification =
            device.wait(Until.hasObject(By.textContains(NOTIFICATION_TITLE)), REPLY_TIMEOUT_MS)
        screenshot(if (sawNotification) "2-NOTIFICATION" else "3-after")
        device.pressBack() // close the shade before finishing

        assertTrue("new-message notification posted for the agent reply", sawNotification)
    }

    // ----- Per-example flow -----

    /** Taps through the connect screen, then awaits the composer. */
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
     * Tap the composer (`@id/composer`), set the text, tap the send button (`@id/send`) once it
     * enables — it is disabled while the composer is empty (ChatScreenController.updateSendEnabled).
     */
    private fun send(text: String) {
        var composer: UiObject2? = device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 10_000)
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

    // ----- Evidence -----

    /** Screenshots saved under the instrumentation-args dir. */
    private fun screenshot(name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("screenshotDir")
            ?: "/sdcard/Download"
        File(dir).mkdirs()
        device.takeScreenshot(File(dir, "NotificationBannerTest-$name.png"))
    }

    private companion object {
        /** The module's applicationId/namespace (build.gradle.kts). */
        const val APP_PACKAGE = "ai.poly.examples.playground.views"

        const val LAUNCH_TIMEOUT_MS = 25_000L
        const val CONNECT_TIMEOUT_MS = 60_000L

        /** 90s deadline for the live agent reply. */
        const val REPLY_TIMEOUT_MS = 90_000L

        const val MESSAGE_TEXT = "hello there"

        /** The notifier titles the alert "New message" when the agent has no display name (dev). */
        const val NOTIFICATION_TITLE = "New message"
    }
}
