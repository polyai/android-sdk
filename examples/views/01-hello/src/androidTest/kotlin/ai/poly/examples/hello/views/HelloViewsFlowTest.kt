// Copyright PolyAI Limited

package ai.poly.examples.hello.views

import ai.poly.messaging.PolyMessaging
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dedicated UI Automator flow test (instrumented; black-box). Drives the
 * example's path against the live dev backend:
 * connect → greeting → send → user bubble → agent reply.
 */
@RunWith(AndroidJUnit4::class)
class HelloViewsFlowTest {

    private lateinit var device: UiDevice

    @Before
    fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Clear any resumable session so the run gets a brand-new greeting.
        // The instrumentation runs inside the app's own process (the
        // Application has already initialized the SDK), so call the API
        // directly. Do NOT `pm clear` here: force-stopping the target package
        // kills the instrumentation process itself ("Process crashed").
        PolyMessaging.clearResumableSession()

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

    @Test
    fun connect_send_receiveReply() {
        // 01-Hello auto-connects (no connect screen).
        // Greeting (best-effort) confirms connect; be generous against the
        // live dev backend.
        device.wait(Until.hasObject(By.textContains("Webchat")), 30_000)

        val composer: UiObject2? =
            device.wait(Until.findObject(By.res(APP_PACKAGE, "composer")), 25_000)
        assertNotNull("composer present after connect", composer)

        val before = visibleTexts()

        composer!!.click()
        composer.text = MESSAGE
        // The Send button is never disabled, so presence is the gate here.
        val send: UiObject2? = device.wait(Until.findObject(By.res(APP_PACKAGE, "send")), 15_000)
        assertNotNull("send button present", send)
        send!!.click()

        // sending → confirmed: the user bubble renders.
        assertTrue(
            "user message renders",
            device.wait(Until.hasObject(By.textContains(MESSAGE)), 15_000),
        )

        // agent reply: a NEW non-user text appears (robust to suggestion pills).
        assertTrue("agent reply appears", waitForReply(before))
    }

    private fun waitForReply(before: Set<String>, timeoutMs: Long = 60_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val texts = runCatching { visibleTexts() }.getOrDefault(emptySet())
            if (texts.any {
                    it.isNotEmpty() && it !in before && !it.contains(MESSAGE, ignoreCase = true)
                }
            ) {
                return true
            }
            SystemClock.sleep(400)
        }
        return false
    }

    /** Snapshots the labels of all visible text views. */
    private fun visibleTexts(): Set<String> =
        device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .toSet()

    private companion object {
        const val APP_PACKAGE = "ai.poly.examples.hello.views"
        const val MESSAGE = "hello from uiautomator"
    }
}
