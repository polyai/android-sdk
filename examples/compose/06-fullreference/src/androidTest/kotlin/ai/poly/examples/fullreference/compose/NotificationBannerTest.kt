// Copyright PolyAI Limited

package ai.poly.examples.fullreference.compose

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check for the new-message notification
 * (components/NewMessageNotifier.kt) — driven against the live dev backend.
 *
 * Notes:
 *   - `AppRoot` wires `NewMessageNotifier(session, policy = WHEN_BACKGROUNDED)`:
 *     it posts a notification only when the chat is NOT on screen (lifecycle below
 *     RESUMED; collection stays alive at CREATED, i.e. while stopped). So this
 *     test sends a message and immediately BACKGROUNDS the app, then asserts the
 *     agent reply's notification in the system shade instead of a foreground banner.
 *   - The notification title is `agentName ?: "New message"`; the dev agent has no
 *     display name, so "New message" is the reliable signal (NewMessageNotifier.kt
 *     sets the same fallback title).
 *   - Screenshot evidence is saved via UiDevice.takeScreenshot.
 *
 * The resume-dedupe scenario (a notification must not re-fire for an
 * already-shown message) is covered at the adb-script level instead, because
 * killing and relaunching the host app kills the instrumentation process (the
 * test runs inside the app process).
 */
@RunWith(AndroidJUnit4::class)
class NotificationBannerTest {

    // Pre-grant POST_NOTIFICATIONS so NewMessageNotifier can post and its runtime
    // permission dialog never appears.
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant()
        }

    private lateinit var device: UiDevice

    // Launch the app (connect() below picks the fresh path).
    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)) {
            "no launch intent for $APP_PACKAGE"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertTrue(
            "app appears on screen",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), 10_000),
        )
    }

    // WHEN_BACKGROUNDED policy (see class KDoc): connect, send via the composer,
    // immediately background the app, then poll the notification shade for the
    // agent reply's "New message" notification.
    @Test
    fun newMessageNotification_postsForReplyWhileBackgrounded() {
        connect()
        // Wait (deadline-poll) for the greeting so it lands while the chat is
        // RESUMED — the notifier marks it shown without posting, leaving the reply
        // as the only notification source.
        device.wait(Until.hasObject(By.text(GREETING)), 30_000)

        val shotDir = screenshotDir()
        device.takeScreenshot(File(shotDir, "1-before-send.png"))

        send("hello there")
        // Background immediately so the reply arrives while the chat is off screen
        // (WHEN_BACKGROUNDED posts only then; the live agent replies in a few
        // seconds, comfortably after the home transition).
        device.pressHome()

        // Poll the shade with a deadline (90s).
        var sawNotification = false
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            device.openNotification()
            if (device.wait(Until.hasObject(By.textContains(NOTIFICATION_TITLE)), 5_000)) {
                device.takeScreenshot(File(shotDir, "2-NOTIFICATION.png"))
                sawNotification = true
                break
            }
            device.pressBack() // close the shade before re-polling
            SystemClock.sleep(1_000)
        }
        device.takeScreenshot(File(shotDir, "3-after.png"))
        device.pressBack() // close the shade before finishing

        assertTrue(
            "new-message notification posted for the agent reply while backgrounded",
            sawNotification,
        )
    }

    // MARK: - Per-example flow

    // 06 uses a connect screen — tap through it by visible button text ("Start New
    // Chat" when a previous session is resumable, first-run "Start Chat" otherwise,
    // "Resume Chat" as the last-resort fallback).
    private fun connect() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var tapped = false
        while (SystemClock.elapsedRealtime() < deadline && !tapped) {
            val button = device.findObject(By.text("Start New Chat"))
                ?: device.findObject(By.text("Start Chat"))
                ?: device.findObject(By.text("Resume Chat"))
            if (button != null) {
                runCatching { button.click() }
                tapped = true
            } else {
                SystemClock.sleep(500)
            }
        }
        assertTrue("a connect button was offered", tapped)
        assertTrue(
            "composer present after connect",
            device.wait(Until.hasObject(By.res("composer")), 30_000),
        )
    }

    // Set the composer text via semantics and click the explicit send button
    // (contentDescription "Send message").
    private fun send(text: String) {
        val composer = device.wait(Until.findObject(By.res("composer")), 10_000)
        assertNotNull("composer present", composer)
        composer!!.click()
        // Re-find after the click (focus recomposes the node) before setText.
        val focused = device.wait(Until.findObject(By.res("composer")), 5_000)
        assertNotNull("composer present after focus", focused)
        focused!!.text = text
        val sendButton: UiObject2? = device.wait(Until.findObject(By.desc("Send message")), 5_000)
        assertNotNull("send button present", sendButton)
        sendButton!!.click()
    }

    // MARK: - Evidence

    /** Screenshot evidence dir: instrumentation arg if provided, else /sdcard/Download. */
    private fun screenshotDir(): File {
        val args = InstrumentationRegistry.getArguments()
        val dir = args.getString("screenshotDir") ?: "/sdcard/Download"
        return File(dir).apply { mkdirs() }
    }

    private companion object {
        // The module's applicationId (build.gradle.kts) — never a credential.
        const val APP_PACKAGE = "ai.poly.examples.fullreference.compose"

        // NewMessageNotifier titles the notification `agentName ?: "New message"`;
        // the dev agent has no display name.
        const val NOTIFICATION_TITLE = "New message"

        // Greeting text from the live dev agent (same as the flow test).
        val GREETING: Pattern = Pattern.compile("(?si).*(webchat|welcome).*")
    }
}
