// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.LogLevel
import ai.poly.messaging.PolyLogger
import android.util.Log

/** Routes voice SDK logs to Logcat under the `PolyVoice` tag, filtered by the configured threshold. */
internal class AndroidLogLogger(private val threshold: LogLevel) : PolyLogger {
    override fun log(level: LogLevel, message: String, metadata: Map<String, Any?>?) {
        // Higher LogLevel.level == more verbose (DEBUG=4). Skip anything more verbose than the threshold.
        if (threshold == LogLevel.NONE || level.level > threshold.level) return
        val line = if (metadata.isNullOrEmpty()) message else "$message $metadata"
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.ERROR -> Log.e(TAG, line)
            LogLevel.NONE -> Unit
        }
    }

    private companion object {
        const val TAG = "PolyVoice"
    }
}
