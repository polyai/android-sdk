// Copyright PolyAI Limited

package ai.poly.examples.richcontent.compose

import android.content.Intent
import androidx.core.app.NotificationManagerCompat
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
 * End-to-end check for the new-message notification
 * (components/NewMessageNotifier.kt) — driven against the live dev backend.
 *
 * Notable behaviors:
 *
 * 1. No launch-argument hooks (accepted idiom), and ChatScreen wires
 *    `NotificationPolicy.WHEN_BACKGROUNDED` — confirmed in NewMessageNotifier.kt:
 *    a notification is posted only when the chat is NOT on screen (lifecycle
 *    below RESUMED); collection is gated on CREATED so a backgrounded app still
 *    notifies. So instead of waiting for a foreground banner, this test sends a
 *    message, immediately backgrounds the app (pressHome), and polls the
 *    notification shade for the agent reply's notification. The notification
 *    title is `agentName ?: "New message"` — the agent has no display name on
 *    dev, so "New message" is the reliable signal.
 *
 * 2. A resume-does-not-re-notify scenario is intentionally NOT covered here:
 *    killing and relaunching the host app on Android also kills the
 *    instrumentation process, so that scenario can't run inside one
 *    instrumented test. It is covered at the adb-script level instead.
 *
 * The launch keeps the app's data (no `pm clear`): the NotifiedMessageStore
 * dedupe survives, so replayed history can't raise stale banners and the only
 * notification we can see is the one for *our* fresh reply.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBannerTest {

    @get:Rule
    val grantNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear any notification a previous run left in the shade so the poll
        // below can only match the one raised for this run's reply.
        NotificationManagerCompat.from(context).cancelAll()

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
     * Android's WHEN_BACKGROUNDED policy notifies while the app is
     * backgrounded, so the shade — not an in-app banner — is asserted.
     */
    @Test
    fun newMessageBanner() {
        connect()

        takeScreenshot("1-before-send")
        send("hello there")
        // Background IMMEDIATELY after the send. The live dev agent's completed
        // reply lands ~2s after the send, and WHEN_BACKGROUNDED both suppresses
        // AND permanently dedupes (markShown) any reply that completes while the
        // chat is still RESUMED — so waiting for the user bubble here loses the
        // race and no banner can ever appear. The WS send is already queued by
        // the click, so the message is delivered regardless, and the SDK keeps
        // the socket open in the background (the Coordinator only reacts to
        // foreground events), so the reply arrives while we're off screen.
        device.pressHome()
        device.openNotification()
        // 90s deadline for the live agent reply to land and post into the shade.
        val sawNotification: Boolean =
            device.wait(Until.hasObject(By.textContains("New message")), 90_000)
        takeScreenshot(if (sawNotification) "2-NOTIFICATION" else "2-no-notification")

        device.pressBack() // close the shade before finishing
        takeScreenshot("3-after")

        assertTrue(
            "new-message notification posted for the agent reply while backgrounded",
            sawNotification,
        )
    }

    // MARK: - Per-example flow

    /** 03-RichContent auto-connects (no connect screen). */
    private fun connect() {
        assertNotNull(
            "composer present after auto-connect",
            // testTagsAsResourceId exposes the RAW tag as the resource id (no
            // "pkg:id/" prefix), so match with By.res(String), not By.res(pkg, id).
            device.wait(Until.findObject(By.res("composer")), 30_000),
        )
    }

    /**
     * The Android composer sends via the round Send button
     * (contentDescription "Send" in ChatScreen.InputBar).
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

    // MARK: - Evidence

    /**
     * Capture a screenshot as test evidence. Best-effort:
     * evidence capture must never fail the flow itself.
     */
    private fun takeScreenshot(name: String) {
        runCatching {
            val dir = File(
                InstrumentationRegistry.getArguments().getString("screenshotDir")
                    ?: "/sdcard/Download",
            )
            dir.mkdirs()
            device.takeScreenshot(File(dir, "NotificationBannerTest-$name.png"))
        }
    }

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.richcontent.compose"
    }
}
