// Copyright PolyAI Limited

package ai.poly.messaging

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM contract tests for the public model layer. No Android framework, no coroutines:
 * just the value/sealed types.
 */
class ModelContractTest {

    // ---- PolyError: user copy, structural form, classification ----

    @Test
    fun polyError_userFacingMessages_matchExpectedCopy() {
        assertEquals(
            "Your API key was rejected. Please contact support.",
            PolyError.Auth.Unauthorized.message,
        )
        assertEquals(
            "Your session timed out. Start a new chat to continue.",
            PolyError.Session.SessionExpired.message,
        )
        assertEquals("Message is too large (max 1 KB).", PolyError.Message.PayloadTooLarge(1024).message)
        assertEquals(
            "Disconnected unexpectedly (code 1006): boom",
            PolyError.Session.UnexpectedDisconnect(1006, "boom").message,
        )
    }

    @Test
    fun polyError_classificationFlags() {
        assertTrue(PolyError.Auth.Unauthorized.isAuthError)
        assertTrue(PolyError.Session.SessionExpired.isSessionExpired)
        assertTrue(PolyError.Transport.NotConnected.isRetryable)
        assertTrue(PolyError.Session.MaxReconnectAttemptsExceeded.isRetryable)
        assertFalse(PolyError.Auth.Unauthorized.isRetryable)
    }

    @Test
    fun polyError_debugDescription_isStructural() {
        assertEquals("auth(unauthorized)", PolyError.Auth.Unauthorized.debugDescription)
        assertEquals(
            "session(unexpectedDisconnect(code=1006, reason=boom))",
            PolyError.Session.UnexpectedDisconnect(1006, "boom").debugDescription,
        )
    }

    // ---- Configuration: defaults + Java-style builder ----

    @Test
    fun configuration_defaults() {
        val c = Configuration(apiKey = "k")
        assertEquals(Environment.US, c.environment)
        assertTrue(c.streamingEnabled)
        assertEquals(LogLevel.ERROR, c.logLevel)
        assertNull(c.hostIdentifier)
        assertNull(c.maxReconnectAttempts)
    }

    @Test
    fun configuration_builder_matchesConstructor() {
        val built = Configuration.Builder("k")
            .environment(Environment.cluster("dev"))
            .streamingEnabled(false)
            .maxReconnectAttempts(3)
            .build()
        assertEquals("k", built.apiKey)
        assertFalse(built.streamingEnabled)
        assertEquals(3, built.maxReconnectAttempts)
        assertEquals(Environment.Cluster("dev"), built.environment)
    }

    // ---- Environment factories ----

    @Test
    fun environment_factories() {
        assertTrue(Environment.cluster("dev") is Environment.Cluster)
        val custom = Environment.custom(URI("https://r"), URI("wss://w"))
        assertTrue(custom is Environment.Custom)
        assertEquals(URI("wss://w"), (custom as Environment.Custom).wsBaseUrl)
    }

    // ---- ConnectionStatus helpers ----

    @Test
    fun connectionStatus_helpers() {
        assertTrue(ConnectionStatus.Open.isConnected)
        assertFalse(ConnectionStatus.Open.isReconnecting)
        assertEquals(2, ConnectionStatus.Reconnecting(2).reconnectAttempt)
        assertTrue(ConnectionStatus.Failed(PolyError.Session.SessionExpired).isTerminal)
        assertTrue(ConnectionStatus.Connecting.isActive)
    }

    // ---- ChatMessage base helpers + equality (RecyclerView/Compose diffing) ----

    @Test
    fun chatMessage_baseHelpers() {
        val user = ChatMessage.User(UserMessage(text = "hi", delivery = Delivery.SENT, draftId = "d1"))
        assertTrue(user.isUser)
        assertEquals("hi", user.text)
        assertEquals(Delivery.SENT, user.delivery)

        val sys = ChatMessage.System(SystemMessage(SystemEvent.HandoffStarted))
        assertTrue(sys.isSystem)
        assertNull(sys.text)
        assertTrue(sys.suggestions.isEmpty())
    }

    @Test
    fun systemEvent_helpers() {
        assertTrue(SystemEvent.HandoffStarted.isHandoff)
        assertTrue(SystemEvent.ConversationEnded("done").isTerminal)
        assertEquals("done", SystemEvent.ConversationEnded("done").reason)
        assertFalse(SystemEvent.IdleWarning.isTerminal)
    }

    // ---- Wire-string enums round-trip (decoder fallback safety) ----

    @Test
    fun enums_fromRaw_fallbacks() {
        assertEquals(AttachmentContentType.IMAGE, AttachmentContentType.fromRaw("ATTACHMENT_CONTENT_TYPE_IMAGE"))
        assertEquals(AttachmentContentType.UNKNOWN, AttachmentContentType.fromRaw("bogus"))
        assertEquals(SessionStatus.ACTIVE, SessionStatus.fromRaw("active"))
        assertEquals(SessionStatus.UNKNOWN, SessionStatus.fromRaw("???"))
        assertEquals(TypingState.STARTED, TypingState.fromRaw("TYPING_STATE_STARTED"))
        assertEquals(CloseCode.ABNORMAL, CloseCode.fromCode(1006))
    }

    // ---- Value equality (data classes / enums) ----

    @Test
    fun envelope_and_metadata_haveValueEquality() {
        val a = Envelope("e1", 3, 1000L, EventMetadata(mapOf("k" to "v")))
        val b = Envelope("e1", 3, 1000L, EventMetadata(mapOf("k" to "v")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == Envelope("e2", 3, 1000L, null))
        assertEquals(EventMetadata(mapOf("k" to "v")), EventMetadata(mapOf("k" to "v")))
    }

    @Test
    fun polyError_payloadCases_haveValueEquality() {
        assertEquals(PolyError.Session.UnexpectedDisconnect(1006, "x"), PolyError.Session.UnexpectedDisconnect(1006, "x"))
        assertFalse(PolyError.Session.UnexpectedDisconnect(1006, "x") == PolyError.Session.UnexpectedDisconnect(1006, "y"))
        assertEquals(PolyError.Message.PayloadTooLarge(1024), PolyError.Message.PayloadTooLarge(1024))
        assertEquals(PolyError.Transport.NetworkError("net"), PolyError.Transport.NetworkError("net"))
        assertEquals(
            PolyError.Session.SessionCreationFailed(SessionErrorCode.CONNECTOR_VALIDATION_FAILED),
            PolyError.Session.SessionCreationFailed(SessionErrorCode.CONNECTOR_VALIDATION_FAILED),
        )
    }

    // ---- Debug surface (MessagingEvent debug rendering) ----

    @Test
    fun messagingEvent_debugSummary_matchesExpectedFormat() {
        assertEquals("Connected", MessagingEvent.Connected.debugSummary)
        assertEquals("Reconnecting (attempt 2)", MessagingEvent.Reconnecting(2).debugSummary)
        assertEquals("Message failed [abcdefgh]", MessagingEvent.MessageFailed("abcdefgh-1234").debugSummary)
        val env = Envelope("id", 1, 0L, null)
        val agent = MessagingEvent.AgentMessage(env, AgentMessagePayload("m1", "hello there", null, null, emptyList(), emptyList(), emptyList(), false))
        assertEquals("Agent: hello there", agent.debugSummary)
        assertTrue(agent.debugDetail.contains("messageId: m1"))
    }
}
