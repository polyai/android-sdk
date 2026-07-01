// Copyright PolyAI Limited

package ai.poly.examples.hello.views

import ai.poly.messaging.Configuration
import ai.poly.messaging.Environment
import ai.poly.messaging.PolyMessaging
import android.app.Application

/** Initialize the SDK once at launch, in `Application.onCreate()`. */
class HelloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(this, Configuration(apiKey = API_KEY, environment = Environment.US))
    }

    companion object {
        const val API_KEY = "YOUR_API_KEY"
    }
}
