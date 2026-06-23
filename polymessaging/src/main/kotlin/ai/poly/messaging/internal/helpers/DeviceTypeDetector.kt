// Copyright PolyAI Limited

package ai.poly.messaging.internal.helpers

import android.content.Context
import android.content.pm.PackageManager

/**
 * Classifies the device as `mobile` / `tablet` for the `device_type` dimension on session
 * create (orthogonal to `platform`), using `smallestScreenWidthDp >= 600` (the canonical
 * tablet threshold). `desktop` is not applicable on Android.
 */
internal object DeviceTypeDetector {
    const val MOBILE = "mobile"
    const val TABLET = "tablet"

    fun detect(context: Context): String {
        val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
        return if (smallestWidthDp >= 600) TABLET else MOBILE
    }

    /** Pure mapping, unit-testable without a Context. */
    fun deviceType(smallestWidthDp: Int, @Suppress("UNUSED_PARAMETER") hasTelephony: Boolean = true): String =
        if (smallestWidthDp >= 600) TABLET else MOBILE

    @Suppress("unused")
    private fun hasTelephony(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
}
