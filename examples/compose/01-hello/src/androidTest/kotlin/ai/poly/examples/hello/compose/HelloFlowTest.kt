// Copyright PolyAI Limited

package ai.poly.examples.hello.compose

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
 * End-to-end UI Automator flow test, driven against the live dev backend.
 *
 * 01-Hello is the smallest possible chat — its README is just
 * initialize + send + render — so this test stays minimal:
 *   connect (auto) → greeting → send → user bubble → agent reply.
 *
 * UI Automator is a black-box driver that doesn't wait for an idle app, so
 * every assertion is a deadline-based poll.
 */
@RunWith(AndroidJUnit4::class)
class HelloFlowTest {

    private lateinit var device: UiDevice

    // Launch the app, then connectFresh().
    // (The test tolerates either a fresh greeting or a resumed transcript — the
    // assertions below hold for both.)
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
        connectFresh()
    }

    // MARK: - Test

    // The whole 01-Hello surface — greeting renders, a sent message shows as a
    // user bubble, and the agent replies.
    @Test
    fun send_and_reply() {
        assertTrue(
            "greeting renders after connect",
            device.wait(Until.hasObject(By.text(GREETING)), 30_000),
        )

        val before = textSnapshot()
        send(TEST_MESSAGE)
        assertTrue(
            "user message renders",
            device.wait(Until.hasObject(By.textContains(TEST_MESSAGE)), 15_000),
        )
        assertTrue("agent reply appears", waitForNewReply(before))
    }

    // MARK: - Flow helpers

    // 01-Hello auto-connects (no connect screen) — just wait for the composer.
    private fun connectFresh() {
        assertTrue(
            "composer present after connect",
            device.wait(Until.hasObject(By.res(COMPOSER_TAG)), 30_000),
        )
    }

    // The module sends via its explicit "Send" button, so set the composer text
    // and click that.
    private fun send(text: String) {
        val composer = device.wait(Until.findObject(By.res(COMPOSER_TAG)), 10_000)
        assertNotNull("composer present", composer)
        composer.click()
        // Re-find after the click (focus can recompose the node) before setText —
        // UiObject2.setText drives the compose text field via its semantics.
        val focused = device.wait(Until.findObject(By.res(COMPOSER_TAG)), 5_000)
        assertNotNull("composer present after focus", focused)
        focused.text = text
        val sendButton = device.wait(Until.findObject(By.res(SEND_BUTTON_TAG)), 5_000)
        assertNotNull("send button present", sendButton)
        sendButton.click()
    }

    // MARK: - Query helpers (deadline polls are robust to the non-idle app)

    // Every visible text on screen.
    private fun textSnapshot(): Set<String> = visibleTexts().toSet()

    private fun visibleTexts(): List<String> =
        try {
            device.findObjects(By.text(ANY_TEXT)).mapNotNull {
                try {
                    it.text
                } catch (_: Exception) { // node went stale mid-walk (live app)
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

    // A new non-user text appears (robust to the exact reply content).
    private fun waitForNewReply(before: Set<String>, timeoutMs: Long = 60_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = visibleTexts()
            if (now.any {
                    it.isNotEmpty() && it !in before &&
                        !it.contains(TEST_MESSAGE, ignoreCase = true)
                }
            ) {
                return true
            }
            SystemClock.sleep(500)
        }
        return false
    }

    private companion object {
        // The module's applicationId (build.gradle.kts) — never a credential.
        const val APP_PACKAGE = "ai.poly.examples.hello.compose"
        const val TEST_MESSAGE = "hello from uiautomator"

        // Compose `testTagsAsResourceId` exposes the raw testTag as the node's
        // resource-id (no "<pkg>:id/" prefix — verified via `uiautomator dump`),
        // so match with By.res(String), not By.res(pkg, id).
        const val COMPOSER_TAG = "composer"
        const val SEND_BUTTON_TAG = "sendButton"

        // Any node with text containing "Webchat" or "Welcome" (case-insensitive,
        // multi-line).
        val GREETING: Pattern = Pattern.compile("(?si).*(webchat|welcome).*")

        // Any node exposing non-empty text.
        val ANY_TEXT: Pattern = Pattern.compile("(?s).+")
    }
}
