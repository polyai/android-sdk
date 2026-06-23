// Copyright PolyAI Limited

package ai.poly.messaging.internal.adapters

import ai.poly.messaging.LogLevel
import ai.poly.messaging.PolyLogger
import android.util.Log

/**
 * Default [PolyLogger] — writes via `android.util.Log`, filtered by the configured
 * threshold. The metadata is rendered ` {key=value …}` (sorted).
 */
internal class AndroidLogLogger(private val threshold: LogLevel) : PolyLogger {
    override fun log(level: LogLevel, message: String, metadata: Map<String, Any?>?) {
        if (level == LogLevel.NONE || level.level > threshold.level) return
        val meta = metadata?.takeIf { it.isNotEmpty() }
            ?.entries?.sortedBy { it.key }
            ?.joinToString(separator = " ", prefix = " {", postfix = "}") { "${it.key}=${it.value}" }
            ?: ""
        val line = "$message$meta"
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.NONE -> {}
        }
    }

    private companion object {
        const val TAG = "PolyMessaging"
    }
}
