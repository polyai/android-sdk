// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.adapters.OkHttpRestApi
import ai.poly.messaging.internal.ports.SessionCreateContext
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hermetic REST-headers probe. Asserts the request-construction invariants
 * (`device_type`, `platform`, `Authorization`, and a 2xx response) directly on MockWebServer's
 * RecordedRequest.
 *
 * Runs under Robolectric because the session User-Agent reads `android.os.Build.VERSION.RELEASE`.
 */
@RunWith(RobolectricTestRunner::class)
class LiveRestHeadersProbeTests {

    @Test
    fun sessionCreate_bodyCarriesDeviceTypePlatformAndBearerAuth() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse(code = 200, body = """{"access_token":"$TEST_TOKEN","expires_in":3600}"""),
            )
            server.enqueue(MockResponse(code = 200, body = """{"session_id":"abc"}"""))
            server.start()

            val api = OkHttpRestApi(
                baseUrl = server.url("/api/v1").toString(),
                apiKey = "connector-key",
                hostIdentifier = "test-host",
                version = "1.0.0-test",
                logger = NoopLogger,
            )

            val (tokenResp, created) = runBlocking {
                val token = api.obtainAccessToken()
                // deviceType is passed explicitly through SessionCreateContext —
                // detection itself (the 600dp heuristic) is out of scope here.
                val ctx = SessionCreateContext(streamingEnabled = true, platform = "android", deviceType = "mobile")
                token to api.createSession(token.token, ctx)
            }

            // The /access-token response parsed.
            assertTrue(tokenResp.token.isNotEmpty(), "access token should be returned")
            assertEquals(TEST_TOKEN, tokenResp.token)

            // The /sessions response parsed.
            // Both endpoints returning parsed results confirms the 2xx responses.
            assertTrue(created.sessionId.isNotEmpty(), "session_id should be returned")
            assertEquals("abc", created.sessionId)

            // Consume the /access-token request first (requests are recorded in order).
            val tokenRequest = server.takeRequest()
            assertEquals("POST", tokenRequest.method)
            assertTrue(tokenRequest.url.encodedPath.endsWith("/access-token"))

            val sessionRequest = server.takeRequest()
            assertEquals("POST", sessionRequest.method)
            assertTrue(sessionRequest.url.encodedPath.endsWith("/sessions"))

            // session-create body must include device_type and platform.
            val bodyText = assertNotNull(sessionRequest.body, "session-create must send a body").utf8()
            val body = JSONObject(bodyText)
            assertEquals("mobile", body.getString("device_type"))
            assertEquals("android", body.getString("platform"))
            assertTrue(body.getBoolean("streaming_enabled"))

            // session-create must send an Authorization header (Bearer access token).
            assertEquals("Bearer $TEST_TOKEN", sessionRequest.headers["Authorization"])
        }
    }
}

// Structurally-valid non-expiring JWT (same shape as Doubles.kt's FakeRestApi.TEST_JWT); private
// top-level copy so this file stays self-contained for sibling agents writing concurrently.
private const val TEST_TOKEN = "eyJhbGciOiJub25lIn0.e30.sig"
