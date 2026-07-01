// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.voice.internal.protocol.SignalMessage
import ai.poly.voice.internal.protocol.SignalingProtocol
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format fidelity for the WebRTC-gateway signaling protocol (encode/decode). Pure JVM.
 */
class SignalingProtocolTest {

    @Test
    fun encodeOffer_matchesGatewayShape() {
        val json = JSONObject(SignalingProtocol.encodeOffer(sessionId = null, sdp = "SDP", authToken = "tok", callSid = "cs-1"))
        assertEquals("offer", json.getString("type"))
        assertTrue(json.isNull("sessionId")) // null on the first offer
        assertEquals("offer", json.getJSONObject("data").getString("type"))
        assertEquals("SDP", json.getJSONObject("data").getString("sdp"))
        assertEquals("end-to-end", json.getString("mode"))
        assertEquals("tok", json.getString("authToken"))
        assertEquals("cs-1", json.getString("callSid"))
        assertEquals("Polyphone", json.getString("caller"))
        assertEquals("Polyphone", json.getString("callee"))
    }

    @Test
    fun encodeIceCandidate_carriesSessionAndCandidateData() {
        val json = JSONObject(SignalingProtocol.encodeIceCandidate("sig-1", "cand", "0", 0))
        assertEquals("ice-candidate", json.getString("type"))
        assertEquals("sig-1", json.getString("sessionId"))
        val data = json.getJSONObject("data")
        assertEquals("cand", data.getString("candidate"))
        assertEquals("0", data.getString("sdpMid"))
        assertEquals(0, data.getInt("sdpMLineIndex"))
    }

    @Test
    fun encodeIceCandidate_nullableFieldsBecomeJsonNull() {
        val data = JSONObject(SignalingProtocol.encodeIceCandidate(null, "cand", null, null)).getJSONObject("data")
        assertTrue(data.isNull("sdpMid"))
        assertTrue(data.isNull("sdpMLineIndex"))
    }

    @Test
    fun encodeClose_carriesSessionId() {
        val json = JSONObject(SignalingProtocol.encodeClose("sig-1"))
        assertEquals("close", json.getString("type"))
        assertEquals("sig-1", json.getString("sessionId"))
    }

    @Test
    fun decode_answer() {
        val msg = SignalingProtocol.decode("""{"type":"answer","sessionId":"sig-9","data":{"type":"answer","sdp":"REMOTE"}}""")
        assertEquals(SignalMessage.Answer(sessionId = "sig-9", sdp = "REMOTE"), msg)
    }

    @Test
    fun decode_answer_missingSdp_isNull() {
        assertNull(SignalingProtocol.decode("""{"type":"answer","sessionId":"x","data":{"type":"answer"}}"""))
    }

    @Test
    fun decode_iceCandidate() {
        val msg = SignalingProtocol.decode("""{"type":"ice-candidate","data":{"candidate":"c","sdpMid":"0","sdpMLineIndex":1}}""")
        assertEquals(SignalMessage.IceCandidate(candidate = "c", sdpMid = "0", sdpMLineIndex = 1), msg)
    }

    @Test
    fun decode_iceCandidate_missingCandidate_isNull() {
        assertNull(SignalingProtocol.decode("""{"type":"ice-candidate","data":{"sdpMid":"0"}}"""))
    }

    @Test
    fun decode_iceCandidate_optionalFieldsAbsent() {
        val msg = SignalingProtocol.decode("""{"type":"ice-candidate","data":{"candidate":"c"}}""")
        assertEquals(SignalMessage.IceCandidate(candidate = "c", sdpMid = null, sdpMLineIndex = null), msg)
    }

    @Test
    fun decode_error_withAndWithoutMessage() {
        assertEquals(SignalMessage.Error("boom"), SignalingProtocol.decode("""{"type":"error","data":{"message":"boom"}}"""))
        assertEquals(SignalMessage.Error("Connection failed"), SignalingProtocol.decode("""{"type":"error"}"""))
    }

    @Test
    fun decode_pongAndClose() {
        assertEquals(SignalMessage.Pong, SignalingProtocol.decode("""{"type":"pong"}"""))
        assertEquals(SignalMessage.Close, SignalingProtocol.decode("""{"type":"close"}"""))
    }

    @Test
    fun decode_unknownType_isNull() {
        assertNull(SignalingProtocol.decode("""{"type":"whatever"}"""))
    }

    @Test
    fun decode_malformed_isNull() {
        assertNull(SignalingProtocol.decode("not json"))
        assertNull(SignalingProtocol.decode("""{"no":"type"}"""))
        assertNull(SignalingProtocol.decode(""))
    }
}
