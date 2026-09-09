// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Environment
import ai.poly.messaging.PolyError
import ai.poly.voice.internal.IceServer
import ai.poly.voice.internal.VoiceHosts
import ai.poly.voice.internal.protocol.BridgeProtocol
import org.json.JSONObject
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-level tests for the `webrtc-bridge` protocol. The provision payload here is a verbatim
 * capture from the dev deployment (`webrtc-bridge.dev.polyai.app`), so a server-side shape change
 * breaks these rather than a live call.
 */
class BridgeProtocolTest {

    private val liveProvisionJson = """
        {"callId":"call-5f9ec645","creds":{"provider":"cloudflare",
        "connectUrl":"/api/v1/call/call-5f9ec645/sdp",
        "token":"1788794409.CSOgLZEbgQ9bloJ0EE7zk8KHVNSTpogvB_sEaIzGtCA",
        "trackName":"agent-echo","extra":{
        "eventsUrl":"/api/v1/call/call-5f9ec645/events",
        "pullUrl":"/api/v1/call/call-5f9ec645/sdp/pull",
        "renegotiateUrl":"/api/v1/call/call-5f9ec645/sdp/renegotiate"}}}
    """.trimIndent()

    // ── provision ─────────────────────────────────────────────────

    @Test
    fun `parses the live dev provision response`() {
        val provision = BridgeProtocol.parseProvision(liveProvisionJson)!!

        assertEquals("call-5f9ec645", provision.callId)
        assertEquals("cloudflare", provision.credentials.provider)
        assertEquals("/api/v1/call/call-5f9ec645/sdp", provision.credentials.connectPath)
        assertEquals("agent-echo", provision.credentials.trackName)
        assertEquals("/api/v1/call/call-5f9ec645/sdp/pull", provision.credentials.pullPath)
        assertEquals("/api/v1/call/call-5f9ec645/sdp/renegotiate", provision.credentials.renegotiatePath)
        assertEquals("/api/v1/call/call-5f9ec645/events", provision.credentials.eventsPath)
        assertTrue(provision.credentials.token!!.startsWith("1788794409."))
    }

    /**
     * The dev bridge sends no `iceServers` yet (RUN-1780). Absence must leave the list empty so the
     * coordinator falls back to Cloudflare STUN, rather than producing a bogus entry.
     */
    @Test
    fun `missing ice servers yields an empty list`() {
        val provision = BridgeProtocol.parseProvision(liveProvisionJson)!!
        assertTrue(provision.credentials.iceServers.isEmpty())
    }

    @Test
    fun `reads ice servers when present`() {
        val json = """
            {"callId":"call-1","creds":{"provider":"cloudflare","connectUrl":"/sdp",
            "iceServers":[{"urls":["turn:turn.example:3478"],"username":"u","credential":"c"}]}}
        """.trimIndent()
        val provision = BridgeProtocol.parseProvision(json)!!
        assertEquals(
            listOf(IceServer(urls = listOf("turn:turn.example:3478"), username = "u", credential = "c")),
            provision.credentials.iceServers,
        )
    }

    @Test
    fun `rejects payloads missing the essentials`() {
        assertNull(BridgeProtocol.parseProvision("{}"))
        assertNull(BridgeProtocol.parseProvision("""{"callId":"call-1"}"""))
        assertNull(BridgeProtocol.parseProvision("""{"callId":"","creds":{"connectUrl":"/sdp"}}"""))
        assertNull(BridgeProtocol.parseProvision("""{"callId":"c","creds":{"connectUrl":""}}"""))
        assertNull(BridgeProtocol.parseProvision("not json"))
    }

    /** `mode` must be `voice-agent`: the bridge defaults to `echo`, which authenticated deployments 403. */
    @Test
    fun `provision body requests voice-agent mode`() {
        val json = JSONObject(BridgeProtocol.provisionBody())
        assertEquals("voice-agent", json.optString("mode"))
        // No caller: a mobile call has no signed-in user to attribute it to.
        assertTrue(!json.has("caller"))
    }

    // ── SDP ───────────────────────────────────────────────────────

    @Test
    fun `offer body carries sdp and mid`() {
        val json = JSONObject(BridgeProtocol.offerBody("v=0\r\noffer", "0"))
        assertEquals("v=0\r\noffer", json.optString("sdp"))
        assertEquals("0", json.optString("mid"))
    }

    @Test
    fun `parses an answer and rejects an empty one`() {
        assertEquals("v=0 answer", BridgeProtocol.parseSdp("""{"sdp":"v=0 answer"}"""))
        assertNull(BridgeProtocol.parseSdp("""{"sdp":""}"""))
        assertNull(BridgeProtocol.parseSdp("{}"))
        assertNull(BridgeProtocol.parseSdp(""))
    }

    // ── events socket ─────────────────────────────────────────────

    @Test
    fun `auth frame matches the server's expected shape`() {
        val json = JSONObject(BridgeProtocol.eventsAuthFrame("tok"))
        assertEquals("auth", json.optString("type"))
        assertEquals("tok", json.optString("token"))
    }

    @Test
    fun `maps control frames`() {
        assertEquals(BridgeProtocol.Event.REPULL, BridgeProtocol.parseEvent("""{"event":"repull"}"""))
        assertEquals(BridgeProtocol.Event.UNMUTE, BridgeProtocol.parseEvent("""{"event":"unmute"}"""))
        assertEquals(BridgeProtocol.Event.BARGE_IN, BridgeProtocol.parseEvent("""{"event":"barge-in"}"""))
        // The whole barge-in* family means "silence the agent now".
        assertEquals(BridgeProtocol.Event.BARGE_IN, BridgeProtocol.parseEvent("""{"event":"barge-in-detected"}"""))
    }

    @Test
    fun `ignores unknown and malformed frames`() {
        assertNull(BridgeProtocol.parseEvent("""{"event":"something-new"}"""))
        assertNull(BridgeProtocol.parseEvent("""{"type":"stats"}"""))
        assertNull(BridgeProtocol.parseEvent("not json"))
    }

    // ── host resolution ───────────────────────────────────────────

    /**
     * Hosts come from the bridge's own gitops overlays; `plg-us-1-prod` is the one cluster that
     * doesn't sit under `.platform`.
     */
    @Test
    fun `resolves bridge hosts per cluster`() {
        assertEquals("https://webrtc-bridge.us-1.platform.polyai.app/", VoiceHosts(Environment.US).bridgeBaseUrl())
        assertEquals("https://webrtc-bridge.uk-1.platform.polyai.app/", VoiceHosts(Environment.UK).bridgeBaseUrl())
        assertEquals("https://webrtc-bridge.euw-1.platform.polyai.app/", VoiceHosts(Environment.EUW).bridgeBaseUrl())
        assertEquals(
            "https://webrtc-bridge.dev.polyai.app/",
            VoiceHosts(Environment.cluster("dev")).bridgeBaseUrl(),
        )
        assertEquals(
            "https://webrtc-bridge.plg-us-1-prod.polyai.app/",
            VoiceHosts(Environment.cluster("plg-us-1-prod")).bridgeBaseUrl(),
        )
    }

    @Test
    fun `custom environment requires an explicit bridge host`() {
        val custom = Environment.custom(URI("https://api.test/api/v1"), URI("wss://api.test/ws"))
        assertFailsWith<PolyError.InvalidConfiguration> { VoiceHosts(custom).bridgeBaseUrl() }
        assertEquals(
            "https://localhost:8080/",
            VoiceHosts(custom, signalingHost = "localhost:8080").bridgeBaseUrl(),
        )
    }
}
