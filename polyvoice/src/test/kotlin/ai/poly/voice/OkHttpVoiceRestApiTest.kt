// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.PolyError
import ai.poly.voice.internal.adapters.OkHttpVoiceRestApi
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The voice REST adapter against MockWebServer: token/session parsing + headers, and the best-effort
 * ICE-servers fetch with STUN fallback. Robolectric because the session User-Agent reads
 * `android.os.Build.VERSION.RELEASE`.
 */
@RunWith(RobolectricTestRunner::class)
class OkHttpVoiceRestApiTest {

    private fun api(server: MockWebServer) = OkHttpVoiceRestApi(
        restBaseUrl = server.url("/api/v1").toString(),
        iceServersUrl = { token -> server.url("/api/v1/ice-servers?token=$token").toString() },
        apiKey = "connector-key",
        hostIdentifier = "test-host",
        deviceType = "tablet",
        version = "1.0.0-test",
        logger = NoopLogger,
    )

    @Test
    fun obtainAccessToken_parsesTokenAndSendsApiKeyHeader() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 200, body = """{"access_token":"acc-123"}"""))
            server.start()

            val token = runBlocking { api(server).obtainAccessToken() }
            assertEquals("acc-123", token)

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertTrue(req.url.encodedPath.endsWith("/access-token"))
            assertEquals("connector-key", req.headers["X-Token"])
            assertEquals("test-host", req.headers["X-Host"])
        }
    }

    @Test
    fun obtainAccessToken_unauthorizedMapsToAuthError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 401, body = "{}"))
            server.start()
            assertFailsWith<PolyError.Auth.Unauthorized> { runBlocking { api(server).obtainAccessToken() } }
        }
    }

    @Test
    fun createSession_parsesSessionIdWithBearerAuth() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 200, body = """{"session_id":"sess-9"}"""))
            server.start()

            val sessionId = runBlocking { api(server).createSession("acc-123") }
            assertEquals("sess-9", sessionId)

            val req = server.takeRequest()
            assertTrue(req.url.encodedPath.endsWith("/sessions"))
            assertEquals("Bearer acc-123", req.headers["Authorization"])
            // platform + device_type are reported on session create, matching the chat client.
            val body = req.body?.utf8().orEmpty()
            assertTrue(body.contains("\"platform\":\"android\""), "expected platform in $body")
            assertTrue(body.contains("\"device_type\":\"tablet\""), "expected passed device_type in $body")
        }
    }

    @Test
    fun fetchIceServers_parsesTurnAndStun() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """
                        {"iceServers":[
                          {"urls":["stun:stun.l.google.com:19302"]},
                          {"urls":["turn:turn.example.com:3478"],"username":"u","credential":"c"}
                        ]}
                    """.trimIndent(),
                ),
            )
            server.start()

            val servers = runBlocking { api(server).fetchIceServers("acc-123") }
            assertEquals(2, servers.size)
            assertEquals(listOf("turn:turn.example.com:3478"), servers[1].urls)
            assertEquals("u", servers[1].username)
            assertEquals("c", servers[1].credential)
        }
    }

    @Test
    fun fetchIceServers_fallsBackToStunOnError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 500, body = "boom"))
            server.start()
            val servers = runBlocking { api(server).fetchIceServers("acc-123") }
            assertEquals(listOf("stun:stun.l.google.com:19302"), servers.single().urls)
        }
    }

    @Test
    fun fetchIceServers_fallsBackOnMalformedBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 200, body = "not json"))
            server.start()
            val servers = runBlocking { api(server).fetchIceServers("acc-123") }
            assertEquals(listOf("stun:stun.l.google.com:19302"), servers.single().urls)
        }
    }
}
