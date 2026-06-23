// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

import ai.poly.messaging.Configuration
import ai.poly.messaging.Environment
import ai.poly.messaging.PolyMessaging
import android.app.Application

/**
 * Initialize the SDK once, at app launch, in `Application.onCreate()`.
 * The playground rebuilds a fresh Configuration from DevSettings on every
 * connect, so this just primes a sane default (and seeds the DevSettings
 * environment picker).
 */
class PlaygroundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(
            context = this,
            config = Configuration(
                apiKey = API_KEY,
                environment = Environment.US,
            ),
        )
    }

    companion object {
        // Set your key from Agent Studio. (For the screenshot/E2E harness this is patched at runtime.)
        const val API_KEY = "YOUR_API_KEY"
    }
}
