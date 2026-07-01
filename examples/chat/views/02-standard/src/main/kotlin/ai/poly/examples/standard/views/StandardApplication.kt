// Copyright PolyAI Limited

package ai.poly.examples.standard.views

import ai.poly.messaging.Configuration
import ai.poly.messaging.Environment
import ai.poly.messaging.PolyMessaging
import android.app.Application

/** Initialize the SDK once at launch, in `Application.onCreate()`. */
class StandardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(this, Configuration(apiKey = API_KEY, environment = Environment.US))
    }

    companion object {
        // Set your key from Agent Studio. (For the screenshot/E2E harness this is patched at runtime.)
        const val API_KEY = "YOUR_API_KEY"
    }
}
