// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.PolyError
import ai.poly.voice.internal.adapters.OkHttpVoiceSessionLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The voice-session link handshake against a MockWebServer WebSocket: SESSION_START (bare or inside
 * an EVENT_BATCH) triggers the LINK_TO_WEBRTC frame; timeout / early close fail.
 */
class OkHttpVoiceSessionLinkTest {

    private fun link(server: MockWebServer, timeoutMs: Long = 5_000) = OkHttpVoiceSessionLink(
        wsUrl = { sessionId, token -> server.url("/ws?session_id=$sessionId&auth_token=$token").toString() },
        logger = NoopLogger,
        handshakeTimeoutMs = timeoutMs,
    )

    /** Upgrade the next request to a WebSocket whose server sends [openFrame] on connect. */
    private fun MockWebServer.enqueueWs(openFrame: String?, received: CompletableDeferred<String>) {
        enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (openFrame != null) webSocket.send(openFrame)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.complete(text)
                    }
                })
                .build(),
        )
    }

    @Test
    fun sendsLinkFrameOnSessionStart() {
        MockWebServer().use { server ->
            val received = CompletableDeferred<String>()
            server.enqueueWs("""{"type":"EVENT_TYPE_SESSION_START"}""", received)
            server.start()

            runBlocking {
                link(server).open("tok", "sess-1", "cs-1")
                val frame = withTimeout(3_000) { received.await() }
                val json = JSONObject(frame)
                assertEquals("EVENT_TYPE_LINK_TO_WEBRTC_CONVERSATION", json.getString("type"))
                assertEquals("cs-1", json.getJSONObject("payload").getString("call_sid"))
            }
        }
    }

    @Test
    fun sessionStartInsideEventBatch_alsoLinks() {
        MockWebServer().use { server ->
            val received = CompletableDeferred<String>()
            val batch = """{"type":"EVENT_TYPE_EVENT_BATCH","payload":{"events":[{"type":"EVENT_TYPE_SESSION_START"}]}}"""
            server.enqueueWs(batch, received)
            server.start()

            runBlocking {
                link(server).open("tok", "sess-1", "cs-2")
                val json = JSONObject(withTimeout(3_000) { received.await() })
                assertEquals("cs-2", json.getJSONObject("payload").getString("call_sid"))
            }
        }
    }

    @Test
    fun handshakeTimeout_failsWhenNoSessionStart() {
        MockWebServer().use { server ->
            val received = CompletableDeferred<String>()
            server.enqueueWs(openFrame = null, received = received) // never sends SESSION_START
            server.start()
            assertFailsWith<PolyError.Voice.SignalingFailed> {
                runBlocking { link(server, timeoutMs = 400).open("tok", "sess-1", "cs-3") }
            }
        }
    }
}
