// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Environment
import ai.poly.messaging.PolyError
import ai.poly.voice.internal.VoiceHosts
import ai.poly.voice.internal.adapters.OkHttpBridgeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live end-to-end check against a real `webrtc-bridge` deployment, driving the actual production
 * adapter. Proves the call-lifecycle contract the whole pipeline rests on: provision mints a call,
 * the per-call token gates every route, and DELETE tears it down.
 *
 * No libwebrtc here, so no SDP exchange follows — every HTTP step before it is exercised for real,
 * which is what pinned down the provision response shape the unit tests now assert against. The
 * media path is verified on-device.
 *
 * OPT-IN: skipped unless the dev credential is supplied via env so no secrets live in source:
 * ```
 * POLYVOICE_WEBRTC_TOKEN=… ./gradlew :polyvoice:testDebugUnitTest --tests '*LiveBridgeBackendTest*'
 * ```
 * Defaults to the dev cluster; override with `POLYVOICE_CLUSTER`.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBridgeBackendTest {

    private val webrtcToken: String? = System.getenv("POLYVOICE_WEBRTC_TOKEN")
    private val cluster: String = System.getenv("POLYVOICE_CLUSTER") ?: "dev"

    private fun hosts() = VoiceHosts(Environment.cluster(cluster))

    @Test
    fun `provisions and deletes a call on the live bridge`() {
        assumeTrue("set POLYVOICE_WEBRTC_TOKEN to run the live bridge check", !webrtcToken.isNullOrBlank())

        runBlocking {
            val bridge = OkHttpBridgeApi(
                baseUrl = hosts().bridgeBaseUrl(),
                authToken = webrtcToken!!,
                logger = NoopLogger,
            )

            val provision = bridge.provision()
            assertTrue(provision.callId.startsWith("call-"), "the bridge mints the call id")
            assertTrue(provision.credentials.connectPath.isNotEmpty())
            assertNotNull(provision.credentials.token, "a per-call token gates the remaining routes")
            assertNotNull(provision.credentials.eventsPath)
            assertNotNull(provision.credentials.pullPath)
            assertNotNull(provision.credentials.renegotiatePath)
            assertTrue(bridge.eventsUrl(provision)!!.startsWith("ws"), "the events URL is a WebSocket URL")

            // Junk SDP with a valid call token must fail at the SFU, not at auth — proof the
            // X-Call-Token header is accepted where the contract says it is.
            val error = assertFailsWith<PolyError.Voice.SignalingFailed> {
                bridge.sendOffer(provision, sdp = "not-an-sdp", mid = "0")
            }
            assertTrue("401" !in (error.message ?: ""), "a valid per-call token must get past auth")

            bridge.deleteCall(provision)
        }
    }

    /** The credential contract from the other side: no valid Bearer token, no call. */
    @Test
    fun `rejects an unauthenticated provision`() {
        assumeTrue("set POLYVOICE_WEBRTC_TOKEN to run the live bridge check", !webrtcToken.isNullOrBlank())

        runBlocking {
            val bridge = OkHttpBridgeApi(
                baseUrl = hosts().bridgeBaseUrl(),
                authToken = "not-a-token",
                logger = NoopLogger,
            )
            val error = assertFailsWith<PolyError.Voice.SignalingFailed> { bridge.provision() }
            assertTrue("401" in (error.message ?: ""), "expected a 401, got ${error.message}")
        }
    }
}
