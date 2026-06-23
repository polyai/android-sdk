// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

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
 * End-to-end instrumented flow test, driven black-box with UI Automator against the LIVE dev
 * backend.
 *
 * The conversation itself is NOT exercised here: 07's chat surface is identical to
 * 06-FullReference (whose flow test drives greeting / send / reply), and the message state
 * machine is covered by the session unit tests. This rung's tests cover the playground-specific
 * surface: connecting to the live backend, and the Dev Settings toolbox opening/closing from the
 * connect screen.
 *
 * Notable behaviors:
 *  - A resumable session may exist when the suite starts. The connect helpers and connect-screen
 *    assertions therefore accept "Start Chat" / "Resume Chat" / "Start New Chat" by visible button
 *    text.
 *  - The Dev Settings gear is an icon button whose handle is its content description
 *    ("Dev Settings", AppRoot.kt).
 */
@RunWith(AndroidJUnit4::class)
class PlaygroundFlowTest {

    // The chat screen requests POST_NOTIFICATIONS (NewMessageNotifier) on first entry; granting
    // it up front keeps the system permission dialog from covering the composer mid-test.
    @get:Rule
    val grantNotifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

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

    // MARK: - Tests

    /**
     * Tapping Start Chat establishes a live session — the composer only renders once the WebSocket
     * session is up (AppRoot routes Loading → Chat on SessionStart / isReady).
     */
    @Test
    fun connects_to_live_backend() {
        startChat()
        assertTrue(
            "composer present after connecting to the live backend",
            device.wait(Until.hasObject(testTagSelector("composer")), CONNECT_TIMEOUT_MS),
        )
    }

    /**
     * The Dev Settings gear on the connect screen opens the dev toolbox sheet (title "Dev
     * Settings", with a Done button) and Done dismisses it back to the connect screen. The
     * "Apply & Start New Session" path is covered elsewhere.
     */
    @Test
    fun devSettingsPanel_opensAndCloses() {
        // When the previous test left a live chat, the relaunch animates the old chat window out
        // while the new connect screen settles — wait for the connect-screen primary button first
        // so the gear we find (and the coordinates we click) belong to the window actually on top.
        assertNotNull(
            "connect screen rendered after relaunch",
            waitForAny(15_000, By.text("Start Chat"), By.text("Resume Chat"), By.text("Start New Chat")),
        )
        // A click injected during the relaunch window transition can be swallowed (it lands in the
        // dying chat window, whose ellipsis button shares the gear's coordinates) — retry the tap
        // until the sheet's Done button is on screen. The assertion itself is unchanged: the
        // panel must open.
        var panelOpened = false
        repeat(3) {
            if (!panelOpened) {
                val gear = checkNotNull(device.wait(Until.findObject(By.desc("Dev Settings")), 15_000)) {
                    "Dev Settings gear is available on the connect screen"
                }
                gear.click()
                panelOpened = device.wait(Until.hasObject(By.text("Done")), 5_000)
            }
        }

        assertTrue("the dev settings panel opened", panelOpened)
        assertTrue(
            "the panel is the Dev Settings toolbox",
            device.wait(Until.hasObject(By.text("Dev Settings")), 3_000),
        )

        device.findObject(By.text("Done")).click()
        // The primary button may read "Start Chat" or "Resume Chat" depending on whether a
        // resumable session exists — accept either connect-screen primary.
        assertNotNull(
            "dismissing returns to the connect screen",
            waitForAny(10_000, By.text("Start Chat"), By.text("Resume Chat")),
        )
    }

    // MARK: - Helpers

    /** Taps through the connect screen by button text. */
    private fun startChat() {
        device.wait(Until.findObject(By.text("Start Chat")), 8_000)?.let { it.click(); return }
        device.wait(Until.findObject(By.text("Start New Chat")), 3_000)?.let { it.click(); return }
        device.wait(Until.findObject(By.text("Resume Chat")), 3_000)?.click()
    }

    /**
     * Selector for a Compose `testTag` surfaced via `testTagsAsResourceId`. Compose exposes the
     * raw tag as the resource name; the regex also tolerates the classic `pkg:id/tag` form for
     * robustness across Compose versions.
     */
    private fun testTagSelector(tag: String): BySelector =
        By.res(Pattern.compile("(?:${Pattern.quote(APP_PACKAGE)}:id/)?${Pattern.quote(tag)}"))

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

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.playground.compose"
        const val LAUNCH_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
