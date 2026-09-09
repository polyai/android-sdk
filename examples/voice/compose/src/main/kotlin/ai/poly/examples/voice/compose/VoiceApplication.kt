// Copyright PolyAI Limited

package ai.poly.examples.voice.compose

import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyMessaging
import android.app.Application

/**
 * Initialize the SDK once, at app launch, in `Application.onCreate()` — both tokens, so every
 * `PolyVoice.call(context)` site downstream needs no `Configuration`/`VoiceOptions` of its own.
 */
class VoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PolyMessaging.initialize(
            context = this,
            config = Configuration(
                apiKey = API_KEY, // connector token, sent as X-Token
                webrtcToken = WEBRTC_TOKEN, // the connector's WebRTC token (distinct from apiKey)
                // environment defaults to Environment.US; hostIdentifier defaults to this app's package name
            ),
        )
    }

    companion object {
        // Set your connector from Agent Studio › Connector Settings (see the README's "Use your own agent").
        const val API_KEY = "YOUR_API_KEY"
        const val WEBRTC_TOKEN = "YOUR_WEBRTC_TOKEN"
    }
}
