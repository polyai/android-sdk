// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.DeviceTypeDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `DeviceTypeDetectorTests` suite. The accepted Android idiom is the 600dp smallest-width heuristic
 * (`smallestScreenWidthDp >= 600` => tablet), and `desktop` is intentionally absent on Android.
 *
 * The class runs under Robolectric because `detect(context)` needs a real `Configuration`;
 * the other three tests are pure mappings and use no Android framework state.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceTypeDetectorTests {

    // ---- Pure mapping (deviceType(smallestWidthDp)) ----

    // Android uses the 600dp heuristic to map below-threshold widths to mobile.
    @Test
    fun deviceTypeBelowSw600dpIsMobile() {
        assertEquals(DeviceTypeDetector.MOBILE, DeviceTypeDetector.deviceType(smallestWidthDp = 320))
        assertEquals(DeviceTypeDetector.MOBILE, DeviceTypeDetector.deviceType(smallestWidthDp = 599))
    }

    // 600dp is the canonical tablet threshold; the exact-boundary assertion pins the
    // ambiguous-idiom handling at the boundary.
    @Test
    fun deviceTypeAtOrAboveSw600dpIsTablet() {
        assertEquals(DeviceTypeDetector.TABLET, DeviceTypeDetector.deviceType(smallestWidthDp = 600))
        assertEquals(DeviceTypeDetector.TABLET, DeviceTypeDetector.deviceType(smallestWidthDp = 800))
    }

    // ---- Live detection ----

    // Verifies detect returns a valid value on the test host: the default Robolectric
    // device resolves to mobile.
    @Test
    fun detectReturnsAValidDeviceType() {
        val detected = DeviceTypeDetector.detect(RuntimeEnvironment.getApplication())
        assertTrue(detected in setOf(DeviceTypeDetector.MOBILE, DeviceTypeDetector.TABLET))
    }

    // ---- Wire contract ----

    // These constants feed the `device_type` field that OkHttpRestApi serializes on session
    // create. "desktop" is intentionally absent on Android (accepted platform idioms: mobile
    // and tablet only).
    @Test
    fun wireConstantsMatchWebSdkContract() {
        assertEquals("mobile", DeviceTypeDetector.MOBILE)
        assertEquals("tablet", DeviceTypeDetector.TABLET)
    }
}
