// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.adapters.OkHttpRestApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /sessions/{id}/context` over a real OkHttp client. Robolectric for the User-Agent's `Build.VERSION`. */
@RunWith(RobolectricTestRunner::class)
class VerifiedContextRestTests {

    private val logged = mutableListOf<String>()
    private val recordingLogger = object : PolyLogger {
        override fun log(level: LogLevel, message: String, metadata: Map<String, Any?>?) { logged += message }
    }

    private fun api(server: MockWebServer) = OkHttpRestApi(
        baseUrl = server.url("/api/v1").toString().trimEnd('/'),
        apiKey = "connector-key",
        hostIdentifier = "test-host",
        version = "1.0.0-test",
        logger = recordingLogger,
    )

    private fun submit(api: OkHttpRestApi, notifyAgent: Boolean = false) = runBlocking {
        api.submitVerifiedContext("session-access-token", "sess-1", "signed.jwt.token", notifyAgent)
    }

    private fun failure(code: Int, body: String): PolyError.VerifiedContext = MockWebServer().use { server ->
        server.enqueue(MockResponse(code = code, body = body))
        server.start()
        assertFailsWith<PolyError.VerifiedContext> { submit(api(server)) }
    }

    @Test
    fun submit_postsTokenWithSessionBearerAuth() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 204))
            server.start()

            submit(api(server))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions/sess-1/context", request.url.encodedPath)
            assertEquals("Bearer session-access-token", request.headers["Authorization"])
            val body = JSONObject(assertNotNull(request.body).utf8())
            assertFalse(body.has("notify_agent"))
            assertEquals("signed.jwt.token", body.getString("token"))
        }
    }

    @Test
    fun submit_sendsNotifyAgentOnlyWhenAsked() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 204))
            server.start()

            submit(api(server), notifyAgent = true)

            val body = JSONObject(assertNotNull(server.takeRequest().body).utf8())
            assertTrue(body.getBoolean("notify_agent"))
        }
    }

    @Test
    fun submit_neverLogsTheSignedToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 204))
            server.start()

            submit(api(server))

            assertTrue(logged.isNotEmpty(), "Positive control: the request was logged")
            assertFalse(logged.any { it.contains("signed.jwt.token") })
        }
    }

    @Test
    fun errors_mapToDistinctStates() {
        assertEquals(
            PolyError.VerifiedContext.VerificationFailed("unknown signing key"),
            failure(401, """{"message":"unknown signing key"}"""),
        )
        // A non-JSON body (e.g. an edge proxy's error page) falls back to the status.
        assertEquals(
            PolyError.VerifiedContext.VerificationFailed("HTTP 403"),
            failure(403, """<?xml version="1.0"?><Error><Code>AccessDenied</Code></Error>"""),
        )
        assertEquals(PolyError.VerifiedContext.InvalidToken("token is required"), failure(400, """{"error":"token is required"}"""))
        assertEquals(PolyError.VerifiedContext.InvalidToken("HTTP 413"), failure(413, ""))
        assertEquals(PolyError.VerifiedContext.SubmitFailed("HTTP 404"), failure(404, ""))
    }

    @Test
    fun cancellingTheCaller_stopsAnInFlightSubmit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(204).headersDelay(30, TimeUnit.SECONDS).build())
            server.start()
            val api = api(server)

            val started = System.nanoTime()
            val result = runBlocking {
                withTimeoutOrNull(200) {
                    api.submitVerifiedContext("session-access-token", "sess-1", "signed.jwt.token", false)
                }
            }

            assertNull(result)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "Cancellation must not wait for the response")
        }
    }
}
