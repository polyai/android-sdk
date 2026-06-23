// Copyright PolyAI Limited

package ai.poly.examples.richcontent.views

import ai.poly.messaging.PolyMessaging
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check for the new-message notification (`NewMessageNotifier.kt`)
 * — driven against the live dev backend.
 *
 * The `NewMessageNotifier` has no launch-argument hook, and ChatActivity uses
 * the default [NotificationPolicy.WHEN_BACKGROUNDED]: it posts only when the
 * chat is not RESUMED (collection is CREATED-gated, so it keeps running off
 * screen).
 *
 * So this test exercises the policy the app actually ships: connect, send a
 * message, immediately background the app (pressHome), and assert the
 * new-message notification lands in the shade. The notifier titles the alert
 * "New message" when the agent has no display name (it has none on dev).
 *
 * A dedupe-on-relaunch scenario is intentionally NOT covered here: killing or
 * relaunching the host app kills the instrumentation process on Android, so it
 * is covered at the adb-script level instead.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBannerTest {

    // The androidx idiom for notification access on API 33+ (the @Before below
    // also re-grants via shell, belt-and-braces).
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // For a brand-new greeting, the instrumentation runs inside the app's
        // own process, so call the SDK directly — do NOT `pm clear` (it kills
        // the instrumentation process
        // itself). The notifier's already-shown messageId store survives, but a
        // fresh session means fresh messageIds, so dedupe cannot mask new replies.
        PolyMessaging.clearResumableSession()
        // Belt-and-braces re-grant so the notifier can post (and so
        // ChatActivity's onCreate permission dialog never appears).
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

    /** Verifies the new-message banner appears while the chat is foreground. */
    @Test
    fun newMessageBanner() {
        connect()

        // Let the greeting land while the chat is RESUMED so WHEN_BACKGROUNDED
        // marks it shown WITHOUT posting — the notification we find below then
        // belongs to the agent's reply. Best-effort.
        pollUntil(30_000) { visibleTexts().any { it.isNotEmpty() && it !in CHROME_TEXTS } }

        screenshot("1-before-send.png")
        send("hello there")

        // Background the app right away: WHEN_BACKGROUNDED only posts while the
        // chat is off screen.
        device.pressHome()

        // Poll the notification shade for the agent reply's notification.
        var sawNotification = false
        val deadline = SystemClock.uptimeMillis() + REPLY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline && !sawNotification) {
            device.openNotification()
            sawNotification =
                device.wait(Until.hasObject(By.textContains(NOTIFICATION_TITLE)), 10_000)
            if (!sawNotification) device.pressBack() // close before re-opening the shade
        }

        if (sawNotification) screenshot("2-banner.png") // shade open, notification visible
        screenshot("3-after.png")
        device.pressBack() // close the shade before finishing

        assertTrue("new-message notification posted for the agent reply", sawNotification)
    }

    // MARK: - Per-example flow

    /** 03 auto-connects (no connect screen) — wait for the composer. */
    private fun connect() {
        val composer: UiObject2? =
            device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 30_000)
        assertNotNull("composer present after auto-connect", composer)
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

    // MARK: - Helpers

    /** Captures a screenshot attachment. */
    private fun screenshot(name: String) {
        val dir = File(
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: "/sdcard/Download",
        )
        dir.mkdirs()
        device.takeScreenshot(File(dir, "notificationBanner-$name"))
    }

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

        /**
         * The notifier titles every alert "New message" when the agent has no
         * display name — it has none on dev, so this is the reliable signal.
         */
        const val NOTIFICATION_TITLE = "New message"

        /** Polls 90s for the banner; the reply rides the live dev backend. */
        const val REPLY_TIMEOUT_MS = 90_000L

        /** Static chrome present before any message: action-bar title + "End" menu item. */
        val CHROME_TEXTS = setOf("Chat", "End")
    }
}
