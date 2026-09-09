// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyMessaging
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `PolyVoice.call` web-calling-token contract: the token comes from
 * [Configuration.webrtcToken], and the zero-`Configuration` overload reads whatever
 * [PolyMessaging.initialize] last stored.
 *
 * `PolyVoice.call(...)`'s success path builds the real WebRTC engine
 * ([ai.poly.voice.internal.adapters.AndroidWebRtcPeer]), which — like the rest of that adapter —
 * isn't covered by JVM unit tests (it needs the native engine + a device). Validation is exercised
 * through `PolyVoice.call(...)` throw paths, which fail fast before the engine is ever touched.
 */
@RunWith(RobolectricTestRunner::class)
class PolyVoiceTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun voiceOptions_defaults() {
        val options = VoiceOptions()
        assertTrue(options.speakerphone, "hands-free is the default for a voice agent")
        assertNull(options.signalingHost)
    }

    @Test
    fun configurationBuilder_setsWebrtcToken() {
        assertEquals("t", Configuration.Builder("k").webrtcToken("t").build().webrtcToken)
    }

    @Test
    fun call_emptyApiKey_throws() {
        val error = assertFailsWith<PolyError.InvalidConfiguration> {
            PolyVoice.call(context(), Configuration(apiKey = "", webrtcToken = "t"))
        }
        assertTrue(error.detail.contains("apiKey"))
    }

    @Test
    fun call_noWebrtcToken_throws() {
        val error = assertFailsWith<PolyError.InvalidConfiguration> {
            PolyVoice.call(context(), Configuration(apiKey = "k"))
        }
        assertTrue(error.detail.contains("webrtcToken"))
    }

    @Test
    fun zeroArgCall_readsPolyMessagingInitialize() {
        // The PolyMessaging.chat()/voice() pattern: call(context) alone, no config, reading
        // whatever PolyMessaging.initialize(...) last stored (process-global, like those two).
        // apiKey is set but webrtcToken isn't (and options supplies none either), so this fails fast
        // on the token check — proving the stored config flowed through — before ever touching the
        // (JVM-untestable) native engine.
        PolyMessaging.initialize(context(), Configuration(apiKey = "k"))
        val error = assertFailsWith<PolyError.InvalidConfiguration> { PolyVoice.call(context()) }
        assertTrue(error.detail.contains("webrtcToken"))
    }
}
