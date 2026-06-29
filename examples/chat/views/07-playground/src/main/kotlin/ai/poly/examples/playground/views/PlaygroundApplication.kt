// Copyright PolyAI Limited

package ai.poly.examples.playground.views

import ai.poly.messaging.Configuration
import ai.poly.messaging.Environment
import ai.poly.messaging.LogLevel
import ai.poly.messaging.PolyMessaging
import android.app.Application

/**
 * Initialize the SDK once at launch, in `Application.onCreate()`.
 * The playground rebuilds a fresh Configuration from DevSettings on every connect, so
 * initialize() here just primes a sane default (the connect / start paths pass an explicit
 * config built by DevSettings).
 */
class PlaygroundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(this, Configuration(apiKey = API_KEY, environment = Environment.US, streamingEnabled = true, logLevel = LogLevel.DEBUG))
    }

    companion object {
        // Set your key from Agent Studio. (For the screenshot/E2E harness this is patched at runtime.)
        const val API_KEY = "YOUR_API_KEY"
    }
}
